// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cstring>
#include <map>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <span>
#include <thread>
#include <utility>
#include <vector>

#include "common/alignment.h"
#include "common/div_ceil.h"
#include "common/fs/file.h"
#include "common/fs/fs.h"
#include "common/logging.h"
#include "common/scope_exit.h"
#include "core/core.h"
#include "core/hle/ipc.h"
#include "core/hle/kernel/k_auto_object.h"
#include "core/hle/kernel/k_handle_table.h"
#include "core/hle/kernel/k_hardware_timer.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/k_scoped_resource_reservation.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/svc.h"
#include "core/hle/kernel/svc_common.h"
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
// 9: each saved thread's priority, ideal core and affinity mask, to create it again
// 10: and the kernel call it is in
constexpr u32 CurrentVersion = 10;

// Regions are stored in fixed-size chunks, each preceded by a one-byte flag: 1 if the whole
// chunk is zero (skip writing/reading the actual bytes), 0 if it isn't (bytes follow as
// usual). Measured on a real, demanding title (Super Mario 3D World): ~80% of captured bytes
// are zero, and ~68% of 4096-byte-aligned chunks are entirely zero - most of a game's
// reserved-but-untouched heap never gets written to. Skipping those chunks shrinks a ~3.4GB
// capture to roughly a third of that, for the cost of one memcmp per chunk - far cheaper than
// general-purpose compression, and unlike compression this doesn't cost anything on the CPU
// budget during Restore()'s memory writes (a zero chunk there is just a memset).
constexpr size_t ChunkSize = 4096;
constexpr u64 GuestPageSize = 0x1000;
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
    // What a load needs to create the thread again when it has ended since (see PlanLoad).
    s32 priority;
    s32 ideal_core;
    u64 affinity_mask;
    u32 svc_id; // the kernel call it is in (or made last), see KThread::GetSvcId
    u32 padding;
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

// The address a thread asleep on a condition variable or in an address-arbiter wait waits on. An
// arbiter wait (WaitForAddress) keeps its address in the condition variable key, not the address
// key: that one still held the lock of the thread's last mutex or condition variable wait, so two
// arbiter waits on different addresses looked alike.
u64 WaitKey(const Kernel::KThread& thread) {
    if (thread.GetWaitReasonForDebugging() == Kernel::ThreadWaitReasonForDebugging::Arbitration) {
        return thread.GetAddressArbiterKey();
    }
    return thread.GetAddressKey().GetValue();
}

// Whether a thread the save does not have can be ended now: already ended, or asleep inside a
// kernel call, which it then leaves without running any more guest code. One running, or waiting
// for a service's answer, is in the middle of something: try again.
bool CanEndThread(Kernel::KThread& thread) {
    if (thread.GetState() == Kernel::ThreadState::Terminated) {
        return true;
    }
    return thread.GetState() == Kernel::ThreadState::Waiting && thread.IsCallingSvc() &&
           IsParkedInKernel(thread) &&
           thread.GetWaitReasonForDebugging() != Kernel::ThreadWaitReasonForDebugging::Suspended;
}

// Ends threads the game started after the save (see Restore) the way the kernel ends the threads
// of an exiting process: each is woken from its wait with "termination requested" and exits before
// it runs any more guest code. The game's handles to them are closed too, as the game would have
// closed them once they ended: the restored memory holds none of them, so nothing else ever would.
// Then the kernel lets go of them, which also frees their thread-local storage for the threads the
// restored game starts again later.
void EndThreadsNotInSave(Core::System& system, Kernel::KProcess& process,
                         const std::vector<Kernel::KThread*>& threads) {
    auto& kernel = system.Kernel();
    const auto started = std::chrono::steady_clock::now();
    std::vector<Kernel::KThread*> held;
    std::string described;
    for (auto* thread : threads) {
        described +=
            fmt::format("{}id {} tls {:#x} {}", described.empty() ? "" : "; ",
                        thread->GetThreadId(), thread->GetTlsAddress().GetValue(),
                        thread->GetState() == Kernel::ThreadState::Terminated
                            ? std::string{"already ended"}
                            : fmt::format("asleep ({})",
                                          static_cast<u32>(thread->GetWaitReasonForDebugging())));
        if (thread->Open(kernel)) {
            held.push_back(thread);
        }
    }
    for (auto* thread : held) {
        if (thread->GetState() != Kernel::ThreadState::Terminated) {
            thread->RequestTerminate(kernel);
        }
    }

    // Each runs once more, on its own, to exit (the game stays paused).
    const auto deadline = started + std::chrono::seconds(2);
    const auto all_ended = [&] {
        return std::ranges::all_of(held, [&](auto* t) { return t->IsSignaled(kernel); });
    };
    while (!all_ended() && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    const bool ended = all_ended();

    std::vector<Kernel::Handle> handles;
    process.GetHandleTable().ForEachEntry(
        kernel, [&](s32 index, u32 linear_id, Kernel::KAutoObject* object) {
            if (std::ranges::find(held, object, [](auto* t) {
                    return static_cast<Kernel::KAutoObject*>(t);
                }) != held.end()) {
                handles.push_back(EncodeHandleValue(static_cast<u32>(index), linear_id));
            }
        });
    u32 closed = 0;
    for (const auto handle : handles) {
        if (process.GetHandleTable().Remove(kernel, handle)) {
            closed++;
        }
    }
    for (auto* thread : held) {
        thread->Close(kernel);
    }

    // Wait for them to leave the thread list. Compared by address only: they may be gone already.
    const auto in_list = [&] {
        Kernel::KScopedLightLock list_lock{process.GetListLock()};
        for (auto& thread : process.GetThreadList()) {
            if (std::ranges::find(held, std::addressof(thread)) != held.end()) {
                return true;
            }
        }
        return false;
    };
    while (in_list() && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    const bool gone = !in_list();
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - started)
                        .count();
    if (ended && gone) {
        LOG_INFO(Core,
                 "SaveState::Restore: ended {} thread(s) started after the save or to be created "
                 "again ({}): {} handle(s) closed, gone after {} ms",
                 threads.size(), described, closed, ms);
    } else {
        LOG_WARNING(
            Core,
            "SaveState::Restore: ending {} thread(s) started after the save or to be created "
            "again ({}): ended {}, "
            "gone from the thread list {}, {} handle(s) closed, after {} ms",
            threads.size(), described, ended, gone, closed, ms);
    }
}

// Creates a saved thread again for a load (see PlanLoad): in the process, at its saved
// thread-local storage, priority and cores, held paused as the game is, with a handle in the
// process' handle table. Its registers and wait are set later, after the memory is written. Null
// if it could not be created; `handle` receives its handle.
Kernel::KThread* CreateThreadForRestore(Core::System& system, Kernel::KProcess& process,
                                        const SavedThread& saved,
                                        const Kernel::Svc::ThreadContext& context,
                                        Kernel::Handle* handle) {
    auto& kernel = system.Kernel();
    Kernel::KScopedResourceReservation reservation(kernel, std::addressof(process),
                                                   Kernel::LimitableResource::ThreadCountMax, 1,
                                                   kernel.HardwareTimer().GetTick() + 100000000);
    if (!reservation.Succeeded()) {
        LOG_ERROR(Core, "SaveState::Restore: thread id {}: no thread left to create", saved.id);
        return nullptr;
    }
    Kernel::KThread* thread = Kernel::KThread::Create(kernel);
    if (thread == nullptr) {
        return nullptr;
    }
    SCOPE_EXIT {
        // The process' thread list and the handle table hold their own references.
        thread->Close(kernel);
    };
    // A thread can have no ideal core (Svc::IdealCoreDontCare, -1), but it must be created on
    // one: the first core it may run on, its saved ideal core and affinity are set below.
    s32 create_core = saved.ideal_core;
    if (create_core < 0) {
        create_core = saved.affinity_mask != 0 ? std::countr_zero(saved.affinity_mask)
                                               : process.GetIdealCoreId();
    }
    {
        Kernel::KScopedLightLock lk{process.GetStateLock()};
        const Result result = Kernel::KThread::InitializeUserThread(
            system, thread, context.pc, context.r[0], context.sp, saved.priority, create_core,
            std::addressof(process));
        if (R_FAILED(result)) {
            LOG_ERROR(Core, "SaveState::Restore: thread id {}: could not be created ({:#x})",
                      saved.id, result.raw);
            return nullptr;
        }
    }
    // The slot the thread it replaces just left is freed when the kernel lets go of that thread,
    // a moment after it leaves the thread list.
    Result moved = ResultUnknown;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(200);
    while (R_FAILED(moved = thread->MoveThreadLocalRegionForRestore(
                        kernel, Common::ProcessAddress(saved.tls))) &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (R_FAILED(moved)) {
        LOG_ERROR(Core,
                  "SaveState::Restore: thread id {}: its thread-local storage {:#x} is taken "
                  "({:#x})",
                  saved.id, saved.tls, moved.raw);
        kernel.GlobalSchedulerContext().RemoveThread(thread);
        return nullptr;
    }
    reservation.Commit();
    if (saved.affinity_mask != 0 &&
        (saved.ideal_core != create_core || saved.affinity_mask != (1ULL << create_core))) {
        (void)thread->SetCoreMask(kernel, saved.ideal_core, saved.affinity_mask);
    }
    Kernel::KThread::Register(kernel, thread);
    if (R_FAILED(process.GetHandleTable().Add(kernel, handle, thread))) {
        LOG_ERROR(Core, "SaveState::Restore: thread id {}: no handle left for it", saved.id);
        *handle = Kernel::Svc::InvalidHandle;
    }
    // Paused like the rest of the game: it runs when the game is let run again.
    thread->RequestSuspend(kernel, Kernel::SuspendType::System);
    thread->GetContext() = context;
    return thread;
}

// Writes `size` bytes of `data` to `address`, except the parts inside the `keep` ranges (address,
// size), which are not touched at all.
bool WriteExcept(Core::Memory::Memory& memory, u64 address, const u8* data, u64 size,
                 std::span<const std::pair<u64, u64>> keep) {
    bool ok = true;
    const u64 end = address + size;
    for (u64 pos = address; pos < end;) {
        u64 write_end = end;
        u64 skip_to = pos;
        for (const auto& [start, length] : keep) {
            if (start + length <= pos || start >= end) {
                continue;
            }
            if (start <= pos) {
                skip_to = std::max(skip_to, start + length);
            } else {
                write_end = std::min(write_end, start);
            }
        }
        if (skip_to > pos) {
            pos = std::min(skip_to, end);
            continue;
        }
        ok = memory.WriteBlock(pos, data + (pos - address), write_end - pos) && ok;
        pos = write_end;
    }
    return ok;
}

// Writes the 4 KiB chunks of `data` that differ from guest memory, in runs, leaving the `keep`
// ranges as they are. A false return means part of the range is not mapped.
bool WriteChanged(Core::Memory::Memory& memory, u64 address, const u8* data, u64 size,
                  std::vector<u8>& live, u64& written, std::span<const std::pair<u64, u64>> keep) {
    live.resize(size);
    if (!memory.ReadBlockUnsafe(address, live.data(), size)) {
        return WriteExcept(memory, address, data, size, keep);
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
            ok = WriteExcept(memory, address + run_start, data + run_start, run_end - run_start,
                             keep) &&
                 ok;
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
        s32 ideal_core = 0;
        u64 affinity_mask = 0;
        if (R_FAILED(threads[i]->GetCoreMask(system.Kernel(), &ideal_core, &affinity_mask))) {
            ideal_core = threads[i]->GetActiveCore();
            affinity_mask = 1ULL << ideal_core;
        }
        saved.push_back({threads[i]->GetThreadId(), threads[i]->GetTlsAddress().GetValue(),
                         handles[i].index, handles[i].linear_id, threads[i]->GetBasePriority(),
                         ideal_core, affinity_mask, threads[i]->GetSvcId(), 0});
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
    return CloseServiceGate(gate, std::chrono::milliseconds(500));
}

// The saves made by Capture, by file: the moment each was made (UndoJournal::Mark), which a load
// of it undoes the journals back to, and whether a load of an earlier save went back past it.
struct SaveMark {
    u64 mark;
    bool loaded_past;
};
std::mutex save_marks_mutex;
std::map<std::string, SaveMark> save_marks;

std::optional<SaveMark> FindSaveMark(const std::string& path) {
    std::scoped_lock lock{save_marks_mutex};
    const auto it = save_marks.find(path);
    if (it == save_marks.end() || !UndoJournal::InThisRun(it->second.mark)) {
        return std::nullopt;
    }
    return it->second;
}

// Whether the three journals can undo everything since the save made at `mark`.
bool CanUndoTo(u64 mark) {
    return UndoJournal::Memory().CanUndoTo(mark) && UndoJournal::Gpu().CanUndoTo(mark) &&
           UndoJournal::Services().CanUndoTo(mark);
}
} // namespace

bool CloseServiceGate(std::unique_lock<std::shared_timed_mutex>& gate,
                      std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (!gate.owns_lock() && !gate.try_lock() && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return gate.owns_lock();
}

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

namespace {
// One thread's WaitSignature word.
u32 WaitWord(Core::System& system, const Kernel::KThread& thread) {
    // The kernel state (running/waiting/...) as well as the wait reason: a save/load pair
    // that disagreed only on the state (the main thread waiting at the save, runnable at
    // the load) froze the game while a reason-only signature said "same".
    const u32 state = static_cast<u32>(static_cast<u16>(thread.GetState()) &
                                       static_cast<u16>(Kernel::ThreadState::Mask));
    u32 word = (state << 8) | static_cast<u32>(thread.GetWaitReasonForDebugging());
    // Whether it is inside a kernel call: its saved registers then still hold the call's
    // arguments, and the call writes its results when the thread resumes. A thread caught
    // runnable at the instruction after a call is inside it or already past it, and the two
    // cannot be swapped: restoring the registers of one onto the other gave a game's main
    // thread its request's session handle as the call's result.
    if (thread.IsCallingSvc()) {
        word |= 0x80;
    }
    // And what it waits for. A thread restored onto a wait for another object stays there:
    // a worker sat on one condition variable at the save and on another at the load, the
    // restored game signalled the first one and then waited forever for the worker to finish.
    const auto reason = thread.GetWaitReasonForDebugging();
    if (state == static_cast<u32>(Kernel::ThreadState::Waiting)) {
        u64 what = 0;
        if (reason == Kernel::ThreadWaitReasonForDebugging::ConditionVar ||
            reason == Kernel::ThreadWaitReasonForDebugging::Arbitration) {
            what = WaitKey(thread);
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
    return word;
}
} // namespace

std::vector<u32> WaitSignature(Core::System& system, const std::vector<u64>* saved_tls) {
    std::vector<u32> signature;
    if (auto* process = system.ApplicationProcess()) {
        for (auto& thread : process->GetThreadList()) {
            if (saved_tls != nullptr &&
                std::ranges::find(*saved_tls, thread.GetTlsAddress().GetValue()) ==
                    saved_tls->end()) {
                continue;
            }
            signature.push_back(WaitWord(system, thread));
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

namespace {
// How a load treats one saved thread (see PlanLoad).
enum class SlotPlan : u8 {
    // The live thread is as at the save (or asleep on another condition variable, which Restore
    // moves it from, see SignaturesMatch).
    AsSaved,
    // The live thread is running game code, not inside a kernel call: it gets the saved registers,
    // and is put back to sleep on its saved condition variable if it slept on one.
    Reuse,
    // The thread has ended since the save, or is in or just back from a kernel call it can be
    // ended in: it is (ended and) created again from the save.
    Recreate,
    // None of these: the load has to wait (or cannot be made).
    Blocked,
};

struct LoadPlan {
    std::vector<Kernel::KThread*> live; // per saved thread, by thread-local storage; null if ended
    std::vector<SlotPlan> slots;
    std::vector<Kernel::KThread*> extra; // live threads in a slot the save does not have
    bool ok = true;
    bool ended_blocked = false; // a saved thread has ended and cannot be created again
    std::string blocked;        // for the log
};

constexpr u32 StateOf(u32 word) {
    return (word >> 8) & 0xFF;
}
constexpr u32 ReasonOf(u32 word) {
    return word & 0x7F;
}
constexpr bool InSvc(u32 word) {
    return (word & 0x80) != 0;
}

// Whether a saved thread waited inside a kernel call, for `reason`.
bool IsSavedWait(u32 saved_word, Kernel::ThreadWaitReasonForDebugging reason) {
    return StateOf(saved_word) == static_cast<u32>(Kernel::ThreadState::Waiting) &&
           InSvc(saved_word) && ReasonOf(saved_word) == static_cast<u32>(reason);
}

// Whether a live thread whose wait matches a saved one's is also back from the same kernel call. A
// thread runnable inside a call has finished it and is about to return, and its registers are made
// from that call's result when it does, whatever a load puts in them; its wait does not say which
// call it was. One saved back from a service request and found back from a timed condition
// variable wait was given that wait's TimedOut as its request's result, and the game aborted
// (2001-0117).
bool BackFromSameCall(const Kernel::KThread& thread, u32 saved_word, u32 saved_svc_id) {
    if (StateOf(saved_word) != static_cast<u32>(Kernel::ThreadState::Runnable) ||
        !InSvc(saved_word)) {
        return true;
    }
    return thread.GetSvcId() == saved_svc_id;
}

// Whether a saved thread slept on a condition variable (WaitProcessWideKeyAtomic). The kernel
// gives a thread waiting for a user-mode mutex (ArbitrateLock) the same wait reason: such a thread
// put on a condition variable with that call's arguments was never woken, and its "timeout" (a
// leftover x3) ended the call with a timeout the game aborted on (2001-0117).
bool IsSavedConditionVariableWait(u32 saved_word, u32 saved_svc_id) {
    return IsSavedWait(saved_word, Kernel::ThreadWaitReasonForDebugging::ConditionVar) &&
           saved_svc_id == static_cast<u32>(Kernel::Svc::SvcId::WaitProcessWideKeyAtomic);
}

// Whether a saved thread waited in a call it is put back into by making the call again: waiting
// for objects (WaitSynchronization), for a service's answer (SendSyncRequest) or for a user-mode
// mutex (ArbitrateLock). It starts at the call's instruction with the call's saved arguments (see
// IsReissuableCallBefore), so it waits on the same objects again, sends the request again (the
// save was made before the answer came), or asks for the mutex again: the call returns at once if
// the mutex word no longer says that owner has it, and the game's lock then tries again.
bool IsReissuedWait(u32 saved_word, u32 saved_svc_id) {
    using Kernel::ThreadWaitReasonForDebugging;
    return IsSavedWait(saved_word, ThreadWaitReasonForDebugging::Synchronization) ||
           IsSavedWait(saved_word, ThreadWaitReasonForDebugging::IPC) ||
           (IsSavedWait(saved_word, ThreadWaitReasonForDebugging::ConditionVar) &&
            saved_svc_id == static_cast<u32>(Kernel::Svc::SvcId::ArbitrateLock));
}

// The timeout (in ns) a thread asleep in a kernel call at the save made the call with, for the
// calls that take one in x3 (WaitSynchronization, WaitProcessWideKeyAtomic, WaitForAddress).
std::optional<s64> SavedCallTimeout(u32 saved_svc_id, const Kernel::Svc::ThreadContext& context) {
    using Kernel::Svc::SvcId;
    switch (static_cast<SvcId>(saved_svc_id)) {
    case SvcId::WaitSynchronization:
    case SvcId::WaitProcessWideKeyAtomic:
    case SvcId::WaitForAddress:
        return static_cast<s64>(context.r[3]);
    default:
        return std::nullopt;
    }
}

// Whether a thread can be created again in the state the save holds it in: asleep on a condition
// variable (put back to sleep there, see KConditionVariable::WaitForRestore), asleep in
// SleepThread or yielding in it (runnable inside the call): it starts as if the call had ended,
// as the call returns nothing; in a wait it is put back into by making the call again
// (IsReissuedWait), or running game code. Not asleep in any other call (its result and the
// kernel's state cannot be made up), and not between another kernel call's end and its return.
bool CanBeCreatedAs(u32 saved_word, u32 saved_svc_id) {
    using Kernel::ThreadState;
    using Kernel::ThreadWaitReasonForDebugging;
    if (IsReissuedWait(saved_word, saved_svc_id) ||
        IsSavedConditionVariableWait(saved_word, saved_svc_id)) {
        return true;
    }
    if (StateOf(saved_word) == static_cast<u32>(ThreadState::Waiting) && InSvc(saved_word)) {
        return ReasonOf(saved_word) == static_cast<u32>(ThreadWaitReasonForDebugging::Sleep);
    }
    if (StateOf(saved_word) == static_cast<u32>(ThreadState::Runnable) && InSvc(saved_word)) {
        return saved_svc_id == static_cast<u32>(Kernel::Svc::SvcId::SleepThread);
    }
    return StateOf(saved_word) == static_cast<u32>(ThreadState::Runnable);
}

// Whether a re-issued wait (IsReissuedWait) can make its saved call again from the instruction
// before `pc`, where a thread inside a call resumes: the saved call has to be the one for that
// wait, and the instruction that call (`svc #id`, 0xD4000001 | id << 5), or the branch native code
// execution (NCE) put in its place, to code that makes the same call.
bool IsReissuableCallBefore(Core::Memory::Memory& memory, u64 pc, u32 saved_word, u32 svc_id) {
    using Kernel::ThreadWaitReasonForDebugging;
    using Kernel::Svc::SvcId;
    const auto svc = static_cast<SvcId>(svc_id);
    bool right_call = false;
    switch (static_cast<ThreadWaitReasonForDebugging>(ReasonOf(saved_word))) {
    case ThreadWaitReasonForDebugging::Synchronization:
        right_call = svc == SvcId::WaitSynchronization;
        break;
    case ThreadWaitReasonForDebugging::IPC:
        right_call = svc == SvcId::SendSyncRequest || svc == SvcId::SendSyncRequestWithUserBuffer;
        break;
    case ThreadWaitReasonForDebugging::ConditionVar:
        right_call = svc == SvcId::ArbitrateLock;
        break;
    default:
        break;
    }
    if (!right_call || pc < 4 || !memory.IsValidVirtualAddressRange(pc - 4, 4)) {
        return false;
    }
    const u32 instruction = memory.Read32(pc - 4);
    return instruction == (0xD4000001 | (svc_id << 5)) || (instruction & 0xFC000000) == 0x14000000;
}

// Whether a live thread can be ended so that it is created again: one CanEndThread allows (asleep
// inside a kernel call other than a service request), or one woken from a call that holds nothing
// once it has ended (a sleep, a wait for objects or an address, a service request whose answer
// it has), which then exits before it runs any more game code.
bool CanEndToCreateAgain(Kernel::KThread& thread) {
    if (CanEndThread(thread)) {
        return true;
    }
    if (thread.GetState() != Kernel::ThreadState::Runnable || !thread.IsCallingSvc()) {
        return false;
    }
    switch (static_cast<Kernel::Svc::SvcId>(thread.GetSvcId())) {
    case Kernel::Svc::SvcId::SleepThread:
    case Kernel::Svc::SvcId::WaitSynchronization:
    case Kernel::Svc::SvcId::SendSyncRequest:
    case Kernel::Svc::SvcId::SendSyncRequestWithUserBuffer:
    case Kernel::Svc::SvcId::WaitForAddress:
        return true;
    default:
        return false;
    }
}

// Matches the live threads to the saved ones by thread-local storage (a thread created again gets
// its slot back, see KThread::MoveThreadLocalRegionForRestore) and decides, for each, how a load
// treats it. Without `allow_rebuild`, every thread must be as at the save.
LoadPlan PlanLoad(Core::System& system, Kernel::KProcess& process,
                  const std::vector<u32>& saved_signature, const std::vector<u64>& saved_tls,
                  const std::vector<u32>& saved_svc, bool allow_rebuild) {
    LoadPlan plan;
    const size_t count = std::min(saved_signature.size(), saved_tls.size());
    plan.live.assign(count, nullptr);
    plan.slots.assign(count, SlotPlan::Blocked);
    for (auto& thread : process.GetThreadList()) {
        const auto it = std::ranges::find(saved_tls, thread.GetTlsAddress().GetValue());
        const auto index = static_cast<size_t>(it - saved_tls.begin());
        if (it == saved_tls.end() || index >= count) {
            plan.extra.push_back(std::addressof(thread));
        } else {
            plan.live[index] = std::addressof(thread);
        }
    }
    for (size_t i = 0; i < count; i++) {
        auto* thread = plan.live[i];
        const u32 live_word = thread != nullptr ? WaitWord(system, *thread) : 0;
        const u32 saved_svc_id = i < saved_svc.size() ? saved_svc[i] : 0;
        if (thread != nullptr &&
            SignaturesMatch(std::vector<u32>{saved_signature[i]}, std::vector<u32>{live_word}) &&
            BackFromSameCall(*thread, saved_signature[i], saved_svc_id)) {
            plan.slots[i] = SlotPlan::AsSaved;
            continue;
        }
        if (allow_rebuild && CanBeCreatedAs(saved_signature[i], saved_svc_id)) {
            if (thread == nullptr) {
                plan.slots[i] = SlotPlan::Recreate;
                continue;
            }
            if (thread->GetState() == Kernel::ThreadState::Runnable && !thread->IsCallingSvc()) {
                plan.slots[i] = SlotPlan::Reuse;
                continue;
            }
            if (CanEndToCreateAgain(*thread)) {
                plan.slots[i] = SlotPlan::Recreate;
                continue;
            }
        }
        plan.ok = false;
        if (thread == nullptr) {
            plan.ended_blocked = true;
        }
        if (!plan.blocked.empty()) {
            plan.blocked += "; ";
        }
        plan.blocked +=
            thread == nullptr
                ? fmt::format("thread {}: saved {}, ended", i, DescribeWaitWord(saved_signature[i]))
                : fmt::format("thread {}: saved {} (call {:#x}), live {} (last call {:#x})", i,
                              DescribeWaitWord(saved_signature[i]), saved_svc_id,
                              DescribeWaitWord(live_word), thread->GetSvcId());
    }
    for (auto* thread : plan.extra) {
        if (!CanEndThread(*thread)) {
            plan.ok = false;
            if (!plan.blocked.empty()) {
                plan.blocked += "; ";
            }
            plan.blocked += fmt::format(
                "thread id {} (tls {:#x}), started after the save, is not asleep in a kernel call",
                thread->GetThreadId(), thread->GetTlsAddress().GetValue());
        }
    }
    return plan;
}
} // namespace

LoadCheck CheckLoad(Core::System& system, const std::vector<u32>& saved_signature,
                    const std::vector<u64>& saved_tls, const std::vector<u32>& saved_svc,
                    bool allow_rebuild) {
    LoadCheck check;
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        return check;
    }
    const LoadPlan plan =
        PlanLoad(system, *process, saved_signature, saved_tls, saved_svc, allow_rebuild);
    check.ok = plan.ok;
    check.ended = static_cast<u32>(std::ranges::count(plan.live, nullptr));
    check.ended_blocked = plan.ended_blocked;
    check.rebuilt = static_cast<u32>(std::ranges::count_if(plan.slots, [](SlotPlan slot) {
        return slot == SlotPlan::Reuse || slot == SlotPlan::Recreate;
    }));
    check.blocked = plan.blocked;
    return check;
}

bool HasSaveFromThisSession(const std::string& path) {
    const auto save = FindSaveMark(path);
    return save && !save->loaded_past;
}

bool CanStillLoad(const std::string& path) {
    const auto save = FindSaveMark(path);
    return save && !save->loaded_past && CanUndoTo(save->mark);
}

void DiscardSave(const std::string& path) {
    std::scoped_lock lock{save_marks_mutex};
    save_marks.erase(path);
    if (Common::FS::Exists(path) && !Common::FS::RemoveFile(path)) {
        LOG_WARNING(Core, "SaveState::DiscardSave: could not delete '{}'", path);
    }
}

bool ReadSavedSignature(const std::string& path, std::vector<u32>& out, std::vector<u64>* tls,
                        std::vector<u32>* svc) {
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
    if (thread_count > 0 && file.ReadSpan<u32>(out) != out.size()) {
        return false;
    }
    if (tls == nullptr) {
        return true;
    }
    // The saved threads follow their contexts.
    std::vector<SavedThread> saved(thread_count);
    if (!file.Seek(static_cast<s64>(thread_count * sizeof(Kernel::Svc::ThreadContext)),
                   Common::FS::SeekOrigin::CurrentPosition) ||
        (thread_count > 0 && file.ReadSpan<SavedThread>(saved) != saved.size())) {
        return false;
    }
    tls->clear();
    for (const auto& thread : saved) {
        tls->push_back(thread.tls);
    }
    if (svc != nullptr) {
        svc->clear();
        for (const auto& thread : saved) {
            svc->push_back(thread.svc_id);
        }
    }
    return true;
}

size_t CopyGpuDrawnImages(Core::System& system) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        return 0;
    }
    // Capture reads memory without asking the GPU for its copy, so what the GPU drew into images
    // was not in the save; a load that rebuilt those images from memory drew a black 3D scene.
    // Copy those images back to memory first: only the ones the GPU wrote since the last copy.
    auto& memory = system.ApplicationMemory();
    const auto start = std::chrono::steady_clock::now();
    size_t runs = 0;
    for (const auto& region : EnumerateScannableRegions(process->GetPageTable())) {
        runs += memory.DownloadGpuWrittenImages(region.address, region.size);
    }
    LOG_INFO(Core, "SaveState: copied GPU-drawn images back in {} run(s), {} ms", runs,
             std::chrono::duration_cast<std::chrono::milliseconds>(
                 std::chrono::steady_clock::now() - start)
                 .count());
    return runs;
}

namespace {
// A load writes back what the save copied from the GPU-drawn images (CopyGpuDrawnImages), but only
// the pages that differ from memory now. What the GPU drew since the save is in its images, not in
// memory, so their pages look unchanged, are not written and the images keep it: a title screen
// loaded after starting a run was drawn with that run's images and came out black. Drop it before
// the load writes memory: those images are loaded again from the restored memory when next used.
size_t DiscardGpuDrawnImages(Core::System& system) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        return 0;
    }
    auto& memory = system.ApplicationMemory();
    size_t runs = 0;
    for (const auto& region : EnumerateScannableRegions(process->GetPageTable())) {
        runs += memory.DiscardGpuWrittenImages(region.address, region.size);
    }
    return runs;
}
} // namespace

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

    CopyGpuDrawnImages(system);

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

    // From now on, remember how to put back to this moment what a savestate does not hold. What
    // was recorded before the oldest other save that can still be loaded is no longer needed.
    const u64 mark = UndoJournal::Mark();
    u64 keep_from = mark;
    {
        std::scoped_lock lock{save_marks_mutex};
        for (const auto& [other_path, other] : save_marks) {
            if (other_path != path && !other.loaded_past && UndoJournal::InThisRun(other.mark) &&
                CanUndoTo(other.mark)) {
                keep_from = std::min(keep_from, other.mark);
            }
        }
        save_marks[path] = {mark, false};
    }
    UndoJournal::Memory().Start(mark, keep_from);
    UndoJournal::Gpu().Start(mark, keep_from);
    UndoJournal::Services().Start(mark, keep_from);

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

bool Restore(Core::System& system, const std::string& path, RestoreFailure* failure,
             std::unique_lock<std::shared_timed_mutex>* held_gate, bool allow_rebuild) {
    if (failure != nullptr) {
        *failure = RestoreFailure::Other;
    }
    // No service may answer a request while the restore runs (see ServiceReplyGate); the threads
    // are compared once it is closed.
    std::unique_lock gate{ServiceReplyGate(), std::defer_lock};
    const bool gate_held = held_gate != nullptr && held_gate->owns_lock();
    if (!gate_held && !TakeServiceGate(gate)) {
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
        bool read_ok =
            thread_count == 0 || file.ReadSpan<SavedThread>(saved_threads) == saved_threads.size();
        read_ok = read_ok && file.ReadObject(block_count) && block_count <= 1'000'000;
        if (read_ok) {
            saved_blocks.resize(block_count);
            read_ok =
                block_count == 0 || file.ReadSpan<SavedBlock>(saved_blocks) == saved_blocks.size();
        }
        if (!read_ok) {
            LOG_ERROR(Core, "SaveState::Restore: truncated world section in '{}'", path);
            return false;
        }
    }

    // Match the live threads to the saved ones, by thread-local storage, and decide how each is
    // treated (see PlanLoad), before touching anything. Threads the game started after the save in
    // a slot no saved thread had are left out here and ended just before anything is changed
    // (EndThreadsNotInSave): the restored game never started them and holds nothing of theirs. A
    // game started three each time it wrote its own save file and they lived on for a minute or
    // more; every Quick Load in that time was refused for the thread count.
    std::vector<u64> saved_tls;
    std::vector<u32> saved_svc;
    for (const auto& thread : saved_threads) {
        saved_tls.push_back(thread.tls);
        saved_svc.push_back(thread.svc_id);
    }
    const LoadPlan plan =
        PlanLoad(system, *process, saved_signature, saved_tls, saved_svc, allow_rebuild);
    if (!plan.ok) {
        LOG_WARNING(Core,
                    "SaveState::Restore: the guest's threads are not as they were at the save and "
                    "cannot be put back{}, refusing for now: {}",
                    allow_rebuild ? "" : " (rebuilding not allowed yet)", plan.blocked);
        if (failure != nullptr) {
            *failure = RestoreFailure::NotSettled;
        }
        return false;
    }
    std::vector<Kernel::KThread*> live_threads = plan.live;
    const std::vector<Kernel::KThread*>& extra_threads = plan.extra;
    const auto is_rebuilt = [&](u32 i) {
        return plan.slots[i] == SlotPlan::Reuse || plan.slots[i] == SlotPlan::Recreate;
    };
    std::vector<u32> live_signature(thread_count, 0);
    for (u32 i = 0; i < thread_count; i++) {
        if (live_threads[i] != nullptr) {
            live_signature[i] = WaitWord(system, *live_threads[i]);
        }
    }

    // Threads asleep on another condition variable than at the save (same call, same instruction):
    // they get their saved registers and stack and are moved back onto the saved condition
    // variable, so they wait exactly where the restored memory has them waiting. Without this, a
    // worker of Garfield asleep on another condition variable was never woken by the restored game.
    std::vector<bool> move_wait(thread_count, false);
    for (u32 i = 0; i < thread_count; i++) {
        if (plan.slots[i] != SlotPlan::AsSaved || saved_signature[i] == live_signature[i]) {
            continue;
        }
        if (!live_threads[i]->IsWaitingForConditionVariable() ||
            live_threads[i]->GetContext().pc != thread_contexts[i].pc) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} cannot be moved to its saved wait, refusing "
                        "for now: saved {}, live {}; waiting on a condition variable {}, pc {:#x} "
                        "(saved {:#x})",
                        i, DescribeWaitWord(saved_signature[i]),
                        DescribeWaitWord(live_signature[i]),
                        live_threads[i]->IsWaitingForConditionVariable(),
                        live_threads[i]->GetContext().pc, thread_contexts[i].pc);
            if (failure != nullptr) {
                *failure = RestoreFailure::NotSettled;
            }
            return false;
        }
        move_wait[i] = true;
    }

    // A thread in an address-arbiter wait (WaitForAddress) waits on exactly the address it waited
    // on at the save, not only one with the same 16-bit hash in the signature: the save holds the
    // call's arguments in its registers, the address in x0. The kernel's list of who waits on which
    // address is the live one, so only then do it and the restored memory agree.
    for (u32 i = 0; i < thread_count; i++) {
        const auto* thread = live_threads[i];
        if (plan.slots[i] != SlotPlan::AsSaved ||
            thread->GetState() != Kernel::ThreadState::Waiting ||
            thread->GetWaitReasonForDebugging() !=
                Kernel::ThreadWaitReasonForDebugging::Arbitration) {
            continue;
        }
        if (thread_contexts[i].r[0] != thread->GetAddressArbiterKey()) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} waits on address {:#x}, at the save on "
                        "{:#x}, refusing for now",
                        i, thread->GetAddressArbiterKey(), thread_contexts[i].r[0]);
            if (failure != nullptr) {
                *failure = RestoreFailure::NotSettled;
            }
            return false;
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
    //
    // A thread asleep in the same kernel call as at the save (same instruction, same
    // lock and condition variable) gets its saved registers below and its saved stack, as a
    // move_wait thread does, whether the guest destroyed and created it again since or not. Being
    // asleep in the same place does not mean it slept the whole time: it may have woken, worked
    // and gone back to the same wait, and its stack then holds what it worked on after the save
    // (after a Quick Load across scenes, at the next scene change a long-lived thread read
    // through a null pointer forever). One that did sleep the whole time
    // has its saved registers and stack already, so nothing changes for it. A thread whose stack
    // shares a region with one left asleep is left as before, as that region is not written.
    std::vector<bool> rewind_parked(thread_count, false);
    for (u32 i = 0; i < thread_count; i++) {
        if (plan.slots[i] != SlotPlan::AsSaved || !IsParkedInKernel(*live_threads[i]) ||
            move_wait[i]) {
            continue;
        }
        const auto& live = live_threads[i]->GetContext();
        const auto& saved = thread_contexts[i];
        rewind_parked[i] =
            live.pc == saved.pc && live.r[0] == saved.r[0] && live.r[1] == saved.r[1];
    }
    const auto region_holds = [](const std::pair<u64, u64>& region, u64 stack_top) {
        return stack_top > region.first && stack_top <= region.first + region.second;
    };
    u32 rewinds_cancelled = 0;
    for (bool changed = true; changed;) {
        changed = false;
        for (const auto& region : region_ranges) {
            bool holds_left = false;
            for (u32 i = 0; i < thread_count; i++) {
                if (plan.slots[i] == SlotPlan::AsSaved && IsParkedInKernel(*live_threads[i]) &&
                    !move_wait[i] && !rewind_parked[i] &&
                    region_holds(region, live_threads[i]->GetUserStackTop().GetValue())) {
                    holds_left = true;
                    break;
                }
            }
            if (!holds_left) {
                continue;
            }
            for (u32 i = 0; i < thread_count; i++) {
                if (rewind_parked[i] &&
                    region_holds(region, live_threads[i]->GetUserStackTop().GetValue())) {
                    rewind_parked[i] = false;
                    rewinds_cancelled++;
                    changed = true;
                }
            }
        }
    }
    std::vector<u64> sleeping_stack_tops;
    // Diagnostic only - which specific wait each excluded thread is parked in. A high count
    // by itself isn't necessarily a problem (e.g. an idle worker pool sitting in ConditionVar
    // between jobs is normal and harmless to leave untouched); what matters for a frozen
    // restore is whether something CRITICAL to game-loop progress is among them.
    std::array<u32, 7> wait_reason_counts{};
    for (u32 i = 0; i < thread_count; i++) {
        auto* thread = live_threads[i];
        if (plan.slots[i] == SlotPlan::AsSaved && IsParkedInKernel(*thread) && !move_wait[i] &&
            !rewind_parked[i]) {
            sleeping_stack_tops.push_back(thread->GetUserStackTop().GetValue());
            wait_reason_counts[static_cast<size_t>(thread->GetWaitReasonForDebugging())]++;
        }
    }
    if (rewinds_cancelled > 0) {
        LOG_INFO(Core,
                 "SaveState::Restore: {} thread(s) asleep in their saved wait left as they are: "
                 "their stack shares a region with a thread left asleep",
                 rewinds_cancelled);
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

    // A thread put back or created again runs on its saved stack, so that has to be written: not
    // when it shares a region with a thread left asleep.
    for (u32 i = 0; i < thread_count; i++) {
        const u64 sp = thread_contexts[i].sp;
        if (is_rebuilt(i) && std::ranges::any_of(region_ranges, [&](const auto& region) {
                return region_holds(region, sp) && is_excluded_stack(region.first, region.second);
            })) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} cannot be put back: its stack shares a "
                        "region with a thread left asleep, refusing for now",
                        i);
            if (failure != nullptr) {
                *failure = RestoreFailure::NotSettled;
            }
            return false;
        }
    }

    // A thread put back into its wait by making the call again starts at the call's instruction:
    // check that it is that call.
    for (u32 i = 0; i < thread_count; i++) {
        if (is_rebuilt(i) && IsReissuedWait(saved_signature[i], saved_threads[i].svc_id) &&
            !IsReissuableCallBefore(system.ApplicationMemory(), thread_contexts[i].pc,
                                    saved_signature[i], saved_threads[i].svc_id)) {
            LOG_WARNING(Core,
                        "SaveState::Restore: thread {} cannot be put back into its saved wait ({}, "
                        "call {:#x}): instruction {:#010x} before pc {:#x} is not that call, "
                        "refusing for now",
                        i, DescribeWaitWord(saved_signature[i]), saved_threads[i].svc_id,
                        system.ApplicationMemory().Read32(thread_contexts[i].pc - 4),
                        thread_contexts[i].pc);
            if (failure != nullptr) {
                *failure = RestoreFailure::NotSettled;
            }
            return false;
        }
    }

    // The thread-local storage pages of threads created again, which are mapped again if the
    // kernel freed them when their last thread ended (see KProcess::CreateThreadLocalRegionAt).
    std::vector<u64> recreated_tls_pages;
    for (u32 i = 0; i < thread_count; i++) {
        if (plan.slots[i] == SlotPlan::Recreate) {
            recreated_tls_pages.push_back(Common::AlignDown(saved_threads[i].tls, GuestPageSize));
        }
    }

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
    const auto save = FindSaveMark(path);
    if (!save || save->loaded_past) {
        LOG_WARNING(Core, "SaveState::Restore: refusing '{}': {}", path,
                    save ? "a load of an earlier save went back past it"
                         : "it was not made since the game started");
        if (failure != nullptr) {
            *failure = RestoreFailure::MemoryLayoutChanged;
        }
        return false;
    }
    const u64 mark = save->mark;
    if (!CanUndoTo(mark)) {
        LOG_WARNING(Core,
                    "SaveState::Restore: refusing '{}': the changes since the save cannot be "
                    "undone (memory journal {}, GPU journal {}, services journal {})",
                    path, memory_journal.CanUndoTo(mark) ? "ok" : "unusable",
                    gpu_journal.CanUndoTo(mark) ? "ok" : "unusable",
                    services_journal.CanUndoTo(mark) ? "ok" : "unusable");
        if (failure != nullptr) {
            *failure = RestoreFailure::MemoryLayoutChanged;
        }
        return false;
    }
    {
        u32 lost_regions = 0;
        // Every page mapped, or mapped again by the undo or for a thread created again.
        const auto will_be_mapped = [&](u64 address, u64 size) {
            for (u64 page = Common::AlignDown(address, GuestPageSize); page < address + size;
                 page += GuestPageSize) {
                if (!memory.IsValidVirtualAddress(page) &&
                    !memory_journal.WillRestore(page, GuestPageSize, mark) &&
                    std::ranges::find(recreated_tls_pages, page) == recreated_tls_pages.end()) {
                    return false;
                }
            }
            return true;
        };
        for (const auto& [address, size] : region_ranges) {
            if (is_excluded_stack(address, size) ||
                memory.IsValidVirtualAddressRange(address, size) ||
                memory_journal.WillRestore(address, size, mark) ||
                (!recreated_tls_pages.empty() && will_be_mapped(address, size))) {
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
    // From here on the restore goes ahead. Threads started since the save are ended, and so are
    // the ones created again below.
    {
        std::vector<Kernel::KThread*> to_end = extra_threads;
        for (u32 i = 0; i < thread_count; i++) {
            if (plan.slots[i] == SlotPlan::Recreate && live_threads[i] != nullptr) {
                to_end.push_back(live_threads[i]);
                live_threads[i] = nullptr;
            }
        }
        if (!to_end.empty()) {
            EndThreadsNotInSave(system, *process, to_end);
        }
    }
    {
        const size_t memory_steps = memory_journal.Size(mark);
        const size_t gpu_steps = gpu_journal.Size(mark);
        const size_t services_steps = services_journal.Size(mark);
        // Newest first across the three journals, not one journal after the other: see
        // UndoJournal::UndoAllTogether.
        const std::vector<bool> undone = UndoJournal::UndoAllTogether(
            {std::addressof(memory_journal), std::addressof(gpu_journal),
             std::addressof(services_journal)},
            mark);
        // Saves made after this one are gone with what was undone.
        {
            std::scoped_lock lock{save_marks_mutex};
            for (auto& [other_path, other] : save_marks) {
                if (other.mark > mark) {
                    other.loaded_past = true;
                }
            }
        }
        const bool memory_ok = undone[0];
        const bool gpu_ok = undone[1];
        const bool services_ok = undone[2];
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

    // The threads that ended, or were ended above, are created again.
    std::string rebuilt_log;
    for (u32 i = 0; i < thread_count; i++) {
        if (!is_rebuilt(i)) {
            continue;
        }
        if (plan.slots[i] == SlotPlan::Recreate) {
            Kernel::Handle handle = Kernel::Svc::InvalidHandle;
            live_threads[i] = CreateThreadForRestore(system, *process, saved_threads[i],
                                                     thread_contexts[i], &handle);
        }
        rebuilt_log += fmt::format("{}thread {} (id {}, tls {:#x}): {} as saved {}",
                                   rebuilt_log.empty() ? "" : "; ", i, saved_threads[i].id,
                                   saved_threads[i].tls,
                                   plan.slots[i] == SlotPlan::Reuse ? "reused"
                                   : live_threads[i] != nullptr     ? "created again"
                                                                    : "COULD NOT BE CREATED",
                                   DescribeWaitWord(saved_signature[i]));
    }
    if (!rebuilt_log.empty()) {
        LOG_INFO(Core, "SaveState::Restore: threads put back into their saved state: {}",
                 rebuilt_log);
    }

    // Saved handle value -> live handle value of each thread slot's own handle (of the thread
    // created again, for one that was).
    HandleMap handle_map;
    u32 handle_low = 0xFFFFFFFF;
    u32 handle_high = 0;
    {
        const auto live_handles = CollectThreadHandles(system, *process, live_threads);
        for (u32 i = 0; i < thread_count; i++) {
            if (live_threads[i] == nullptr || saved_threads[i].handle_index == NoHandle ||
                live_handles[i].index == NoHandle) {
                continue;
            }
            const u32 saved_value =
                EncodeHandleValue(saved_threads[i].handle_index, saved_threads[i].handle_linear_id);
            const u32 live_value =
                EncodeHandleValue(live_handles[i].index, live_handles[i].linear_id);
            if (saved_value != live_value) {
                handle_map.emplace_back(saved_value, live_value);
                handle_low = std::min(handle_low, saved_value);
                handle_high = std::max(handle_high, saved_value);
            }
        }
    }

    if (const size_t runs = DiscardGpuDrawnImages(system); runs > 0) {
        LOG_INFO(Core,
                 "SaveState::Restore: dropped what the GPU drew since the save in {} run(s) of "
                 "images",
                 runs);
    }

    // A thread that waited for a service's answer at the save, and is in a service request again
    // now, gets the answer to the live request: a service thread writes it into the thread's
    // message buffer (the start of its TLS) at any moment, also while the game is paused for this
    // load. The saved buffer only holds the request sent then, and writing it back over an answer
    // that came in the meantime left the game reading its own request as the answer (it aborted
    // on the reply's header). That buffer is left as it is.
    std::vector<std::pair<u64, u64>> live_message_buffers;
    for (u32 i = 0; i < thread_count; i++) {
        const auto* thread = live_threads[i];
        if (thread == nullptr || plan.slots[i] != SlotPlan::AsSaved ||
            !IsSavedWait(saved_signature[i], Kernel::ThreadWaitReasonForDebugging::IPC) ||
            !thread->IsCallingSvc() ||
            thread->GetSvcId() != static_cast<u8>(Kernel::Svc::SvcId::SendSyncRequest)) {
            continue;
        }
        live_message_buffers.emplace_back(thread->GetTlsAddress().GetValue(),
                                          IPC::COMMAND_BUFFER_LENGTH * sizeof(u32));
    }
    if (!live_message_buffers.empty()) {
        LOG_INFO(Core,
                 "SaveState::Restore: {} thread(s) waiting for a service's answer keep their live "
                 "message buffer",
                 live_message_buffers.size());
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
        if (!WriteChanged(memory, address, buffer.data(), size, live_buffer, written_bytes,
                          live_message_buffers)) {
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
    std::string timeouts_changed;
    for (u32 i = 0; i < thread_count; i++) {
        if (live_threads[i] == nullptr) {
            continue; // could not be created again (logged above)
        }
        if (plan.slots[i] == SlotPlan::AsSaved && IsParkedInKernel(*live_threads[i])) {
            // A sleeping thread keeps its live registers and stack, except one asleep in the same
            // kernel call as at the save (same instruction, same lock and condition variable; see
            // rewind_parked) or one moved back to its saved condition variable (move_wait). It
            // gets its saved registers, and its saved stack is written above: when it wakes,
            // the kernel finishes that call and resumes it from them, so it carries on in the saved
            // world, on its saved stack, instead of in the later one - where a recreated worker of
            // Super Mario 3D World had already finished the job the restored main thread handed it
            // again, and went back to sleep without doing it (the game then waited forever).
            if (!rewind_parked[i] && !move_wait[i]) {
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
        // A thread asleep in the same call as at the save may have gone to sleep with another
        // timeout than its saved call (the same code waits with and without one): its saved
        // registers and stack carry on from the saved call, so its wait times out as that call
        // would have. A worker asleep with a timeout, where at the save it waited without one,
        // woke timed out right after the load, and the game aborted on that result (2001-0117).
        if (plan.slots[i] == SlotPlan::AsSaved && IsParkedInKernel(*live_threads[i])) {
            if (const auto timeout = SavedCallTimeout(saved_threads[i].svc_id, context)) {
                auto& kernel = system.Kernel();
                const s64 now = kernel.HardwareTimer().GetTick();
                const s64 was = live_threads[i]->SetWaitTimeoutForRestore(kernel, *timeout);
                if (was >= 0 && (was > 0) != (*timeout > 0)) {
                    timeouts_changed += fmt::format(
                        "{}thread {}: {} -> {}", timeouts_changed.empty() ? "" : "; ", i,
                        was <= 0     ? std::string("no timeout")
                        : was <= now ? fmt::format("timed out {} ms ago", (now - was) / 1'000'000)
                                     : fmt::format("timing out in {} ms", (was - now) / 1'000'000),
                        *timeout > 0 ? fmt::format("{} ms as saved", *timeout / 1'000'000)
                                     : std::string("no timeout as saved"));
                }
            }
        }
        if (!is_rebuilt(i)) {
            continue;
        }
        // Put back into its saved state: one created again starts (still held paused), and one
        // that slept on a condition variable at the save sleeps there again, with the lock, the
        // tag and the timeout of its saved call; its saved registers hold them (x0-x3).
        if (plan.slots[i] == SlotPlan::Recreate) {
            if (const Result result = live_threads[i]->Run(system.Kernel()); R_FAILED(result)) {
                LOG_ERROR(Core,
                          "SaveState::Restore: thread {} created again could not start ({:#x})", i,
                          result.raw);
                continue;
            }
        }
        if (IsReissuedWait(saved_signature[i], saved_threads[i].svc_id)) {
            // Makes the call again when it runs: the instruction before the saved pc (checked
            // above), with the call's arguments still in its saved registers (thread handles in
            // them renamed above).
            context.pc -= 4;
        } else if (IsSavedConditionVariableWait(saved_signature[i], saved_threads[i].svc_id)) {
            process->WaitConditionVariableForRestore(
                live_threads[i], Common::ProcessAddress(context.r[0]),
                Common::AlignDown(context.r[1], sizeof(u32)), static_cast<u32>(context.r[2]),
                static_cast<s64>(context.r[3]));
        }
    }

    if (rewound_parked > 0) {
        LOG_INFO(Core, "SaveState::Restore: {} sleeping thread(s) given their saved registers",
                 rewound_parked);
    }
    if (!timeouts_changed.empty()) {
        LOG_INFO(Core,
                 "SaveState::Restore: sleeping thread(s) given their saved call's timeout: {}",
                 timeouts_changed);
    }

    // Give the live threads and their handles the names the restored memory knows them by.
    u32 renamed_threads = 0;
    for (u32 i = 0; i < thread_count; i++) {
        if (live_threads[i] != nullptr && live_threads[i]->GetThreadId() != saved_threads[i].id) {
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

std::string DescribeWaitWord(u32 word) {
    static constexpr std::array<const char*, 4> States{"initialized", "waiting", "runnable",
                                                       "terminated"};
    static constexpr std::array<const char*, 7> Reasons{
        "none", "sleep", "ipc", "sync", "condvar", "arbitration", "suspended"};
    const u32 state = (word >> 8) & 0xF;
    const u32 reason = word & 0x7F;
    return fmt::format("{} {}{} target {:04x}", state < States.size() ? States[state] : "?",
                       reason < Reasons.size() ? Reasons[reason] : "?",
                       (word & 0x80) != 0 ? " in-svc" : "", word >> 16);
}

std::string DescribeWaitDifferences(const std::vector<u32>& saved, const std::vector<u32>& live) {
    if (saved.size() != live.size()) {
        return fmt::format("thread count differs (saved {}, live {})", saved.size(), live.size());
    }
    std::string out;
    for (size_t i = 0; i < saved.size(); i++) {
        if (SignaturesMatch(std::vector<u32>{saved[i]}, std::vector<u32>{live[i]})) {
            continue;
        }
        if (!out.empty()) {
            out += "; ";
        }
        out += fmt::format("thread {}: saved {}, live {}", i, DescribeWaitWord(saved[i]),
                           DescribeWaitWord(live[i]));
    }
    return out.empty() ? "none" : out;
}

std::string DescribeRiskyPendingWaits(Core::System& system) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        return "no process";
    }
    std::string out;
    u32 index = 0;
    for (auto& thread : process->GetThreadList()) {
        if (thread.GetWaitReasonForDebugging() ==
            Kernel::ThreadWaitReasonForDebugging::Arbitration) {
            if (!out.empty()) {
                out += ", ";
            }
            out += fmt::format("thread {} (id {}, address {:#x})", index, thread.GetThreadId(),
                               WaitKey(thread));
        }
        index++;
    }
    return out.empty() ? "none" : out;
}

} // namespace Core::SaveState
