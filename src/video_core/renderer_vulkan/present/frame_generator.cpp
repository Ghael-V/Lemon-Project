// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cmath>

#include "common/logging/log.h"
#include "common/settings.h"
#include "video_core/frame_gen_stats.h"
#include "video_core/renderer_vulkan/present/frame_generator.h"
#include "video_core/renderer_vulkan/present/lemon_fg.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

// Lemon FG reads and writes rgba8; the presentation frames are blitted to and from it.
constexpr VkFormat FG_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

// Longer than this the previous frame is too old to blend with (a loading pause, a menu).
constexpr auto MAX_FRAME_TIME = std::chrono::milliseconds(100);

// frame_generation_quality -> how much smaller the motion search runs than the output.
constexpr std::array<u32, 3> SEARCH_REDUCTIONS{2, 4, 8};

constexpr VkImageSubresourceRange COLOR_RANGE{
    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
    .baseMipLevel = 0,
    .levelCount = 1,
    .baseArrayLayer = 0,
    .layerCount = 1,
};

constexpr VkImageSubresourceLayers COLOR_LAYERS{
    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
    .mipLevel = 0,
    .baseArrayLayer = 0,
    .layerCount = 1,
};

VkImageMemoryBarrier MakeBarrier(VkImage image, VkAccessFlags src, VkAccessFlags dst,
                                 VkImageLayout old_layout, VkImageLayout new_layout) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = src,
        .dstAccessMask = dst,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = COLOR_RANGE,
    };
}

void Blit(vk::CommandBuffer cmdbuf, VkImage src, VkImageLayout src_layout, VkImage dst,
          VkImageLayout dst_layout, VkExtent2D extent) {
    const VkOffset3D end{static_cast<s32>(extent.width), static_cast<s32>(extent.height), 1};
    const VkImageBlit region{
        .srcSubresource = COLOR_LAYERS,
        .srcOffsets = {VkOffset3D{0, 0, 0}, end},
        .dstSubresource = COLOR_LAYERS,
        .dstOffsets = {VkOffset3D{0, 0, 0}, end},
    };
    cmdbuf.BlitImage(src, src_layout, dst, dst_layout, region, VK_FILTER_NEAREST);
}

} // Anonymous namespace

FrameGenerator::FrameGenerator(const Device& device_, MemoryAllocator& memory_allocator_,
                               Scheduler& scheduler_)
    : device{device_}, memory_allocator{memory_allocator_}, scheduler{scheduler_},
      supported{LemonFgChain::IsSupported(device_, FG_FORMAT)} {
    if (!supported) {
        LOG_INFO(Render_Vulkan, "Frame generation is not supported on this GPU");
    }
}

FrameGenerator::~FrameGenerator() = default;

void FrameGenerator::Rebuild(VkExtent2D extent, u32 search_reduction) {
    // The chain and targets may still be in use by frames in flight.
    scheduler.Finish();
    device.GetLogical().WaitIdle();
    chain = std::make_unique<LemonFgChain>(device, memory_allocator, extent, FG_FORMAT,
                                           search_reduction);
    for (size_t i = 0; i < targets.size(); ++i) {
        targets[i] = FgImage(device, memory_allocator, extent, FG_FORMAT);
        chain->SetTarget(device, targets.size(), i, static_cast<u32>(i), targets[i].View());
    }
    chain_extent = extent;
    chain_reduction = search_reduction;
    frame_count = 0;
    LOG_INFO(Render_Vulkan, "Frame generation at {}x{}, motion at 1/{}", extent.width,
             extent.height, search_reduction);
}

size_t FrameGenerator::BeginFrame(VkExtent2D extent, size_t available) {
    const auto now = std::chrono::steady_clock::now();
    const auto frame_time = now - last_frame;
    last_frame = now;

    if (!supported || !Settings::values.frame_generation.GetValue() || extent.width == 0 ||
        extent.height == 0) {
        if (chain && !Settings::values.frame_generation.GetValue()) {
            // Turned off: free its memory.
            scheduler.Finish();
            device.GetLogical().WaitIdle();
            chain.reset();
            targets = {};
            chain_extent = {};
        }
        return 0;
    }
    const size_t quality = static_cast<size_t>(
        std::clamp(Settings::values.frame_generation_quality.GetValue(), 0, 2));
    const u32 reduction = SEARCH_REDUCTIONS[quality];
    if (!chain || chain_extent.width != extent.width || chain_extent.height != extent.height ||
        chain_reduction != reduction) {
        Rebuild(extent, reduction);
    }
    // The first frame after a rebuild has nothing to blend with.
    if (frame_count == 0 || frame_time > MAX_FRAME_TIME) {
        return 0;
    }

    size_t count;
    const int mode = Settings::values.frame_generation_mode.GetValue();
    if (mode >= 2) {
        // A fixed multiple of the game's rate.
        count = static_cast<size_t>(mode - 1);
    } else {
        // As many as bring the game to the target: a 30 fps game gets one for 60, a 20 fps
        // game two. Games already at the target get none.
        const double seconds = std::chrono::duration<double>(frame_time).count();
        const double target = static_cast<double>(Settings::values.frame_generation_target.GetValue());
        const long wanted = std::lround(target * seconds) - 1;
        count = static_cast<size_t>(std::max(0L, wanted));
    }
    return std::min({count, MAX_GENERATED, available});
}

void FrameGenerator::Record(VkImage source, std::span<const VkImage> destinations,
                            VkExtent2D extent) {
    if (!chain) {
        return;
    }
    LemonFgChain* const fg = chain.get();
    const u64 count = frame_count++;
    const size_t generated = std::min(destinations.size(), MAX_GENERATED);
    std::array<VkImage, MAX_GENERATED> outputs{};
    std::array<VkImage, MAX_GENERATED> target_images{};
    for (size_t i = 0; i < generated; ++i) {
        outputs[i] = destinations[i];
        target_images[i] = targets[i].Handle();
    }
    if (count > 0 && generated > 0) {
        VideoCore::CountGeneratedFrames(generated);
    }
    FgImage& input = fg->Input(count);
    const VkImage input_image = input.Handle();
    const VkImageLayout input_layout = input.Layout();
    input.SetLayout(VK_IMAGE_LAYOUT_GENERAL);

    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([=](vk::CommandBuffer cmdbuf) {
        // The drawn frame into the history: render pass writes before the copy, earlier reads
        // of the history slot before it is overwritten.
        const std::array before_copy{
            MakeBarrier(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
            MakeBarrier(input_image, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        input_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, {}, {}, before_copy);
        Blit(cmdbuf, source, VK_IMAGE_LAYOUT_GENERAL, input_image,
             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, extent);
        const std::array after_copy{
            MakeBarrier(input_image, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
            // The presentation copy of the source comes later and expects render pass writes.
            MakeBarrier(source, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL),
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT,
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                                   VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                               0, {}, {}, after_copy);

        if (count == 0) {
            return;
        }
        // Motion between the previous frame and this one, shared by every generated frame.
        fg->DispatchShared(cmdbuf, count);

        // Frame i of n at t = (i + 1) / (n + 1), each into its target, then into its
        // presentation frame.
        for (size_t i = 0; i < generated; ++i) {
            fg->DispatchGeneration(cmdbuf, count, generated, i, static_cast<u32>(i),
                                   target_images[i], extent);
            const std::array before_present_copy{
                MakeBarrier(outputs[i], 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
            };
            cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                   VK_PIPELINE_STAGE_TRANSFER_BIT, 0, {}, {}, before_present_copy);
            Blit(cmdbuf, target_images[i], VK_IMAGE_LAYOUT_GENERAL, outputs[i],
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, extent);
            const std::array after_present_copy{
                MakeBarrier(outputs[i], VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
            };
            cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                       VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   0, {}, {}, after_present_copy);
        }
    });
}

} // namespace Vulkan
