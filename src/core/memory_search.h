// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <optional>
#include <span>
#include <vector>

#include "common/common_types.h"

namespace Core {
class System;
}

// Live memory search/edit ("Lemon Cheater"): a Cheat Engine style value scanner, distinct
// from Core::Memory::CheatEngine (the Atmosphere-style .txt cheat code VM/parser).
//
// Scope (v1): values are 4-byte signed integers (i32). Everything here uses
// Memory::ReadBlockUnsafe()/WriteBlockUnsafe(), not the regular ReadBlock()/WriteBlock() -
// the regular versions sync with whatever the GPU emulation has cached for that memory
// (HandleRasterizerDownload()/HandleRasterizerWrite() per chunk), which is unwanted overhead
// for a tool that only cares about plain game-logic values. Confirmed on-device: scanning
// multiple GB of heap through the regular (safe) path forced enough GPU cache invalidation
// to visibly wreck the game's own rendering performance in a way that persisted until the
// game was reloaded, even with zero writes.
namespace Core::MemorySearch {

struct Match {
    u64 address;
    s32 value; // the value at `address` as of the call that produced this Match
};

// Searches the process' general heap (Kernel::KMemoryState::Normal - see
// Core::IsScannableMemoryState for the full set this deliberately narrows from: a real
// game's total scannable footprint can be multiple GB, and reading all of it is enough
// sustained work to visibly heat up and throttle a handheld. Long-lived game-logic values
// live on the heap, so this trade-off doesn't cost much in practice) for an exact match of
// `needle_value`. Returns up to `max_results` matches - a real game's heap is orders of
// magnitude bigger than a homebrew test's, so a common small value can easily have
// thousands of matches; the default is high enough that a real search shouldn't get
// truncated before Refine() has a chance to narrow it down (truncation would silently drop
// the one address the caller actually wants). It only exists as a safety valve against a
// degenerate value matching most of memory. The caller is expected to cap how many of the
// results it actually displays, separately - this is the same split real Cheat-Engine-style
// tools make between "candidates tracked" and "candidates shown". Does not require the
// system to be paused - this is a live memory read, same as the game's own code reading its
// own memory.
[[nodiscard]] std::vector<Match> Search(Core::System& system, s32 needle_value,
                                        size_t max_results = 300000);

// Step 1 of a "blind" search, for when the caller doesn't know an exact starting value (e.g.
// "find my HP" without knowing the current number). Snapshots the current contents of the
// general heap and holds onto it (module-level state, one snapshot at a time - same pattern
// as global_config/per_game_config in native_config.cpp) for a later CompareSnapshot() call.
// Same cost class as Search(): one bulk read per Normal-state region.
void TakeSnapshot(Core::System& system);

enum class Comparison {
    Increased, // current value > previous value
    Decreased, // current value < previous value
    Unchanged, // current value == previous value
};

// Step 2 of a blind search - and the first point it produces an actual, addressable
// candidate list: re-reads the heap and compares each 4-byte-aligned value against what
// TakeSnapshot() captured there, keeping only addresses whose current value satisfies
// `comparison` relative to the snapshotted one. Frees the snapshot afterward (single-use).
// Returns up to `max_results` matches, same reasoning as Search().
[[nodiscard]] std::vector<Match> CompareSnapshot(Core::System& system, Comparison comparison,
                                                 size_t max_results = 300000);

// "Next scan": narrows an existing candidate list down to just the ones that still qualify.
// For Comparison::Increased/Decreased, each candidate's current value is compared against
// its OWN previous value (`candidates[i].value`, e.g. from an earlier Search()/Refine()/
// CompareSnapshot() call) - this is what lets "greater than/less than" chain across
// multiple refine passes, not just once right after a blind search. `needle_value` is only
// used when `comparison` is std::nullopt (exact-match refine, the original behavior); when
// `comparison` is set, `needle_value` is ignored.
//
// Reads exactly `candidates.size() * 4` bytes total - one small direct read per
// already-known address, nothing more. (An earlier version instead re-walked memory
// region-by-region like Search() to avoid per-address read overhead, but that backfires
// when candidates are scattered across most of the heap: "only the regions containing a
// candidate" ends up being nearly the whole heap again, i.e. multiple GB re-read per
// refine - worse than the many-small-reads it was trying to avoid.)
[[nodiscard]] std::vector<Match> Refine(Core::System& system, std::span<const Match> candidates,
                                        std::optional<Comparison> comparison,
                                        s32 needle_value = 0);

// Reads `out.size()` bytes at `address` from the current application process' memory.
[[nodiscard]] bool Read(Core::System& system, u64 address, std::span<u8> out);

// Writes `value` at `address` in the current application process' memory. This does not
// (and cannot safely) change the size of anything - `value` must be the same length as
// whatever it's replacing, or it will overwrite unrelated adjacent memory.
[[nodiscard]] bool Write(Core::System& system, u64 address, std::span<const u8> value);

// Freeze: keeps re-writing `value` at `address` (roughly every 100ms, via a background
// thread that starts on the first frozen address and stops itself once none remain) so the
// game can't change it without a matching cheat code - infinite HP/ammo/etc. Calling this
// again for an already-frozen address just updates the value being held.
void SetFrozen(Core::System& system, u64 address, s32 value);

// Unfreezes a single address. No-op if it wasn't frozen.
void ClearFrozen(u64 address);

// Unfreezes everything and stops the background thread. Must be called when a game session
// ends (EmulationSession::ShutdownEmulation) - frozen addresses are only meaningful for the
// process they were found in, and would otherwise keep getting written into whatever game
// loads next.
void ClearAllFrozen();

// The current frozen (address, value) set, for the UI to display/manage.
[[nodiscard]] std::vector<Match> GetFrozen();

} // namespace Core::MemorySearch
