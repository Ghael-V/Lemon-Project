// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#include <bit>
#include <vector>

#include "video_core/host_shaders/lemon_fg_generate_comp_spv.h"
#include "video_core/host_shaders/lemon_fg_luma_comp_spv.h"
#include "video_core/host_shaders/lemon_fg_median_comp_spv.h"
#include "video_core/host_shaders/lemon_fg_project_comp_spv.h"
#include "video_core/host_shaders/lemon_fg_search_comp_spv.h"
#include "video_core/host_shaders/lemon_fg_still_comp_spv.h"
#include "video_core/renderer_vulkan/present/lemon_fg.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

constexpr VkFormat WORK_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr u32 BLOCK = 8;

// Penalties of the motion search, in SAD units (0-255 scale) per pixel of vector length, per
// block row: chosen on captured gameplay (scratchpad harness), see lemon_fg_search.comp.
constexpr f32 ZERO_BIAS = 0.2f;
constexpr f32 SMOOTH_BIAS = 1.0f;

enum SearchMode : u32 {
    MODE_EXHAUSTIVE = 0,
    MODE_FROM_COARSER = 1,
    MODE_REFINE = 2,
};

[[nodiscard]] u32 DivCeil(u32 value, u32 divisor) {
    return (value + divisor - 1) / divisor;
}

[[nodiscard]] VkExtent2D GridOf(VkExtent2D size) {
    return {DivCeil(size.width, BLOCK), DivCeil(size.height, BLOCK)};
}

[[nodiscard]] u32 Bits(f32 value) {
    return std::bit_cast<u32>(value);
}

} // Anonymous namespace

LemonFgChain::LemonFgChain(const Device& device_, MemoryAllocator& memory_allocator,
                           VkExtent2D extent_, VkFormat format, u32 search_reduction)
    : extent{extent_}, device{&device_} {
    // Motion is searched on a smaller copy: 2 (half) to 8 (an eighth of the output).
    level_size[0] = {std::max(1u, DivCeil(extent.width, search_reduction)),
                     std::max(1u, DivCeil(extent.height, search_reduction))};
    for (size_t level = 1; level < LEVELS; ++level) {
        level_size[level] = {std::max(1u, DivCeil(level_size[level - 1].width, 2)),
                             std::max(1u, DivCeil(level_size[level - 1].height, 2))};
    }
    for (size_t level = 0; level < LEVELS; ++level) {
        grid[level] = GridOf(level_size[level]);
    }

    still_size = {std::max(1u, DivCeil(extent.width, 2)), std::max(1u, DivCeil(extent.height, 2))};

    descriptor_pool = CreateWrappedDescriptorPool(
        *device, 1024, 256,
        {VK_DESCRIPTOR_TYPE_SAMPLER, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
         VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
    sampler = CreateFgSampler(*device, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

    constexpr auto SAMPLER = VK_DESCRIPTOR_TYPE_SAMPLER;
    constexpr auto SAMPLED = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    constexpr auto STORAGE = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    luma_pass = CreatePass(*device, LEMON_FG_LUMA_COMP_SPV, {SAMPLER, SAMPLED, STORAGE},
                           sizeof(u32) * 5);
    search_pass = CreatePass(*device, LEMON_FG_SEARCH_COMP_SPV,
                             {SAMPLER, SAMPLED, SAMPLED, SAMPLED, SAMPLED, STORAGE},
                             sizeof(u32) * 11);
    median_pass = CreatePass(*device, LEMON_FG_MEDIAN_COMP_SPV, {SAMPLER, SAMPLED, STORAGE},
                             sizeof(u32) * 2);
    project_pass = CreatePass(*device, LEMON_FG_PROJECT_COMP_SPV, {SAMPLER, SAMPLED, STORAGE},
                              sizeof(u32) * 3);
    still_pass = CreatePass(*device, LEMON_FG_STILL_COMP_SPV, {SAMPLER, SAMPLED, SAMPLED, STORAGE},
                            sizeof(u32) * 5);
    generate_pass =
        CreatePass(*device, LEMON_FG_GENERATE_COMP_SPV,
                   {SAMPLER, SAMPLED, SAMPLED, SAMPLED, SAMPLED, STORAGE}, sizeof(u32) * 9);

    for (auto& frame : frames) {
        frame = FgImage(*device, memory_allocator, extent, format);
    }
    for (auto& slot : pyramid) {
        for (size_t level = 0; level < LEVELS; ++level) {
            slot[level] = FgImage(*device, memory_allocator, level_size[level], WORK_FORMAT);
        }
    }
    for (size_t level = 0; level < LEVELS; ++level) {
        for (auto& image : fields[level]) {
            image = FgImage(*device, memory_allocator, grid[level], WORK_FORMAT);
        }
    }
    for (auto& image : flows) {
        image = FgImage(*device, memory_allocator, grid[0], WORK_FORMAT);
    }
    offsets = FgImage(*device, memory_allocator, grid[0], WORK_FORMAT);
    offsets_filtered = FgImage(*device, memory_allocator, grid[0], WORK_FORMAT);
    for (auto& image : still_masks) {
        image = FgImage(*device, memory_allocator, still_size, WORK_FORMAT);
    }

    for (size_t slot = 0; slot < 2; ++slot) {
        luma_sets[slot] = AllocateSet(luma_pass);
        FgDescriptorWriter(luma_sets[slot])
            .AddSampler(*sampler)
            .AddSampledImage(frames[slot])
            .AddStorageImage(pyramid[slot][0])
            .Build(*device);
        for (size_t level = 1; level < LEVELS; ++level) {
            VkDescriptorSet& set = down_sets[slot][level - 1];
            set = AllocateSet(luma_pass);
            FgDescriptorWriter(set)
                .AddSampler(*sampler)
                .AddSampledImage(pyramid[slot][level - 1])
                .AddStorageImage(pyramid[slot][level])
                .Build(*device);
        }

        // The pair ending at this slot: A is the other slot's frame, C this one's.
        const auto& luma_a = pyramid[1 - slot];
        const auto& luma_c = pyramid[slot];
        auto& steps = search_steps[slot];
        size_t next = 0;
        const auto add_step = [&](size_t level, u32 mode, const FgImage& in, VkExtent2D in_grid,
                                  const FgImage& out) {
            SearchStep& step = steps[next++];
            step.set = AllocateSet(search_pass);
            step.grid = grid[level];
            FgDescriptorWriter(step.set)
                .AddSampler(*sampler)
                .AddSampledImage(luma_a[level])
                .AddSampledImage(luma_c[level])
                .AddSampledImage(in)
                .AddSampledImage(flows[1 - slot])
                .AddStorageImage(out)
                .Build(*device);
            step.push = {
                level_size[level].width,
                level_size[level].height,
                grid[level].width,
                grid[level].height,
                in_grid.width,
                in_grid.height,
                mode,
                0, // use_temporal, set when recording
                Bits(1.0f / static_cast<f32>(1u << level)),
                Bits(ZERO_BIAS),
                Bits(SMOOTH_BIAS),
            };
        };
        // Coarsest level: exhaustive, then two passes; middle levels: two passes; the finest:
        // one (a second one gained little on captured gameplay).
        auto& coarsest = fields[LEVELS - 1];
        add_step(LEVELS - 1, MODE_EXHAUSTIVE, coarsest[1], grid[LEVELS - 1], coarsest[0]);
        add_step(LEVELS - 1, MODE_REFINE, coarsest[0], grid[LEVELS - 1], coarsest[1]);
        add_step(LEVELS - 1, MODE_REFINE, coarsest[1], grid[LEVELS - 1], coarsest[0]);
        const FgImage* result = &coarsest[0];
        for (size_t level = LEVELS - 1; level-- > 0;) {
            auto& current = fields[level];
            add_step(level, MODE_FROM_COARSER, *result, grid[level + 1], current[0]);
            result = &current[0];
            if (level > 0) {
                add_step(level, MODE_REFINE, current[0], grid[level], current[1]);
                result = &current[1];
            }
        }

        flow_median_sets[slot] = AllocateSet(median_pass);
        FgDescriptorWriter(flow_median_sets[slot])
            .AddSampler(*sampler)
            .AddSampledImage(*result)
            .AddStorageImage(flows[slot])
            .Build(*device);

        // Still mask: raw from the frames, then majority and grow, ping-ponging between the
        // two images.
        for (size_t pass = 0; pass < STILL_PASSES; ++pass) {
            VkDescriptorSet& set = still_sets[slot][pass];
            set = AllocateSet(still_pass);
            const FgImage& in_a =
                pass == 0 ? frames[1 - slot] : still_masks[pass % 2 == 1 ? 0 : 1];
            const FgImage& in_c = pass == 0 ? frames[slot] : in_a;
            FgDescriptorWriter(set)
                .AddSampler(*sampler)
                .AddSampledImage(in_a)
                .AddSampledImage(in_c)
                .AddStorageImage(still_masks[pass % 2 == 0 ? 0 : 1])
                .Build(*device);
        }

        project_sets[slot] = AllocateSet(project_pass);
        FgDescriptorWriter(project_sets[slot])
            .AddSampler(*sampler)
            .AddSampledImage(flows[slot])
            .AddStorageImage(offsets)
            .Build(*device);
        for (auto& set : generate_sets[slot]) {
            set = AllocateSet(generate_pass);
        }
    }
    offsets_median_set = AllocateSet(median_pass);
    FgDescriptorWriter(offsets_median_set)
        .AddSampler(*sampler)
        .AddSampledImage(offsets)
        .AddStorageImage(offsets_filtered)
        .Build(*device);
}

bool LemonFgChain::IsSupported(const Device& device_, VkFormat format) {
    // The search sums costs with subgroup adds; the generate shader writes through an rgba8
    // storage image.
    // (Vulkan 1.1 requires subgroup operations in compute shaders.)
    return device_.IsSubgroupFeatureSupported(VK_SUBGROUP_FEATURE_BASIC_BIT) &&
           device_.IsSubgroupFeatureSupported(VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) &&
           format == VK_FORMAT_R8G8B8A8_UNORM;
}

LemonFgChain::Pass LemonFgChain::CreatePass(const Device& device_, std::span<const u32> code,
                                            std::initializer_list<VkDescriptorType> bindings,
                                            u32 push_size) {
    Pass pass;
    pass.push_size = push_size;
    pass.module = CreateWrappedShaderModule(device_, code);
    const std::vector<VkDescriptorType> types(bindings);
    pass.set_layout = CreateFgDescriptorSetLayout(
        device_, std::span<const VkDescriptorType>{types}, VK_SHADER_STAGE_COMPUTE_BIT);
    const VkDescriptorSetLayout set_layout = *pass.set_layout;
    const VkPushConstantRange range{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = push_size,
    };
    pass.pipeline_layout = device_.GetLogical().CreatePipelineLayout(VkPipelineLayoutCreateInfo{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = &set_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &range,
    });
    pass.pipeline = CreateFgComputePipeline(device_, pass.pipeline_layout, pass.module);
    return pass;
}

VkDescriptorSet LemonFgChain::AllocateSet(const Pass& pass) {
    VkDescriptorSetLayout layout = *pass.set_layout;
    owned_sets.push_back(CreateWrappedDescriptorSets(descriptor_pool, layout));
    return owned_sets.back()[0];
}

void LemonFgChain::Run(vk::CommandBuffer cmdbuf, const Pass& pass, VkDescriptorSet set,
                       const void* push, u32 groups_x, u32 groups_y) {
    cmdbuf.BindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE, *pass.pipeline);
    cmdbuf.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pass.pipeline_layout, 0, set, {});
    cmdbuf.PushConstants(*pass.pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pass.push_size,
                         push);
    cmdbuf.Dispatch(groups_x, groups_y, 1);
    static constexpr VkMemoryBarrier WRITE_TO_READ{
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, WRITE_TO_READ);
}

void LemonFgChain::InitializeImages(vk::CommandBuffer cmdbuf) {
    FgBarriers barriers(cmdbuf);
    for (auto& slot : pyramid) {
        barriers.ReadToWriteAll(slot);
    }
    for (auto& level : fields) {
        barriers.ReadToWriteAll(level);
    }
    barriers.ReadToWriteAll(flows)
        .ReadToWriteAll(still_masks)
        .ReadToWrite(offsets)
        .ReadToWrite(offsets_filtered)
        .Build();
    initialized = true;
}

void LemonFgChain::BuildPyramid(vk::CommandBuffer cmdbuf, size_t slot) {
    const std::array<u32, 5> luma_push{extent.width, extent.height, level_size[0].width,
                                       level_size[0].height, 0};
    Run(cmdbuf, luma_pass, luma_sets[slot], luma_push.data(), DivCeil(level_size[0].width, 8),
        DivCeil(level_size[0].height, 8));
    for (size_t level = 1; level < LEVELS; ++level) {
        const std::array<u32, 5> down_push{level_size[level - 1].width,
                                           level_size[level - 1].height, level_size[level].width,
                                           level_size[level].height, 1};
        Run(cmdbuf, luma_pass, down_sets[slot][level - 1], down_push.data(),
            DivCeil(level_size[level].width, 8), DivCeil(level_size[level].height, 8));
    }
}

void LemonFgChain::DispatchShared(vk::CommandBuffer cmdbuf, u64 frame_count) {
    if (!initialized) {
        InitializeImages(cmdbuf);
    }
    const size_t slot = frame_count % 2;
    if (last_pyramid + 1 != frame_count) {
        // The previous frame was only copied, never analysed (first pair after a rebuild).
        BuildPyramid(cmdbuf, 1 - slot);
    }
    BuildPyramid(cmdbuf, slot);
    last_pyramid = frame_count;

    const u32 use_temporal = last_flow + 1 == frame_count ? 1 : 0;
    for (SearchStep& step : search_steps[slot]) {
        step.push[7] = use_temporal;
        // One workgroup per block.
        Run(cmdbuf, search_pass, step.set, step.push.data(), step.grid.width, step.grid.height);
    }
    const std::array<u32, 2> median_push{grid[0].width, grid[0].height};
    Run(cmdbuf, median_pass, flow_median_sets[slot], median_push.data(),
        DivCeil(grid[0].width, 8), DivCeil(grid[0].height, 8));
    last_flow = frame_count;

    for (u32 pass = 0; pass < STILL_PASSES; ++pass) {
        const std::array<u32, 5> still_push{still_size.width, still_size.height, extent.width,
                                            extent.height, pass};
        Run(cmdbuf, still_pass, still_sets[slot][pass], still_push.data(),
            DivCeil(still_size.width, 8), DivCeil(still_size.height, 8));
    }
}

void LemonFgChain::SetTarget(const Device& device_, size_t, size_t, u32 target,
                             VkImageView view) {
    if (target_views[target] == view) {
        return;
    }
    target_views[target] = view;
    for (size_t slot = 0; slot < 2; ++slot) {
        FgDescriptorWriter(generate_sets[slot][target])
            .AddSampler(*sampler)
            .AddSampledImage(frames[1 - slot])
            .AddSampledImage(frames[slot])
            .AddSampledImage(offsets_filtered)
            .AddSampledImage(still_masks[STILL_FINAL])
            .AddStorageView(view)
            .Build(device_);
    }
}

void LemonFgChain::DispatchGeneration(vk::CommandBuffer cmdbuf, u64 frame_count,
                                      size_t generation_count, size_t generation, u32 target,
                                      VkImage image, VkExtent2D target_extent) {
    const size_t slot = frame_count % 2;
    const f32 t = FgTimestamp(generation, generation_count);

    const std::array<u32, 3> project_push{grid[0].width, grid[0].height, Bits(t)};
    Run(cmdbuf, project_pass, project_sets[slot], project_push.data(), DivCeil(grid[0].width, 8),
        DivCeil(grid[0].height, 8));
    const std::array<u32, 2> median_push{grid[0].width, grid[0].height};
    Run(cmdbuf, median_pass, offsets_median_set, median_push.data(), DivCeil(grid[0].width, 8),
        DivCeil(grid[0].height, 8));

    FgBarriers(cmdbuf).DiscardToWrite(image).Build();
    const std::array<u32, 9> generate_push{
        target_extent.width, target_extent.height, level_size[0].width, level_size[0].height,
        grid[0].width,       grid[0].height,       still_size.width,    still_size.height,
        Bits(t)};
    Run(cmdbuf, generate_pass, generate_sets[slot][target], generate_push.data(),
        DivCeil(target_extent.width, 16), DivCeil(target_extent.height, 8));

    const VkImageMemoryBarrier after{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_TRANSFER_BIT,
                           0, after);
}

} // namespace Vulkan
