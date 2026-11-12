// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string>
#include <vector>

namespace Core {
class System;
}

namespace Core::SaveState {

// Captures the current guest CPU register state (all threads) and all mapped
// process memory to a file at `path`. The caller must ensure `system` is
// already paused (no guest CPU instructions executing) before calling this -
// it is not checked here.
//
// Scope: same-session only. Does not capture GPU state or HLE service state
// (open IPC sessions, pending timers, etc.) - restoring onto a process whose
// non-CPU/memory state has diverged since the capture is not supported.
[[nodiscard]] bool Capture(Core::System& system, const std::string& path);

// Restores CPU register state and process memory previously written by
// Capture(). The caller must ensure `system` is already paused. Fails safely
// (logs and returns false, without modifying anything) if the file is
// missing/invalid, or if the live process' thread count no longer matches
// the captured state (e.g. the guest created/destroyed threads since the
// capture) - there is no way to reconcile that without recreating kernel
// objects, which is out of scope for this same-session MVP.
//
// A thread that is still asleep in a kernel wait (condvar/IPC reply/
// WaitSynchronization/address arbiter/sleep) at the moment Restore() runs is
// deliberately left untouched - neither its Svc::ThreadContext nor its own
// guest stack memory is overwritten. That thread is parked inside a live
// host fiber (KThread::GetHostContext()) whose true resume point lives on
// that native call stack, not in the serialized register/memory snapshot;
// blindly restoring over it left the fiber resuming into a world that no
// longer matched what it was holding, and the guest detected this and
// self-terminated via svcBreak (confirmed reproducible on two different
// devices with two different retail titles). Leaving it alone means that
// thread simply keeps running from wherever it currently is instead of
// rewinding - for a typical short-lived wait (an IPC reply, a condvar signal)
// this is a small, self-correcting discrepancy rather than a crash. See
// GetWaitReasonForDebugging() in core/hle/kernel/k_thread.h.
enum class RestoreFailure {
    Other,
    // The guest's threads are not waiting the way they were when the save was made (some are in
    // the middle of waking or going to sleep), so the kernel and the saved memory would disagree.
    // Nothing was changed; trying again a moment later usually works.
    NotSettled,
    // Memory the savestate holds is no longer mapped: the guest freed or moved it after the save
    // (dying and respawning does this). Nothing was changed.
    MemoryLayoutChanged,
};

// `failure`, when given, receives why a false return happened.
[[nodiscard]] bool Restore(Core::System& system, const std::string& path,
                           RestoreFailure* failure = nullptr);

// One entry per guest thread, in thread-list order: its kernel state in bits 8 and up and what it
// is waiting for (the ThreadWaitReasonForDebugging value) in the low byte. Saved inside every
// savestate. A save or a restore is only consistent when no thread is in the middle of a
// transition, which this shows as the signature changing from one look to the next, or differing
// from the saved one. Over 33 save/load pairs on Super Mario 3D World every pair with identical
// signatures loaded fine and every frozen load had a difference.
[[nodiscard]] std::vector<u32> WaitSignature(Core::System& system);

// Reads the signature stored in a savestate file without loading anything else.
[[nodiscard]] bool ReadSavedSignature(const std::string& path, std::vector<u32>& out);

// Read-only: true if any live thread is currently in an address-arbiter wait,
// which is a lock handoff in progress (SignalToAddress/WaitForAddress). A save
// taken, or a restore done, at that instant leaves guest memory saying "a
// waiter is queued on this lock" while the kernel has nobody queued there (or
// the other way round), so the waiter is never woken: the game stays frozen.
// Measured on Super Mario 3D World with an automatic save/load loop: every
// load that froze involved a save or a live state with one thread in this wait
// (waits Cond=12 Arb=1 instead of the steady Cond=13 Arb=0), and 9 of 9
// saves without it loaded fine. Condition-variable waiters are NOT the
// problem: a game's idle worker pool sits in them permanently (13 threads
// here), so counting them made this check true all the time and useless.
// Touches nothing and doesn't look at any savestate file; meant to be polled
// while the game is paused, letting it run on in short steps until it is false.
[[nodiscard]] bool HasRiskyPendingWaits(Core::System& system);


} // namespace Core::SaveState
