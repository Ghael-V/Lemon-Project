// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <array>
#include <span>
#include <vector>

#include "common/fs/file.h"
#include "common/fs/fs.h"
#include "common/logging.h"
#include "core/core.h"
#include "core/hle/kernel/k_memory_block.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/k_process_page_table.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/svc_types.h"
#include "core/memory.h"
#include "core/savestate.h"

namespace Core::SaveState {

namespace {

constexpr std::array<char, 4> Magic{'L', 'M', 'S', 'S'};
constexpr u32 CurrentVersion = 1;

struct Region {
    u64 address;
    u64 size;
};

// Only these carry actual guest-written data worth capturing. Everything else
// (Free, Inaccessible, Kernel, Io*, Ipc/Transferred buffers, ASLR guard gaps,
// etc.) is either unbacked or not meaningful for a CPU+memory-only savestate,
// and including it risks trying to dump enormous unbacked address ranges.
//
// Code is deliberately excluded: under NCE those pages are directly-executable
// host memory, and WriteBlock()-ing new bytes into them on restore doesn't
// invalidate the CPU's instruction cache, so the core can end up fetching stale
// instructions and fault with SIGILL. Since guest code doesn't change at
// runtime for anything this MVP targets, it doesn't need to round-trip at all.
constexpr bool IsCapturableState(Kernel::KMemoryState state) {
    switch (state) {
    case Kernel::KMemoryState::CodeData:
    case Kernel::KMemoryState::Normal:
    case Kernel::KMemoryState::Stack:
    case Kernel::KMemoryState::ThreadLocal:
    case Kernel::KMemoryState::Shared:
        return true;
    default:
        return false;
    }
}

// Walks the process' memory via the same QueryInfo mechanism svcQueryMemory uses,
// collecting every region in a capturable state. Avoids probing the full 39-bit
// address space byte by byte.
std::vector<Region> EnumerateRegions(Kernel::KProcessPageTable& page_table) {
    std::vector<Region> regions;

    const auto start = page_table.GetAddressSpaceStart();
    const auto end = start + page_table.GetAddressSpaceSize();

    auto addr = start;
    while (addr < end) {
        Kernel::KMemoryInfo info{};
        Kernel::Svc::PageInfo page_info{};
        if (page_table.QueryInfo(&info, &page_info, addr).IsError()) {
            break;
        }

        if (IsCapturableState(info.m_state)) {
            regions.push_back({.address = info.m_address, .size = info.m_size});
        }

        const Common::ProcessAddress next = info.m_address + info.m_size;
        if (next <= addr) {
            // Didn't advance - stop rather than loop forever.
            break;
        }
        addr = next;
    }

    return regions;
}

} // namespace

bool Capture(Core::System& system, const std::string& path) {
    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        LOG_ERROR(Core, "SaveState::Capture: no application process");
        return false;
    }

    auto& memory = system.ApplicationMemory();
    auto& page_table = process->GetPageTable();

    std::vector<Kernel::Svc::ThreadContext> thread_contexts;
    for (auto& thread : process->GetThreadList()) {
        thread_contexts.push_back(thread.GetContext());
    }

    const auto regions = EnumerateRegions(page_table);

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
    ok = ok && file.WriteSpan<Kernel::Svc::ThreadContext>(thread_contexts) ==
                   thread_contexts.size();

    const auto region_count = static_cast<u32>(regions.size());
    ok = ok && file.WriteObject(region_count);

    std::vector<u8> buffer;
    for (const auto& region : regions) {
        if (!ok) {
            break;
        }

        ok = ok && file.WriteObject(region.address);
        ok = ok && file.WriteObject(region.size);

        buffer.resize(region.size);
        if (!memory.ReadBlock(region.address, buffer.data(), region.size)) {
            LOG_ERROR(Core, "SaveState::Capture: failed to read {} bytes at {:#x}", region.size,
                       region.address);
            ok = false;
            break;
        }
        ok = ok && file.WriteSpan<u8>(buffer) == buffer.size();
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

    LOG_INFO(Core, "SaveState::Capture: saved {} thread(s), {} region(s) to '{}'", thread_count,
              region_count, path);
    return true;
}

bool Restore(Core::System& system, const std::string& path) {
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

    std::vector<Kernel::Svc::ThreadContext> thread_contexts(thread_count);
    if (thread_count > 0 &&
        file.ReadSpan<Kernel::Svc::ThreadContext>(thread_contexts) != thread_contexts.size()) {
        LOG_ERROR(Core, "SaveState::Restore: truncated thread contexts in '{}'", path);
        return false;
    }

    // Match the live thread list against the captured one before touching anything -
    // same list, so iteration order matches Capture()'s as long as nothing created or
    // destroyed a thread in between.
    std::vector<Kernel::KThread*> live_threads;
    for (auto& thread : process->GetThreadList()) {
        live_threads.push_back(std::addressof(thread));
    }

    if (live_threads.size() != thread_contexts.size()) {
        LOG_ERROR(Core,
                   "SaveState::Restore: thread count mismatch (saved {}, live {}) - the guest's "
                   "thread set changed since this savestate was captured, refusing to load",
                   thread_contexts.size(), live_threads.size());
        return false;
    }

    u32 region_count = 0;
    if (!file.ReadObject(region_count)) {
        LOG_ERROR(Core, "SaveState::Restore: truncated file '{}'", path);
        return false;
    }

    auto& memory = system.ApplicationMemory();
    std::vector<u8> buffer;
    for (u32 i = 0; i < region_count; i++) {
        u64 address = 0;
        u64 size = 0;
        if (!file.ReadObject(address) || !file.ReadObject(size)) {
            LOG_ERROR(Core, "SaveState::Restore: truncated region table in '{}'", path);
            return false;
        }

        buffer.resize(size);
        if (file.ReadSpan<u8>(buffer) != size) {
            LOG_ERROR(Core, "SaveState::Restore: truncated region data in '{}'", path);
            return false;
        }

        if (!memory.WriteBlock(address, buffer.data(), size)) {
            LOG_ERROR(Core, "SaveState::Restore: failed to write {} bytes at {:#x}", size,
                       address);
            return false;
        }
    }

    // Everything was read and validated successfully - only now overwrite CPU state,
    // so a truncated/corrupt file never leaves the guest half-restored.
    for (size_t i = 0; i < live_threads.size(); i++) {
        live_threads[i]->GetContext() = thread_contexts[i];
    }

    LOG_INFO(Core, "SaveState::Restore: loaded {} thread(s), {} region(s) from '{}'",
              thread_count, region_count, path);
    return true;
}

} // namespace Core::SaveState
