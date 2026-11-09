// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2015 Citra Emulator Project
// SPDX-FileCopyrightText: 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>
#include <span>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "common/assert.h"
#include "common/atomic_ops.h"
#include "common/common_types.h"
#include "common/heap_tracker.h"
#include "common/logging.h"
#include "common/page_table.h"
#include "common/scope_exit.h"
#include "common/settings.h"
#include "common/swap.h"
#include "core/core.h"
#include "core/device_memory.h"
#include "core/gpu_dirty_memory_manager.h"
#include "core/hardware_properties.h"
#include "core/hle/kernel/k_page_table.h"
#include "core/hle/kernel/k_process.h"
#include "core/memory.h"
#include "video_core/gpu.h"
#include "video_core/host1x/gpu_device_memory_manager.h"
#include "video_core/host1x/host1x.h"
#include "video_core/rasterizer_download_area.h"

namespace Core::Memory {

static inline bool AddressSpaceContains(const Common::PageTable& table, const Common::ProcessAddress addr, const std::size_t size) {
    const Common::ProcessAddress max_addr = 1ULL << table.GetAddressSpaceBits();
    return addr + size >= addr && addr + size <= max_addr;
}

// Implementation class used to keep the specifics of the memory subsystem hidden
// from outside classes. This also allows modification to the internals of the memory
// subsystem without needing to rebuild all files that make use of the memory interface.
struct Memory::Impl {
    explicit Impl(Core::System& system_) : system{system_} {}

    void SetCurrentPageTable(Kernel::KProcess& process) {
        current_page_table = &process.GetPageTable().GetImpl();

        if (process.IsApplication() && Settings::IsFastmemEnabled()) {
            current_page_table->fastmem_arena = system.DeviceMemory().buffer.VirtualBasePointer();
        } else {
            current_page_table->fastmem_arena = nullptr;
        }

#ifdef __ANDROID__
        heap_tracker.emplace(system.DeviceMemory().buffer);
        host_buffer = std::addressof(*heap_tracker);
#else
        host_buffer = std::addressof(system.DeviceMemory().buffer);
#endif
    }

    void MapMemoryRegion(Common::PageTable& page_table, Common::ProcessAddress base, u64 size,
                         Common::PhysicalAddress target, Common::MemoryPermission perms,
                         bool separate_heap) {
        ASSERT_MSG((size & YUZU_PAGEMASK) == 0, "non-page aligned size: {:016X}", size);
        ASSERT_MSG((base & YUZU_PAGEMASK) == 0, "non-page aligned base: {:016X}", GetInteger(base));
        ASSERT_MSG(target >= DramMemoryMap::Base, "Out of bounds target: {:016X}",
                   GetInteger(target));
        MapPages(page_table, base / YUZU_PAGESIZE, size / YUZU_PAGESIZE, target,
                 Common::PageType::Memory);

        if (current_page_table->fastmem_arena) {
            host_buffer->Map(GetInteger(base), GetInteger(target) - DramMemoryMap::Base, size, perms, separate_heap);
        }
    }

    void UnmapRegion(Common::PageTable& page_table, Common::ProcessAddress base, u64 size,
                     bool separate_heap) {
        ASSERT_MSG((size & YUZU_PAGEMASK) == 0, "non-page aligned size: {:016X}", size);
        ASSERT_MSG((base & YUZU_PAGEMASK) == 0, "non-page aligned base: {:016X}", GetInteger(base));
        MapPages(page_table, base / YUZU_PAGESIZE, size / YUZU_PAGESIZE, 0,
                 Common::PageType::Unmapped);

        if (current_page_table->fastmem_arena) {
            host_buffer->Unmap(GetInteger(base), size, separate_heap);
        }
    }

    void ProtectRegion(Common::PageTable& page_table, VAddr vaddr, u64 size,
                       Common::MemoryPermission perms) {
        ASSERT_MSG((size & YUZU_PAGEMASK) == 0, "non-page aligned size: {:016X}", size);
        ASSERT_MSG((vaddr & YUZU_PAGEMASK) == 0, "non-page aligned base: {:016X}", vaddr);

        if (!current_page_table->fastmem_arena) {
            return;
        }

        u64 protect_bytes = 0, protect_begin = 0;

        current_page_table->entries.CommitRegion(vaddr >> YUZU_PAGEBITS, (vaddr + size) >> YUZU_PAGEBITS);
        for (u64 addr = vaddr; addr < vaddr + size; addr += YUZU_PAGESIZE) {
            const Common::PageType page_type = current_page_table->entries.GetUnchecked(addr >> YUZU_PAGEBITS).Type();
            switch (page_type) {
            case Common::PageType::RasterizerCachedMemory:
                if (protect_bytes > 0) {
                    host_buffer->Protect(protect_begin, protect_bytes, perms);
                    protect_bytes = 0;
                }
                break;
            default:
                if (protect_bytes == 0)
                    protect_begin = addr;
                protect_bytes += YUZU_PAGESIZE;
            }
        }

        if (protect_bytes > 0) {
            host_buffer->Protect(protect_begin, protect_bytes, perms);
        }
    }

    [[nodiscard]] u8* GetPointerFromRasterizerCachedMemory(u64 vaddr) const {
        if (u64 paddr = current_page_table->entries[vaddr >> YUZU_PAGEBITS].Pointer(true); paddr)
            return reinterpret_cast<u8*>(paddr) + vaddr;
        return {};
    }

    [[nodiscard]] u8* GetPointerFromDebugMemory(u64 vaddr) const {
        if (u64 paddr = current_page_table->entries[vaddr >> YUZU_PAGEBITS].Pointer(true); paddr)
            return reinterpret_cast<u8*>(paddr) + vaddr;
        return {};
    }

    u8 Read8(const Common::ProcessAddress addr) {
        return Read<u8>(addr);
    }

    u16 Read16(const Common::ProcessAddress addr) {
        if ((addr & 1) == 0) {
            return Read<u16_le>(addr);
        } else {
            const u32 a{Read<u8>(addr)};
            const u32 b{Read<u8>(addr + sizeof(u8))};
            return static_cast<u16>((b << 8) | a);
        }
    }

    u32 Read32(const Common::ProcessAddress addr) {
        if ((addr & 3) == 0) {
            return Read<u32_le>(addr);
        } else {
            const u32 a{Read16(addr)};
            const u32 b{Read16(addr + sizeof(u16))};
            return (b << 16) | a;
        }
    }

    u64 Read64(const Common::ProcessAddress addr) {
        if ((addr & 7) == 0) {
            return Read<u64_le>(addr);
        } else {
            const u32 a{Read32(addr)};
            const u32 b{Read32(addr + sizeof(u32))};
            return (static_cast<u64>(b) << 32) | a;
        }
    }

    void Write8(const Common::ProcessAddress addr, const u8 data) {
        Write<u8>(addr, data);
    }

    void Write16(const Common::ProcessAddress addr, const u16 data) {
        if ((addr & 1) == 0) {
            Write<u16_le>(addr, data);
        } else {
            Write<u8>(addr, static_cast<u8>(data));
            Write<u8>(addr + sizeof(u8), static_cast<u8>(data >> 8));
        }
    }

    void Write32(const Common::ProcessAddress addr, const u32 data) {
        if ((addr & 3) == 0) {
            Write<u32_le>(addr, data);
        } else {
            Write16(addr, static_cast<u16>(data));
            Write16(addr + sizeof(u16), static_cast<u16>(data >> 16));
        }
    }

    void Write64(const Common::ProcessAddress addr, const u64 data) {
        if ((addr & 7) == 0) {
            Write<u64_le>(addr, data);
        } else {
            Write32(addr, static_cast<u32>(data));
            Write32(addr + sizeof(u32), static_cast<u32>(data >> 32));
        }
    }

    bool WriteExclusive8(const Common::ProcessAddress addr, const u8 data, const u8 expected) {
        return WriteExclusive<u8>(addr, data, expected);
    }

    bool WriteExclusive16(const Common::ProcessAddress addr, const u16 data, const u16 expected) {
        return WriteExclusive<u16_le>(addr, data, expected);
    }

    bool WriteExclusive32(const Common::ProcessAddress addr, const u32 data, const u32 expected) {
        return WriteExclusive<u32_le>(addr, data, expected);
    }

    bool WriteExclusive64(const Common::ProcessAddress addr, const u64 data, const u64 expected) {
        return WriteExclusive<u64_le>(addr, data, expected);
    }

    std::string ReadCString(Common::ProcessAddress vaddr, std::size_t max_length) {
        std::string string;
        string.reserve(max_length);
        for (std::size_t i = 0; i < max_length; ++i) {
            const char c = Read<s8>(vaddr);
            if (c == '\0') {
                break;
            }
            string.push_back(c);
            ++vaddr;
        }
        string.shrink_to_fit();
        return string;
    }

    template<typename F1, typename F2, typename F3>
    inline bool WalkBlock(const Common::ProcessAddress addr, const std::size_t size, F1&& on_unmapped, F2&& on_memory, F3&& on_rasterizer) {
        std::size_t offset = 0;
        if (!AddressSpaceContains(*current_page_table, addr, size)) {
            on_unmapped(offset, size, addr);
            return false;
        }
        std::size_t remaining_size = size;
        std::size_t page_index = addr >> YUZU_PAGEBITS;
        std::size_t page_offset = addr & YUZU_PAGEMASK;
        bool user_accessible = true;

        current_page_table->entries.CommitRegion(page_index, page_index + (size >> YUZU_PAGEBITS) + 1);
        while (remaining_size != 0) {
            const std::size_t copy_amount = (std::min)(std::size_t(YUZU_PAGESIZE) - page_offset, remaining_size);
            const auto current_vaddr = u64((page_index << YUZU_PAGEBITS) + page_offset);
            const auto [pointer, type, _] = current_page_table->entries.GetUnchecked(page_index).PointerTypeBlock();
            switch (type) {
            case Common::PageType::Unmapped: {
                user_accessible = false;
                on_unmapped(offset, copy_amount, current_vaddr);
                break;
            }
            case Common::PageType::Memory: {
                u8* mem_ptr = reinterpret_cast<u8*>(pointer + page_offset + (page_index << YUZU_PAGEBITS));
                on_memory(offset, copy_amount, mem_ptr);
                break;
            }
            case Common::PageType::DebugMemory: {
                u8* const mem_ptr = GetPointerFromDebugMemory(current_vaddr);
                on_memory(offset, copy_amount, mem_ptr);
                break;
            }
            case Common::PageType::RasterizerCachedMemory: {
                u8* const host_ptr = GetPointerFromRasterizerCachedMemory(current_vaddr);
                on_rasterizer(current_vaddr, offset, copy_amount, host_ptr);
                break;
            }
            default:
                UNREACHABLE();
            }
            page_index++;
            page_offset = 0;
            offset += copy_amount;
            remaining_size -= copy_amount;
        }
        return user_accessible;
    }

    bool ReadBlockImpl(const Common::ProcessAddress addr, void* buffer, const std::size_t size, bool unsafe) {
        return WalkBlock(addr, size,
        [addr, size, &buffer](const std::size_t offset, const std::size_t copy_amount, const Common::ProcessAddress current_vaddr) {
            LOG_ERROR(HW_Memory, "Unmapped @ {:#016x} (start address = {:#016x}, size = {})", GetInteger(current_vaddr), GetInteger(addr), size);
            std::memset(reinterpret_cast<u8*>(buffer) + offset, 0, copy_amount);
        },
        [&](const std::size_t offset, const std::size_t copy_amount, const u8* const ptr) {
            std::memcpy(reinterpret_cast<u8*>(buffer) + offset, ptr, copy_amount);
        },
        [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t copy_amount, const u8* const ptr) {
            if (!unsafe) {
                HandleRasterizerDownload(GetInteger(current_vaddr), copy_amount);
            }
            std::memcpy(reinterpret_cast<u8*>(buffer) + offset, ptr, copy_amount);
        });
    }

    [[nodiscard]] inline const u8* GetSpan(const VAddr addr, const std::size_t size) const noexcept {
        return IsSameMapping(addr, addr + size) ? GetPointerSilent(addr) : nullptr;
    }
    [[nodiscard]] inline u8* GetSpan(const VAddr addr, const std::size_t size) noexcept {
        return IsSameMapping(addr, addr + size) ? GetPointerSilent(addr) : nullptr;
    }

    bool WriteBlockImpl(const Common::ProcessAddress addr, const void* buffer, const std::size_t size, bool unsafe) {
        return WalkBlock(addr, size,
        [addr, size](const std::size_t offset, const std::size_t copy_amount, const Common::ProcessAddress current_vaddr) {
            LOG_ERROR(HW_Memory, "Unmapped @ {:#016x} (start address = {:#016x}, size = {})", GetInteger(current_vaddr), GetInteger(addr), size);
        },
        [&](const std::size_t offset, const std::size_t copy_amount, u8* const ptr) {
            std::memcpy(ptr, reinterpret_cast<u8 const*>(buffer) + offset, copy_amount);
        },
        [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t copy_amount, u8* const ptr) {
            if (!unsafe) {
                HandleRasterizerWrite(GetInteger(current_vaddr), copy_amount);
            }
            std::memcpy(ptr, reinterpret_cast<u8 const*>(buffer) + offset, copy_amount);
        });
    }

    bool ZeroBlock(const Common::ProcessAddress addr, const std::size_t size) {
        return WalkBlock(addr, size,
        [addr, size](const std::size_t offset, const std::size_t copy_amount, const Common::ProcessAddress current_vaddr) {
            LOG_ERROR(HW_Memory, "Unmapped @ {:#016x} (start address = {:#016x}, size = {})", GetInteger(current_vaddr), GetInteger(addr), size);
        },
        [=](const std::size_t offset, const std::size_t copy_amount, u8* const ptr) {
            std::memset(ptr, 0, copy_amount);
        },
        [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t copy_amount, u8* const ptr) {
            HandleRasterizerWrite(GetInteger(current_vaddr), copy_amount);
            std::memset(ptr, 0, copy_amount);
        });
    }

    bool CopyBlock(Common::ProcessAddress dest_addr, Common::ProcessAddress src_addr, const std::size_t size) {
        return WalkBlock(dest_addr, size,
        [&](const std::size_t offset, const std::size_t copy_amount, const Common::ProcessAddress current_vaddr) {
            LOG_ERROR(HW_Memory, "Unmapped @ {:#016x} (start address = {:#016x}, size = {})", GetInteger(current_vaddr), GetInteger(src_addr), size);
            ZeroBlock(dest_addr + offset, copy_amount);
        },
        [&](const std::size_t offset, const std::size_t copy_amount, const u8* const ptr) {
            WriteBlockImpl(dest_addr + offset, ptr, copy_amount, false);
        },
        [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t copy_amount, u8* const ptr) {
            HandleRasterizerDownload(GetInteger(current_vaddr), copy_amount);
            WriteBlockImpl(dest_addr + offset, ptr, copy_amount, false);
        });
    }

    template <typename Callback>
    Result PerformCacheOperation(Common::ProcessAddress dest_addr, std::size_t size, Callback&& cb) {
        bool had_unmapped = false;
        WalkBlock(dest_addr, size,
            [&](const std::size_t offset, const std::size_t copy_amount, const Common::ProcessAddress current_vaddr) {
                LOG_ERROR(HW_Memory, "Unmapped @ {:#018X}", GetInteger(current_vaddr));
                had_unmapped = true;
            },
            [](const std::size_t offset, const std::size_t copy_amount, u8* const host_ptr) {},
            [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t copy_amount, u8* const host_ptr) { cb(current_vaddr, offset, copy_amount); }
        );
        return had_unmapped ? Kernel::ResultInvalidCurrentMemory : ResultSuccess;
    }

    Result InvalidateDataCache(Common::ProcessAddress dest_addr, std::size_t size) {
        auto on_rasterizer = [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t block_size) {
            // dc ivac: Invalidate to point of coherency
            // GPU flush -> CPU invalidate
            HandleRasterizerDownload(GetInteger(current_vaddr), block_size);
        };
        return PerformCacheOperation(dest_addr, size, on_rasterizer);
    }

    Result StoreDataCache(Common::ProcessAddress dest_addr, std::size_t size) {
        auto on_rasterizer = [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t block_size) {
            // dc cvac: Store to point of coherency
            // CPU flush -> GPU invalidate
            HandleRasterizerWrite(GetInteger(current_vaddr), block_size);
        };
        return PerformCacheOperation(dest_addr, size, on_rasterizer);
    }

    Result FlushDataCache(Common::ProcessAddress dest_addr, std::size_t size) {
        auto on_rasterizer = [&](const Common::ProcessAddress current_vaddr, const std::size_t offset, const std::size_t block_size) {
            // dc civac: Store to point of coherency, and invalidate from cache
            // CPU flush -> GPU invalidate
            HandleRasterizerWrite(GetInteger(current_vaddr), block_size);
        };
        return PerformCacheOperation(dest_addr, size, on_rasterizer);
    }

    void MarkRegionDebug(u64 vaddr, u64 size, bool debug) {
        if (vaddr == 0 || !AddressSpaceContains(*current_page_table, vaddr, size)) {
            return;
        }

        if (current_page_table->fastmem_arena) {
            const auto perm{debug ? Common::MemoryPermission{}
                                  : Common::MemoryPermission::ReadWrite};
            host_buffer->Protect(vaddr, size, perm);
        }

        // Iterate over a contiguous CPU address space, marking/unmarking the region.
        // The region is at a granularity of CPU pages.

        const u64 num_pages = ((vaddr + size - 1) >> YUZU_PAGEBITS) - (vaddr >> YUZU_PAGEBITS) + 1;

        current_page_table->entries.CommitRegion(vaddr >> YUZU_PAGEBITS, (vaddr >> YUZU_PAGEBITS) + num_pages);
        for (u64 i = 0; i < num_pages; ++i, vaddr += YUZU_PAGESIZE) {
            auto& entry = current_page_table->entries.GetUnchecked(vaddr >> YUZU_PAGEBITS);
            const auto [pointer, type, block] = entry.PointerTypeBlock(true);
            if (debug) {
                // Switch page type to debug if now debug
                switch (type) {
                case Common::PageType::Unmapped:
                    ASSERT(false && "Attempted to mark unmapped pages as debug");
                    break;
                case Common::PageType::RasterizerCachedMemory:
                case Common::PageType::DebugMemory:
                    // Page is already marked.
                    break;
                case Common::PageType::Memory:
                    entry.MarkDebug(pointer, block);
                    break;
                default:
                    UNREACHABLE();
                }
            } else {
                // Switch page type to non-debug if now non-debug
                switch (type) {
                case Common::PageType::Unmapped:
                    ASSERT(false && "Attempted to mark unmapped pages as non-debug");
                    break;
                case Common::PageType::RasterizerCachedMemory:
                case Common::PageType::Memory:
                    // Don't mess with already non-debug or rasterizer memory.
                    break;
                case Common::PageType::DebugMemory: {
                    entry.Store(false, Common::PageType::Memory, block, pointer);
                    break;
                }
                default:
                    UNREACHABLE();
                }
            }
        }
    }

    void RasterizerMarkRegionCached(u64 vaddr, u64 size, bool cached) {
        if (vaddr == 0 || !AddressSpaceContains(*current_page_table, vaddr, size)) {
            return;
        }

        if (current_page_table->fastmem_arena) {
            // Cached pages stay readable but not writable: a CPU write faults and invalidates the
            // GPU caches over the page. Pages holding images the GPU drew into are the exception,
            // they are unreadable too while cached (RasterizerProtectGpuWritten).
            Common::MemoryPermission perm = Common::MemoryPermission::Read;
            if (!cached) {
                perm |= Common::MemoryPermission::Write;
            }
            if (cached && has_volatile_pages.load(std::memory_order_relaxed)) {
                ProtectSkippingVolatile(vaddr, size, perm);
            } else {
                host_buffer->Protect(vaddr, size, perm);
            }

            std::scoped_lock lock{gpu_written_mutex};
            if (!gpu_written_pages.empty()) {
                const u64 first = vaddr >> YUZU_PAGEBITS;
                const u64 last = (vaddr + size - 1) >> YUZU_PAGEBITS;
                for (u64 page = first; page <= last; ++page) {
                    if (!cached) {
                        gpu_written_pages.erase(page);
                    } else if (gpu_written_pages.contains(page)) {
                        host_buffer->Protect(page << YUZU_PAGEBITS, YUZU_PAGESIZE,
                                             Common::MemoryPermission{});
                    }
                }
            }
        }

        // Iterate over a contiguous CPU address space, which corresponds to the specified GPU
        // address space, marking the region as un/cached. The region is marked un/cached at a
        // granularity of CPU pages, hence why we iterate on a CPU page basis (note: GPU page size
        // is different). This assumes the specified GPU address region is contiguous as well.

        const u64 num_pages = ((vaddr + size - 1) >> YUZU_PAGEBITS) - (vaddr >> YUZU_PAGEBITS) + 1;
        current_page_table->entries.CommitRegion(vaddr >> YUZU_PAGEBITS, (vaddr >> YUZU_PAGEBITS) + num_pages);
        for (u64 i = 0; i < num_pages; ++i, vaddr += YUZU_PAGESIZE) {
            auto& entry = current_page_table->entries.GetUnchecked(vaddr >> YUZU_PAGEBITS);
            const Common::PageType page_type = entry.Type();
            if (cached) {
                // Switch page type to cached if now cached
                switch (page_type) {
                case Common::PageType::Unmapped:
                    // It is not necessary for a process to have this region mapped into its address
                    // space, for example, a system module need not have a VRAM mapping.
                    break;
                case Common::PageType::DebugMemory:
                case Common::PageType::Memory:
                    entry.MarkRasterizerCached();
                    break;
                case Common::PageType::RasterizerCachedMemory:
                    // There can be more than one GPU region mapped per CPU region, so it's common
                    // that this area is already marked as cached.
                    break;
                default:
                    UNREACHABLE();
                }
            } else {
                // Switch page type to uncached if now uncached
                switch (page_type) {
                case Common::PageType::Unmapped: // NOLINT(bugprone-branch-clone)
                    // It is not necessary for a process to have this region mapped into its address
                    // space, for example, a system module need not have a VRAM mapping.
                    break;
                case Common::PageType::DebugMemory:
                case Common::PageType::Memory:
                    // There can be more than one GPU region mapped per CPU region, so it's common
                    // that this area is already unmarked as cached.
                    break;
                case Common::PageType::RasterizerCachedMemory: {
                    if (auto [ptr, _, block] = entry.PointerTypeBlock(true); ptr == 0) {
                        // It's possible that this function has been called while updating the
                        // pagetable after unmapping a VMA. In that case the underlying VMA will no
                        // longer exist, and we should just leave the pagetable entry blank.
                        entry.Store(false, Common::PageType::Unmapped, block, 0);
                    } else {
                        entry.Store(false, Common::PageType::Memory, block, ptr);
                    }
                    break;
                }
                default:
                    UNREACHABLE();
                }
            }
        }
    }

    /**
     * Maps a region of pages as a specific type.
     *
     * @param page_table The page table to use to perform the mapping.
     * @param base       The base address to begin mapping at.
     * @param size       The total size of the range in bytes.
     * @param target     The target address to begin mapping from.
     * @param type       The page type to map the memory as.
     */
    void MapPages(Common::PageTable& page_table, Common::ProcessAddress base_address, u64 size,
                  Common::PhysicalAddress target, Common::PageType type) {
        auto base = GetInteger(base_address);

        LOG_DEBUG(HW_Memory, "Mapping {:016X} onto {:016X}-{:016X}", GetInteger(target),
                  base * YUZU_PAGESIZE, (base + size) * YUZU_PAGESIZE);

        const auto end = base + size;
        ASSERT_MSG(end <= page_table.entries.size(), "out of range mapping at {:016X}", base + page_table.entries.size());

        if (!target) {
            ASSERT_MSG(type != Common::PageType::Memory,
                       "Mapping memory page without a pointer @ {:016x}", base * YUZU_PAGESIZE);

            page_table.entries.ZeroRegion(base, end);
        } else {
            const u16 current_block = NextBlock();

            page_table.entries.CommitRegion(base, end);
            while (base != end) {
                auto host_ptr = reinterpret_cast<u64>(system.DeviceMemory().GetPointer<u8>(target)) - (base << YUZU_PAGEBITS);;
                auto& entry = page_table.entries.GetUnchecked(base);

                entry.Store(false, type, current_block, host_ptr);
                ASSERT_MSG(page_table.entries[base].Pointer(),
                           "memory mapping base yield a nullptr within the table");

                base += 1;
                target += YUZU_PAGESIZE;
            }
        }
    }

    template<typename F, typename G>
    [[nodiscard]] u8* GetPointerImpl(u64 vaddr, F&& on_unmapped, G&& on_rasterizer) const {
        // AARCH64 masks the upper 16 bit of all memory accesses
        vaddr &= 0xffffffffffffULL;
        if (AddressSpaceContains(*current_page_table, vaddr, 1)) [[likely]] {
            // Avoid adding any extra logic to this fast-path block
            const auto raw = current_page_table->entries[vaddr >> YUZU_PAGEBITS].Raw();
            if (auto pointer = Common::PageTable::PageEntryData::ExtractPointer(raw); pointer) [[likely]] {
                return reinterpret_cast<u8*>(pointer + vaddr);
            } else {
                switch (static_cast<Common::PageType>(raw.type)) {
                case Common::PageType::Memory:
                    ASSERT_MSG(false, "Mapped memory page without a pointer @ {:#016x}", vaddr);
                    return nullptr;
                case Common::PageType::DebugMemory:
                    return GetPointerFromDebugMemory(vaddr);
                case Common::PageType::RasterizerCachedMemory: {
                    u8* const host_ptr{GetPointerFromRasterizerCachedMemory(vaddr)};
                    on_rasterizer();
                    return host_ptr;
                }
                case Common::PageType::Unmapped: [[unlikely]] {
                    on_unmapped();
                    return nullptr;
                }
                default:
                    UNREACHABLE();
                }
                return nullptr;
            }
        } else {
            on_unmapped();
            return nullptr;
        }
    }

    [[nodiscard]] u8* GetPointer(const Common::ProcessAddress vaddr) const {
        return GetPointerImpl(
            GetInteger(vaddr),
            [vaddr]() {
                LOG_ERROR(HW_Memory, "Unmapped GetPointer @ {:#016x}", GetInteger(vaddr));
            },
            []() {});
    }

    [[nodiscard]] u8* GetPointerSilent(const Common::ProcessAddress vaddr) const {
        return GetPointerImpl(
            GetInteger(vaddr), []() {}, []() {});
    }

    /// @brief Reads a particular data type out of memory at the given virtual address.
    /// @param vaddr The virtual address to read the data type from.
    /// @tparam T The data type to read out of memory.
    /// @returns The instance of T read from the specified virtual address.
    template <typename T>
    inline T Read(Common::ProcessAddress vaddr) noexcept requires(std::is_trivially_copyable_v<T>) {
        const u64 addr = GetInteger(vaddr);
        if (auto const ptr = GetPointerImpl(addr, [addr]() {
            LOG_ERROR(HW_Memory, "Unmapped Read{} @ {:#016x}", sizeof(T) * 8, addr);
        }, [&]() {
            HandleRasterizerDownload(addr, sizeof(T));
        }); ptr) [[likely]] {
            // It may be tempting to rewrite this particular section to use "reinterpret_cast";
            // afterall, it's trivially copyable so surely it can be copied ov- Alignment.
            // Remember, alignment. memcpy() will deal with all the alignment extremely fast.
            T result{};
            std::memcpy(&result, ptr, sizeof(T));
            return result;
        }
        return T{};
    }

    /// @brief Writes a particular data type to memory at the given virtual address.
    /// @param vaddr The virtual address to write the data type to.
    /// @tparam T The data type to write to memory.
    template <typename T>
    inline void Write(Common::ProcessAddress vaddr, const T data) noexcept requires(std::is_trivially_copyable_v<T>) {
        const u64 addr = GetInteger(vaddr);
        if (auto const ptr = GetPointerImpl(addr, [addr, data]() {
            LOG_ERROR(HW_Memory, "Unmapped Write{} @ {:#016x} = {:#016x}", sizeof(T) * 8, addr, u64(data));
        }, [&]() { HandleRasterizerWrite(addr, sizeof(T)); }); ptr) [[likely]]
            std::memcpy(ptr, &data, sizeof(T));
    }

    template <typename T>
    bool WriteExclusive(Common::ProcessAddress vaddr, const T data, const T expected) {
        u8* const ptr = GetPointerImpl(
            GetInteger(vaddr),
            [vaddr, data]() {
                LOG_ERROR(HW_Memory, "Unmapped WriteExclusive{} @ {:#016x} = {:#016x}",
                          sizeof(T) * 8, GetInteger(vaddr), static_cast<u64>(data));
            },
            [&]() { HandleRasterizerWrite(GetInteger(vaddr), sizeof(T)); });
        if (ptr) {
            return Common::AtomicCompareAndSwap(reinterpret_cast<T*>(ptr), data, expected);
        }
        return true;
    }

    bool WriteExclusive128(Common::ProcessAddress vaddr, const u128 data, const u128 expected) {
        u8* const ptr = GetPointerImpl(
            GetInteger(vaddr),
            [vaddr, data]() {
                LOG_ERROR(HW_Memory, "Unmapped WriteExclusive128 @ {:#016x} = {:#016x}{:016X}",
                          GetInteger(vaddr), static_cast<u64>(data[1]), static_cast<u64>(data[0]));
            },
            [&]() { HandleRasterizerWrite(GetInteger(vaddr), sizeof(u128)); });
        if (ptr) {
            return Common::AtomicCompareAndSwap(reinterpret_cast<u64*>(ptr), data, expected);
        }
        return true;
    }

    size_t DownloadGpuWrittenImages(VAddr v_address, size_t size) {
        auto& gpu = system.GPU();
        return ForEachDeviceRun(v_address, size, [&gpu](DAddr address, u64 run_size) {
            return gpu.DownloadGpuWrittenImages(address, run_size);
        });
    }

    size_t DiscardGpuWrittenImages(VAddr v_address, size_t size) {
        auto& gpu = system.GPU();
        return ForEachDeviceRun(v_address, size, [&gpu](DAddr address, u64 run_size) {
            return gpu.DiscardGpuWrittenImages(address, run_size);
        });
    }

    // Calls `op` on each run of contiguous device memory backing the mapped pages of the range;
    // returns on how many it returned true.
    template <typename Op>
    size_t ForEachDeviceRun(VAddr v_address, size_t size, Op&& op) {
        if (!gpu_device_memory) [[unlikely]] {
            gpu_device_memory = &system.Host1x().MemoryManager();
        }
        Common::ScratchBuffer<u32> scratch;
        size_t runs_with_images = 0;
        DAddr run_start = 0;
        DAddr run_end = 0;
        const auto flush_run = [&] {
            if (run_end > run_start && op(run_start, run_end - run_start)) {
                runs_with_images++;
            }
            run_start = run_end = 0;
        };
        const VAddr end = v_address + size;
        for (VAddr page = Common::AlignDown(v_address, YUZU_PAGESIZE); page < end;
             page += YUZU_PAGESIZE) {
            const u8* p = GetPointerSilent(page);
            if (p == nullptr) {
                continue;
            }
            gpu_device_memory->ApplyOpOnPointer(p, scratch, [&](DAddr address) {
                if (address == run_end && run_end != 0) {
                    run_end += YUZU_PAGESIZE;
                    return;
                }
                flush_run();
                run_start = address;
                run_end = address + YUZU_PAGESIZE;
            });
        }
        flush_run();
        return runs_with_images;
    }

    void HandleRasterizerDownload(VAddr v_address, size_t size) {
        const auto* p = GetPointerImpl(
            v_address, []() {}, []() {});
        if (!gpu_device_memory) [[unlikely]] {
            gpu_device_memory = &system.Host1x().MemoryManager();
        }
        const size_t core = system.GetCurrentHostThreadID();
        auto& current_area = rasterizer_read_areas[core];
        gpu_device_memory->ApplyOpOnPointer(p, scratch_buffers[core], [&](DAddr address) {
            const DAddr end_address = address + size;
            if (current_area.start_address <= address && end_address <= current_area.end_address)
                [[likely]] {
                return;
            }
            current_area = system.GPU().OnCPURead(address, size);
        });
    }

    void HandleRasterizerWrite(VAddr v_address, size_t size) {
        const auto* p = GetPointerImpl(
            v_address, []() {}, []() {});
        constexpr size_t sys_core = Core::Hardware::NUM_CPU_CORES - 1;
        const size_t core = (std::min)(system.GetCurrentHostThreadID(),
                                     sys_core); // any other calls threads go to syscore.
        if (!gpu_device_memory) [[unlikely]] {
            gpu_device_memory = &system.Host1x().MemoryManager();
        }
        // Guard on sys_core;
        if (core == sys_core) [[unlikely]] {
            sys_core_guard.lock();
        }
        SCOPE_EXIT {
            if (core == sys_core) [[unlikely]] {
                sys_core_guard.unlock();
            }
        };
        gpu_device_memory->ApplyOpOnPointer(p, scratch_buffers[core], [&](DAddr address) {
            auto& current_area = rasterizer_write_areas[core];
            PAddr subaddress = address >> YUZU_PAGEBITS;
            // Performance note:
            // It may not be a good idea to assume accesses are within the same subaddress (i.e same page)
            // It is often the case the games like to access wildly different addresses. Hence why I propose
            // we should let the compiler just do it's thing...
            if (current_area.last_address != subaddress) {
                // Short circuit the need to check for address/size
                auto const do_collection = (address != 0 && size != 0)
                    && system.GPU().OnCPUWrite(address, size);
                if (do_collection) {
                    current_area.last_address = subaddress;
                } else {
                    return;
                }
            }
            gpu_dirty_managers[core].Collect(address, size);
        });
    }

    struct GPUDirtyState {
        PAddr last_address;
    };

    void RasterizerProtectGpuWritten(u64 vaddr, u64 size) {
        if (vaddr == 0 || size == 0 || !current_page_table->fastmem_arena ||
            !AddressSpaceContains(*current_page_table, vaddr, size)) {
            return;
        }
        const u64 first = vaddr >> YUZU_PAGEBITS;
        const u64 last = (vaddr + size - 1) >> YUZU_PAGEBITS;
        if (has_volatile_pages.load(std::memory_order_relaxed)) {
            // A CPU read there must fault to get what the GPU drew, so it can't stay unprotected.
            std::scoped_lock lock{volatile_mutex};
            for (u64 page = first; page <= last; ++page) {
                volatile_pages.erase(page);
            }
        }
        std::scoped_lock lock{gpu_written_mutex};
        for (u64 page = first; page <= last; ++page) {
            gpu_written_pages.insert(page);
        }
        host_buffer->Protect(first << YUZU_PAGEBITS, (last - first + 1) << YUZU_PAGEBITS,
                             Common::MemoryPermission{});
    }

    void RasterizerUnprotectGpuWritten(u64 vaddr, u64 size) {
        if (vaddr == 0 || size == 0 || !current_page_table->fastmem_arena ||
            !AddressSpaceContains(*current_page_table, vaddr, size)) {
            return;
        }
        const u64 first = vaddr >> YUZU_PAGEBITS;
        const u64 last = (vaddr + size - 1) >> YUZU_PAGEBITS;
        std::scoped_lock lock{gpu_written_mutex};
        for (u64 page = first; page <= last; ++page) {
            // Only pages still in the set are cached and unreadable; as cached pages they get
            // back the permission RasterizerMarkRegionCached gives them.
            if (gpu_written_pages.erase(page) != 0) {
                host_buffer->Protect(page << YUZU_PAGEBITS, YUZU_PAGESIZE,
                                     Common::MemoryPermission::Read);
            }
        }
    }

    static s64 SteadyNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
    }

    /// Counts a CPU write fault on a cached page (NCE): a page that keeps faulting soon after the
    /// GPU cached it again becomes volatile, and stays writable from then on.
    void NoteCachedWrite(u64 vaddr) {
        const u64 page = vaddr >> YUZU_PAGEBITS;
        {
            std::scoped_lock lock{gpu_written_mutex};
            if (gpu_written_pages.contains(page)) {
                return;
            }
        }
        const s64 now = SteadyNs();
        std::scoped_lock lock{volatile_mutex};
        if (hot_pages.size() >= MaxHotPages) {
            ForgetColdPages(now);
        }
        HotPage& hot = hot_pages[page];
        hot.strikes = now - hot.last_fault_ns < HotPageWindowNs ? hot.strikes + 1 : 1;
        hot.last_fault_ns = now;
        if (hot.strikes < (hot.was_volatile ? VolatileAgainStrikes : VolatileStrikes)) {
            return;
        }
        MakeVolatile(page, now);
        // A buffer's pages are written together, so its pages that faulted just now are as hot:
        // they turn volatile with this one, and are made writable at once, instead of each
        // taking its own faults to get there (a new scene's buffers took ~40,000 at once). The
        // next report covers their writes from now on.
        const u64 first = page > VolatileNeighbours ? page - VolatileNeighbours : 0;
        for (u64 neighbour = first; neighbour <= page + VolatileNeighbours; ++neighbour) {
            const auto it = hot_pages.find(neighbour);
            if (neighbour == page || it == hot_pages.end() ||
                now - it->second.last_fault_ns >= HotPageWindowNs ||
                volatile_pages.contains(neighbour)) {
                continue;
            }
            // Held while protecting, so RasterizerProtectGpuWritten can't make it unreadable
            // in between and have that undone here.
            std::scoped_lock gpu_written_lock{gpu_written_mutex};
            if (gpu_written_pages.contains(neighbour)) {
                continue;
            }
            MakeVolatile(neighbour, now);
            host_buffer->Protect(neighbour << YUZU_PAGEBITS, YUZU_PAGESIZE,
                                 Common::MemoryPermission::ReadWrite);
        }
    }

    /// Makes a faulting cached page writable and queues it, for ReportVolatilePages to invalidate
    /// the GPU caches over it before the GPU runs its next command list. Invalidating them here
    /// took the texture and buffer cache locks, which the GPU thread holds while it runs a command
    /// list, so a fault could wait for the rest of the list: 30-60 ms on a slow CPU. A page
    /// holding an image the GPU drew can't wait (what it drew is copied back before the write):
    /// returns false for those.
    bool QueueCachedWrite(u64 vaddr) {
        const u64 page = vaddr >> YUZU_PAGEBITS;
        {
            // Held while protecting, as in NoteCachedWrite.
            std::scoped_lock lock{gpu_written_mutex};
            if (gpu_written_pages.contains(page)) {
                return false;
            }
            host_buffer->Protect(page << YUZU_PAGEBITS, YUZU_PAGESIZE,
                                 Common::MemoryPermission::ReadWrite);
        }
        // Queued after it became writable, before the write: a report in between invalidates
        // nothing for it, the next one does.
        std::scoped_lock lock{volatile_mutex};
        written_pages.insert(page);
        has_written_pages.store(true, std::memory_order_relaxed);
        return true;
    }

    void MakeVolatile(u64 page, s64 now) {
        auto& hot = hot_pages[page];
        hot.strikes = 0;
        hot.was_volatile = true;
        // Pages turn volatile in bursts (a new scene's buffers); their lifetimes are spread out
        // so they don't all expire, and fault again, at once.
        const u64 spread_ms =
            (((page * 0x9E3779B97F4A7C15ULL) >> 32) * VolatileLifetimeSpreadMs) >> 32;
        const s64 lifetime = VolatileLifetimeNs + static_cast<s64>(spread_ms) * 1'000'000;
        volatile_pages.insert_or_assign(page, now + lifetime);
        has_volatile_pages.store(true, std::memory_order_relaxed);
    }

    /// Forgets the fault history of pages that stopped faulting, keeping volatile pages' longer.
    void ForgetColdPages(s64 now) {
        std::erase_if(hot_pages, [this, now](const auto& entry) {
            const auto& [page, hot] = entry;
            const s64 keep = hot.was_volatile ? HotVolatilePageKeepNs : HotPageWindowNs;
            return now - hot.last_fault_ns >= keep && !volatile_pages.contains(page);
        });
    }

    /// Protects the range as the GPU caches it, leaving its volatile pages writable.
    void ProtectSkippingVolatile(u64 vaddr, u64 size, Common::MemoryPermission perm) {
        std::scoped_lock lock{volatile_mutex};
        const u64 end = vaddr + size;
        u64 run_begin = vaddr;
        for (u64 addr = vaddr; addr < end; addr += YUZU_PAGESIZE) {
            if (volatile_pages.contains(addr >> YUZU_PAGEBITS)) {
                if (addr > run_begin) {
                    host_buffer->Protect(run_begin, addr - run_begin, perm);
                }
                run_begin = addr + YUZU_PAGESIZE;
            }
        }
        if (end > run_begin) {
            host_buffer->Protect(run_begin, end - run_begin, perm);
        }
    }

    /// The CPU may have written any volatile page since the GPU last read it: invalidates the GPU
    /// caches over all of them, so their next use reads guest memory again. A page stops being
    /// volatile after its lifetime, after this last report: the GPU caches and protects it again,
    /// and if the CPU still writes it, it is volatile again at its first fault.
    void ReportVolatilePages() {
        if (!has_volatile_pages.load(std::memory_order_relaxed) &&
            !has_written_pages.load(std::memory_order_relaxed)) {
            return;
        }
        std::vector<u64> pages;
        {
            std::scoped_lock lock{volatile_mutex};
            pages.reserve(volatile_pages.size() + written_pages.size());
            pages.assign(written_pages.begin(), written_pages.end());
            written_pages.clear();
            has_written_pages.store(false, std::memory_order_relaxed);
            const s64 now = SteadyNs();
            for (auto it = volatile_pages.begin(); it != volatile_pages.end();) {
                pages.push_back(it->first);
                it = now >= it->second ? volatile_pages.erase(it) : std::next(it);
            }
            if (volatile_pages.empty()) {
                has_volatile_pages.store(false, std::memory_order_relaxed);
            }
            if (now - last_forget_ns >= HotVolatilePageKeepNs) {
                last_forget_ns = now;
                ForgetColdPages(now);
            }
        }
        if (!gpu_device_memory) [[unlikely]] {
            gpu_device_memory = &system.Host1x().MemoryManager();
        }
        std::scoped_lock report_lock{report_mutex};
        // The same GPU memory can be mapped at more than one device address.
        report_addresses.clear();
        for (const u64 page : pages) {
            const u8* const ptr = GetPointerImpl(page << YUZU_PAGEBITS, [] {}, [] {});
            if (ptr != nullptr) {
                gpu_device_memory->ApplyOpOnPointer(ptr, report_scratch, [this](DAddr address) {
                    report_addresses.push_back(address >> YUZU_PAGEBITS);
                });
            }
        }
        std::sort(report_addresses.begin(), report_addresses.end());
        auto& gpu = system.GPU();
        for (size_t i = 0; i < report_addresses.size();) {
            size_t end = i + 1;
            while (end < report_addresses.size() &&
                   report_addresses[end] <= report_addresses[end - 1] + 1) {
                ++end;
            }
            const u64 first = report_addresses[i];
            const u64 count = report_addresses[end - 1] - first + 1;
            gpu.InvalidateRegion(first << YUZU_PAGEBITS, count << YUZU_PAGEBITS);
            i = end;
        }
    }

    void InvalidateGPUMemory(u8* p, size_t size) {
        constexpr size_t sys_core = Core::Hardware::NUM_CPU_CORES - 1;
        const size_t core = (std::min)(system.GetCurrentHostThreadID(),
                                     sys_core); // any other calls threads go to syscore.
        if (!gpu_device_memory) [[unlikely]] {
            gpu_device_memory = &system.Host1x().MemoryManager();
        }
        // Guard on sys_core;
        if (core == sys_core) [[unlikely]] {
            sys_core_guard.lock();
        }
        SCOPE_EXIT {
            if (core == sys_core) [[unlikely]] {
                sys_core_guard.unlock();
            }
        };
        auto& gpu = system.GPU();
        gpu_device_memory->ApplyOpOnPointer(
            p, scratch_buffers[core], [&](DAddr address) { gpu.InvalidateRegion(address, size, true); });
    }

    Core::System& system;
    Tegra::MaxwellDeviceMemoryManager* gpu_device_memory{};
    Common::PageTable* current_page_table = nullptr;

    std::array<VideoCore::RasterizerDownloadArea, Core::Hardware::NUM_CPU_CORES> rasterizer_read_areas{};
    std::array<GPUDirtyState, Core::Hardware::NUM_CPU_CORES> rasterizer_write_areas{};
    std::array<Common::ScratchBuffer<u32>, Core::Hardware::NUM_CPU_CORES> scratch_buffers{};
    std::span<Core::GPUDirtyMemoryManager> gpu_dirty_managers;
    std::mutex sys_core_guard;

    // Cached pages the GPU drew images into, kept unreadable (RasterizerProtectGpuWritten). Without
    // this, the CPU read stale guest memory there: TOTK reads data the GPU produced to decide which
    // grass to draw, and stopped drawing it 20-30 s into play.
    std::mutex gpu_written_mutex;
    std::unordered_set<u64> gpu_written_pages;

    // Volatile pages, as Ryujinx calls them. With NCE every CPU write to a cached page faults, and
    // the fault invalidates the GPU caches over the page, which the GPU caches (protects) again
    // at its next use. A page the game writes every frame (vertex data, constants, a video frame
    // decoded by the CPU) did that every frame: one game took ~50,000 faults a second, two CPU
    // cores' worth, and stuttered. A page whose last VolatileStrikes faults each came within
    // HotPageWindowNs of the one before stays writable instead, and is reported written at the
    // start of each command list (ReportVolatilePages), before the GPU can read it again.
    static constexpr s64 HotPageWindowNs = 250'000'000;
    static constexpr u32 VolatileStrikes = 2;
    static constexpr u32 VolatileAgainStrikes = 1;
    static constexpr s64 VolatileLifetimeNs = 20'000'000'000;
    static constexpr u64 VolatileLifetimeSpreadMs = 20'000;
    static constexpr s64 HotVolatilePageKeepNs = 60'000'000'000;
    static constexpr size_t MaxHotPages = 1 << 16;
    static constexpr u64 VolatileNeighbours = 16;
    struct HotPage {
        s64 last_fault_ns = 0;
        u32 strikes = 0;
        bool was_volatile = false;
    };
    std::mutex volatile_mutex;
    std::unordered_map<u64, HotPage> hot_pages;
    std::unordered_map<u64, s64> volatile_pages; // page -> when it stops being volatile
    std::atomic<bool> has_volatile_pages = false;
    // Pages that faulted since the last report (QueueCachedWrite).
    std::unordered_set<u64> written_pages;
    std::atomic<bool> has_written_pages = false;
    s64 last_forget_ns = 0;
    // ReportVolatilePages runs on the GPU thread, and for presents on the thread that signals.
    std::mutex report_mutex;
    Common::ScratchBuffer<u32> report_scratch;
    std::vector<u64> report_addresses;
#ifdef __ANDROID__
    std::optional<Common::HeapTracker> heap_tracker;
    Common::HeapTracker* host_buffer{};
#else
    Common::HostMemory* host_buffer{};
#endif
    // Block ids let GetSpan hand out a direct pointer when both ends of a range lie in the same
    // mapping. They are only 16 bits wide: once they run out (Mario & Luigi: Brothership gets
    // there in seconds), later mappings get NoSpanBlock and GetSpan takes the safe path for
    // them, instead of wrapping around and mistaking two unrelated mappings for one.
    static constexpr u16 NoSpanBlock = 0xFFFF;
    std::atomic<u32> block_count = 1;

    u16 NextBlock() {
        const u32 block = block_count.fetch_add(1, std::memory_order_relaxed);
        return block < NoSpanBlock ? static_cast<u16>(block) : NoSpanBlock;
    }

    bool IsSameMapping(VAddr first, VAddr last) const {
        const u16 block = current_page_table->entries[first >> YUZU_PAGEBITS].Block();
        return block != NoSpanBlock &&
               block == current_page_table->entries[last >> YUZU_PAGEBITS].Block();
    }
};

Memory::Memory(Core::System& system_) : system{system_} {
    Reset();
}

Memory::~Memory() = default;

void Memory::Reset() {
    impl = std::make_unique<Impl>(system);
}

void Memory::SetCurrentPageTable(Kernel::KProcess& process) {
    impl->SetCurrentPageTable(process);
}

void Memory::MapMemoryRegion(Common::PageTable& page_table, Common::ProcessAddress base, u64 size,
                             Common::PhysicalAddress target, Common::MemoryPermission perms,
                             bool separate_heap) {
    impl->MapMemoryRegion(page_table, base, size, target, perms, separate_heap);
}

void Memory::UnmapRegion(Common::PageTable& page_table, Common::ProcessAddress base, u64 size,
                         bool separate_heap) {
    impl->UnmapRegion(page_table, base, size, separate_heap);
}

void Memory::ProtectRegion(Common::PageTable& page_table, Common::ProcessAddress vaddr, u64 size,
                           Common::MemoryPermission perms) {
    impl->ProtectRegion(page_table, GetInteger(vaddr), size, perms);
}

bool Memory::IsValidVirtualAddress(const Common::ProcessAddress vaddr) const {
    const auto& page_table = *impl->current_page_table;
    const size_t page = vaddr >> YUZU_PAGEBITS;
    if (page >= page_table.entries.size()) {
        return false;
    }
    const auto [pointer, type, _] = page_table.entries[page].PointerTypeBlock();
    return pointer != 0 || type == Common::PageType::RasterizerCachedMemory ||
           type == Common::PageType::DebugMemory;
}

bool Memory::IsValidVirtualAddressRange(Common::ProcessAddress base, u64 size) const {
    Common::ProcessAddress end = base + size;
    Common::ProcessAddress page = Common::AlignDown(GetInteger(base), YUZU_PAGESIZE);

    for (; page < end; page += YUZU_PAGESIZE) {
        if (!IsValidVirtualAddress(page)) {
            return false;
        }
    }

    return true;
}

u8* Memory::GetPointer(Common::ProcessAddress vaddr) {
    return impl->GetPointer(vaddr);
}

u8* Memory::GetPointerSilent(Common::ProcessAddress vaddr) {
    return impl->GetPointerSilent(vaddr);
}

const u8* Memory::GetPointer(Common::ProcessAddress vaddr) const {
    return impl->GetPointer(vaddr);
}

u8 Memory::Read8(const Common::ProcessAddress addr) {
    return impl->Read8(addr);
}

u16 Memory::Read16(const Common::ProcessAddress addr) {
    return impl->Read16(addr);
}

u32 Memory::Read32(const Common::ProcessAddress addr) {
    return impl->Read32(addr);
}

u64 Memory::Read64(const Common::ProcessAddress addr) {
    return impl->Read64(addr);
}

void Memory::Write8(Common::ProcessAddress addr, u8 data) {
    impl->Write8(addr, data);
}

void Memory::Write16(Common::ProcessAddress addr, u16 data) {
    impl->Write16(addr, data);
}

void Memory::Write32(Common::ProcessAddress addr, u32 data) {
    impl->Write32(addr, data);
}

void Memory::Write64(Common::ProcessAddress addr, u64 data) {
    impl->Write64(addr, data);
}

bool Memory::WriteExclusive8(Common::ProcessAddress addr, u8 data, u8 expected) {
    return impl->WriteExclusive8(addr, data, expected);
}

bool Memory::WriteExclusive16(Common::ProcessAddress addr, u16 data, u16 expected) {
    return impl->WriteExclusive16(addr, data, expected);
}

bool Memory::WriteExclusive32(Common::ProcessAddress addr, u32 data, u32 expected) {
    return impl->WriteExclusive32(addr, data, expected);
}

bool Memory::WriteExclusive64(Common::ProcessAddress addr, u64 data, u64 expected) {
    return impl->WriteExclusive64(addr, data, expected);
}

bool Memory::WriteExclusive128(Common::ProcessAddress addr, u128 data, u128 expected) {
    return impl->WriteExclusive128(addr, data, expected);
}

std::string Memory::ReadCString(Common::ProcessAddress vaddr, std::size_t max_length) {
    return impl->ReadCString(vaddr, max_length);
}

bool Memory::ReadBlock(const Common::ProcessAddress src_addr, void* dest_buffer,
                       const std::size_t size) {
    return impl->ReadBlockImpl(src_addr, dest_buffer, size, false);
}

std::size_t Memory::DownloadGpuWrittenImages(const Common::ProcessAddress addr,
                                             const std::size_t size) {
    return impl->DownloadGpuWrittenImages(GetInteger(addr), size);
}

std::size_t Memory::DiscardGpuWrittenImages(const Common::ProcessAddress addr,
                                            const std::size_t size) {
    return impl->DiscardGpuWrittenImages(GetInteger(addr), size);
}

bool Memory::ReadBlockUnsafe(const Common::ProcessAddress src_addr, void* dest_buffer,
                             const std::size_t size) {
    return impl->ReadBlockImpl(src_addr, dest_buffer, size, true);
}

const u8* Memory::GetSpan(const VAddr src_addr, const std::size_t size) const {
    return impl->GetSpan(src_addr, size);
}

u8* Memory::GetSpan(const VAddr src_addr, const std::size_t size) {
    return impl->GetSpan(src_addr, size);
}

bool Memory::WriteBlock(const Common::ProcessAddress dest_addr, const void* src_buffer,
                        const std::size_t size) {
    return impl->WriteBlockImpl(dest_addr, src_buffer, size, false);
}

bool Memory::WriteBlockUnsafe(const Common::ProcessAddress dest_addr, const void* src_buffer,
                              const std::size_t size) {
    return impl->WriteBlockImpl(dest_addr, src_buffer, size, true);
}

bool Memory::CopyBlock(Common::ProcessAddress dest_addr, Common::ProcessAddress src_addr,
                       const std::size_t size) {
    return impl->CopyBlock(dest_addr, src_addr, size);
}

bool Memory::ZeroBlock(Common::ProcessAddress dest_addr, const std::size_t size) {
    return impl->ZeroBlock(dest_addr, size);
}

void Memory::SetGPUDirtyManagers(std::span<Core::GPUDirtyMemoryManager> managers) {
    impl->gpu_dirty_managers = managers;
}

Result Memory::InvalidateDataCache(Common::ProcessAddress dest_addr, const std::size_t size) {
    return impl->InvalidateDataCache(dest_addr, size);
}

Result Memory::StoreDataCache(Common::ProcessAddress dest_addr, const std::size_t size) {
    return impl->StoreDataCache(dest_addr, size);
}

Result Memory::FlushDataCache(Common::ProcessAddress dest_addr, const std::size_t size) {
    return impl->FlushDataCache(dest_addr, size);
}

void Memory::RasterizerMarkRegionCached(Common::ProcessAddress vaddr, u64 size, bool cached) {
    impl->RasterizerMarkRegionCached(GetInteger(vaddr), size, cached);
}

void Memory::RasterizerProtectGpuWritten(Common::ProcessAddress vaddr, u64 size) {
    impl->RasterizerProtectGpuWritten(GetInteger(vaddr), size);
}

void Memory::RasterizerUnprotectGpuWritten(Common::ProcessAddress vaddr, u64 size) {
    impl->RasterizerUnprotectGpuWritten(GetInteger(vaddr), size);
}

void Memory::MarkRegionDebug(Common::ProcessAddress vaddr, u64 size, bool debug) {
    impl->MarkRegionDebug(GetInteger(vaddr), size, debug);
}

bool Memory::InvalidateNCE(Common::ProcessAddress vaddr, size_t size) {
    [[maybe_unused]] bool mapped = true;
    [[maybe_unused]] bool rasterizer = false;

    u8* const ptr = impl->GetPointerImpl(
        GetInteger(vaddr),
        [&] {
            LOG_ERROR(HW_Memory, "Unmapped InvalidateNCE for {} bytes @ {:#x}", size,
                      GetInteger(vaddr));
            mapped = false;
        },
        [&] { rasterizer = true; });
    bool queued = false;
    if (rasterizer) {
        impl->NoteCachedWrite(GetInteger(vaddr));
        queued = impl->QueueCachedWrite(GetInteger(vaddr));
        if (!queued) {
            impl->InvalidateGPUMemory(ptr, size);
        }
    }

#ifdef __ANDROID__
    // A queued page stays cached until the next report, so it is mapped here if it is in a
    // separate heap mapping that isn't resident (one made in a burst of many small mappings can
    // be unmapped again to make room). Otherwise the access faults again and again, and the report
    // never comes when the GPU is waiting for this thread's next command list.
    if ((!rasterizer || queued) && mapped) {
        impl->host_buffer->DeferredMapSeparateHeap(GetInteger(vaddr));
    }
#endif

    return mapped && ptr != nullptr;
}

void Memory::ReportVolatilePages() {
    impl->ReportVolatilePages();
}

bool Memory::InvalidateSeparateHeap(void* fault_address) {
#ifdef __ANDROID__
    return impl->host_buffer->DeferredMapSeparateHeap(static_cast<u8*>(fault_address));
#else
    return false;
#endif
}

} // namespace Core::Memory
