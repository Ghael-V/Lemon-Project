// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2014 The Android Open Source Project
// SPDX-License-Identifier: GPL-3.0-or-later
// Parts of this implementation were based on:
// https://cs.android.com/android/platform/superproject/+/android-5.1.1_r38:frameworks/native/libs/gui/BufferQueueCore.cpp

#include <algorithm>
#include <mutex>
#include <vector>

#include "common/assert.h"
#include "core/savestate.h"

#include "core/hle/service/nvnflinger/buffer_queue_core.h"
#include "core/hle/service/nvnflinger/ui/graphic_buffer.h"

namespace Service::android {

namespace {
// The live buffer queues, for a Quick Save and a Quick Load.
std::mutex g_queues_mutex;
std::vector<BufferQueueCore*> g_queues;
} // namespace

BufferQueueCore::BufferQueueCore() {
    std::scoped_lock lock{g_queues_mutex};
    g_queues.push_back(this);
}

BufferQueueCore::~BufferQueueCore() {
    std::scoped_lock lock{g_queues_mutex};
    std::erase(g_queues, this);
}

size_t BufferQueueCore::RewindFencesForRestore(u32 id, u32 delta) {
    size_t moved = 0;
    const auto rewind = [&](Fence& fence) {
        for (u32 f = 0; f < fence.num_fences && f < fence.fences.size(); f++) {
            if (fence.fences[f].id == static_cast<s32>(id)) {
                fence.fences[f].value -= delta;
                moved++;
            }
        }
    };
    std::scoped_lock lock{g_queues_mutex};
    for (BufferQueueCore* core : g_queues) {
        std::scoped_lock core_lock{core->mutex};
        for (auto& slot : core->slots) {
            rewind(slot.fence);
        }
        for (auto& item : core->queue) {
            rewind(item.fence);
        }
    }
    return moved;
}

std::vector<BufferQueueCore::ProducerSlots> BufferQueueCore::SnapshotProducerSlots() {
    std::vector<ProducerSlots> out;
    std::scoped_lock lock{g_queues_mutex};
    for (BufferQueueCore* core : g_queues) {
        std::scoped_lock core_lock{core->mutex};
        ProducerSlots entry{.core = core};
        for (s32 n = 0; n < BufferQueueDefs::NUM_BUFFER_SLOTS; n++) {
            entry.dequeued[n] = core->slots[n].buffer_state == BufferState::Dequeued;
            entry.requested[n] = core->slots[n].request_buffer_called;
        }
        out.push_back(entry);
    }
    return out;
}

void BufferQueueCore::RestoreProducerSlots(const std::vector<ProducerSlots>& saved) {
    std::scoped_lock lock{g_queues_mutex};
    for (const auto& entry : saved) {
        const auto it = std::ranges::find(g_queues, entry.core);
        if (it == g_queues.end()) {
            continue;
        }
        BufferQueueCore* core = *it;
        std::scoped_lock core_lock{core->mutex};
        bool freed_any = false;
        for (s32 n = 0; n < BufferQueueDefs::NUM_BUFFER_SLOTS; n++) {
            BufferSlot& slot = core->slots[n];
            const bool held_now = slot.buffer_state == BufferState::Dequeued;
            if (held_now && !entry.dequeued[n]) {
                // As CancelBuffer would have, had the game known it held it.
                slot.buffer_state = BufferState::Free;
                slot.frame_number = 0;
                freed_any = true;
                LOG_INFO(Service_Nvnflinger,
                         "Quick Load: slot {} freed (the game held it after the "
                         "save, not at it)",
                         n);
            } else if (!held_now && entry.dequeued[n]) {
                if (slot.buffer_state == BufferState::Free && slot.graphic_buffer != nullptr) {
                    slot.buffer_state = BufferState::Dequeued;
                    slot.request_buffer_called = entry.requested[n];
                    slot.fence = Fence::NoFence();
                    LOG_INFO(Service_Nvnflinger,
                             "Quick Load: slot {} given back to the game (it "
                             "held it at the save)",
                             n);
                } else if (slot.buffer_state == BufferState::Queued &&
                           slot.graphic_buffer != nullptr) {
                    // Waiting for the display. Take it out of the queue and give it back.
                    std::erase_if(core->queue,
                                  [n](const BufferItem& item) { return item.slot == n; });
                    slot.buffer_state = BufferState::Dequeued;
                    slot.request_buffer_called = entry.requested[n];
                    slot.fence = Fence::NoFence();
                    LOG_INFO(Service_Nvnflinger,
                             "Quick Load: slot {} taken out of the display "
                             "queue for the game (it held it at the save)",
                             n);
                } else if (slot.buffer_state == BufferState::Acquired &&
                           slot.graphic_buffer != nullptr) {
                    // On screen now. Take it back; the display's release of it is then stale,
                    // and its next acquire of this slot hands it the buffer again.
                    slot.buffer_state = BufferState::Dequeued;
                    slot.request_buffer_called = entry.requested[n];
                    slot.fence = Fence::NoFence();
                    slot.needs_cleanup_on_release = true;
                    slot.acquire_called = false;
                    LOG_INFO(Service_Nvnflinger,
                             "Quick Load: slot {} taken back from the display "
                             "for the game (it held it at the save)",
                             n);
                } else {
                    LOG_INFO(Service_Nvnflinger,
                             "Quick Load: slot {} held by the game at the save is {} now, left", n,
                             static_cast<u32>(slot.buffer_state));
                }
            }
        }
        if (freed_any) {
            core->SignalDequeueCondition();
        }
    }
}

}

void BufferQueueCore::SignalDequeueCondition() {
    dequeue_possible.store(true);
    dequeue_condition.notify_all();
}

bool BufferQueueCore::WaitForDequeueCondition(std::unique_lock<std::mutex>& lk) {
    // A Quick Load may run while this waits (see Core::SaveState::ServiceGateRelease).
    Core::SaveState::ServiceGateRelease gate;
    dequeue_condition.wait(lk, [&] { return dequeue_possible.load(); });
    dequeue_possible.store(false);

    return true;
}

s32 BufferQueueCore::GetMinUndequeuedBufferCountLocked(bool async) const {
    // If DequeueBuffer is allowed to error out, we don't have to add an extra buffer.
    if (!use_async_buffer) {
        return 0;
    }

    if (dequeue_buffer_cannot_block || async) {
        return max_acquired_buffer_count + 1;
    }

    return max_acquired_buffer_count;
}

s32 BufferQueueCore::GetMinMaxBufferCountLocked(bool async) const {
    return GetMinUndequeuedBufferCountLocked(async);
}

s32 BufferQueueCore::GetMaxBufferCountLocked(bool async) const {
    const auto min_buffer_count = GetMinMaxBufferCountLocked(async);
    auto max_buffer_count = std::max(default_max_buffer_count, min_buffer_count);

    if (override_max_buffer_count != 0) {
        ASSERT(override_max_buffer_count >= min_buffer_count);
        return override_max_buffer_count;
    }

    // Any buffers that are dequeued by the producer or sitting in the queue waiting to be consumed
    // need to have their slots preserved.
    for (s32 slot = max_buffer_count; slot < BufferQueueDefs::NUM_BUFFER_SLOTS; ++slot) {
        const auto state = slots[slot].buffer_state;
        if (state == BufferState::Queued || state == BufferState::Dequeued) {
            max_buffer_count = slot + 1;
        }
    }

    return max_buffer_count;
}

s32 BufferQueueCore::GetPreallocatedBufferCountLocked() const {
    return static_cast<s32>(std::count_if(slots.begin(), slots.end(),
                                          [](const auto& slot) { return slot.is_preallocated; }));
}

void BufferQueueCore::FreeBufferLocked(s32 slot) {
    LOG_DEBUG(Service_Nvnflinger, "slot {}", slot);

    slots[slot].graphic_buffer.reset();

    if (slots[slot].buffer_state == BufferState::Acquired) {
        slots[slot].needs_cleanup_on_release = true;
    }

    slots[slot].buffer_state = BufferState::Free;
    slots[slot].frame_number = UINT32_MAX;
    slots[slot].acquire_called = false;
    slots[slot].fence = Fence::NoFence();
}

void BufferQueueCore::FreeAllBuffersLocked() {
    buffer_has_been_queued = false;

    for (s32 slot = 0; slot < BufferQueueDefs::NUM_BUFFER_SLOTS; ++slot) {
        FreeBufferLocked(slot);
    }
}

bool BufferQueueCore::StillTracking(const BufferItem& item) const {
    const BufferSlot& slot = slots[item.slot];

    return (slot.graphic_buffer != nullptr) && (item.graphic_buffer == slot.graphic_buffer);
}

void BufferQueueCore::WaitWhileAllocatingLocked() const {
    while (is_allocating) {
        is_allocating_condition.wait(mutex);
    }
}

} // namespace Service::android
