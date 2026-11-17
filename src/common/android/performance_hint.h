// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <chrono>

namespace Common::Android::PerformanceHint {

// The threads whose work makes up each emulated frame (the emulated cores, the GPU thread, the
// Vulkan worker) share one Android performance hint session (ADPF, Android 13+): the system is
// told how long a frame may take and how long frames really take, and sets those threads' cores
// and clocks to keep up, instead of guessing from their load. Without ADPF all of this does
// nothing.

/// Adds the calling thread to the session.
void AddCurrentThread();

/// Removes the calling thread, before it ends.
void RemoveCurrentThread();

/// How long a frame may take: the emulated display's frame period.
void SetTargetFrameDuration(std::chrono::nanoseconds duration);

/// A frame was presented: reports the time since the previous one.
void ReportFrame();

/// Closes the session (the game ended).
void Shutdown();

} // namespace Common::Android::PerformanceHint
