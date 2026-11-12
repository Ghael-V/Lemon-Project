// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <optional>
#include <utility>
#include <vector>

#include "core/core.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/svc.h"
#include "core/savestate_undo_journal.h"

namespace Kernel::Svc {

namespace {
// Quick Load support (see Core::SaveState::UndoJournal). Games that use the SDK's virtual address
// memory map physical memory into the alias region and unmap it again as they go: one game
// unmapped five 16 MiB GPU memory pools on respawning, and a Quick Load then refused the save,
// whose memory they were part of. The two calls below are recorded so a load can reverse them:
//
// - UnmapPhysicalMemory frees every Normal page in the range and leaves Free ones alone, so what it
//   took away is the Normal parts found just before. Undone by MapPhysicalMemory over those parts,
//   which allocates fresh pages (the load writes the saved contents into them afterwards) and
//   reserves them from the process resource limit again. The ranges are noted as restored, so the
//   load does not count the memory they held as lost.
// - MapPhysicalMemory maps every Free page in the range and leaves the rest alone, so what it added
//   is the Free parts found just before. Undone by UnmapPhysicalMemory over those parts, which
//   releases them to the resource limit.
//
// Steps are undone newest first, so memory unmapped after the save is back before the steps that
// came earlier (an nvmap free, which locks the memory for the GPU again) need it, and an nvmap
// alloc made after a map is undone (unlocking the memory) before the map is.
//
// The parts are looked up outside the page table's lock, so another guest thread could change the
// range in between; the result is checked afterwards and the journal is made unusable (the load is
// refused, nothing is changed) when it does not match.

// A range [first, second).
using Range = std::pair<u64, u64>;

// The parts of [address, address + size) in `state` (with no memory attribute set when
// `no_attribute`), adjacent parts merged. Empty optional if the page table could not be queried.
std::optional<std::vector<Range>> FindRanges(const KProcessPageTable& page_table, u64 address,
                                             u64 size, KMemoryState state, bool no_attribute) {
    std::vector<Range> ranges;
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
        if (info.GetState() == state &&
            (!no_attribute || info.GetAttribute() == KMemoryAttribute::None)) {
            if (!ranges.empty() && ranges.back().second == current) {
                ranges.back().second = block_end;
            } else {
                ranges.emplace_back(current, block_end);
            }
        }
        current = block_end;
    }
    return ranges;
}

// Whether every range is entirely in `state` (with no memory attribute set when `no_attribute`).
bool AllInState(const KProcessPageTable& page_table, const std::vector<Range>& ranges,
                KMemoryState state, bool no_attribute) {
    for (const auto& [start, end] : ranges) {
        const auto found = FindRanges(page_table, start, end - start, state, no_attribute);
        if (!found || found->size() != 1 || found->front() != Range{start, end}) {
            return false;
        }
    }
    return true;
}

// The page table of the process `process_id`, if it is still the application: the journal can
// outlive the game it was recorded for.
KProcessPageTable* ApplicationPageTable(Core::System& system, u64 process_id) {
    KProcess* const process = system.ApplicationProcess();
    if (process == nullptr || process->GetId() != process_id) {
        return nullptr;
    }
    return std::addressof(process->GetPageTable());
}
} // namespace

/// Set the process heap to a given Size. It can both extend and shrink the heap.
Result SetHeapSize(Core::System& system, u64* out_address, u64 size) {
    LOG_TRACE(Kernel_SVC, "called, heap_size=0x{:X}", size);

    // Validate size.
    R_UNLESS(Common::IsAligned(size, HeapSizeAlignment), ResultInvalidSize);
    R_UNLESS(size < MainMemorySizeMax, ResultInvalidSize);

    // Set the heap size.
    KProcessAddress address{};
    R_TRY(GetCurrentProcess(system.Kernel())
              .GetPageTable()
              .SetHeapSize(std::addressof(address), size));

    // We succeeded.
    *out_address = GetInteger(address);
    R_SUCCEED();
}

/// Maps memory at a desired address
Result MapPhysicalMemory(Core::System& system, u64 addr, u64 size) {
    LOG_DEBUG(Kernel_SVC, "called, addr=0x{:016X}, size=0x{:X}", addr, size);

    if (!Common::Is4KBAligned(addr)) {
        LOG_ERROR(Kernel_SVC, "Address is not aligned to 4KB, 0x{:016X}", addr);
        R_THROW(ResultInvalidAddress);
    }

    if (!Common::Is4KBAligned(size)) {
        LOG_ERROR(Kernel_SVC, "Size is not aligned to 4KB, 0x{:X}", size);
        R_THROW(ResultInvalidSize);
    }

    if (size == 0) {
        LOG_ERROR(Kernel_SVC, "Size is zero");
        R_THROW(ResultInvalidSize);
    }

    if (!(addr < addr + size)) {
        LOG_ERROR(Kernel_SVC, "Size causes 64-bit overflow of address");
        R_THROW(ResultInvalidMemoryRegion);
    }

    KProcess* const current_process{GetCurrentProcessPointer(system.Kernel())};
    auto& page_table{current_process->GetPageTable()};

    if (current_process->GetTotalSystemResourceSize() == 0) {
        LOG_ERROR(Kernel_SVC, "System Resource Size is zero");
        R_THROW(ResultInvalidState);
    }

    if (!page_table.Contains(addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Address is not within the address space, addr=0x{:016X}, size=0x{:016X}", addr,
                  size);
        R_THROW(ResultInvalidMemoryRegion);
    }

    if (!page_table.IsInAliasRegion(addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Address is not within the alias region, addr=0x{:016X}, size=0x{:016X}", addr,
                  size);
        R_THROW(ResultInvalidMemoryRegion);
    }

    // A Quick Load unmaps what this maps (see FindRanges above): the parts that are Free now.
    auto& journal = Core::SaveState::UndoJournal::Memory();
    std::optional<std::vector<Range>> added;
    if (journal.CanUndo()) {
        added = FindRanges(page_table, addr, size, KMemoryState::Free, false);
    }

    R_TRY(page_table.MapPhysicalMemory(addr, size));

    if (journal.CanUndo()) {
        if (!added || !AllInState(page_table, *added, KMemoryState::Normal, true)) {
            journal.Invalidate();
        } else if (!added->empty()) {
            journal.Record("map physical memory", [&system, process_id = current_process->GetId(),
                                                   ranges = *added] {
                KProcessPageTable* const pt = ApplicationPageTable(system, process_id);
                if (pt == nullptr) {
                    return false;
                }
                bool ok = true;
                for (const auto& [start, end] : ranges) {
                    ok = pt->UnmapPhysicalMemory(start, end - start).IsSuccess() && ok;
                }
                return ok && AllInState(*pt, ranges, KMemoryState::Free, false);
            });
        }
    }
    R_SUCCEED();
}

/// Unmaps memory previously mapped via MapPhysicalMemory
Result UnmapPhysicalMemory(Core::System& system, u64 addr, u64 size) {
    LOG_DEBUG(Kernel_SVC, "called, addr=0x{:016X}, size=0x{:X}", addr, size);

    if (!Common::Is4KBAligned(addr)) {
        LOG_ERROR(Kernel_SVC, "Address is not aligned to 4KB, 0x{:016X}", addr);
        R_THROW(ResultInvalidAddress);
    }

    if (!Common::Is4KBAligned(size)) {
        LOG_ERROR(Kernel_SVC, "Size is not aligned to 4KB, 0x{:X}", size);
        R_THROW(ResultInvalidSize);
    }

    if (size == 0) {
        LOG_ERROR(Kernel_SVC, "Size is zero");
        R_THROW(ResultInvalidSize);
    }

    if (!(addr < addr + size)) {
        LOG_ERROR(Kernel_SVC, "Size causes 64-bit overflow of address");
        R_THROW(ResultInvalidMemoryRegion);
    }

    KProcess* const current_process{GetCurrentProcessPointer(system.Kernel())};
    auto& page_table{current_process->GetPageTable()};

    if (current_process->GetTotalSystemResourceSize() == 0) {
        LOG_ERROR(Kernel_SVC, "System Resource Size is zero");
        R_THROW(ResultInvalidState);
    }

    if (!page_table.Contains(addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Address is not within the address space, addr=0x{:016X}, size=0x{:016X}", addr,
                  size);
        R_THROW(ResultInvalidMemoryRegion);
    }

    if (!page_table.IsInAliasRegion(addr, size)) {
        LOG_ERROR(Kernel_SVC,
                  "Address is not within the alias region, addr=0x{:016X}, size=0x{:016X}", addr,
                  size);
        R_THROW(ResultInvalidMemoryRegion);
    }

    // A Quick Load maps again what this unmaps (see FindRanges above): the parts that are Normal,
    // with no attribute, now. Others make the call fail, and then nothing is recorded.
    auto& journal = Core::SaveState::UndoJournal::Memory();
    std::optional<std::vector<Range>> removed;
    if (journal.CanUndo()) {
        removed = FindRanges(page_table, addr, size, KMemoryState::Normal, true);
    }

    R_TRY(page_table.UnmapPhysicalMemory(addr, size));

    if (journal.CanUndo()) {
        if (!removed || !AllInState(page_table, *removed, KMemoryState::Free, false)) {
            journal.Invalidate();
        } else if (!removed->empty()) {
            journal.Record("unmap physical memory", [&system, process_id = current_process->GetId(),
                                                     ranges = *removed] {
                KProcessPageTable* const pt = ApplicationPageTable(system, process_id);
                if (pt == nullptr) {
                    return false;
                }
                bool ok = true;
                for (const auto& [start, end] : ranges) {
                    ok = pt->MapPhysicalMemory(start, end - start, process_id).IsSuccess() && ok;
                }
                // MapPhysicalMemory leaves what is not Free alone and succeeds, so
                // check that the ranges really hold memory again.
                return ok && AllInState(*pt, ranges, KMemoryState::Normal, true);
            });
            for (const auto& [start, end] : *removed) {
                journal.NoteRestoredRange(start, end - start);
            }
        }
    }
    R_SUCCEED();
}

Result MapPhysicalMemoryUnsafe(Core::System& system, uint64_t address, uint64_t size) {
    UNIMPLEMENTED();
    R_THROW(ResultNotImplemented);
}

Result UnmapPhysicalMemoryUnsafe(Core::System& system, uint64_t address, uint64_t size) {
    UNIMPLEMENTED();
    R_THROW(ResultNotImplemented);
}

Result SetUnsafeLimit(Core::System& system, uint64_t limit) {
    UNIMPLEMENTED();
    R_THROW(ResultNotImplemented);
}

Result SetHeapSize64(Core::System& system, uint64_t* out_address, uint64_t size) {
    R_RETURN(SetHeapSize(system, out_address, size));
}

Result MapPhysicalMemory64(Core::System& system, uint64_t address, uint64_t size) {
    R_RETURN(MapPhysicalMemory(system, address, size));
}

Result UnmapPhysicalMemory64(Core::System& system, uint64_t address, uint64_t size) {
    R_RETURN(UnmapPhysicalMemory(system, address, size));
}

Result MapPhysicalMemoryUnsafe64(Core::System& system, uint64_t address, uint64_t size) {
    R_RETURN(MapPhysicalMemoryUnsafe(system, address, size));
}

Result UnmapPhysicalMemoryUnsafe64(Core::System& system, uint64_t address, uint64_t size) {
    R_RETURN(UnmapPhysicalMemoryUnsafe(system, address, size));
}

Result SetUnsafeLimit64(Core::System& system, uint64_t limit) {
    R_RETURN(SetUnsafeLimit(system, limit));
}

Result SetHeapSize64From32(Core::System& system, uint64_t* out_address, uint32_t size) {
    R_RETURN(SetHeapSize(system, out_address, size));
}

Result MapPhysicalMemory64From32(Core::System& system, uint32_t address, uint32_t size) {
    R_RETURN(MapPhysicalMemory(system, address, size));
}

Result UnmapPhysicalMemory64From32(Core::System& system, uint32_t address, uint32_t size) {
    R_RETURN(UnmapPhysicalMemory(system, address, size));
}

Result MapPhysicalMemoryUnsafe64From32(Core::System& system, uint32_t address, uint32_t size) {
    R_RETURN(MapPhysicalMemoryUnsafe(system, address, size));
}

Result UnmapPhysicalMemoryUnsafe64From32(Core::System& system, uint32_t address, uint32_t size) {
    R_RETURN(UnmapPhysicalMemoryUnsafe(system, address, size));
}

Result SetUnsafeLimit64From32(Core::System& system, uint32_t limit) {
    R_RETURN(SetUnsafeLimit(system, limit));
}

} // namespace Kernel::Svc
