// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <atomic>

#include "common/common_types.h"

namespace VideoCore {

/// Frames made by frame generation and presented since the emulator started, for the
/// frontend's frame rate counters.
inline std::atomic<u64> generated_frames{0};

inline void CountGeneratedFrames(u64 count) {
    generated_frames.fetch_add(count, std::memory_order_relaxed);
}

[[nodiscard]] inline u64 GeneratedFrameCount() {
    return generated_frames.load(std::memory_order_relaxed);
}

} // namespace VideoCore
