// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <span>

#include "common/common_types.h"
#include "video_core/renderer_vulkan/present/fg_common.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;

/// Lemon's own frame generation, used when there is no Lossless Scaling to borrow shaders from.
///
/// For every presented frame: its luma at a quarter of the resolution, as a 3-level pyramid;
/// block motion from the previous frame (A) to this one (C), coarse to fine, each block trying
/// its neighbours' and the previous pair's vectors (3DRS); a mask of what stays still on screen
/// (HUD). For every generated frame at time t: that motion projected to t, and A and C sampled
/// along it and mixed, still content kept in place.
class LemonFgChain {
public:
    static constexpr size_t LEVELS = 3;

    LemonFgChain(const Device& device, MemoryAllocator& memory_allocator, VkExtent2D extent,
                 VkFormat format, u32 search_reduction);

    LemonFgChain(const LemonFgChain&) = delete;
    LemonFgChain& operator=(const LemonFgChain&) = delete;

    /// Whether this chain can run on the device and write frames of the given format.
    [[nodiscard]] static bool IsSupported(const Device& device, VkFormat format);

    void DispatchShared(vk::CommandBuffer cmdbuf, u64 frame_count);

    void DispatchGeneration(vk::CommandBuffer cmdbuf, u64 frame_count, size_t generation_count,
                            size_t generation, u32 target, VkImage image, VkExtent2D extent);

    void SetTarget(const Device& device, size_t generation_count, size_t generation, u32 target,
                   VkImageView view);

    [[nodiscard]] FgImage& Input(u64 frame_count) {
        return frames[frame_count % frames.size()];
    }

private:
    struct Pass {
        vk::ShaderModule module;
        vk::DescriptorSetLayout set_layout;
        vk::PipelineLayout pipeline_layout;
        vk::Pipeline pipeline;
        u32 push_size{};
    };

    /// One recorded step of the motion search: a descriptor set and its push constants.
    struct SearchStep {
        VkDescriptorSet set{};
        std::array<u32, 11> push{};
        VkExtent2D grid{};
    };

    /// Coarsest level: exhaustive + 2 passes; middle levels: 2 passes; finest: 1 pass.
    static constexpr size_t SEARCH_STEPS = 3 + 2 * (LEVELS - 2) + 1;
    static constexpr size_t STILL_PASSES = 3;
    /// still_masks entry the last still pass writes.
    static constexpr size_t STILL_FINAL = (STILL_PASSES - 1) % 2;

    Pass CreatePass(const Device& device, std::span<const u32> code,
                    std::initializer_list<VkDescriptorType> bindings, u32 push_size);
    VkDescriptorSet AllocateSet(const Pass& pass);
    void BuildPyramid(vk::CommandBuffer cmdbuf, size_t slot);
    void Run(vk::CommandBuffer cmdbuf, const Pass& pass, VkDescriptorSet set, const void* push,
             u32 groups_x, u32 groups_y);
    void InitializeImages(vk::CommandBuffer cmdbuf);

    VkExtent2D extent;
    VkExtent2D still_size{};
    std::array<VkExtent2D, LEVELS> level_size{};
    std::array<VkExtent2D, LEVELS> grid{};

    vk::DescriptorPool descriptor_pool;
    vk::Sampler sampler;
    std::vector<vk::DescriptorSets> owned_sets;

    Pass luma_pass;
    Pass search_pass;
    Pass median_pass;
    Pass project_pass;
    Pass still_pass;
    Pass generate_pass;

    std::array<FgImage, 2> frames;
    std::array<std::array<FgImage, LEVELS>, 2> pyramid;
    /// Ping-pong search fields per level.
    std::array<std::array<FgImage, 2>, LEVELS> fields;
    /// Final, median-filtered flow of the pair ending at each frame slot. Also the temporal
    /// candidates of the next pair.
    std::array<FgImage, 2> flows;
    FgImage offsets;
    FgImage offsets_filtered;
    /// Ping-pong still masks; the last pass leaves the final one in still_masks[STILL_FINAL].
    std::array<FgImage, 2> still_masks;

    std::array<VkDescriptorSet, 2> luma_sets{};
    std::array<std::array<VkDescriptorSet, LEVELS - 1>, 2> down_sets{};
    /// [slot][step]
    std::array<std::array<SearchStep, SEARCH_STEPS>, 2> search_steps{};
    std::array<VkDescriptorSet, 2> flow_median_sets{};
    /// [slot][pass]
    std::array<std::array<VkDescriptorSet, STILL_PASSES>, 2> still_sets{};
    std::array<VkDescriptorSet, 2> project_sets{};
    VkDescriptorSet offsets_median_set{};
    std::array<std::array<VkDescriptorSet, FG_MAX_TARGETS>, 2> generate_sets{};
    std::array<VkImageView, FG_MAX_TARGETS> target_views{};

    const Device* device{};
    u64 last_pyramid{~0ULL};
    u64 last_flow{~0ULL};
    bool initialized{};
};

} // namespace Vulkan
