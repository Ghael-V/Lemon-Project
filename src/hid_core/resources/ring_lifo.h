// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <atomic>

#include "common/common_types.h"

namespace Service::HID {

// An entry the application reads without locking, as a sequence lock: its sampling number is odd
// while the entry is being written, and twice the state's own sampling number once it is done.
// Newer SDKs retry while it is odd, so a plain counter (odd every other entry) kept them reading
// the same entry forever (The Drifter, Turok).
template <typename State>
struct AtomicStorage {
    s64 sampling_number;
    State state;
};

template <typename State, std::size_t max_buffer_size>
struct Lifo {
    s64 timestamp{};
    s64 total_buffer_count = static_cast<s64>(max_buffer_size);
    s64 buffer_tail{};
    s64 buffer_count{};
    std::array<AtomicStorage<State>, max_buffer_size> entries{};

    const AtomicStorage<State>& ReadCurrentEntry() const {
        return entries[buffer_tail];
    }

    const AtomicStorage<State>& ReadPreviousEntry() const {
        return entries[GetPreviousEntryIndex()];
    }

    std::size_t GetPreviousEntryIndex() const {
        return static_cast<size_t>((buffer_tail + max_buffer_size - 1) % max_buffer_size);
    }

    std::size_t GetNextEntryIndex() const {
        return static_cast<size_t>((buffer_tail + 1) % max_buffer_size);
    }

    void WriteNextEntry(const State& new_state) {
        const auto next = GetNextEntryIndex();
        auto& entry = entries[next];
        const s64 done = static_cast<s64>(new_state.sampling_number) << 1;

        // Mark the entry as being written, write it, mark it done, and only then publish it.
        entry.sampling_number = done | 1;
        std::atomic_thread_fence(std::memory_order_release);
        entry.state = new_state;
        std::atomic_thread_fence(std::memory_order_release);
        entry.sampling_number = done;
        std::atomic_thread_fence(std::memory_order_release);

        buffer_tail = static_cast<s64>(next);
        if (buffer_count < static_cast<s64>(max_buffer_size) - 1) {
            buffer_count++;
        }
    }
};

} // namespace Service::HID
