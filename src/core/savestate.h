// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <chrono>
#include <shared_mutex>
#include <string>
#include <vector>

#include "common/common_types.h"

namespace Core {
class System;
}

namespace Core::SaveState {

// Captures the guest's threads (registers, wait state, ids, handles), its memory map and all its
// scannable memory to a file at `path`, and starts following, in UndoJournal, the changes the game
// then makes to state the file does not hold. The caller must have paused the system and waited for
// a settled moment (see WaitSignature). Same session only: the journals do not outlive the app.
[[nodiscard]] bool Capture(Core::System& system, const std::string& path);

// Restores a file written by Capture(): undoes the changes followed since the save (memory
// mappings, GPU memory bookkeeping, service objects), writes back the memory that differs, puts the
// threads back under their saved ids and handles, and gives the threads that are not asleep in the
// kernel their saved registers. The caller must have paused the system.
//
// A thread asleep in a kernel wait (condition variable, address arbiter, WaitSynchronization,
// sleep) keeps its registers and its stack: it is parked inside a live host fiber
// (KThread::GetHostContext()) whose resume point is on that native stack, and restoring over it
// made real games self-terminate via svcBreak. Exceptions, which are given their saved registers
// and stack: a thread asleep in the same call as at the save that the game destroyed and created
// again, and one asleep on another condition variable, which is moved back to the saved one.
// Threads waiting for a service's answer (IPC) are restored like running ones.
//
// Fails without changing anything when the file is invalid, the thread set changed, the threads are
// not waiting as at the save, or a change since the save cannot be undone.
enum class RestoreFailure {
    Other,
    // The guest's threads are not waiting the way they were when the save was made (some are in
    // the middle of waking or going to sleep), so the kernel and the saved memory would disagree.
    // Nothing was changed; trying again a moment later usually works.
    NotSettled,
    // The game changed something since the save that cannot be undone (or the save is from an
    // earlier run of the app), or freed memory the save holds that undoing would not bring back.
    // Nothing was changed.
    MemoryLayoutChanged,
};

// `failure`, when given, receives why a false return happened. `held_gate`, when it holds the
// service reply gate already (CloseServiceGate), is used instead of taking it again: the caller
// closed it before it last looked at the threads, so none of them can have had an answer since.
//
// With `allow_rebuild`, a thread not as at the save is put back into its saved state where it can
// be (see CheckLoad): one that has ended since is created again, one running game code gets its
// saved registers and wait, and one asleep in (or just back from) a kernel call is ended and
// created again. A load across a scene change needs this: the scene's threads end, and long-lived
// ones wait elsewhere.
[[nodiscard]] bool Restore(Core::System& system, const std::string& path,
                           RestoreFailure* failure = nullptr,
                           std::unique_lock<std::shared_timed_mutex>* held_gate = nullptr,
                           bool allow_rebuild = false);

// For the log: one WaitSignature() word as text: kernel state, wait reason, whether the thread is
// inside a kernel call, and the folded wait target.
[[nodiscard]] std::string DescribeWaitWord(u32 word);

// For the log: the threads whose wait (see WaitSignature) differs between the two signatures, as
// "thread i: saved <state reason [svc] target>, live <...>".
[[nodiscard]] std::string DescribeWaitDifferences(const std::vector<u32>& saved,
                                                  const std::vector<u32>& live);

// For the log: the threads that HasRiskyPendingWaits() counts.
[[nodiscard]] std::string DescribeRiskyPendingWaits(Core::System& system);

/// Copies the images the GPU drew back to guest memory, so a capture holds them. Capture does
/// this itself; done earlier, it lets the caller see which threads the GPU work this finishes
/// wakes. Returns how many device memory runs held such images.
size_t CopyGpuDrawnImages(Core::System& system);

// Services (HLE) take this shared while they receive a request and while they complete and answer
// it; a save or a restore takes it exclusively. A service answers by writing into the guest's
// memory and waking the thread that asked, and it runs on its own host thread, which pausing the
// game does not stop. The main thread of Super Mario 3D World is waiting for such an answer at
// nearly every moment: when the answer landed during the second a restore takes, the restore
// overwrote it and rewound the thread, and the game aborted on an invalid reply (2010-0212) - the
// failure seen in roughly one load out of seven, with or without dying in between.
[[nodiscard]] std::shared_timed_mutex& ServiceReplyGate();

// Closes the gate for the caller: waits up to `timeout` for the services answering a request now.
[[nodiscard]] bool CloseServiceGate(std::unique_lock<std::shared_timed_mutex>& gate,
                                    std::chrono::milliseconds timeout);

// Held by a service thread while it receives and answers a request.
class ServiceGateHold {
public:
    ServiceGateHold();
    ~ServiceGateHold();
    ServiceGateHold(const ServiceGateHold&) = delete;
    ServiceGateHold& operator=(const ServiceGateHold&) = delete;

private:
    friend class ServiceGateRelease;
    ServiceGateHold* previous;
    bool held{true};
};

// Lets go of the gate while a service blocks inside a request (waiting for a free display buffer,
// for example: the game's main thread asks for one every frame and the answer can take as long as
// the game stays paused). Taken again before the service goes on to write its answer.
class ServiceGateRelease {
public:
    ServiceGateRelease();
    ~ServiceGateRelease();
    ServiceGateRelease(const ServiceGateRelease&) = delete;
    ServiceGateRelease& operator=(const ServiceGateRelease&) = delete;

private:
    ServiceGateHold* hold;
};

// One entry per guest thread, in thread-list order: the wait reason (ThreadWaitReasonForDebugging)
// in bits 0-6, whether it is inside a kernel call in bit 7, its kernel state in bits 8-15 and a
// hash of what it waits for in bits 16-31. Saved inside every
// savestate. A save or a restore is only consistent when no thread is in the middle of a
// transition, which this shows as the signature changing from one look to the next, or differing
// from the saved one. Over 33 save/load pairs on Super Mario 3D World every pair with identical
// signatures loaded fine and every frozen load had a difference.
// Given the thread-local storage addresses of a save's threads (ReadSavedSignature), leaves out
// live threads in a slot no saved thread had: threads the game started after that save, which
// Restore ends.
[[nodiscard]] std::vector<u32> WaitSignature(Core::System& system,
                                             const std::vector<u64>* saved_tls = nullptr);

// Whether a live signature allows restoring a save with the given one: equal, except for threads
// asleep on another condition variable, which Restore moves back to the saved one.
[[nodiscard]] bool SignaturesMatch(const std::vector<u32>& saved, const std::vector<u32>& live);

// Whether a load can go ahead now (see Restore's `allow_rebuild`): each thread of the save is as at
// the save, or, when rebuilding is allowed, one Restore can put back into its saved state.
struct LoadCheck {
    bool ok = false;
    u32 ended = 0;              // threads of the save that have ended since
    bool ended_blocked = false; // one of them cannot be created again
    u32 rebuilt = 0;            // threads the load would put back or create again
    std::string blocked;        // what keeps the load from going ahead, for the log
};
[[nodiscard]] LoadCheck CheckLoad(Core::System& system, const std::vector<u32>& saved_signature,
                                  const std::vector<u64>& saved_tls,
                                  const std::vector<u32>& saved_svc, bool allow_rebuild);

// Reads the signature stored in a savestate file without loading anything else, and the
// thread-local storage address of each saved thread if asked, with (when `svc` is also given) the
// kernel call each was in.
[[nodiscard]] bool ReadSavedSignature(const std::string& path, std::vector<u32>& out,
                                      std::vector<u64>* tls = nullptr,
                                      std::vector<u32>* svc = nullptr);

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
// Since the signature holds what each thread waits on (WaitSignature), a load
// no longer needs this: a matching signature has every arbiter wait of the save
// on the same address and no other, and Restore checks the full address. A save
// allows the arbiter waits that are a thread's usual state, so this only still
// decides a save made while the user holds the game paused.
[[nodiscard]] bool HasRiskyPendingWaits(Core::System& system);

} // namespace Core::SaveState
