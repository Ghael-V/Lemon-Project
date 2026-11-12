// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <optional>
#include <vector>

#include "core/core.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/svc.h"
#include "core/savestate_undo_journal.h"

namespace Kernel::Svc {
namespace {

constexpr bool IsValidSetMemoryPermission(MemoryPermission perm) {
    switch (perm) {
    case MemoryPermission::None:
    case MemoryPermission::Read:
    case MemoryPermission::ReadWrite:
        return true;
    default:
        return false;
    }
}

// Checks if address + size is greater than the given address
// This can return false if the size causes an overflow of a 64-bit type
// or if the given size is zero.
constexpr bool IsValidAddressRange(u64 address, u64 size) {
    return address + size > address;
}

// Helper function that performs the common sanity checks for svcMapMemory
// and svcUnmapMemory. This is doable, as both functions perform their sanitizing
// in the same order.
Result MapUnmapMemorySanityChecks(const KProcessPageTable& manager, u64 dst_addr, u64 src_addr,
                                  u64 size) {
    if (!Common::Is4KBAligned(dst_addr)) {
        LOG_ERROR(Kernel_SVC, "Destination address is not aligned to 4KB, 0x{:016X}", dst_addr);
        R_THROW(ResultInvalidAddress);
    }

    if (!Common::Is4KBAligned(src_addr)) {
        LOG_ERROR(Kernel_SVC, "Source address is not aligned to 4KB, 0x{:016X}", src_addr);
        R_THROW(ResultInvalidSize);
    }

    if (size == 0) {
        LOG_ERROR(Kernel_SVC, "Size is 0");
        R_THROW(ResultInvalidSize);
    }

    if (!Common::Is4KBAligned(size)) {
        LOG_ERROR(Kernel_SVC, "Size is not aligned to 4KB, 0x{:016X}", size);
        R_THROW(ResultInvalidSize);
    }

    if (!IsValidAddressRange(dst_addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Destination is not a valid address range, addr=0x{:016X}, size=0x{:016X}",
                  dst_addr, size);
        R_THROW(ResultInvalidCurrentMemory);
    }

    if (!IsValidAddressRange(src_addr, size)) {
        LOG_ERROR(Kernel_SVC, "Source is not a valid address range, addr=0x{:016X}, size=0x{:016X}",
                  src_addr, size);
        R_THROW(ResultInvalidCurrentMemory);
    }

    if (!manager.Contains(src_addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Source is not within the address space, addr=0x{:016X}, size=0x{:016X}",
                  src_addr, size);
        R_THROW(ResultInvalidCurrentMemory);
    }

    R_SUCCEED();
}

// Quick Load support (see Core::SaveState::UndoJournal). Games that map physical memory for the GPU
// mark it Uncached after mapping it and clear that again before unmapping it, and
// UnmapPhysicalMemory refuses memory with any attribute set. Without SetMemoryAttribute in the
// memory journal, a load across such a pair could not undo the map (in one game, going to the
// title screen: two 16 MiB pools stayed mapped after the load), and pools whose unmap was undone
// came back without Uncached.
//
// The kernel lets the Uncached bit differ across the range (it is not part of the state check), so
// the bit is recorded per part before the call, and undone per part with the same call. Only
// Uncached is recorded: PermissionLocked can never be cleared, so setting it makes the journal
// unusable.

// A part [start, end) of a range, and whether its memory is Uncached.
struct UncachedRun {
    u64 start;
    u64 end;
    bool uncached;
};

// The parts of [address, address + size) with the same Uncached bit, adjacent ones merged. Empty
// optional if the page table could not be queried.
std::optional<std::vector<UncachedRun>> UncachedRuns(const KProcessPageTable& page_table,
                                                     u64 address, u64 size) {
    std::vector<UncachedRun> runs;
    const u64 end = address + size;
    u64 current = address;
    while (current < end) {
        KMemoryInfo info{};
        Svc::PageInfo page_info{};
        if (page_table.QueryInfo(std::addressof(info), std::addressof(page_info), current)
                .IsError()) {
            return std::nullopt;
        }
        const u64 block_end = (std::min)(static_cast<u64>(info.GetEndAddress()), end);
        if (block_end <= current) {
            return std::nullopt;
        }
        const bool uncached = True(info.GetAttribute() & KMemoryAttribute::Uncached);
        if (!runs.empty() && runs.back().end == current && runs.back().uncached == uncached) {
            runs.back().end = block_end;
        } else {
            runs.push_back({current, block_end, uncached});
        }
        current = block_end;
    }
    return runs;
}

} // namespace

Result SetMemoryPermission(Core::System& system, u64 address, u64 size, MemoryPermission perm) {
    LOG_DEBUG(Kernel_SVC, "called, address=0x{:016X}, size=0x{:X}, perm=0x{:08X}", address, size,
              perm);

    // Validate address / size.
    R_UNLESS(Common::IsAligned(address, PageSize), ResultInvalidAddress);
    R_UNLESS(Common::IsAligned(size, PageSize), ResultInvalidSize);
    R_UNLESS(size > 0, ResultInvalidSize);
    R_UNLESS((address < address + size), ResultInvalidCurrentMemory);

    // Validate the permission.
    R_UNLESS(IsValidSetMemoryPermission(perm), ResultInvalidNewMemoryPermission);

    // Validate that the region is in range for the current process.
    auto& page_table = GetCurrentProcess(system.Kernel()).GetPageTable();
    R_UNLESS(page_table.Contains(address, size), ResultInvalidCurrentMemory);

    // Set the memory attribute.
    R_RETURN(page_table.SetMemoryPermission(address, size, perm));
}

Result SetMemoryAttribute(Core::System& system, u64 address, u64 size, u32 mask, u32 attr) {
    LOG_DEBUG(Kernel_SVC,
              "called, address=0x{:016X}, size=0x{:X}, mask=0x{:08X}, attribute=0x{:08X}", address,
              size, mask, attr);

    // Validate address / size.
    R_UNLESS(Common::IsAligned(address, PageSize), ResultInvalidAddress);
    R_UNLESS(Common::IsAligned(size, PageSize), ResultInvalidSize);
    R_UNLESS(size > 0, ResultInvalidSize);
    R_UNLESS((address < address + size), ResultInvalidCurrentMemory);

    // Validate the attribute and mask.
    constexpr u32 SupportedMask =
        static_cast<u32>(MemoryAttribute::Uncached | MemoryAttribute::PermissionLocked);
    R_UNLESS((mask | attr) == mask, ResultInvalidCombination);
    R_UNLESS((mask | attr | SupportedMask) == SupportedMask, ResultInvalidCombination);

    // Check that permission locked is either being set or not masked.
    R_UNLESS((static_cast<Svc::MemoryAttribute>(mask) & Svc::MemoryAttribute::PermissionLocked) ==
                 (static_cast<Svc::MemoryAttribute>(attr) & Svc::MemoryAttribute::PermissionLocked),
             ResultInvalidCombination);

    // Validate that the region is in range for the current process.
    KProcess& process{GetCurrentProcess(system.Kernel())};
    auto& page_table{process.GetPageTable()};
    R_UNLESS(page_table.Contains(address, size), ResultInvalidCurrentMemory);

    // A Quick Load sets the attribute back (see UncachedRun above).
    auto& journal = Core::SaveState::UndoJournal::Memory();
    std::optional<std::vector<UncachedRun>> before;
    if (journal.CanUndo()) {
        before = UncachedRuns(page_table, address, size);
    }

    // Set the memory attribute.
    R_TRY(page_table.SetMemoryAttribute(address, size, static_cast<KMemoryAttribute>(mask),
                                        static_cast<KMemoryAttribute>(attr)));

    if (journal.CanUndo() && mask != 0) {
        const bool uncached = (attr & static_cast<u32>(MemoryAttribute::Uncached)) != 0;
        const auto after = UncachedRuns(page_table, address, size);
        if (mask != static_cast<u32>(MemoryAttribute::Uncached) || !before || !after ||
            after->size() != 1 || after->front().uncached != uncached) {
            // PermissionLocked cannot be cleared again, and a range that does not read back as
            // set was changed by another thread in between.
            journal.Invalidate();
        } else {
            std::vector<UncachedRun> changed;
            for (const UncachedRun& run : *before) {
                if (run.uncached != uncached) {
                    changed.push_back(run);
                }
            }
            if (!changed.empty()) {
                journal.Record("set memory attribute", [&system, process_id = process.GetId(),
                                                        changed = std::move(changed)] {
                    KProcess* const app = system.ApplicationProcess();
                    if (app == nullptr || app->GetId() != process_id) {
                        return false; // the journal outlived the game
                    }
                    auto& pt = app->GetPageTable();
                    bool ok = true;
                    for (const UncachedRun& run : changed) {
                        const u64 run_size = run.end - run.start;
                        ok = pt.SetMemoryAttribute(run.start, run_size, KMemoryAttribute::Uncached,
                                                   run.uncached ? KMemoryAttribute::Uncached
                                                                : KMemoryAttribute::None)
                                 .IsSuccess() &&
                             ok;
                        const auto now = UncachedRuns(pt, run.start, run_size);
                        ok = now && now->size() == 1 && now->front().uncached == run.uncached && ok;
                    }
                    return ok;
                });
            }
        }
    }
    R_SUCCEED();
}

/// Maps a memory range into a different range.
Result MapMemory(Core::System& system, u64 dst_addr, u64 src_addr, u64 size) {
    LOG_TRACE(Kernel_SVC, "called, dst_addr=0x{:X}, src_addr=0x{:X}, size=0x{:X}", dst_addr,
              src_addr, size);

    auto& page_table{GetCurrentProcess(system.Kernel()).GetPageTable()};

    if (const Result result{MapUnmapMemorySanityChecks(page_table, dst_addr, src_addr, size)};
        result.IsError()) {
        return result;
    }

    R_TRY(page_table.MapMemory(dst_addr, src_addr, size));
    // A Quick Load undoes this (see Core::SaveState::UndoJournal): a thread stack is such a mapping.
    Core::SaveState::UndoJournal::Memory().Record(
        "map memory", [&page_table, dst_addr, src_addr, size] {
            return page_table.UnmapMemory(dst_addr, src_addr, size).IsSuccess();
        });
    R_SUCCEED();
}

/// Unmaps a region that was previously mapped with svcMapMemory
Result UnmapMemory(Core::System& system, u64 dst_addr, u64 src_addr, u64 size) {
    LOG_TRACE(Kernel_SVC, "called, dst_addr=0x{:X}, src_addr=0x{:X}, size=0x{:X}", dst_addr,
              src_addr, size);

    auto& page_table{GetCurrentProcess(system.Kernel()).GetPageTable()};

    if (const Result result{MapUnmapMemorySanityChecks(page_table, dst_addr, src_addr, size)};
        result.IsError()) {
        return result;
    }

    R_TRY(page_table.UnmapMemory(dst_addr, src_addr, size));
    auto& journal = Core::SaveState::UndoJournal::Memory();
    journal.Record("unmap memory", [&page_table, dst_addr, src_addr, size] {
        return page_table.MapMemory(dst_addr, src_addr, size).IsSuccess();
    });
    journal.NoteRestoredRange(dst_addr, size);
    R_SUCCEED();
}

Result SetMemoryPermission64(Core::System& system, uint64_t address, uint64_t size,
                             MemoryPermission perm) {
    R_RETURN(SetMemoryPermission(system, address, size, perm));
}

Result SetMemoryAttribute64(Core::System& system, uint64_t address, uint64_t size, uint32_t mask,
                            uint32_t attr) {
    R_RETURN(SetMemoryAttribute(system, address, size, mask, attr));
}

Result MapMemory64(Core::System& system, uint64_t dst_address, uint64_t src_address,
                   uint64_t size) {
    R_RETURN(MapMemory(system, dst_address, src_address, size));
}

Result UnmapMemory64(Core::System& system, uint64_t dst_address, uint64_t src_address,
                     uint64_t size) {
    R_RETURN(UnmapMemory(system, dst_address, src_address, size));
}

Result SetMemoryPermission64From32(Core::System& system, uint32_t address, uint32_t size,
                                   MemoryPermission perm) {
    R_RETURN(SetMemoryPermission(system, address, size, perm));
}

Result SetMemoryAttribute64From32(Core::System& system, uint32_t address, uint32_t size,
                                  uint32_t mask, uint32_t attr) {
    R_RETURN(SetMemoryAttribute(system, address, size, mask, attr));
}

Result MapMemory64From32(Core::System& system, uint32_t dst_address, uint32_t src_address,
                         uint32_t size) {
    R_RETURN(MapMemory(system, dst_address, src_address, size));
}

Result UnmapMemory64From32(Core::System& system, uint32_t dst_address, uint32_t src_address,
                           uint32_t size) {
    R_RETURN(UnmapMemory(system, dst_address, src_address, size));
}

} // namespace Kernel::Svc
