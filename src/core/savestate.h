// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string>

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
//
// Known limitation (confirmed, not yet fixed): only reliable for guests with
// few or no threads blocked in a kernel wait at capture/restore time (this is
// what the test homebrew in tools/savestate-test-homebrew/ exercises, and it
// round-trips correctly there). A real game's threads spend most of their
// time waiting on a condvar/event/IPC reply - each such thread is suspended
// inside a live host fiber (KThread::GetHostContext()), and its true resume
// point lives on that native call stack, not in Svc::ThreadContext. Blindly
// overwriting its register cache AND its guest stack memory leaves that fiber
// resuming into a world that no longer matches what it's holding - the guest
// detects this and self-terminates via svcBreak within the same tick as the
// restore. Confirmed reproducible on two different devices with two different
// retail titles; restoring register context selectively (skipping threads
// still Waiting) was tried and still failed the same way, because the blanket
// per-region memory restore also clobbers those threads' own stack contents.
// Fixing this for real games needs per-thread stack exclusion plus explicit,
// wait-type-aware reconstruction (at minimum condvar/event) - out of scope
// for this MVP.
[[nodiscard]] bool Capture(Core::System& system, const std::string& path);

// Restores CPU register state and process memory previously written by
// Capture(). The caller must ensure `system` is already paused. Fails safely
// (logs and returns false, without modifying anything) if the file is
// missing/invalid, or if the live process' thread count no longer matches
// the captured state (e.g. the guest created/destroyed threads since the
// capture) - there is no way to reconcile that without recreating kernel
// objects, which is out of scope for this same-session MVP.
[[nodiscard]] bool Restore(Core::System& system, const std::string& path);

} // namespace Core::SaveState
