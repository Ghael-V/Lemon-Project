// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <shared_mutex>
#include <span>
#include <thread>
#include <vector>

#include "common/alignment.h"
#include "common/div_ceil.h"
#include "common/fs/file.h"
#include "common/fs/fs.h"
#include "common/logging.h"
#include "core/core.h"
#include "core/hle/kernel/k_auto_object.h"
#include "core/hle/kernel/k_handle_table.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/svc_types.h"
#include "core/memory.h"
#include "core/memory_region_scan.h"
#include "core/savestate.h"
#include "core/savestate_undo_journal.h"

namespace Core::SaveState {

namespace {

constexpr std::array<char, 4> Magic{'L', 'M', 'S', 'S'};
// 3: the wait signature follows the thread count
// 4: the thread ids, the memory map and the thread handles follow the thread contexts
// 5: the signature of a thread blocked on a user-mode lock includes that lock
// 6: the signature tells whether a thread is inside a kernel call
// 7: no lock address in the signature; IPC waits are recorded as such
// 8: the signature includes what a waiting thread waits for
constexpr u32 CurrentVersion = 8;

// Regions are stored in fixed-size chunks, each preceded by a one-byte flag: 1 if the whole
// chunk is zero (skip writing/reading the actual bytes), 0 if it isn't (bytes follow as
// usual). Measured on a real, demanding title (Super Mario 3D World): ~80% of captured bytes
// are zero, and ~68% of 4096-byte-aligned chunks are entirely zero - most of a game's
// reserved-but-untouched heap never gets written to. Skipping those chunks shrinks a ~3.4GB
// capture to roughly a third of that, for the cost of one memcmp per chunk - far cheaper than
// general-purpose compression, and unlike compression this doesn't cost anything on the CPU
// budget during Restore()'s memory writes (a zero chunk there is just a memset).
constexpr size_t ChunkSize = 4096;
constexpr std::array<u8, ChunkSize> ZeroChunk{};

// What the guest's memory refers to by name, as it was at the save: Restore() puts the live kernel
// back under those names. Measured on Super Mario 3D World, dying and respawning changes exactly
// this and nothing else: four worker threads are destroyed and created again with new ids and
// new stacks (same thread-local storage, same argument), and the handles the guest holds for them
// are new too - and the kernel hands the recycled thread objects out in another order, so the
// handle value saved for the thread in a slot now belongs to a different thread's object.
constexpr u32 NoHandle = 0xFFFFFFFF;

struct SavedThread {
    u64 id;
    u64 tls;
    u32 handle_index; // NoHandle when the process holds no handle to this thread
    u32 handle_linear_id;
};

struct SavedBlock {
    u64 address;
    u64 size;
    u32 state;
    u32 permission;
    u32 attribute;
    u32 padding;
};

// The handle a thread is held under in the process' handle table, if any.
struct ThreadHandle {
    u32 index;
    u32 linear_id;
};

std::vector<ThreadHandle> CollectThreadHandles(Core::System& system, Kernel::KProcess& process,
                                               const std::vector<Kernel::KThread*>& threads) {
    std::vector<ThreadHandle> handles(threads.size(), ThreadHandle{NoHandle, 0});
    process.GetHandleTable().ForEachEntry(
        system.Kernel(), [&](s32 index, u32 linear_id, Kernel::KAutoObject* object) {
            for (size_t i = 0; i < threads.size(); i++) {
                if (static_cast<Kernel::KAutoObject*>(threads[i]) == object) {
                    handles[i] = {static_cast<u32>(index), linear_id};
                    break;
                }
            }
        });
    return handles;
}

// Handle values of the saved world and what they are in the live one, for the same thread slot.
using HandleMap = std::vector<std::pair<u32, u32>>;

constexpr u32 MutexWaitersFlag = 0x40000000;

u32 EncodeHandleValue(u32 index, u32 linear_id) {
    return index | (linear_id << 15);
}

// Replaces, in memory about to be written back, every 4-byte word that is a saved handle value
// with the live one. Both worlds have to agree on the names of things: threads asleep in a kernel
// wait are not restored and keep the live handle values in their own stacks, while everything
// restored holds the saved ones - when the guest later compared or used the two (a mutex owner
// tag, a handle passed to the kernel) the game froze or aborted with "invalid handle".
size_t TranslateHandles(u8* data, size_t size, const HandleMap& map, u32 low, u32 high) {
    size_t replaced = 0;
    for (size_t offset = 0; offset + sizeof(u32) <= size; offset += sizeof(u32)) {
        u32 word;
        std::memcpy(&word, data + offset, sizeof(word));
        if ((word & ~MutexWaitersFlag) < low || (word & ~MutexWaitersFlag) > high) {
            continue;
        }
        // A user-mode mutex word is the owner's handle, plus a flag in bit 30 when threads wait.
        const u32 flag = word & MutexWaitersFlag;
        const u32 handle = word & ~MutexWaitersFlag;
        for (const auto& [saved, live] : map) {
            if (handle == saved) {
                const u32 replacement = live | flag;
                std::memcpy(data + offset, &replacement, sizeof(replacement));
                replaced++;
                break;
            }
        }
    }
    return replaced;
}

// True for a thread asleep in a kernel wait with a recorded reason (see Restore).
bool IsParkedInKernel(Kernel::KThread& thread) {
    // A thread waiting for a service's answer is restored like a running one, as it always was
    // (the kernel did not record that reason until now): the main thread of most games waits there
    // at nearly every moment, and its saved registers are what the restored memory goes with.
    const auto reason = thread.GetWaitReasonForDebugging();
    return reason != Kernel::ThreadWaitReasonForDebugging::None &&
           reason != Kernel::ThreadWaitReasonForDebugging::IPC;
}

// Writes the 4 KiB chunks of `data` that differ from guest memory, in runs. A false return means
// part of the range is not mapped.
bool WriteChanged(Core::Memory::Memory& memory, u64 address, const u8* data, u64 size,
                  std::vector<u8>& live, u64& written) {
    live.resize(size);
    if (!memory.ReadBlockUnsafe(address, live.data(), size)) {
        return memory.WriteBlock(address, data, size);
    }
    bool ok = true;
    u64 run_start = 0;
    bool in_run = false;
    for (u64 offset = 0; offset < size + ChunkSize; offset += ChunkSize) {
        const bool differs =
            offset < size &&
            std::memcmp(live.data() + offset, data + offset,
                        static_cast<size_t>(std::min<u64>(ChunkSize, size - offset))) != 0;
        if (differs && !in_run) {
            run_start = offset;
            in_run = true;
        } else if (!differs && in_run) {
            const u64 run_end = std::min(offset, size);
            ok = memory.WriteBlock(address + run_start, data + run_start, run_end - run_start) && ok;
            written += run_end - run_start;
            in_run = false;
        }
    }
    return ok;
}

std::vector<SavedThread> CollectThreads(Core::System& system, Kernel::KProcess& process) {
    std::vector<Kernel::KThread*> threads;
    for (auto& thread : process.GetThreadList()) {
        threads.push_back(std::addressof(thread));
    }
    const auto handles = CollectThreadHandles(system, process, threads);
    std::vector<SavedThread> saved;
    for (size_t i = 0; i < threads.size(); i++) {
        saved.push_back({threads[i]->GetThreadId(), threads[i]->GetTlsAddress().GetValue(),
                         handles[i].index, handles[i].linear_id});
    }
    return saved;
}

// Every block of the process that is not free, in address order.
std::vector<SavedBlock> CollectBlocks(Kernel::KProcess& process) {
    std::vector<SavedBlock> blocks;
    auto& page_table = process.GetPageTable();
    const auto end = page_table.GetAddressSpaceStart() + page_table.GetAddressSpaceSize();
    auto address = page_table.GetAddressSpaceStart();
    while (address < end) {
        Kernel::KMemoryInfo info{};
        Kernel::Svc::PageInfo page_info{};
        if (page_table.QueryInfo(&info, &page_info, address).IsError()) {
            break;
        }
        if ((info.m_state & Kernel::KMemoryState::Mask) != Kernel::KMemoryState::Free) {
            blocks.push_back({info.m_address, info.m_size, static_cast<u32>(info.m_state),
                              static_cast<u32>(info.m_permission),
                              static_cast<u32>(info.m_attribute), 0});
        }
        const Common::ProcessAddress next = info.m_address + info.m_size;
        if (next <= address) {
            break;
        }
        address = next;
    }
    return blocks;
}

} // namespace

std::shared_timed_mutex& ServiceReplyGate() {
    static std::shared_timed_mutex gate;
    return gate;
}

namespace {
thread_local ServiceGateHold* t_gate_hold = nullptr;

// Takes the gate exclusively for a save or a load. Polled rather than waited for: a waiting writer
// would hold back every service meanwhile. A service in the middle of an answer is let finish.
bool TakeServiceGate(std::unique_lock<std::shared_timed_mutex>& gate) {
    for (int attempt = 0; attempt < 500 && !gate.try_lock(); attempt++) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return gate.owns_lock();
}
} // namespace

ServiceGateHold::ServiceGateHold() : previous{t_gate_hold} {
    ServiceReplyGate().lock_shared();
    t_gate_hold = this;
}

ServiceGateHold::~ServiceGateHold() {
    if (held) {
        ServiceReplyGate().unlock_shared();
    }
    t_gate_hold = previous;
}

ServiceGateRelease::ServiceGateRelease() : hold{t_gate_hold} {
    if (hold != nullptr && hold->held) {
        ServiceReplyGate().unlock_shared();
        hold->held = false;
    } else {
        hold = nullptr;
    }
}

ServiceGateRelease::~ServiceGateRelease() {
    if (hold != nullptr) {
        ServiceReplyGate().lock_shared();
        hold->held = true;
    }
}

std::vector<u32> WaitSignature(Core::System& system) {
    std::vector<u32> signature;
    if (auto* process = system.ApplicationProcess()) {
        for (auto& thread : process->GetThreadList()) {
            // The kernel state (running/waiting/...) as well as the wait reason: a save/load pair
            // that disagreed only on the state (the main thread waiting at the save, runnable at
            // the load) froze the game while a reason-only signature said "same".
            const u32 state = static_cast<u32>(static_cast<u16>(thread.GetState()) &
                                               static_cast<u16>(Kernel::ThreadState::Mask));
            u32 word = (state << 8) | static_cast<u32>(thread.GetWaitReasonForDebugging());
            // Whether it is inside a kernel call: its saved registers then still hold the call's
            // arguments, and the call writes its results when the thread resumes. A thread caught
            // runnable at the instruction after a call is inside it or already past it, and the two
            // cannot be swapped: restoring the registers of one onto the other gave the main thread
            // of Super Mario 3D World its request's session handle as the call's result.
            if (thread.IsCallingSvc()) {
                word |= 0x80;
            }
            // And what it waits for. A thread restored onto a wait for another object stays there:
            // in Garfield a worker sat on one condition variable at the save and on another at the
            // load, the restored game signalled the first one and then waited forever for the
            // worker to finish.
            const auto reason = thread.GetWaitReasonForDebugging();
            if (state == static_cast<u32>(Kernel::ThreadState::Waiting)) {
                u64 what = 0;
                if (reason == Kernel::ThreadWaitReasonForDebugging::ConditionVar ||
                    reason == Kernel::ThreadWaitReasonForDebugging::Arbitration) {
                    what = thread.GetAddressKey().GetValue();
                } else if (reason == Kernel::ThreadWaitReasonForDebugging::Synchronization &&
                           thread.IsCallingSvc()) {
                    // WaitSynchronization(handles = x1, count = x2), as the call was made.
                    const auto& context = thread.GetContext();
                    const u64 count = context.r[2];
                    if (count > 0 && count <= 64) {
                        auto& memory = system.ApplicationMemory();
                        what = count;
                        for (u64 i = 0; i < count; i++) {
                            what = what * 0x100000001B3ULL ^ memory.Read32(context.r[1] + i * 4);
                        }
                    }
                }
                const u64 folded = what ^ (what >> 16) ^ (what >> 32) ^ (what >> 48);
                word |= static_cast<u32>(folded & 0xFFFF) << 16;
            }
            signature.push_back(word);
        }
    }
    return signature;
}

namespace {
// A thread asleep on a condition variable, inside the call: it can be moved to another one.
bool IsMovableConditionVariableWait(u32 word) {
    return (word & 0x7F) == static_cast<u32>(Kernel::ThreadWaitReasonForDebugging::ConditionVar) &&
           (word & 0x80) != 0 &&
           ((word >> 8) & 0xFF) == static_cast<u32>(Kernel::ThreadState::Waiting);
}
} // namespace

bool SignaturesMatch(const std::vector<u32>& saved, const std::vector<u32>& live) {
    if (saved.size() != live.size()) {
        return false;
    }
    for (size_t i = 0; i < saved.size(); i++) {
        if (saved[i] == live[i]) {
            continue;
        }
        // Asleep on another condition variable than at the save: Restore moves it back.
        if ((saved[i] & 0xFFFF) == (live[i] & 0xFFFF) && IsMovableConditionVariableWait(saved[i])) {
            continue;
        }
        return false;
    }
    return true;
}

bool ReadSavedSignature(const std::string& path, std::vector<u32>& out) {
    Common::FS::IOFile file{path, Common::FS::FileAccessMode::Read};
    if (!file.IsOpen()) {
        return false;
    }
    std::array<char, 4> magic{};
    u32 version = 0;
    u32 thread_count = 0;
    if (file.ReadSpan<char>(magic) != magic.size() || magic != Magic ||
        !file.ReadObject(version) || version != CurrentVersion || !file.ReadObject(thread_count) ||
        thread_count > 4096) {
        return false;
    }
    out.resize(thread_count);
    return thread_count == 0 || file.ReadSpan<u32>(out) == out.size();
}

bool Capture(Core::System& system, const std::string& path) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        LOG_ERROR(Core, "SaveState::Capture: no application process");
        return false;
    }

    // No service may write an answer into the game's memory while it is being copied.
    std::unique_lock gate{ServiceReplyGate(), std::defer_lock};
    if (!TakeServiceGate(gate)) {
        LOG_WARNING(Core, "SaveState::Capture: a service is still answering a request");
        return false;
    }

    auto& memory = system.ApplicationMemory();
    auto& page_table = process->GetPageTable();

    std::vector<Kernel::Svc::ThreadContext> thread_contexts;
    for (auto& thread : process->GetThreadList()) {
        thread_contexts.push_back(thread.GetContext());
    }

    const auto regions = EnumerateScannableRegions(page_table);

    const auto tmp_path = path + ".tmp";
    Common::FS::IOFile file{tmp_path, Common::FS::FileAccessMode::Write};
    if (!file.IsOpen()) {
        LOG_ERROR(Core, "SaveState::Capture: failed to open '{}' for writing", tmp_path);
        return false;
    }

    bool ok = file.WriteSpan<char>(Magic) == Magic.size();
    ok = ok && file.WriteObject(CurrentVersion);

    const auto thread_count = static_cast<u32>(thread_contexts.size());
    ok = ok && file.WriteObject(thread_count);
    const std::vector<u32> signature = WaitSignature(system);
    ok = ok && signature.size() == thread_count &&
         (thread_count == 0 || file.WriteSpan<u32>(signature) == signature.size());
    ok = ok && file.WriteSpan<Kernel::Svc::ThreadContext>(thread_contexts) ==
                   thread_contexts.size();

    const auto threads = CollectThreads(system, *process);
    ok = ok && threads.size() == thread_count &&
         (thread_count == 0 || file.WriteSpan<SavedThread>(threads) == threads.size());
    const auto blocks = CollectBlocks(*process);
    ok = ok && file.WriteObject(static_cast<u32>(blocks.size()));
    ok = ok && (blocks.empty() || file.WriteSpan<SavedBlock>(blocks) == blocks.size());

    const auto region_count = static_cast<u32>(regions.size());
    ok = ok && file.WriteObject(region_count);

    std::vector<u8> buffer;
    // Built up in memory and flushed with a single WriteSpan() per region, same call count as
    // the old one-big-write approach - writing the flag+data stream one tiny piece at a time
    // instead (one fwrite() per 4K chunk, ~800k+ calls for a real capture) made Capture() slow
    // enough to look like a hang.
    std::vector<u8> encoded;
    u64 data_bytes = 0;
    u64 zero_bytes = 0;
    for (const auto& region : regions) {
        if (!ok) {
            break;
        }

        buffer.resize(region.size);
        // ReadBlockUnsafe(), not ReadBlock() - the regular path calls
        // HandleRasterizerDownload() per chunk to sync with the GPU emulation's cache, and
        // a savestate capture reads the whole multi-GB scannable footprint in one pass. The
        // Lemon Cheater hit this exact issue first (see memory_search.cpp): forcing that
        // much cache invalidation in one go visibly wrecks the game's own rendering
        // performance in a way that persists until the game is reloaded, even though the
        // capture itself never writes anything.
        if (!memory.ReadBlockUnsafe(region.address, buffer.data(), region.size)) {
            LOG_ERROR(Core, "SaveState::Capture: failed to read {} bytes at {:#x}", region.size,
                       region.address);
            ok = false;
            break;
        }

        encoded.clear();
        encoded.reserve(region.size + (region.size + ChunkSize - 1) / ChunkSize);
        for (u64 offset = 0; offset < region.size; offset += ChunkSize) {
            const size_t chunk_len =
                static_cast<size_t>(std::min<u64>(ChunkSize, region.size - offset));
            const u8* chunk = buffer.data() + offset;
            const bool is_zero = std::memcmp(chunk, ZeroChunk.data(), chunk_len) == 0;

            encoded.push_back(static_cast<u8>(is_zero ? 1 : 0));
            if (is_zero) {
                zero_bytes += chunk_len;
            } else {
                encoded.insert(encoded.end(), chunk, chunk + chunk_len);
                data_bytes += chunk_len;
            }
        }

        const auto encoded_size = static_cast<u64>(encoded.size());
        ok = ok && file.WriteObject(region.address);
        ok = ok && file.WriteObject(region.size);
        ok = ok && file.WriteObject(encoded_size);
        ok = ok && file.WriteSpan<u8>(encoded) == encoded.size();
    }

    file.Close();

    if (!ok) {
        LOG_ERROR(Core, "SaveState::Capture: failed while writing '{}'", tmp_path);
        void(Common::FS::RemoveFile(tmp_path));
        return false;
    }

    // RenameFile() refuses to overwrite an existing destination - this is a single
    // overwritable quicksave slot, so clear the previous one first if present.
    if (Common::FS::Exists(path) && !Common::FS::RemoveFile(path)) {
        LOG_ERROR(Core, "SaveState::Capture: failed to remove previous '{}'", path);
        void(Common::FS::RemoveFile(tmp_path));
        return false;
    }

    if (!Common::FS::RenameFile(tmp_path, path)) {
        LOG_ERROR(Core, "SaveState::Capture: failed to rename '{}' to '{}'", tmp_path, path);
        return false;
    }

    // From now on, remember how to put back to this moment what a savestate does not hold.
    UndoJournal::Memory().Start();
    UndoJournal::Gpu().Start();
    UndoJournal::Services().Start();

    LOG_INFO(Core,
              "SaveState::Capture: saved {} thread(s), {} region(s) to '{}' ({} byte(s) written, "
              "{} zero byte(s) skipped, {:.1f}% saved)",
              thread_count, region_count, path, data_bytes, zero_bytes,
              (data_bytes + zero_bytes) > 0
                  ? 100.0 * static_cast<double>(zero_bytes) /
                        static_cast<double>(data_bytes + zero_bytes)
                  : 0.0);
    return true;
}

bool Restore(Core::System& system, const std::string& path, RestoreFailure* failure) {
    if (failure != nullptr) {
        *failure = RestoreFailure::Other;
    }
    // No service may answer a request while the restore runs (see ServiceReplyGate); the threads
    // are compared once it is closed.
    std::unique_lock gate{ServiceReplyGate(), std::defer_lock};
    if (!TakeServiceGate(gate)) {
        LOG_WARNING(Core,
                    "SaveState::Restore: a service is still answering a request, refusing for now");
        if (failure != nullptr) {
            *failure = RestoreFailure::NotSettled;
        }
        return false;
    }
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        LOG_ERROR(Core, "SaveState::Restore: no application process");
        return false;
    }

    Common::FS::IOFile file{path, Common::FS::FileAccessMode::Read};
    if (!file.IsOpen()) {
        LOG_ERROR(Core, "SaveState::Restore: failed to open '{}' for reading", path);
        return false;
    }

    std::array<char, 4> magic{};
    if (file.ReadSpan<char>(magic) != magic.size() || magic != Magic) {
        LOG_ERROR(Core, "SaveState::Restore: '{}' is not a valid savestate file", path);
        return false;
    }

    u32 version = 0;
    if (!file.ReadObject(version) || version != CurrentVersion) {
        LOG_ERROR(Core, "SaveState::Restore: unsupported savestate version in '{}'", path);
        return false;
    }

    u32 thread_count = 0;
    if (!file.ReadObject(thread_count)) {
        LOG_ERROR(Core, "SaveState::Restore: truncated file '{}'", path);
        return false;
    }
    std::vector<u32> saved_signature;
    if (thread_count > 4096) {
        LOG_ERROR(Core, "SaveState::Restore: implausible thread count in '{}'", path);
        return false;
    }
    saved_signature.resize(thread_count);
    if (thread_count > 0 && file.ReadSpan<u32>(saved_signature) != saved_signature.size()) {
        LOG_ERROR(Core, "SaveState::Restore: truncated thread signature in '{}'", path);
        return false;
    }

    // Match the live thread list against the captured one before touching anything -
    // same list, so iteration order matches Capture()'s as long as nothing created or
    // destroyed a thread in between. Checked before allocating anything sized from the file,
    // so a corrupt count can't turn into a huge allocation.
    std::vector<Kernel::KThread*> live_threads;
    for (auto& thread : process->GetThreadList()) {
        live_threads.push_back(std::addressof(thread));
    }

    if (live_threads.size() != thread_count) {
        LOG_ERROR(Core,
                   "SaveState::Restore: thread count mismatch (saved {}, live {}) - the guest's "
                   "thread set changed since this savestate was captured, refusing to load",
                   thread_count, live_threads.size());
        return false;
    }

    // Refuse, before touching anything, when the threads are not waiting the way they were at the
    // save: see WaitSignature. The caller normally waits for this to hold; this keeps any other
    // caller from restoring onto a game caught mid-transition.
    const std::vector<u32> live_signature = WaitSignature(system);
    if (!SignaturesMatch(saved_signature, live_signature)) {
        LOG_WARNING(Core,
                    "SaveState::Restore: the guest's threads are not waiting as they were at the "
                    "save (some are waking or sleeping), refusing for now");
        if (failure != nullptr) {
            *failure = RestoreFailure::NotSettled;
        }
        return false;
    }

    std::vector<Kernel::Svc::ThreadContext> thread_contexts(thread_count);
    if (thread_count > 0 &&
        file.ReadSpan<Kernel::Svc::ThreadContext>(thread_contexts) != thread_contexts.size()) {
        LOG_ERROR(Core, "SaveState::Restore: truncated thread contexts in '{}'", path);
        return false;
    }

    std::vector<SavedThread> saved_threads(thread_count);
    std::vector<SavedBlock> saved_blocks;
    {
        u32 block_count = 0;
        bool read_ok = thread_count == 0 ||
                       file.ReadSpan<SavedThread>(saved_threads) == saved_threads.size();
        read_ok = read_ok && file.ReadObject(block_count) && block_count <= 1'000'000;
        if (read_ok) {
            saved_blocks.resize(block_count);
            read_ok = block_count == 0 ||
                      file.ReadSpan<SavedBlock>(saved_blocks) == saved_blocks.size();
        }
        if (!read_ok) {
            LOG_ERROR(Core, "SaveState::Restore: truncated world section in '{}'", path);
            return false;
        }
    }

    // The thread slots are matched by position, so each live thread must be the one the save
    // holds in that position: same thread-local storage (a recreated worker gets the same one
    // back, which is how a new thread is told apart from an unrelated one).
    for (u32 i = 0; i < thread_count; i++) {
        if (live_threads[i]->GetTlsAddress().GetValue() != saved_threads[i].tls) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} is not the thread the save holds there, "
                        "refusing '{}'",
                        i, path);
            if (failure != nullptr) {
                *failure = RestoreFailure::MemoryLayoutChanged;
            }
            return false;
        }
    }

    // Threads asleep on another condition variable than at the save (same call, same instruction):
    // they get their saved registers and stack and are moved back onto the saved condition
    // variable, so they wait exactly where the restored memory has them waiting. Without this, a
    // worker of Garfield asleep on another condition variable was never woken by the restored game.
    std::vector<bool> move_wait(thread_count, false);
    for (u32 i = 0; i < thread_count; i++) {
        if (saved_signature[i] == live_signature[i]) {
            continue;
        }
        if (!live_threads[i]->IsWaitingForConditionVariable() ||
            live_threads[i]->GetContext().pc != thread_contexts[i].pc) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} cannot be moved to its saved wait, refusing "
                        "for now",
                        i);
            if (failure != nullptr) {
                *failure = RestoreFailure::NotSettled;
            }
            return false;
        }
        move_wait[i] = true;
    }

    // Saved handle value -> live handle value of each thread slot's own handle.
    HandleMap handle_map;
    u32 handle_low = 0xFFFFFFFF;
    u32 handle_high = 0;
    {
        const auto live_handles = CollectThreadHandles(system, *process, live_threads);
        for (u32 i = 0; i < thread_count; i++) {
            if (saved_threads[i].handle_index == NoHandle || live_handles[i].index == NoHandle) {
                continue;
            }
            const u32 saved_value = EncodeHandleValue(saved_threads[i].handle_index,
                                                      saved_threads[i].handle_linear_id);
            const u32 live_value =
                EncodeHandleValue(live_handles[i].index, live_handles[i].linear_id);
            if (saved_value != live_value) {
                handle_map.emplace_back(saved_value, live_value);
                handle_low = std::min(handle_low, saved_value);
                handle_high = std::max(handle_high, saved_value);
            }
        }
    }

    u32 region_count = 0;
    if (!file.ReadObject(region_count)) {
        LOG_ERROR(Core, "SaveState::Restore: truncated file '{}'", path);
        return false;
    }

    // Validate the whole region table before writing any guest memory. Regions are written
    // one by one below, so failing on region N used to leave regions 0..N-1 already restored
    // with the old CPU state still in place - a half-restored game that keeps running. The
    // realistic case is a Quick Save interrupted mid-write (the app killed while writing ~1GB).
    // Header-only pass: seeks over each region's data rather than reading it.
    const s64 regions_start = file.Tell();
    const u64 file_size = file.GetSize();
    std::vector<std::pair<u64, u64>> region_ranges;
    for (u32 i = 0; i < region_count; i++) {
        u64 address = 0;
        u64 size = 0;
        u64 encoded_size = 0;
        if (!file.ReadObject(address) || !file.ReadObject(size) || !file.ReadObject(encoded_size)) {
            LOG_ERROR(Core, "SaveState::Restore: truncated region table in '{}'", path);
            return false;
        }
        region_ranges.emplace_back(address, size);
        const u64 chunks = Common::DivCeil(size, static_cast<u64>(ChunkSize));
        const s64 position = file.Tell();
        // Every chunk costs one flag byte, plus its data unless it was all zeros.
        if (position < 0 || static_cast<u64>(position) > file_size || encoded_size < chunks ||
            encoded_size > size + chunks ||
            encoded_size > file_size - static_cast<u64>(position) ||
            !file.Seek(static_cast<s64>(encoded_size), Common::FS::SeekOrigin::CurrentPosition)) {
            LOG_ERROR(Core, "SaveState::Restore: truncated or corrupt region {} in '{}'", i, path);
            return false;
        }
    }
    if (!file.Seek(regions_start)) {
        LOG_ERROR(Core, "SaveState::Restore: failed to rewind '{}'", path);
        return false;
    }

    // A thread still asleep in a kernel wait (condvar/IPC/WaitSynchronization/address
    // arbiter/sleep) right now, at restore time, is parked inside a live host fiber
    // (KThread::GetHostContext()) - its real "where do I resume" state lives on that
    // fiber's own C++ call stack, not in Svc::ThreadContext, and that call stack expects
    // its own guest stack memory to still hold whatever it was holding when it parked.
    // Blitting captured bytes over it - or overwriting a Svc::ThreadContext that no
    // longer matches which wait it's actually sitting in - is exactly what made real
    // games self-terminate via svcBreak on restore (see savestate.h). A thread whose
    // GetWaitReasonForDebugging() == None was genuinely executing and got stopped
    // cleanly at a scheduler dispatch boundary by the caller's Pause(): that suspend
    // path (SuspendType::System, KernelCore::SuspendEmulation) never touches this field,
    // so checking it here still reflects each thread's state as of this restore.
    std::vector<u64> sleeping_stack_tops;
    // Diagnostic only - which specific wait each excluded thread is parked in. A high count
    // by itself isn't necessarily a problem (e.g. an idle worker pool sitting in ConditionVar
    // between jobs is normal and harmless to leave untouched); what matters for a frozen
    // restore is whether something CRITICAL to game-loop progress is among them.
    std::array<u32, 7> wait_reason_counts{};
    for (u32 i = 0; i < thread_count; i++) {
        auto* thread = live_threads[i];
        if (IsParkedInKernel(*thread) && !move_wait[i]) {
            sleeping_stack_tops.push_back(thread->GetUserStackTop().GetValue());
            wait_reason_counts[static_cast<size_t>(thread->GetWaitReasonForDebugging())]++;
        }
    }
    LOG_INFO(Core,
              "SaveState::Restore: {} thread(s) asleep at restore time - None excluded, "
              "Sleep={} IPC={} Synchronization={} ConditionVar={} Arbitration={} Suspended={}",
              sleeping_stack_tops.size(), wait_reason_counts[1], wait_reason_counts[2],
              wait_reason_counts[3], wait_reason_counts[4], wait_reason_counts[5],
              wait_reason_counts[6]);

    // A sleeping thread's own stack is a single region whose address range contains its
    // stack top - matching on that (rather than persisting KMemoryState in the file, or
    // re-querying it) is enough since capture/restore only ever targets the current
    // process' own live layout.
    const auto is_excluded_stack = [&](u64 address, u64 size) {
        return std::any_of(sleeping_stack_tops.begin(), sleeping_stack_tops.end(),
                           [&](u64 stack_top) {
                               return stack_top > address && stack_top <= address + size;
                           });
    };

    auto& memory = system.ApplicationMemory();

    // What the guest changed since the save in state a savestate does not hold is undone before the
    // memory is written (see UndoJournal): memory it mapped or unmapped, such as the stacks of
    // threads it destroyed and created again, the GPU memory bookkeeping and the objects services
    // handed out, which the restored memory refers to by value. When something since the save
    // cannot be undone (or nothing was recorded, as for a save from an earlier run), or memory the
    // save holds is gone and the undo would not bring it back, the load is refused here, before
    // anything changed: restoring onto such a guest left it resuming into a world that no longer
    // existed (Super Mario 3D World after dying, before the stacks were undone) and never
    // presenting another frame.
    auto& memory_journal = UndoJournal::Memory();
    auto& gpu_journal = UndoJournal::Gpu();
    auto& services_journal = UndoJournal::Services();
    if (!memory_journal.CanUndo() || !gpu_journal.CanUndo() || !services_journal.CanUndo()) {
        LOG_WARNING(Core,
                    "SaveState::Restore: refusing '{}': the changes since the save cannot be "
                    "undone (memory journal {}, GPU journal {}, services journal {})",
                    path, memory_journal.CanUndo() ? "ok" : "unusable",
                    gpu_journal.CanUndo() ? "ok" : "unusable",
                    services_journal.CanUndo() ? "ok" : "unusable");
        if (failure != nullptr) {
            *failure = RestoreFailure::MemoryLayoutChanged;
        }
        return false;
    }
    {
        u32 lost_regions = 0;
        for (const auto& [address, size] : region_ranges) {
            if (is_excluded_stack(address, size) ||
                memory.IsValidVirtualAddressRange(address, size) ||
                memory_journal.WillRestore(address, size)) {
                continue;
            }
            if (lost_regions < 4) {
                LOG_WARNING(Core, "SaveState::Restore: {} byte(s) at {:#x} are no longer mapped",
                            size, address);
            }
            lost_regions++;
        }
        if (lost_regions > 0) {
            LOG_WARNING(Core,
                        "SaveState::Restore: refusing '{}': {} region(s) it holds are no longer "
                        "mapped, so the guest changed its memory layout since the save",
                        path, lost_regions);
            if (failure != nullptr) {
                *failure = RestoreFailure::MemoryLayoutChanged;
            }
            return false;
        }
    }
    {
        const size_t memory_steps = memory_journal.Size();
        const size_t gpu_steps = gpu_journal.Size();
        const size_t services_steps = services_journal.Size();
        const bool memory_ok = memory_journal.UndoAll();
        const bool gpu_ok = gpu_journal.UndoAll();
        const bool services_ok = services_journal.UndoAll();
        if (!memory_ok || !gpu_ok || !services_ok) {
            LOG_ERROR(Core,
                      "SaveState::Restore: undoing the changes since the save failed in part "
                      "(memory {}, GPU {}, services {})",
                      memory_ok ? "ok" : "failed", gpu_ok ? "ok" : "failed",
                      services_ok ? "ok" : "failed");
        }
        if (memory_steps > 0 || gpu_steps > 0 || services_steps > 0) {
            LOG_INFO(Core,
                     "SaveState::Restore: undid {} memory mapping, {} GPU memory and {} service "
                     "object change(s) since the save",
                     memory_steps, gpu_steps, services_steps);
        }
    }

    std::vector<u8> live_buffer;
    u64 written_bytes = 0;
    std::vector<u8> buffer;
    // Read back with a single ReadSpan() per region - same reasoning as Capture()'s encoded
    // buffer, avoids one small fread() per 4K chunk.
    std::vector<u8> encoded;
    u32 sleeping_stack_regions = 0;
    u32 unmapped_regions = 0;
    size_t translated_words = 0;
    for (u32 i = 0; i < region_count; i++) {
        u64 address = 0;
        u64 size = 0;
        u64 encoded_size = 0;
        if (!file.ReadObject(address) || !file.ReadObject(size) || !file.ReadObject(encoded_size)) {
            LOG_ERROR(Core, "SaveState::Restore: truncated region table in '{}'", path);
            return false;
        }

        encoded.resize(encoded_size);
        if (file.ReadSpan<u8>(encoded) != encoded_size) {
            LOG_ERROR(Core, "SaveState::Restore: truncated region data in '{}'", path);
            return false;
        }

        buffer.resize(size);
        size_t encoded_pos = 0;
        for (u64 offset = 0; offset < size; offset += ChunkSize) {
            const size_t chunk_len = static_cast<size_t>(std::min<u64>(ChunkSize, size - offset));
            if (encoded_pos >= encoded.size()) {
                LOG_ERROR(Core, "SaveState::Restore: truncated region data in '{}'", path);
                return false;
            }

            const u8 flag = encoded[encoded_pos++];
            u8* chunk = buffer.data() + offset;
            if (flag != 0) {
                std::memset(chunk, 0, chunk_len);
            } else {
                if (encoded_pos + chunk_len > encoded.size()) {
                    LOG_ERROR(Core, "SaveState::Restore: truncated region data in '{}'", path);
                    return false;
                }
                std::memcpy(chunk, encoded.data() + encoded_pos, chunk_len);
                encoded_pos += chunk_len;
            }
        }

        if (!handle_map.empty() && !is_excluded_stack(address, size)) {
            translated_words += TranslateHandles(buffer.data(), buffer.size(), handle_map,
                                                 handle_low, handle_high);
        }

        if (is_excluded_stack(address, size)) {
            LOG_DEBUG(Core,
                       "SaveState::Restore: leaving {} byte(s) at {:#x} untouched - owned by a "
                       "thread still asleep in a kernel wait",
                       size, address);
            sleeping_stack_regions++;
            continue;
        }

        // A region that is (in part) still not mapped after the undo above is skipped; the pages of
        // it that are mapped were written.
        // Only what differs from the memory as it is now is written, through WriteBlock() so the
        // GPU emulation is told those pages changed. Writing everything that way also told it that
        // every page changed, including the ones backing what the GPU itself drew or computed when
        // the level loaded, which it then threw away and reloaded from guest memory that never
        // held it (Garfield: the HUD came back, the 3D scene stayed black).
        if (!WriteChanged(memory, address, buffer.data(), size, live_buffer, written_bytes)) {
            LOG_WARNING(Core,
                       "SaveState::Restore: {} byte(s) at {:#x} are no longer fully mapped - "
                       "skipping (already-mapped pages within this region were still written)",
                       size, address);
            unmapped_regions++;
            continue;
        }
    }

    // Everything was read and validated successfully - only now overwrite CPU state,
    // so a truncated/corrupt file never leaves the guest half-restored. Same exclusion
    // as above: a still-sleeping thread's context is left exactly as its live fiber
    // expects to find it.
    u32 rewound_parked = 0;
    for (size_t i = 0; i < live_threads.size(); i++) {
        if (IsParkedInKernel(*live_threads[i])) {
            // A sleeping thread keeps its live registers and stack, except one asleep in the same
            // kernel call as at the save that is either a thread the guest destroyed and created
            // again since (same instruction, same lock and condition variable) or one moved back to
            // its saved condition variable (move_wait). It gets its saved registers: when it wakes,
            // the kernel finishes that call and resumes it from them, so it carries on in the saved
            // world, on its saved stack, instead of in the later one - where a recreated worker of
            // Super Mario 3D World had already finished the job the restored main thread handed it
            // again, and went back to sleep without doing it (the game then waited forever).
            const auto& live = live_threads[i]->GetContext();
            const auto& saved = thread_contexts[i];
            const bool recreated = live_threads[i]->GetThreadId() != saved_threads[i].id;
            const bool same_wait =
                live.pc == saved.pc && live.r[0] == saved.r[0] && live.r[1] == saved.r[1];
            if (!(recreated && same_wait) && !move_wait[i]) {
                continue;
            }
            rewound_parked++;
        }
        auto& context = live_threads[i]->GetContext();
        context = thread_contexts[i];
        for (auto& value : context.r) {
            for (const auto& [saved, live] : handle_map) {
                if (value == saved) {
                    value = live;
                    break;
                }
            }
        }
        // WaitProcessWideKeyAtomic(mutex = x0, condition variable = x1, tag = x2).
        if (move_wait[i] &&
            !live_threads[i]->RetargetConditionVariableForRestore(
                system.Kernel(), Common::ProcessAddress(context.r[0]),
                Common::AlignDown(context.r[1], sizeof(u32)), static_cast<u32>(context.r[2]))) {
            LOG_ERROR(Core, "SaveState::Restore: thread {} could not be moved to its saved wait", i);
        }
    }

    if (rewound_parked > 0) {
        LOG_INFO(Core, "SaveState::Restore: {} sleeping thread(s) given their saved registers",
                 rewound_parked);
    }

    // Give the live threads and their handles the names the restored memory knows them by.
    u32 renamed_threads = 0;
    for (u32 i = 0; i < thread_count; i++) {
        if (live_threads[i]->GetThreadId() != saved_threads[i].id) {
            live_threads[i]->SetThreadIdForRestore(saved_threads[i].id);
            renamed_threads++;
        }
    }
    if (renamed_threads > 0 || !handle_map.empty()) {
        LOG_INFO(Core,
                 "SaveState::Restore: {} thread id(s) put back, {} thread handle(s) renamed in {} "
                 "memory word(s)",
                 renamed_threads, handle_map.size(), translated_words);
    }

    LOG_INFO(Core, "SaveState::Restore: {} byte(s) differed from the live memory and were written",
             written_bytes);
    LOG_INFO(Core,
              "SaveState::Restore: loaded {} thread(s), {} region(s) from '{}' ({} region(s) left "
              "untouched - {} thread(s) still asleep in a kernel wait, {} region(s) skipped - no "
              "longer mapped)",
              thread_count, region_count, path, sleeping_stack_regions + unmapped_regions,
              sleeping_stack_tops.size(), unmapped_regions);
    return true;
}

bool HasRiskyPendingWaits(Core::System& system) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        return false;
    }

    for (auto& thread : process->GetThreadList()) {
        if (thread.GetWaitReasonForDebugging() ==
            Kernel::ThreadWaitReasonForDebugging::Arbitration) {
            return true;
        }
    }
    return false;
}

} // namespace Core::SaveState
