// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <chrono>
#include <memory>

#include "common/common_types.h"
#include "video_core/renderer_vulkan/present/fg_common.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;
class LemonFgChain;
class MemoryAllocator;
class Scheduler;

/**
 * Frame generation for presentation: each frame the game presents is copied into Lemon FG's
 * history, and when the game runs well below the display (30 fps and the like) one frame halfway
 * between the previous and the new one is generated, to be presented before the new one.
 */
class FrameGenerator {
public:
    FrameGenerator(const Device& device, MemoryAllocator& memory_allocator, Scheduler& scheduler);
    ~FrameGenerator();

    /**
     * Called on the emulation's render thread for every frame drawn, before it is presented.
     * Returns whether a frame should be generated for it (then GetRenderFrame a second frame and
     * call Record with both).
     */
    [[nodiscard]] bool BeginFrame(VkExtent2D extent);

    /**
     * Records the copy of [source] (the frame just drawn, in GENERAL layout) into the history
     * and, when [destination] is not null, the generated frame into it (left in GENERAL, ready
     * to be presented).
     */
    void Record(VkImage source, VkImage destination, VkExtent2D extent);

private:
    void Rebuild(VkExtent2D extent);

    const Device& device;
    MemoryAllocator& memory_allocator;
    Scheduler& scheduler;

    std::unique_ptr<LemonFgChain> chain;
    FgImage target;
    VkExtent2D chain_extent{};
    bool supported{};

    /// Frames copied into the chain since it was built.
    u64 frame_count{};
    std::chrono::steady_clock::time_point last_frame{};
};

} // namespace Vulkan
