// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <map>
#include <mutex>
#include <optional>
#include <thread>
#include <tuple>

#include "common/logging.h"
#include "common/polyfill_thread.h"
#include "common/thread.h"
#include "core/core.h"
#include "core/hle/kernel/k_memory_block.h"
#include "core/hle/kernel/k_process.h"
#include "core/memory.h"
#include "core/memory_region_scan.h"
#include "core/memory_search.h"

namespace Core::MemorySearch {

namespace {

s32 DecodeValue(const u8* bytes) {
    s32 value{};
    std::memcpy(&value, bytes, sizeof(value));
    return value;
}

std::array<u8, sizeof(s32)> EncodeValue(s32 value) {
    std::array<u8, sizeof(s32)> bytes{};
    std::memcpy(bytes.data(), &value, sizeof(value));
    return bytes;
}

bool SatisfiesComparison(Comparison comparison, s32 previous, s32 current) {
    switch (comparison) {
    case Comparison::Increased:
        return current > previous;
    case Comparison::Decreased:
        return current < previous;
    case Comparison::Unchanged:
        return current == previous;
    }
    return false;
}

// Module-level, one-at-a-time snapshot for the blind-search flow (TakeSnapshot() /
// CompareSnapshot()) - same pattern as global_config/per_game_config in native_config.cpp.
struct SnapshotRegion {
    MemoryRegion region;
    std::vector<u8> data;
};
std::vector<SnapshotRegion> g_snapshot;

// Freeze list + background rewrite thread. Needs its own mutex (unlike g_snapshot above) -
// the freeze thread reads g_frozen from its own thread while SetFrozen()/ClearFrozen() can be
// called concurrently from whatever thread JNI dispatches on.
std::mutex g_freeze_mutex;
std::map<u64, s32> g_frozen;
std::jthread g_freeze_thread;

void FreezeLoop(std::stop_token stop_token, Core::System* system) {
    Common::SetCurrentThreadName("CheatFreeze");

    using namespace std::literals::chrono_literals;
    while (!stop_token.stop_requested()) {
        {
            std::scoped_lock lock(g_freeze_mutex);
            for (const auto& [address, value] : g_frozen) {
                const auto bytes = EncodeValue(value);
                // Best-effort: a single missed tick (e.g. the address is momentarily
                // unreadable/unmapped) isn't worth logging every ~100ms - the next tick retries.
                std::ignore = Write(*system, address, bytes);
            }
        }
        Common::StoppableTimedWait(stop_token, 100ms);
    }
}

} // namespace

std::vector<Match> Search(Core::System& system, s32 needle_value, size_t max_results) {
    const auto t_start = std::chrono::steady_clock::now();
    std::vector<Match> results;

    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        LOG_ERROR(Core, "MemorySearch::Search: no application process");
        return results;
    }

    const auto needle = EncodeValue(needle_value);

    auto& memory = system.ApplicationMemory();
    const auto regions = EnumerateScannableRegions(process->GetPageTable());

    u64 total_bytes_read = 0;
    std::vector<u8> buffer;
    for (const auto& region : regions) {
        // Restrict to the general heap: that's where long-lived game-logic values (coin
        // counts, HP, timers...) actually live. Code/Stack/ThreadLocal/Shared are also
        // technically scannable (see IsScannableMemoryState), but including them roughly
        // doubles the bytes read for a real game's multi-GB memory footprint, which is
        // enough sustained CPU/memory-bandwidth work to visibly heat up and throttle a
        // handheld - not worth it for state that's a poor fit for this search anyway.
        if (region.state != Kernel::KMemoryState::Normal || results.size() >= max_results ||
            region.size < needle.size()) {
            continue;
        }

        buffer.resize(region.size);
        if (!memory.ReadBlockUnsafe(region.address, buffer.data(), region.size)) {
            // Not every region is guaranteed readable end-to-end (partial mappings,
            // races with the guest) - skip it rather than aborting the whole search.
            continue;
        }
        total_bytes_read += region.size;

        auto begin = buffer.begin();
        const auto end = buffer.end();
        while (begin != end) {
            const auto found = std::search(begin, end, needle.begin(), needle.end());
            if (found == end) {
                break;
            }

            results.push_back(
                {.address = region.address + static_cast<u64>(found - buffer.begin()),
                 .value = needle_value});
            if (results.size() >= max_results) {
                break;
            }

            // Advance by one byte rather than past the whole match, so overlapping
            // occurrences of the needle aren't missed.
            begin = found + 1;
        }
    }

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t_start)
                                .count();
    LOG_INFO(Core,
              "MemorySearch::Search: {} region(s), {} byte(s) read, {} match(es) in {} ms",
              regions.size(), total_bytes_read, results.size(), elapsed_ms);

    return results;
}

void TakeSnapshot(Core::System& system) {
    const auto t_start = std::chrono::steady_clock::now();
    g_snapshot.clear();

    auto* process = system.ApplicationProcess();
    if (process == nullptr) {
        LOG_ERROR(Core, "MemorySearch::TakeSnapshot: no application process");
        return;
    }

    auto& memory = system.ApplicationMemory();
    const auto regions = EnumerateScannableRegions(process->GetPageTable());

    u64 total_bytes_read = 0;
    for (const auto& region : regions) {
        if (region.state != Kernel::KMemoryState::Normal || region.size < sizeof(s32)) {
            continue;
        }

        std::vector<u8> data(region.size);
        if (!memory.ReadBlockUnsafe(region.address, data.data(), data.size())) {
            continue;
        }
        total_bytes_read += data.size();
        g_snapshot.push_back({.region = region, .data = std::move(data)});
    }

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t_start)
                                .count();
    LOG_INFO(Core, "MemorySearch::TakeSnapshot: {} region(s), {} byte(s) read in {} ms",
              g_snapshot.size(), total_bytes_read, elapsed_ms);
}

std::vector<Match> CompareSnapshot(Core::System& system, Comparison comparison,
                                   size_t max_results) {
    const auto t_start = std::chrono::steady_clock::now();
    std::vector<Match> results;

    if (system.ApplicationProcess() == nullptr) {
        LOG_ERROR(Core, "MemorySearch::CompareSnapshot: no application process");
        g_snapshot.clear();
        return results;
    }

    auto& memory = system.ApplicationMemory();
    u64 total_bytes_read = 0;
    std::vector<u8> buffer;
    for (const auto& snapshot_region : g_snapshot) {
        if (results.size() >= max_results) {
            break;
        }

        const auto& region = snapshot_region.region;
        buffer.resize(region.size);
        if (!memory.ReadBlockUnsafe(region.address, buffer.data(), buffer.size())) {
            continue;
        }
        total_bytes_read += buffer.size();

        const auto aligned_count = buffer.size() / sizeof(s32);
        for (size_t i = 0; i < aligned_count; i++) {
            const size_t offset = i * sizeof(s32);
            const s32 previous = DecodeValue(&snapshot_region.data[offset]);
            const s32 current = DecodeValue(&buffer[offset]);
            if (!SatisfiesComparison(comparison, previous, current)) {
                continue;
            }

            results.push_back({.address = region.address + offset, .value = current});
            if (results.size() >= max_results) {
                break;
            }
        }
    }

    // Single-use: the caller now has a normal candidate list (Match carries the current
    // value), so subsequent refines go through Refine() instead of needing this again.
    g_snapshot.clear();

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t_start)
                                .count();
    LOG_INFO(Core, "MemorySearch::CompareSnapshot: {} byte(s) read, {} match(es) in {} ms",
              total_bytes_read, results.size(), elapsed_ms);

    return results;
}

std::vector<Match> Refine(Core::System& system, std::span<const Match> candidates,
                          std::optional<Comparison> comparison, s32 needle_value) {
    const auto t_start = std::chrono::steady_clock::now();
    std::vector<Match> results;

    if (candidates.empty() || system.ApplicationProcess() == nullptr) {
        if (system.ApplicationProcess() == nullptr) {
            LOG_ERROR(Core, "MemorySearch::Refine: no application process");
        }
        return results;
    }

    // Go straight to each stored candidate address rather than re-walking memory region by
    // region: candidates already ARE the plain list of exact addresses we care about, so
    // checking them directly reads only candidates.size() * 4 bytes total (a few hundred KB
    // even for 300000 candidates) instead of re-reading whole regions in bulk. Re-reading by
    // region was tried first and made things worse for a common search value - with
    // candidates scattered across nearly every region, "only the regions that contain a
    // candidate" ends up being almost the entire heap again, multiple GB re-read per refine.
    auto& memory = system.ApplicationMemory();
    std::array<u8, sizeof(s32)> buffer{};
    for (const auto& candidate : candidates) {
        if (!memory.ReadBlockUnsafe(candidate.address, buffer.data(), buffer.size())) {
            continue;
        }
        const s32 current = DecodeValue(buffer.data());

        const bool keep = comparison ? SatisfiesComparison(*comparison, candidate.value, current)
                                     : current == needle_value;
        if (keep) {
            results.push_back({.address = candidate.address, .value = current});
        }
    }

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t_start)
                                .count();
    LOG_INFO(Core,
              "MemorySearch::Refine: {} candidate(s) in, {} byte(s) read, {} match(es) in {} ms",
              candidates.size(), candidates.size() * sizeof(s32), results.size(), elapsed_ms);

    return results;
}

bool Read(Core::System& system, u64 address, std::span<u8> out) {
    if (system.ApplicationProcess() == nullptr) {
        LOG_ERROR(Core, "MemorySearch::Read: no application process");
        return false;
    }

    return system.ApplicationMemory().ReadBlockUnsafe(address, out.data(), out.size());
}

bool Write(Core::System& system, u64 address, std::span<const u8> value) {
    if (system.ApplicationProcess() == nullptr) {
        LOG_ERROR(Core, "MemorySearch::Write: no application process");
        return false;
    }

    // Same reasoning as Read(): WriteBlock() calls HandleRasterizerWrite() and syncs with the
    // GPU cache, which is unwanted overhead/disruption for editing a plain game-logic value.
    return system.ApplicationMemory().WriteBlockUnsafe(address, value.data(), value.size());
}

void SetFrozen(Core::System& system, u64 address, s32 value) {
    std::scoped_lock lock(g_freeze_mutex);
    g_frozen[address] = value;

    if (!g_freeze_thread.joinable()) {
        g_freeze_thread = std::jthread(
            [&system](std::stop_token stop_token) { FreezeLoop(stop_token, &system); });
    }
}

void ClearFrozen(u64 address) {
    std::scoped_lock lock(g_freeze_mutex);
    g_frozen.erase(address);

    if (g_frozen.empty()) {
        // Assigning over a joinable jthread requests a stop and joins it - same idiom as
        // PlayTimeManager::Stop() in frontend_common/play_time_manager.cpp.
        g_freeze_thread = {};
    }
}

void ClearAllFrozen() {
    std::scoped_lock lock(g_freeze_mutex);
    g_frozen.clear();
    g_freeze_thread = {};
}

std::vector<Match> GetFrozen() {
    std::scoped_lock lock(g_freeze_mutex);
    std::vector<Match> results;
    results.reserve(g_frozen.size());
    for (const auto& [address, value] : g_frozen) {
        results.push_back({.address = address, .value = value});
    }
    return results;
}

} // namespace Core::MemorySearch
