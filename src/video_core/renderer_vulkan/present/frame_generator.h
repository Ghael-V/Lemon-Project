// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <chrono>
#include <memory>
#include <span>

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
 * history, and up to three frames evenly spaced between the previous and the new one are
 * generated, to be presented before the new one. How many: as many as bring the game to the
 * target rate (adaptive), or a fixed multiple of its rate.
 */
class FrameGenerator {
public:
    FrameGenerator(const Device& device, MemoryAllocator& memory_allocator, Scheduler& scheduler);
    ~FrameGenerator();

    /// Most frames generated per game frame.
    static constexpr size_t MAX_GENERATED = FG_MAX_TARGETS - 1;

    /**
     * Called on the emulation's render thread for every frame drawn, before it is presented.
     * Returns how many frames to generate for it, at most [available] (then take that many
     * presentation frames and call Record with them).
     */
    [[nodiscard]] size_t BeginFrame(VkExtent2D extent, size_t available);

    /**
     * Records the copy of [source] (the frame just drawn, in GENERAL layout) into the history
     * and the generated frames into [destinations], in the order they are to be presented (left
     * in GENERAL, ready to be presented).
     */
    void Record(VkImage source, std::span<const VkImage> destinations, VkExtent2D extent);

private:
    void Rebuild(VkExtent2D extent, u32 search_reduction);

    const Device& device;
    MemoryAllocator& memory_allocator;
    Scheduler& scheduler;

    std::unique_ptr<LemonFgChain> chain;
    std::array<FgImage, MAX_GENERATED> targets;
    VkExtent2D chain_extent{};
    u32 chain_reduction{};
    bool supported{};

    /// Frames copied into the chain since it was built.
    u64 frame_count{};
    std::chrono::steady_clock::time_point last_frame{};
};

} // namespace Vulkan
