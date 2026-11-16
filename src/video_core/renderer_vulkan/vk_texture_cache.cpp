// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <atomic>
#include <algorithm>
#include <array>
#include <span>
#include <tuple>
#include <vector>
#include <boost/container/small_vector.hpp>

#include "common/bit_cast.h"
#include "common/bit_util.h"
#include "common/settings.h"

#include "video_core/renderer_vulkan/vk_texture_cache.h"

#include "video_core/engines/fermi_2d.h"
#include "video_core/renderer_vulkan/blit_image.h"
#include "video_core/renderer_vulkan/maxwell_to_vk.h"
#include "video_core/renderer_vulkan/vk_compute_pass.h"
#include "video_core/renderer_vulkan/vk_render_pass_cache.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_staging_buffer_pool.h"
#include "video_core/texture_cache/formatter.h"
#include "video_core/texture_cache/samples_helper.h"
#include "video_core/texture_cache/util.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

using Tegra::Engines::Fermi2D;
using Tegra::Texture::SwizzleSource;
using Tegra::Texture::TextureMipmapFilter;
using VideoCommon::BufferImageCopy;
using VideoCommon::ImageFlagBits;
using VideoCommon::ImageInfo;
using VideoCommon::ImageType;
using VideoCommon::SubresourceRange;
using VideoCore::Surface::BytesPerBlock;
using VideoCore::Surface::IsPixelFormatASTC;
using VideoCore::Surface::IsPixelFormatInteger;
using VideoCore::Surface::SurfaceType;

namespace {
constexpr VkBorderColor ConvertBorderColor(const std::array<float, 4>& color) {
    if (color == std::array<float, 4>{0, 0, 0, 0}) {
        return VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    } else if (color == std::array<float, 4>{0, 0, 0, 1}) {
        return VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    } else if (color == std::array<float, 4>{1, 1, 1, 1}) {
        return VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
    }
    if (color[0] + color[1] + color[2] > 1.35f) {
        // If color elements are brighter than roughly 0.5 average, use white border
        return VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
    } else if (color[3] > 0.5f) {
        return VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    } else {
        return VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    }
}

[[nodiscard]] VkImageType ConvertImageType(const ImageType type) {
    switch (type) {
    case ImageType::e1D:
        return VK_IMAGE_TYPE_1D;
    case ImageType::e2D:
    case ImageType::Linear:
        return VK_IMAGE_TYPE_2D;
    case ImageType::e3D:
        return VK_IMAGE_TYPE_3D;
    case ImageType::Buffer:
        break;
    }
    ASSERT_MSG(false, "Invalid image type={}", type);
    return {};
}

[[nodiscard]] VkSampleCountFlagBits ConvertSampleCount(u32 num_samples) {
    switch (num_samples) {
    case 1:
        return VK_SAMPLE_COUNT_1_BIT;
    case 2:
        return VK_SAMPLE_COUNT_2_BIT;
    case 4:
        return VK_SAMPLE_COUNT_4_BIT;
    case 8:
        return VK_SAMPLE_COUNT_8_BIT;
    case 16:
        return VK_SAMPLE_COUNT_16_BIT;
    default:
        ASSERT_MSG(false, "Invalid number of samples={}", num_samples);
        return VK_SAMPLE_COUNT_1_BIT;
    }
}

[[nodiscard]] VkImageUsageFlags ImageUsageFlags(const MaxwellToVK::FormatInfo& info,
                                                PixelFormat format) {
    VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                              VK_IMAGE_USAGE_SAMPLED_BIT;
    if (info.attachable) {
        switch (VideoCore::Surface::GetFormatType(format)) {
        case VideoCore::Surface::SurfaceType::ColorTexture:
            usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            break;
        case VideoCore::Surface::SurfaceType::Depth:
        case VideoCore::Surface::SurfaceType::Stencil:
        case VideoCore::Surface::SurfaceType::DepthStencil:
            usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            break;
        default:
            ASSERT_MSG(false, "Invalid surface type");
            break;
        }
    }
    if (info.storage) {
        usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    }
    return usage;
}

[[nodiscard]] VkImageCreateInfo MakeImageCreateInfo(const Device& device, const ImageInfo& info) {
    const auto format_info =
        MaxwellToVK::SurfaceFormat(device, FormatType::Optimal, false, info.format);
    VkImageCreateFlags flags{};
    if (info.type == ImageType::e2D && info.resources.layers >= 6 &&
        info.size.width == info.size.height && !device.HasBrokenCubeImageCompatibility()) {
        flags |= VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
    }
    if (info.type == ImageType::e3D) {
        flags |= VK_IMAGE_CREATE_2D_ARRAY_COMPATIBLE_BIT;
    }
    const auto [samples_x, samples_y] = VideoCommon::SamplesLog2(info.num_samples);
    return VkImageCreateInfo{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = flags,
        .imageType = ConvertImageType(info.type),
        .format = format_info.format,
        .extent{
            .width = info.size.width >> samples_x,
            .height = info.size.height >> samples_y,
            .depth = info.size.depth,
        },
        .mipLevels = static_cast<u32>(info.resources.levels),
        .arrayLayers = static_cast<u32>(info.resources.layers),
        .samples = ConvertSampleCount(info.num_samples),
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = ImageUsageFlags(format_info, info.format),
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
}

[[nodiscard]] vk::Image MakeImage(const Device& device, const MemoryAllocator& allocator,
                                  const ImageInfo& info, std::span<const VkFormat> view_formats) {
    if (info.type == ImageType::Buffer) {
        return vk::Image{};
    }
    VkImageCreateInfo image_ci = MakeImageCreateInfo(device, info);
    const VkImageFormatListCreateInfo image_format_list = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO,
        .pNext = nullptr,
        .viewFormatCount = static_cast<u32>(view_formats.size()),
        .pViewFormats = view_formats.data(),
    };
    if (view_formats.size() > 1) {
        image_ci.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        if (device.IsKhrImageFormatListSupported()) {
            image_ci.pNext = &image_format_list;
        }
    }
    return allocator.CreateImage(image_ci);
}

[[nodiscard]] vk::ImageView MakeStorageView(const vk::Device& device, u32 level, VkImage image,
                                            VkFormat format) {
    static constexpr VkImageViewUsageCreateInfo storage_image_view_usage_create_info{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_USAGE_CREATE_INFO,
        .pNext = nullptr,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT,
    };
    return device.CreateImageView(VkImageViewCreateInfo{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = &storage_image_view_usage_create_info,
        .flags = 0,
        .image = image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY,
        .format = format,
        .components{
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = level,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        },
    });
}

[[nodiscard]] VkImageAspectFlags ImageAspectMask(PixelFormat format) {
    switch (VideoCore::Surface::GetFormatType(format)) {
    case VideoCore::Surface::SurfaceType::ColorTexture:
        return VK_IMAGE_ASPECT_COLOR_BIT;
    case VideoCore::Surface::SurfaceType::Depth:
        return VK_IMAGE_ASPECT_DEPTH_BIT;
    case VideoCore::Surface::SurfaceType::Stencil:
        return VK_IMAGE_ASPECT_STENCIL_BIT;
    case VideoCore::Surface::SurfaceType::DepthStencil:
        return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
    default:
        ASSERT_MSG(false, "Invalid surface type");
        return VkImageAspectFlags{};
    }
}

[[nodiscard]] VkImageAspectFlags ImageViewAspectMask(const VideoCommon::ImageViewInfo& info) {
    if (info.IsRenderTarget()) {
        return ImageAspectMask(info.format);
    }
    bool any_r =
        std::ranges::any_of(info.Swizzle(), [](SwizzleSource s) { return s == SwizzleSource::R; });
    switch (info.format) {
    case PixelFormat::D24_UNORM_S8_UINT:
    case PixelFormat::D32_FLOAT_S8_UINT:
        // R = depth, G = stencil
        return any_r ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_STENCIL_BIT;
    case PixelFormat::S8_UINT_D24_UNORM:
        // R = stencil, G = depth
        return any_r ? VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
    case PixelFormat::D16_UNORM:
    case PixelFormat::D32_FLOAT:
    case PixelFormat::X8_D24_UNORM:
        return VK_IMAGE_ASPECT_DEPTH_BIT;
    case PixelFormat::S8_UINT:
        return VK_IMAGE_ASPECT_STENCIL_BIT;
    default:
        return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}

[[nodiscard]] VkComponentSwizzle ComponentSwizzle(SwizzleSource swizzle) {
    switch (swizzle) {
    case SwizzleSource::Zero:
        return VK_COMPONENT_SWIZZLE_ZERO;
    case SwizzleSource::R:
        return VK_COMPONENT_SWIZZLE_R;
    case SwizzleSource::G:
        return VK_COMPONENT_SWIZZLE_G;
    case SwizzleSource::B:
        return VK_COMPONENT_SWIZZLE_B;
    case SwizzleSource::A:
        return VK_COMPONENT_SWIZZLE_A;
    case SwizzleSource::OneFloat:
    case SwizzleSource::OneInt:
        return VK_COMPONENT_SWIZZLE_ONE;
    }
    ASSERT_MSG(false, "Invalid swizzle={}", swizzle);
    return VK_COMPONENT_SWIZZLE_ZERO;
}

[[nodiscard]] VkImageViewType ImageViewType(Shader::TextureType type) {
    switch (type) {
    case Shader::TextureType::Color1D:
        return VK_IMAGE_VIEW_TYPE_1D;
    case Shader::TextureType::Color2D:
    case Shader::TextureType::Color2DRect:
        return VK_IMAGE_VIEW_TYPE_2D;
    case Shader::TextureType::ColorCube:
        return VK_IMAGE_VIEW_TYPE_CUBE;
    case Shader::TextureType::Color3D:
        return VK_IMAGE_VIEW_TYPE_3D;
    case Shader::TextureType::ColorArray1D:
        return VK_IMAGE_VIEW_TYPE_1D_ARRAY;
    case Shader::TextureType::ColorArray2D:
        return VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    case Shader::TextureType::ColorArrayCube:
        return VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
    case Shader::TextureType::Buffer:
        ASSERT_MSG(false, "Texture buffers can't be image views");
        return VK_IMAGE_VIEW_TYPE_1D;
    }
    ASSERT_MSG(false, "Invalid image view type={}", type);
    return VK_IMAGE_VIEW_TYPE_2D;
}

[[nodiscard]] VkImageViewType ImageViewType(VideoCommon::ImageViewType type) {
    switch (type) {
    case VideoCommon::ImageViewType::e1D:
        return VK_IMAGE_VIEW_TYPE_1D;
    case VideoCommon::ImageViewType::e2D:
    case VideoCommon::ImageViewType::Rect:
        return VK_IMAGE_VIEW_TYPE_2D;
    case VideoCommon::ImageViewType::Cube:
        return VK_IMAGE_VIEW_TYPE_CUBE;
    case VideoCommon::ImageViewType::e3D:
        return VK_IMAGE_VIEW_TYPE_3D;
    case VideoCommon::ImageViewType::e1DArray:
        return VK_IMAGE_VIEW_TYPE_1D_ARRAY;
    case VideoCommon::ImageViewType::e2DArray:
        return VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    case VideoCommon::ImageViewType::CubeArray:
        return VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
    case VideoCommon::ImageViewType::Buffer:
        ASSERT_MSG(false, "Texture buffers can't be image views");
        return VK_IMAGE_VIEW_TYPE_1D;
    }
    ASSERT_MSG(false, "Invalid image view type={}", type);
    return VK_IMAGE_VIEW_TYPE_2D;
}

[[nodiscard]] VkImageSubresourceLayers MakeImageSubresourceLayers(
    VideoCommon::SubresourceLayers subresource, VkImageAspectFlags aspect_mask) {
    return VkImageSubresourceLayers{
        .aspectMask = aspect_mask,
        .mipLevel = static_cast<u32>(subresource.base_level),
        .baseArrayLayer = static_cast<u32>(subresource.base_layer),
        .layerCount = static_cast<u32>(subresource.num_layers),
    };
}

[[nodiscard]] VkOffset3D MakeOffset3D(VideoCommon::Offset3D offset3d) {
    return VkOffset3D{
        .x = offset3d.x,
        .y = offset3d.y,
        .z = offset3d.z,
    };
}

[[nodiscard]] VkExtent3D MakeExtent3D(VideoCommon::Extent3D extent3d) {
    return VkExtent3D{
        .width = static_cast<u32>(extent3d.width),
        .height = static_cast<u32>(extent3d.height),
        .depth = static_cast<u32>(extent3d.depth),
    };
}

[[nodiscard]] VkImageCopy MakeImageCopy(const VideoCommon::ImageCopy& copy,
                                        VkImageAspectFlags aspect_mask) noexcept {
    return VkImageCopy{
        .srcSubresource = MakeImageSubresourceLayers(copy.src_subresource, aspect_mask),
        .srcOffset = MakeOffset3D(copy.src_offset),
        .dstSubresource = MakeImageSubresourceLayers(copy.dst_subresource, aspect_mask),
        .dstOffset = MakeOffset3D(copy.dst_offset),
        .extent = MakeExtent3D(copy.extent),
    };
}

[[nodiscard]] VkBufferImageCopy MakeBufferImageCopy(const VideoCommon::ImageCopy& copy, bool is_src,
                                                    VkImageAspectFlags aspect_mask) noexcept {
    return VkBufferImageCopy{
        .bufferOffset = 0,
        .bufferRowLength = 0,
        .bufferImageHeight = 0,
        .imageSubresource = MakeImageSubresourceLayers(
            is_src ? copy.src_subresource : copy.dst_subresource, aspect_mask),
        .imageOffset = MakeOffset3D(is_src ? copy.src_offset : copy.dst_offset),
        .imageExtent = MakeExtent3D(copy.extent),
    };
}

[[maybe_unused]] [[nodiscard]] boost::container::small_vector<VkBufferCopy, 16>
TransformBufferCopies(std::span<const VideoCommon::BufferCopy> copies, size_t buffer_offset) {
    boost::container::small_vector<VkBufferCopy, 16> result(copies.size());
    std::ranges::transform(
        copies, result.begin(), [buffer_offset](const VideoCommon::BufferCopy& copy) {
            return VkBufferCopy{
                .srcOffset = static_cast<VkDeviceSize>(copy.src_offset + buffer_offset),
                .dstOffset = static_cast<VkDeviceSize>(copy.dst_offset),
                .size = static_cast<VkDeviceSize>(copy.size),
            };
        });
    return result;
}

[[nodiscard]] boost::container::small_vector<VkBufferImageCopy, 16> TransformBufferImageCopies(
    std::span<const BufferImageCopy> copies, size_t buffer_offset, VkImageAspectFlags aspect_mask) {
    struct Maker {
        VkBufferImageCopy operator()(const BufferImageCopy& copy) const {
            return VkBufferImageCopy{
                .bufferOffset = copy.buffer_offset + buffer_offset,
                .bufferRowLength = copy.buffer_row_length,
                .bufferImageHeight = copy.buffer_image_height,
                .imageSubresource =
                    {
                        .aspectMask = aspect_mask,
                        .mipLevel = static_cast<u32>(copy.image_subresource.base_level),
                        .baseArrayLayer = static_cast<u32>(copy.image_subresource.base_layer),
                        .layerCount = static_cast<u32>(copy.image_subresource.num_layers),
                    },
                .imageOffset =
                    {
                        .x = copy.image_offset.x,
                        .y = copy.image_offset.y,
                        .z = copy.image_offset.z,
                    },
                .imageExtent =
                    {
                        .width = copy.image_extent.width,
                        .height = copy.image_extent.height,
                        .depth = copy.image_extent.depth,
                    },
            };
        }
        size_t buffer_offset;
        VkImageAspectFlags aspect_mask;
    };
    if (aspect_mask == (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)) {
        boost::container::small_vector<VkBufferImageCopy, 16> result(copies.size() * 2);
        std::ranges::transform(copies, result.begin(),
                               Maker{buffer_offset, VK_IMAGE_ASPECT_DEPTH_BIT});
        std::ranges::transform(copies, result.begin() + copies.size(),
                               Maker{buffer_offset, VK_IMAGE_ASPECT_STENCIL_BIT});
        return result;
    } else {
        boost::container::small_vector<VkBufferImageCopy, 16> result(copies.size());
        std::ranges::transform(copies, result.begin(), Maker{buffer_offset, aspect_mask});
        return result;
    }
}

[[nodiscard]] VkImageSubresourceRange MakeSubresourceRange(VkImageAspectFlags aspect_mask,
                                                           const SubresourceRange& range) {
    return VkImageSubresourceRange{
        .aspectMask = aspect_mask,
        .baseMipLevel = static_cast<u32>(range.base.level),
        .levelCount = static_cast<u32>(range.extent.levels),
        .baseArrayLayer = static_cast<u32>(range.base.layer),
        .layerCount = static_cast<u32>(range.extent.layers),
    };
}

[[nodiscard]] VkImageSubresourceRange MakeSubresourceRange(const ImageView* image_view) {
    SubresourceRange range = image_view->range;
    if (True(image_view->flags & VideoCommon::ImageViewFlagBits::Slice)) {
        // Slice image views always affect a single layer, but their subresource range corresponds
        // to the slice. Override the value to affect a single layer.
        range.base.layer = 0;
        range.extent.layers = 1;
    }
    return MakeSubresourceRange(ImageAspectMask(image_view->format), range);
}

[[nodiscard]] VkImageSubresourceLayers MakeSubresourceLayers(const ImageView* image_view) {
    return VkImageSubresourceLayers{
        .aspectMask = ImageAspectMask(image_view->format),
        .mipLevel = static_cast<u32>(image_view->range.base.level),
        .baseArrayLayer = static_cast<u32>(image_view->range.base.layer),
        .layerCount = static_cast<u32>(image_view->range.extent.layers),
    };
}

[[nodiscard]] SwizzleSource ConvertGreenRed(SwizzleSource value) {
    switch (value) {
    case SwizzleSource::G:
        return SwizzleSource::R;
    default:
        return value;
    }
}

[[nodiscard]] SwizzleSource SwapBlueRed(SwizzleSource value) {
    switch (value) {
    case SwizzleSource::R:
        return SwizzleSource::B;
    case SwizzleSource::B:
        return SwizzleSource::R;
    default:
        return value;
    }
}

[[nodiscard]] SwizzleSource SwapGreenRed(SwizzleSource value) {
    switch (value) {
    case SwizzleSource::R:
        return SwizzleSource::G;
    case SwizzleSource::G:
        return SwizzleSource::R;
    default:
        return value;
    }
}

[[nodiscard]] SwizzleSource SwapSpecial(SwizzleSource value) {
    switch (value) {
    case SwizzleSource::A:
        return SwizzleSource::R;
    case SwizzleSource::R:
        return SwizzleSource::A;
    case SwizzleSource::G:
        return SwizzleSource::B;
    case SwizzleSource::B:
        return SwizzleSource::G;
    default:
        return value;
    }
}

void CopyBufferToImage(vk::CommandBuffer cmdbuf, VkBuffer src_buffer, VkImage image,
                       VkImageAspectFlags aspect_mask, bool is_initialized,
                       std::span<const VkBufferImageCopy> copies) {
    static constexpr VkAccessFlags WRITE_ACCESS_FLAGS =
        VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    static constexpr VkAccessFlags READ_ACCESS_FLAGS = VK_ACCESS_SHADER_READ_BIT |
                                                       VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                                                       VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
    const VkImageMemoryBarrier read_barrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = WRITE_ACCESS_FLAGS,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = is_initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = aspect_mask,
            .baseMipLevel = 0,
            .levelCount = VK_REMAINING_MIP_LEVELS,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        },
    };
    const VkImageMemoryBarrier write_barrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = WRITE_ACCESS_FLAGS | READ_ACCESS_FLAGS,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = aspect_mask,
            .baseMipLevel = 0,
            .levelCount = VK_REMAINING_MIP_LEVELS,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        },
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                           read_barrier);
    cmdbuf.CopyBufferToImage(src_buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);
    // TODO: Move this to another API
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                           write_barrier);
}

[[nodiscard]] VkImageBlit MakeImageBlit(const Region2D& dst_region, const Region2D& src_region,
                                        const VkImageSubresourceLayers& dst_layers,
                                        const VkImageSubresourceLayers& src_layers) {
    return VkImageBlit{
        .srcSubresource = src_layers,
        .srcOffsets =
            {
                {
                    .x = src_region.start.x,
                    .y = src_region.start.y,
                    .z = 0,
                },
                {
                    .x = src_region.end.x,
                    .y = src_region.end.y,
                    .z = 1,
                },
            },
        .dstSubresource = dst_layers,
        .dstOffsets =
            {
                {
                    .x = dst_region.start.x,
                    .y = dst_region.start.y,
                    .z = 0,
                },
                {
                    .x = dst_region.end.x,
                    .y = dst_region.end.y,
                    .z = 1,
                },
            },
    };
}

[[nodiscard]] VkImageResolve MakeImageResolve(const Region2D& dst_region,
                                              const Region2D& src_region,
                                              const VkImageSubresourceLayers& dst_layers,
                                              const VkImageSubresourceLayers& src_layers) {
    return VkImageResolve{
        .srcSubresource = src_layers,
        .srcOffset =
            {
                .x = src_region.start.x,
                .y = src_region.start.y,
                .z = 0,
            },
        .dstSubresource = dst_layers,
        .dstOffset =
            {
                .x = dst_region.start.x,
                .y = dst_region.start.y,
                .z = 0,
            },
        .extent =
            {
                .width = static_cast<u32>(dst_region.end.x - dst_region.start.x),
                .height = static_cast<u32>(dst_region.end.y - dst_region.start.y),
                .depth = 1,
            },
    };
}

void TryTransformSwizzleIfNeeded(PixelFormat format, std::array<SwizzleSource, 4>& swizzle,
                                 bool emulate_bgr565, bool emulate_a4b4g4r4) {
    switch (format) {
    case PixelFormat::A1B5G5R5_UNORM:
        std::ranges::transform(swizzle, swizzle.begin(), SwapBlueRed);
        break;
    case PixelFormat::B5G6R5_UNORM:
        if (emulate_bgr565) {
            std::ranges::transform(swizzle, swizzle.begin(), SwapBlueRed);
        }
        break;
    case PixelFormat::A5B5G5R1_UNORM:
        std::ranges::transform(swizzle, swizzle.begin(), SwapSpecial);
        break;
    case PixelFormat::G4R4_UNORM:
        std::ranges::transform(swizzle, swizzle.begin(), SwapGreenRed);
        break;
    case PixelFormat::A4B4G4R4_UNORM:
        if (emulate_a4b4g4r4) {
            std::ranges::reverse(swizzle);
        }
        break;
    default:
        break;
    }
}

struct RangedBarrierRange {
    u32 min_mip = std::numeric_limits<u32>::max();
    u32 max_mip = std::numeric_limits<u32>::min();
    u32 min_layer = std::numeric_limits<u32>::max();
    u32 max_layer = std::numeric_limits<u32>::min();

    void AddLayers(const VkImageSubresourceLayers& layers) {
        min_mip = std::min(min_mip, layers.mipLevel);
        max_mip = std::max(max_mip, layers.mipLevel + 1);
        min_layer = std::min(min_layer, layers.baseArrayLayer);
        max_layer = std::max(max_layer, layers.baseArrayLayer + layers.layerCount);
    }

    VkImageSubresourceRange SubresourceRange(VkImageAspectFlags aspect_mask) const noexcept {
        return VkImageSubresourceRange{
            .aspectMask = aspect_mask,
            .baseMipLevel = min_mip,
            .levelCount = max_mip - min_mip,
            .baseArrayLayer = min_layer,
            .layerCount = max_layer - min_layer,
        };
    }
};

[[nodiscard]] VkFormat Format(Shader::ImageFormat format) {
    switch (format) {
    case Shader::ImageFormat::Typeless:
        break;
    case Shader::ImageFormat::R8_SINT:
        return VK_FORMAT_R8_SINT;
    case Shader::ImageFormat::R8_UINT:
        return VK_FORMAT_R8_UINT;
    case Shader::ImageFormat::R16_UINT:
        return VK_FORMAT_R16_UINT;
    case Shader::ImageFormat::R16_SINT:
        return VK_FORMAT_R16_SINT;
    case Shader::ImageFormat::R32_UINT:
        return VK_FORMAT_R32_UINT;
    case Shader::ImageFormat::R32G32_UINT:
        return VK_FORMAT_R32G32_UINT;
    case Shader::ImageFormat::R32G32B32A32_UINT:
        return VK_FORMAT_R32G32B32A32_UINT;
    }
    ASSERT_MSG(false, "Invalid image format={}", format);
    return VK_FORMAT_R32_UINT;
}

void BlitScale(Scheduler& scheduler, VkImage src_image, VkImage dst_image, const ImageInfo& info,
               VkImageAspectFlags aspect_mask, const Settings::ResolutionScalingInfo& resolution,
               bool up_scaling = true) {
    const bool is_2d = info.type == ImageType::e2D;
    const auto resources = info.resources;
    const VkExtent2D extent{
        .width = info.size.width,
        .height = info.size.height,
    };
    // Depth and integer formats must use NEAREST filter for blits.
    const bool is_color{aspect_mask == VK_IMAGE_ASPECT_COLOR_BIT};
    const bool is_bilinear{is_color && !IsPixelFormatInteger(info.format)};
    const VkFilter vk_filter = is_bilinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;

    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([dst_image, src_image, extent, resources, aspect_mask, resolution, is_2d,
                      vk_filter, up_scaling](vk::CommandBuffer cmdbuf) {
        const VkOffset2D src_size{
            .x = static_cast<s32>(up_scaling ? extent.width : resolution.ScaleUp(extent.width)),
            .y = static_cast<s32>(is_2d && up_scaling ? extent.height
                                                      : resolution.ScaleUp(extent.height)),
        };
        const VkOffset2D dst_size{
            .x = static_cast<s32>(up_scaling ? resolution.ScaleUp(extent.width) : extent.width),
            .y = static_cast<s32>(is_2d && up_scaling ? resolution.ScaleUp(extent.height)
                                                      : extent.height),
        };
        boost::container::small_vector<VkImageBlit, 4> regions;
        regions.reserve(resources.levels);
        for (s32 level = 0; level < resources.levels; level++) {
            regions.push_back({
                .srcSubresource{
                    .aspectMask = aspect_mask,
                    .mipLevel = static_cast<u32>(level),
                    .baseArrayLayer = 0,
                    .layerCount = static_cast<u32>(resources.layers),
                },
                .srcOffsets{
                    {
                        .x = 0,
                        .y = 0,
                        .z = 0,
                    },
                    {
                        .x = std::max(1, src_size.x >> level),
                        .y = std::max(1, src_size.y >> level),
                        .z = 1,
                    },
                },
                .dstSubresource{
                    .aspectMask = aspect_mask,
                    .mipLevel = static_cast<u32>(level),
                    .baseArrayLayer = 0,
                    .layerCount = static_cast<u32>(resources.layers),
                },
                .dstOffsets{
                    {
                        .x = 0,
                        .y = 0,
                        .z = 0,
                    },
                    {
                        .x = std::max(1, dst_size.x >> level),
                        .y = std::max(1, dst_size.y >> level),
                        .z = 1,
                    },
                },
            });
        }
        const VkImageSubresourceRange subresource_range{
            .aspectMask = aspect_mask,
            .baseMipLevel = 0,
            .levelCount = VK_REMAINING_MIP_LEVELS,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        };
        const std::array read_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = subresource_range,
            },
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, // Discard contents
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = subresource_range,
            },
        };
        const std::array write_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = subresource_range,
            },
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = subresource_range,
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, nullptr, nullptr, read_barriers);
        cmdbuf.BlitImage(src_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst_image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions, vk_filter);
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, nullptr, nullptr, write_barriers);
    });
}
// Orders the transfers and compute dispatches that read and write raw BCn blocks.
void RawBcnBarrier(vk::CommandBuffer cmdbuf) {
    static constexpr VkMemoryBarrier BARRIER{
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT |
                         VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, BARRIER);
}

// Size of an image level in its own blocks: 4x4 texels for BCn, texels otherwise. A raw copy
// between a BCn image and an uncompressed one maps one block to one texel.
[[nodiscard]] VideoCommon::Extent2D RawBlockGrid(const VideoCommon::ImageInfo& info, s32 level) {
    const VideoCommon::Extent3D size = VideoCommon::MipSize(info.size, static_cast<u32>(level));
    if (VideoCore::Surface::IsPixelFormatBCn(info.format)) {
        return {Common::DivCeil(size.width, 4U), Common::DivCeil(size.height, 4U)};
    }
    return {size.width, size.height};
}

// BCn formats whose blocks decode the same way.
[[nodiscard]] PixelFormat RawBcnFamily(PixelFormat format) {
    switch (format) {
    case PixelFormat::BC1_RGBA_SRGB:
        return PixelFormat::BC1_RGBA_UNORM;
    case PixelFormat::BC2_SRGB:
        return PixelFormat::BC2_UNORM;
    case PixelFormat::BC3_SRGB:
        return PixelFormat::BC3_UNORM;
    case PixelFormat::BC7_SRGB:
        return PixelFormat::BC7_UNORM;
    default:
        return format;
    }
}

} // Anonymous namespace

TextureCacheRuntime::TextureCacheRuntime(const Device& device_, Scheduler& scheduler_,
                                         MemoryAllocator& memory_allocator_,
                                         StagingBufferPool& staging_buffer_pool_,
                                         BlitImageHelper& blit_image_helper_,
                                         RenderPassCache& render_pass_cache_,
                                         DescriptorPool& descriptor_pool,
                                         ComputePassDescriptorQueue& compute_pass_descriptor_queue)
    : device{device_}, scheduler{scheduler_}, memory_allocator{memory_allocator_},
      staging_buffer_pool{staging_buffer_pool_}, blit_image_helper{blit_image_helper_},
      render_pass_cache{render_pass_cache_}, resolution{Settings::values.resolution_info} {
    VideoCore::Surface::SetBcnAstcRecompression(
        !device.IsOptimalBcnSupported() && device.IsOptimalAstcSupported() &&
        Settings::values.bcn_astc_recompression.GetValue());
    if (VideoCore::Surface::IsBcnRecompressedToAstc(PixelFormat::BC1_RGBA_UNORM)) {
        bcn_astc_pass.emplace(device, scheduler, descriptor_pool, staging_buffer_pool,
                              compute_pass_descriptor_queue, memory_allocator);
    }
    LOG_INFO(Render_Vulkan, "BCn to ASTC recompression: {} (native BCn: {}, ASTC: {}, setting: {})",
             bcn_astc_pass ? "on" : "off", device.IsOptimalBcnSupported(),
             device.IsOptimalAstcSupported(), Settings::values.bcn_astc_recompression.GetValue());
    // BC4 / BC5 (masks, normal maps) go to EAC instead: same channels, half the memory of R8 / RG8.
    VideoCore::Surface::SetBcnEacRecompression(
        !device.IsOptimalBcnSupported() && device.IsOptimalEtc2Supported() &&
        Settings::values.bcn_astc_recompression.GetValue());
    if (VideoCore::Surface::IsBcnRecompressedToEac(PixelFormat::BC4_UNORM)) {
        bcn_eac_pass.emplace(device, scheduler, descriptor_pool, staging_buffer_pool,
                             compute_pass_descriptor_queue);
    }
    if (!device.IsOptimalBcnSupported()) {
        bcn_decode_pass.emplace(device, scheduler, descriptor_pool, staging_buffer_pool,
                                compute_pass_descriptor_queue);
    }
    LOG_INFO(Render_Vulkan, "BC4/BC5 to EAC recompression: {} (EAC: {})",
             bcn_eac_pass ? "on" : "off", device.IsOptimalEtc2Supported());
    if (Settings::values.accelerate_astc.GetValue() == Settings::AstcDecodeMode::Gpu) {
        astc_decoder_pass.emplace(device, scheduler, descriptor_pool, staging_buffer_pool,
                                  compute_pass_descriptor_queue, memory_allocator);
    }
    if (device.IsStorageImageMultisampleSupported()) {
        msaa_copy_pass = std::make_unique<MSAACopyPass>(
            device, scheduler, descriptor_pool, staging_buffer_pool, compute_pass_descriptor_queue);
    }
    if (!device.IsKhrImageFormatListSupported()) {
        return;
    }
    for (size_t index_a = 0; index_a < VideoCore::Surface::MaxPixelFormat; index_a++) {
        const auto image_format = static_cast<PixelFormat>(index_a);
        if (IsPixelFormatASTC(image_format) && !device.IsOptimalAstcSupported()) {
            view_formats[index_a].push_back(VK_FORMAT_A8B8G8R8_UNORM_PACK32);
        }
        for (size_t index_b = 0; index_b < VideoCore::Surface::MaxPixelFormat; index_b++) {
            const auto view_format = static_cast<PixelFormat>(index_b);
            if (VideoCore::Surface::IsViewCompatible(image_format, view_format, false, true)) {
                const auto view_info =
                    MaxwellToVK::SurfaceFormat(device, FormatType::Optimal, true, view_format);
                // A BCn image stored as ASTC can only be viewed as the other ASTC 4x4 format.
                if (VideoCore::Surface::IsBcnRecompressedToAstc(image_format) &&
                    view_info.format != VK_FORMAT_ASTC_4x4_UNORM_BLOCK &&
                    view_info.format != VK_FORMAT_ASTC_4x4_SRGB_BLOCK) {
                    continue;
                }
                // Likewise, a BC4 / BC5 image stored as EAC only has its own EAC format.
                if (VideoCore::Surface::IsBcnRecompressedToEac(image_format) &&
                    view_info.format !=
                        MaxwellToVK::SurfaceFormat(device, FormatType::Optimal, true, image_format)
                            .format) {
                    continue;
                }
                view_formats[index_a].push_back(view_info.format);
            }
        }
    }
}

void TextureCacheRuntime::Finish() {
    scheduler.Finish();
}

StagingBufferRef TextureCacheRuntime::UploadStagingBuffer(size_t size) {
    return staging_buffer_pool.Request(size, MemoryUsage::Upload);
}

StagingBufferRef TextureCacheRuntime::DownloadStagingBuffer(size_t size, bool deferred) {
    return staging_buffer_pool.Request(size, MemoryUsage::Download, deferred);
}

void TextureCacheRuntime::FreeDeferredStagingBuffer(StagingBufferRef& ref) {
    staging_buffer_pool.FreeDeferred(ref);
}

bool TextureCacheRuntime::ShouldReinterpret(Image& dst, Image& src) {
    if (VideoCore::Surface::GetFormatType(dst.info.format) ==
            VideoCore::Surface::SurfaceType::DepthStencil &&
        !device.IsExtShaderStencilExportSupported()) {
        return true;
    }
    if (dst.info.format == PixelFormat::D32_FLOAT_S8_UINT ||
        src.info.format == PixelFormat::D32_FLOAT_S8_UINT) {
        return true;
    }
    return false;
}

VkBuffer TextureCacheRuntime::GetTemporaryBuffer(size_t needed_size) {
    const auto level = (8 * sizeof(size_t)) - std::countl_zero(needed_size - 1ULL);
    if (buffers[level]) {
        return *buffers[level];
    }
    const auto new_size = Common::NextPow2(needed_size);
    static constexpr VkBufferUsageFlags flags =
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;
    const VkBufferCreateInfo temp_ci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .size = new_size,
        .usage = flags,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
    };
    buffers[level] = memory_allocator.CreateBuffer(temp_ci, MemoryUsage::DeviceLocal);
    return *buffers[level];
}

void TextureCacheRuntime::BarrierFeedbackLoop() {
    scheduler.RequestOutsideRenderPassOperationContext();
}

void TextureCacheRuntime::ReinterpretImage(Image& dst, Image& src,
                                           std::span<const VideoCommon::ImageCopy> copies) {
    boost::container::small_vector<VkBufferImageCopy, 16> vk_in_copies(copies.size());
    boost::container::small_vector<VkBufferImageCopy, 16> vk_out_copies(copies.size());
    const VkImageAspectFlags src_aspect_mask = src.AspectMask();
    const VkImageAspectFlags dst_aspect_mask = dst.AspectMask();

    const auto bpp_in = BytesPerBlock(src.info.format) / DefaultBlockWidth(src.info.format);
    const auto bpp_out = BytesPerBlock(dst.info.format) / DefaultBlockWidth(dst.info.format);
    // The destination texels' bytes, as source texels. Rounded up: a destination narrower than one
    // source texel (4 bytes out of a 16-byte texel) was a zero-width copy that moved nothing.
    std::ranges::transform(copies, vk_in_copies.begin(), [&src, src_aspect_mask, bpp_in,
                                                          bpp_out](const auto& copy) {
        auto copy2 = copy;
        copy2.src_offset.x = (bpp_out * copy.src_offset.x) / bpp_in;
        const s32 level_width = std::max<s32>(
            1, static_cast<s32>(src.info.size.width) >> copy.src_subresource.base_level);
        const s32 width = static_cast<s32>(Common::DivCeil(bpp_out * copy.extent.width, bpp_in));
        copy2.extent.width = static_cast<u32>(
            std::clamp(width, 1, std::max(1, level_width - copy2.src_offset.x)));
        return MakeBufferImageCopy(copy2, true, src_aspect_mask);
    });
    std::ranges::transform(copies, vk_out_copies.begin(), [dst_aspect_mask](const auto& copy) {
        return MakeBufferImageCopy(copy, false, dst_aspect_mask);
    });
    const u32 img_bpp = BytesPerBlock(dst.info.format);
    size_t total_size = 0;
    for (const auto& copy : copies) {
        total_size += copy.extent.width * copy.extent.height * copy.extent.depth * img_bpp;
    }
    // The source side can be a little larger once rounded up to whole source texels.
    size_t in_size = 0;
    const u32 src_bpp = BytesPerBlock(src.info.format);
    for (const VkBufferImageCopy& copy : vk_in_copies) {
        in_size += static_cast<size_t>(copy.imageExtent.width) * copy.imageExtent.height *
                   copy.imageExtent.depth * src_bpp;
    }
    total_size = std::max(total_size, in_size);
    const VkBuffer copy_buffer = GetTemporaryBuffer(total_size);
    const VkImage dst_image = dst.Handle();
    const VkImage src_image = src.Handle();
    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([dst_image, src_image, copy_buffer, src_aspect_mask, dst_aspect_mask,
                      vk_in_copies, vk_out_copies](vk::CommandBuffer cmdbuf) {
        RangedBarrierRange dst_range;
        RangedBarrierRange src_range;
        for (const VkBufferImageCopy& copy : vk_in_copies) {
            src_range.AddLayers(copy.imageSubresource);
        }
        for (const VkBufferImageCopy& copy : vk_out_copies) {
            dst_range.AddLayers(copy.imageSubresource);
        }
        static constexpr VkMemoryBarrier READ_BARRIER{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
        };
        static constexpr VkMemoryBarrier WRITE_BARRIER{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        };
        const std::array pre_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = src_range.SubresourceRange(src_aspect_mask),
            },
        };
        const std::array middle_in_barrier{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = 0,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = src_range.SubresourceRange(src_aspect_mask),
            },
        };
        const std::array middle_out_barrier{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = dst_range.SubresourceRange(dst_aspect_mask),
            },
        };
        const std::array post_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = dst_range.SubresourceRange(dst_aspect_mask),
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, {}, {}, pre_barriers);

        cmdbuf.CopyImageToBuffer(src_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, copy_buffer,
                                 vk_in_copies);
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, WRITE_BARRIER, nullptr, middle_in_barrier);

        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, READ_BARRIER, {}, middle_out_barrier);
        cmdbuf.CopyBufferToImage(copy_buffer, dst_image, VK_IMAGE_LAYOUT_GENERAL, vk_out_copies);
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, {}, {}, post_barriers);
    });
}

void TextureCacheRuntime::BlitImage(Framebuffer* dst_framebuffer, ImageView& dst, ImageView& src,
                                    const Region2D& dst_region, const Region2D& src_region,
                                    Tegra::Engines::Fermi2D::Filter filter,
                                    Tegra::Engines::Fermi2D::Operation operation) {
    const VkImageAspectFlags aspect_mask = ImageAspectMask(src.format);
    const bool is_dst_msaa = dst.Samples() != VK_SAMPLE_COUNT_1_BIT;
    const bool is_src_msaa = src.Samples() != VK_SAMPLE_COUNT_1_BIT;
    if (aspect_mask != ImageAspectMask(dst.format)) {
        UNIMPLEMENTED_MSG("Incompatible blit from format {} to {}", src.format, dst.format);
        return;
    }
    if (aspect_mask == VK_IMAGE_ASPECT_COLOR_BIT && !is_src_msaa && !is_dst_msaa) {
        blit_image_helper.BlitColor(dst_framebuffer, src.Handle(Shader::TextureType::Color2D),
                                    dst_region, src_region, filter, operation);
        return;
    }
    ASSERT(src.format == dst.format);
    if (aspect_mask == (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)) {
        const auto format = src.format;
        const auto can_blit_depth_stencil = [this, format] {
            switch (format) {
            case VideoCore::Surface::PixelFormat::D24_UNORM_S8_UINT:
            case VideoCore::Surface::PixelFormat::S8_UINT_D24_UNORM:
                return device.IsBlitDepth24Stencil8Supported();
            case VideoCore::Surface::PixelFormat::D32_FLOAT_S8_UINT:
                return device.IsBlitDepth32Stencil8Supported();
            default:
                UNREACHABLE();
            }
        }();
        if (!can_blit_depth_stencil) {
            UNIMPLEMENTED_IF(is_src_msaa || is_dst_msaa);
            blit_image_helper.BlitDepthStencil(dst_framebuffer, src.DepthView(), src.StencilView(),
                                               dst_region, src_region, filter, operation);
            return;
        }
    }
    ASSERT(!(is_dst_msaa && !is_src_msaa));
    ASSERT(operation == Fermi2D::Operation::SrcCopy);

    const VkImage dst_image = dst.ImageHandle();
    const VkImage src_image = src.ImageHandle();
    const VkImageSubresourceLayers dst_layers = MakeSubresourceLayers(&dst);
    const VkImageSubresourceLayers src_layers = MakeSubresourceLayers(&src);
    const bool is_resolve = is_src_msaa && !is_dst_msaa;
    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([filter, dst_region, src_region, dst_image, src_image, dst_layers, src_layers,
                      aspect_mask, is_resolve](vk::CommandBuffer cmdbuf) {
        const std::array read_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange{
                    .aspectMask = aspect_mask,
                    .baseMipLevel = 0,
                    .levelCount = VK_REMAINING_MIP_LEVELS,
                    .baseArrayLayer = 0,
                    .layerCount = VK_REMAINING_ARRAY_LAYERS,
                },
            },
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange{
                    .aspectMask = aspect_mask,
                    .baseMipLevel = 0,
                    .levelCount = VK_REMAINING_MIP_LEVELS,
                    .baseArrayLayer = 0,
                    .layerCount = VK_REMAINING_ARRAY_LAYERS,
                },
            },
        };
        VkImageMemoryBarrier write_barrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                             VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                             VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                             VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = dst_image,
            .subresourceRange{
                .aspectMask = aspect_mask,
                .baseMipLevel = 0,
                .levelCount = VK_REMAINING_MIP_LEVELS,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, nullptr, nullptr, read_barriers);
        if (is_resolve) {
            cmdbuf.ResolveImage(src_image, VK_IMAGE_LAYOUT_GENERAL, dst_image,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                MakeImageResolve(dst_region, src_region, dst_layers, src_layers));
        } else {
            const bool is_linear = filter == Fermi2D::Filter::Bilinear;
            const VkFilter vk_filter = is_linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            cmdbuf.BlitImage(
                src_image, VK_IMAGE_LAYOUT_GENERAL, dst_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                MakeImageBlit(dst_region, src_region, dst_layers, src_layers), vk_filter);
        }
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, write_barrier);
    });
}

void TextureCacheRuntime::ConvertImage(Framebuffer* dst, ImageView& dst_view, ImageView& src_view) {
    switch (dst_view.format) {
    case PixelFormat::R16_UNORM:
        if (src_view.format == PixelFormat::D16_UNORM) {
            return blit_image_helper.ConvertD16ToR16(dst, src_view);
        }
        break;
    case PixelFormat::A8B8G8R8_SRGB:
        if (src_view.format == PixelFormat::D32_FLOAT) {
            return blit_image_helper.ConvertD32FToABGR8(dst, src_view);
        }
        break;
    case PixelFormat::A8B8G8R8_UNORM:
        if (src_view.format == PixelFormat::S8_UINT_D24_UNORM) {
            return blit_image_helper.ConvertD24S8ToABGR8(dst, src_view);
        }
        if (src_view.format == PixelFormat::D24_UNORM_S8_UINT) {
            return blit_image_helper.ConvertS8D24ToABGR8(dst, src_view);
        }
        if (src_view.format == PixelFormat::D32_FLOAT) {
            return blit_image_helper.ConvertD32FToABGR8(dst, src_view);
        }
        break;
    case PixelFormat::B8G8R8A8_SRGB:
        if (src_view.format == PixelFormat::D32_FLOAT) {
            return blit_image_helper.ConvertD32FToABGR8(dst, src_view);
        }
        break;
    case PixelFormat::B8G8R8A8_UNORM:
        if (src_view.format == PixelFormat::D32_FLOAT) {
            return blit_image_helper.ConvertD32FToABGR8(dst, src_view);
        }
        break;
    case PixelFormat::R32_FLOAT:
        if (src_view.format == PixelFormat::D32_FLOAT) {
            return blit_image_helper.ConvertD32ToR32(dst, src_view);
        }
        break;
    case PixelFormat::D16_UNORM:
        if (src_view.format == PixelFormat::R16_UNORM) {
            return blit_image_helper.ConvertR16ToD16(dst, src_view);
        }
        break;
    case PixelFormat::S8_UINT_D24_UNORM:
        if (src_view.format == PixelFormat::A8B8G8R8_UNORM ||
            src_view.format == PixelFormat::B8G8R8A8_UNORM) {
            return blit_image_helper.ConvertABGR8ToD24S8(dst, src_view);
        }
        break;
    case PixelFormat::D32_FLOAT:
        if (src_view.format == PixelFormat::A8B8G8R8_UNORM ||
            src_view.format == PixelFormat::B8G8R8A8_UNORM ||
            src_view.format == PixelFormat::A8B8G8R8_SRGB ||
            src_view.format == PixelFormat::B8G8R8A8_SRGB) {
            return blit_image_helper.ConvertABGR8ToD32F(dst, src_view);
        }
        if (src_view.format == PixelFormat::R32_FLOAT) {
            return blit_image_helper.ConvertR32ToD32(dst, src_view);
        }
        break;
    default:
        break;
    }
    UNIMPLEMENTED_MSG("Unimplemented format copy from {} to {}", src_view.format, dst_view.format);
}

bool TextureCacheRuntime::IsBcnStoredConverted(const Image& image) const {
    return bcn_decode_pass && image.info.type == ImageType::e2D && image.info.num_samples == 1 &&
           BcnDecodePass::IsSupported(image.info.format);
}

bool TextureCacheRuntime::IsRawBcnCopy(const Image& dst, const Image& src) const {
    const bool dst_converted = IsBcnStoredConverted(dst);
    const bool src_converted = IsBcnStoredConverted(src);
    if (!dst_converted && !src_converted) {
        return false;
    }
    if (dst.info.type != src.info.type || dst.info.num_samples != src.info.num_samples ||
        BytesPerBlock(dst.info.format) != BytesPerBlock(src.info.format)) {
        return false;
    }
    // The other side has to hold plain blocks or texels, or be converted too.
    if ((!dst_converted && VideoCore::Surface::IsPixelFormatBCn(dst.info.format)) ||
        (!src_converted && VideoCore::Surface::IsPixelFormatBCn(src.info.format))) {
        return false;
    }
    if (dst_converted && src_converted &&
        RawBcnFamily(dst.info.format) == RawBcnFamily(src.info.format)) {
        // Both hold the same decoded or recompressed format: a plain copy is right, but the
        // destination's raw blocks have to follow the source's when it has them.
        return src.HasRawBcnBlocks() || dst.HasRawBcnBlocks();
    }
    return true;
}

void TextureCacheRuntime::CopyRawBcn(Image& dst, Image& src,
                                     std::span<const VideoCommon::ImageCopy> copies) {
    const bool dst_converted = IsBcnStoredConverted(dst);
    const bool src_converted = IsBcnStoredConverted(src);
    ASSERT(!dst_converted || dst.HasRawBcnBlocks());
    ASSERT(!src_converted || src.HasRawBcnBlocks());
    const u64 block_bytes = BytesPerBlock(src.info.format);

    struct RawLevel {
        u64 offset;      // of the first layer, in bytes
        u32 row;         // blocks per row
        u32 rows;        // rows per layer
        u64 layer_bytes;
    };
    const auto raw_level = [block_bytes](const Image& image, s32 level) {
        const VideoCommon::BufferImageCopy& raw = image.RawBcnLevel(level);
        const u32 row = Common::DivCeil(raw.buffer_row_length, 4U);
        const u32 rows = Common::DivCeil(raw.buffer_image_height, 4U);
        return RawLevel{raw.buffer_offset, row, rows, u64{row} * rows * block_bytes};
    };
    const auto block_offset = [](const Image& image, s32 offset) {
        return VideoCore::Surface::IsPixelFormatBCn(image.info.format) ? offset / 4 : offset;
    };

    for (const VideoCommon::ImageCopy& copy : copies) {
        const s32 src_level = copy.src_subresource.base_level;
        const s32 dst_level = copy.dst_subresource.base_level;
        const u32 layers = static_cast<u32>(copy.src_subresource.num_layers);
        const s32 src_layer = copy.src_subresource.base_layer;
        const s32 dst_layer = copy.dst_subresource.base_layer;
        // Callers copy whole levels; their extents come in texels or in blocks depending on the
        // caller, so the region is worked out in blocks here.
        const u32 sx = static_cast<u32>(block_offset(src, copy.src_offset.x));
        const u32 sy = static_cast<u32>(block_offset(src, copy.src_offset.y));
        const u32 dx = static_cast<u32>(block_offset(dst, copy.dst_offset.x));
        const u32 dy = static_cast<u32>(block_offset(dst, copy.dst_offset.y));
        const VideoCommon::Extent2D src_grid = RawBlockGrid(src.info, src_level);
        const VideoCommon::Extent2D dst_grid = RawBlockGrid(dst.info, dst_level);
        if (sx >= src_grid.width || sy >= src_grid.height || dx >= dst_grid.width ||
            dy >= dst_grid.height) {
            continue;
        }
        const u32 width = (std::min)(src_grid.width - sx, dst_grid.width - dx);
        const u32 height = (std::min)(src_grid.height - sy, dst_grid.height - dy);

        if (src_converted && dst_converted) {
            const RawLevel s = raw_level(src, src_level);
            const RawLevel d = raw_level(dst, dst_level);
            std::vector<VkBufferCopy> regions;
            regions.reserve(size_t{layers} * height);
            for (u32 layer = 0; layer < layers; ++layer) {
                for (u32 y = 0; y < height; ++y) {
                    regions.push_back(VkBufferCopy{
                        .srcOffset = s.offset + (src_layer + layer) * s.layer_bytes +
                                     (u64{sy + y} * s.row + sx) * block_bytes,
                        .dstOffset = d.offset + (dst_layer + layer) * d.layer_bytes +
                                     (u64{dy + y} * d.row + dx) * block_bytes,
                        .size = u64{width} * block_bytes,
                    });
                }
            }
            scheduler.RequestOutsideRenderPassOperationContext();
            scheduler.Record([src_buffer = src.RawBcnBuffer(), dst_buffer = dst.RawBcnBuffer(),
                              regions = std::move(regions)](vk::CommandBuffer cmdbuf) {
                RawBcnBarrier(cmdbuf);
                cmdbuf.CopyBuffer(src_buffer, dst_buffer, regions);
                RawBcnBarrier(cmdbuf);
            });
        } else if (dst_converted) {
            // Raw blocks written through an uncompressed format, one texel per block.
            const RawLevel d = raw_level(dst, dst_level);
            const VideoCommon::BufferImageCopy download{
                .buffer_offset = d.offset + dst_layer * d.layer_bytes +
                                 (u64{dy} * d.row + dx) * block_bytes,
                .buffer_size = d.layer_bytes * layers,
                .buffer_row_length = d.row,
                .buffer_image_height = d.rows,
                .image_subresource = copy.src_subresource,
                .image_offset = {static_cast<s32>(sx), static_cast<s32>(sy), 0},
                .image_extent = {width, height, 1},
            };
            src.DownloadMemory(dst.RawBcnBuffer(), 0, std::span{&download, 1});
            scheduler.RequestOutsideRenderPassOperationContext();
            scheduler.Record([](vk::CommandBuffer cmdbuf) { RawBcnBarrier(cmdbuf); });
        } else {
            // Raw blocks read through an uncompressed format.
            const RawLevel s = raw_level(src, src_level);
            const VideoCommon::BufferImageCopy upload{
                .buffer_offset = s.offset + src_layer * s.layer_bytes +
                                 (u64{sy} * s.row + sx) * block_bytes,
                .buffer_size = s.layer_bytes * layers,
                .buffer_row_length = s.row,
                .buffer_image_height = s.rows,
                .image_subresource = copy.dst_subresource,
                .image_offset = {static_cast<s32>(dx), static_cast<s32>(dy), 0},
                .image_extent = {width, height, 1},
            };
            scheduler.RequestOutsideRenderPassOperationContext();
            scheduler.Record([](vk::CommandBuffer cmdbuf) { RawBcnBarrier(cmdbuf); });
            dst.UploadMemory(src.RawBcnBuffer(), 0, std::span{&upload, 1});
            continue;
        }

        // Decode the destination level again, recompressing it if the image is stored that way.
        const RawLevel d = raw_level(dst, dst_level);
        const VideoCommon::Extent3D mip = VideoCommon::MipSize(dst.info.size,
                                                               static_cast<u32>(dst_level));
        VideoCommon::BufferImageCopy decode{
            .buffer_offset = d.offset + dst_layer * d.layer_bytes,
            .buffer_size = d.layer_bytes * layers,
            .buffer_row_length = d.row * 4,
            .buffer_image_height = d.rows * 4,
            .image_subresource = copy.dst_subresource,
            .image_offset = {0, 0, 0},
            .image_extent = {mip.width, mip.height, 1},
        };
        const auto [decoded, decoded_offset] = bcn_decode_pass->Decode(
            dst.RawBcnBuffer(), 0, dst.unswizzled_size_bytes, std::span{&decode, 1},
            dst.info.format);
        dst.UploadMemory(decoded, decoded_offset, std::span{&decode, 1});
    }
}

void TextureCacheRuntime::CopyImage(Image& dst, Image& src,
                                    std::span<const VideoCommon::ImageCopy> copies) {
    boost::container::small_vector<VkImageCopy, 16> vk_copies(copies.size());
    const VkImageAspectFlags aspect_mask = dst.AspectMask();
    ASSERT(aspect_mask == src.AspectMask());

    std::ranges::transform(copies, vk_copies.begin(), [aspect_mask](const auto& copy) {
        return MakeImageCopy(copy, aspect_mask);
    });
    const VkImage dst_image = dst.Handle();
    const VkImage src_image = src.Handle();
    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([dst_image, src_image, aspect_mask, vk_copies](vk::CommandBuffer cmdbuf) {
        RangedBarrierRange dst_range;
        RangedBarrierRange src_range;
        for (const VkImageCopy& copy : vk_copies) {
            dst_range.AddLayers(copy.dstSubresource);
            src_range.AddLayers(copy.srcSubresource);
        }
        const std::array pre_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = src_range.SubresourceRange(aspect_mask),
            },
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = dst_range.SubresourceRange(aspect_mask),
            },
        };
        const std::array post_barriers{
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = 0,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = src_image,
                .subresourceRange = src_range.SubresourceRange(aspect_mask),
            },
            VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                                 VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                                 VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                 VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = dst_image,
                .subresourceRange = dst_range.SubresourceRange(aspect_mask),
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, {}, {}, pre_barriers);
        cmdbuf.CopyImage(src_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst_image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, vk_copies);
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, {}, {}, post_barriers);
    });
}

void TextureCacheRuntime::CopyImageMSAA(Image& dst, Image& src,
                                        std::span<const VideoCommon::ImageCopy> copies) {
    const bool msaa_to_non_msaa = src.info.num_samples > 1 && dst.info.num_samples == 1;
    if (msaa_copy_pass) {
        return msaa_copy_pass->CopyImage(dst, src, copies, msaa_to_non_msaa);
    }
    UNIMPLEMENTED_MSG("Copying images with different samples is not supported.");
}

u64 TextureCacheRuntime::GetDeviceLocalMemory() const {
    return device.GetDeviceLocalMemory();
}

u64 TextureCacheRuntime::GetDeviceMemoryUsage() const {
    return device.GetDeviceMemoryUsage();
}

bool TextureCacheRuntime::CanReportMemoryUsage() const {
    return device.CanReportMemoryUsage();
}

void TextureCacheRuntime::TickFrame() {}

Image::Image(TextureCacheRuntime& runtime_, const ImageInfo& info_, GPUVAddr gpu_addr_,
             VAddr cpu_addr_)
    : VideoCommon::ImageBase(info_, gpu_addr_, cpu_addr_), scheduler{&runtime_.scheduler},
      runtime{&runtime_}, original_image(MakeImage(runtime_.device, runtime_.memory_allocator, info,
                                                   runtime->ViewFormats(info.format))),
      aspect_mask(ImageAspectMask(info.format)) {
    if (IsPixelFormatASTC(info.format) && !runtime->device.IsOptimalAstcSupported()) {
        switch (Settings::values.accelerate_astc.GetValue()) {
        case Settings::AstcDecodeMode::Gpu:
            if (Settings::values.astc_recompression.GetValue() ==
                    Settings::AstcRecompression::Uncompressed &&
                info.size.depth == 1) {
                flags |= VideoCommon::ImageFlagBits::AcceleratedUpload;
            }
            break;
        case Settings::AstcDecodeMode::CpuAsynchronous:
            flags |= VideoCommon::ImageFlagBits::AsynchronousDecode;
            break;
        default:
            break;
        }
        flags |= VideoCommon::ImageFlagBits::Converted;
        flags |= VideoCommon::ImageFlagBits::CostlyLoad;
    }
    if (IsPixelFormatBCn(info.format) && !runtime->device.IsOptimalBcnSupported()) {
        flags |= VideoCommon::ImageFlagBits::Converted;
        flags |= VideoCommon::ImageFlagBits::CostlyLoad;
    }
    if (runtime->device.HasDebuggingToolAttached()) {
        original_image.SetObjectNameEXT(VideoCommon::Name(*this).c_str());
    }
    current_image = *original_image;
    storage_image_views.resize(info.resources.levels);
    if (IsPixelFormatASTC(info.format) && !runtime->device.IsOptimalAstcSupported() &&
        Settings::values.astc_recompression.GetValue() ==
            Settings::AstcRecompression::Uncompressed) {
        const auto& device = runtime->device.GetLogical();
        for (s32 level = 0; level < info.resources.levels; ++level) {
            storage_image_views[level] =
                MakeStorageView(device, level, *original_image, VK_FORMAT_A8B8G8R8_UNORM_PACK32);
        }
    }
}

Image::Image(const VideoCommon::NullImageParams& params) : VideoCommon::ImageBase{params} {}

Image::~Image() = default;

void Image::UploadMemory(VkBuffer buffer, VkDeviceSize offset,
                         std::span<const VideoCommon::BufferImageCopy> copies) {
    // BCn stored as ASTC: the staging buffer holds the texels decoded to RGBA8, encode them first.
    boost::container::small_vector<VideoCommon::BufferImageCopy, 16> astc_copies;
    if (runtime != nullptr && runtime->bcn_astc_pass &&
        VideoCore::Surface::IsBcnRecompressedToAstc(info.format)) {
        static std::atomic_bool logged_first{false};
        if (!logged_first.exchange(true)) {
            LOG_INFO(Render_Vulkan, "BCn to ASTC: first texture recompressed ({}x{})",
                     info.size.width, info.size.height);
        }
        astc_copies.assign(copies.begin(), copies.end());
        std::tie(buffer, offset) = runtime->bcn_astc_pass->Encode(buffer, offset, astc_copies);
        copies = astc_copies;
    } else if (runtime != nullptr && runtime->bcn_eac_pass &&
               VideoCore::Surface::IsBcnRecompressedToEac(info.format)) {
        // BC4 / BC5 stored as EAC: the staging buffer holds them decoded to R8 / RG8.
        static std::atomic_bool logged_first{false};
        if (!logged_first.exchange(true)) {
            LOG_INFO(Render_Vulkan, "BC4/BC5 to EAC: first texture recompressed ({}x{})",
                     info.size.width, info.size.height);
        }
        const bool is_bc5 = info.format == PixelFormat::BC5_UNORM ||
                            info.format == PixelFormat::BC5_SNORM;
        const bool is_signed = info.format == PixelFormat::BC4_SNORM ||
                               info.format == PixelFormat::BC5_SNORM;
        astc_copies.assign(copies.begin(), copies.end());
        std::tie(buffer, offset) = runtime->bcn_eac_pass->Encode(
            buffer, offset, astc_copies, is_bc5 ? 2U : 1U, is_signed);
        copies = astc_copies;
    }
    // TODO: Move this to another API
    const bool is_rescaled = True(flags & ImageFlagBits::Rescaled);
    if (is_rescaled) {
        ScaleDown(true);
    }
    scheduler->RequestOutsideRenderPassOperationContext();
    auto vk_copies = TransformBufferImageCopies(copies, offset, aspect_mask);
    const VkBuffer src_buffer = buffer;
    const VkImage vk_image = *original_image;
    const VkImageAspectFlags vk_aspect_mask = aspect_mask;
    const bool is_initialized = std::exchange(initialized, true);
    scheduler->Record([src_buffer, vk_image, vk_aspect_mask, is_initialized,
                       vk_copies](vk::CommandBuffer cmdbuf) {
        CopyBufferToImage(cmdbuf, src_buffer, vk_image, vk_aspect_mask, is_initialized, vk_copies);
    });
    if (is_rescaled) {
        ScaleUp();
    }
}

void Image::UploadMemory(const StagingBufferRef& map, std::span<const BufferImageCopy> copies) {
    UploadMemory(map.buffer, map.offset, copies);
}

void Image::UploadRawBcnBlocks(const StagingBufferRef& map,
                               std::span<const BufferImageCopy> copies) {
    if (!raw_bcn_blocks) {
        raw_bcn_blocks = runtime->memory_allocator.CreateBuffer(
            VkBufferCreateInfo{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = unswizzled_size_bytes,
                .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                         VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
            },
            MemoryUsage::DeviceLocal);
    }
    raw_bcn_levels.assign(copies.begin(), copies.end());
    has_raw_bcn_blocks = true;
    const VkBufferCopy region{
        .srcOffset = map.offset,
        .dstOffset = 0,
        .size = unswizzled_size_bytes,
    };
    scheduler->RequestOutsideRenderPassOperationContext();
    scheduler->Record([src = map.buffer, dst = *raw_bcn_blocks, region](vk::CommandBuffer cmdbuf) {
        RawBcnBarrier(cmdbuf);
        cmdbuf.CopyBuffer(src, dst, region);
        RawBcnBarrier(cmdbuf);
    });
}

bool Image::DownloadRawBcnBlocks(std::span<VkBuffer> buffers, std::span<size_t> offsets,
                                 std::span<const BufferImageCopy> copies) {
    // Guest memory wants the raw blocks, not the texels the image holds; whole levels only.
    if (!has_raw_bcn_blocks) {
        return false;
    }
    std::vector<VkBufferCopy> regions;
    regions.reserve(copies.size());
    for (const BufferImageCopy& copy : copies) {
        const s32 level = copy.image_subresource.base_level;
        if (level >= static_cast<s32>(raw_bcn_levels.size()) ||
            copy.image_offset != VideoCommon::Offset3D{} ||
            copy.image_extent != VideoCommon::MipSize(info.size, static_cast<u32>(level)) ||
            copy.image_subresource.base_layer != 0 ||
            copy.image_subresource.num_layers != info.resources.layers) {
            return false;
        }
        const BufferImageCopy& raw = raw_bcn_levels[level];
        regions.push_back(VkBufferCopy{
            .srcOffset = raw.buffer_offset,
            .dstOffset = copy.buffer_offset,
            .size = raw.buffer_size,
        });
    }
    scheduler->RequestOutsideRenderPassOperationContext();
    for (size_t index = 0; index < buffers.size(); ++index) {
        std::vector<VkBufferCopy> buffer_regions = regions;
        for (VkBufferCopy& region : buffer_regions) {
            region.dstOffset += offsets[index];
        }
        scheduler->Record([src = *raw_bcn_blocks, dst = buffers[index],
                           buffer_regions = std::move(buffer_regions)](vk::CommandBuffer cmdbuf) {
            static constexpr VkMemoryBarrier READ_BARRIER{
                .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
            };
            RawBcnBarrier(cmdbuf);
            cmdbuf.CopyBuffer(src, dst, buffer_regions);
            cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, READ_BARRIER);
        });
    }
    return true;
}

void Image::DownloadMemory(VkBuffer buffer, size_t offset,
                           std::span<const VideoCommon::BufferImageCopy> copies) {
    std::array buffer_handles{
        buffer,
    };
    std::array buffer_offsets{
        offset,
    };
    DownloadMemory(buffer_handles, buffer_offsets, copies);
}

void Image::DownloadMemory(std::span<VkBuffer> buffers_span, std::span<size_t> offsets_span,
                           std::span<const VideoCommon::BufferImageCopy> copies) {
    if (DownloadRawBcnBlocks(buffers_span, offsets_span, copies)) {
        return;
    }
    const bool is_rescaled = True(flags & ImageFlagBits::Rescaled);
    if (is_rescaled) {
        ScaleDown();
    }
    boost::container::small_vector<VkBuffer, 8> buffers_vector{};
    boost::container::small_vector<boost::container::small_vector<VkBufferImageCopy, 16>, 8>
        vk_copies;
    for (size_t index = 0; index < buffers_span.size(); index++) {
        buffers_vector.emplace_back(buffers_span[index]);
        vk_copies.emplace_back(
            TransformBufferImageCopies(copies, offsets_span[index], aspect_mask));
    }
    scheduler->RequestOutsideRenderPassOperationContext();
    scheduler->Record([buffers = std::move(buffers_vector), image = *original_image,
                       aspect_mask_ = aspect_mask, vk_copies](vk::CommandBuffer cmdbuf) {
        const VkImageMemoryBarrier read_barrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image,
            .subresourceRange{
                .aspectMask = aspect_mask_,
                .baseMipLevel = 0,
                .levelCount = VK_REMAINING_MIP_LEVELS,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, read_barrier);

        for (size_t index = 0; index < buffers.size(); index++) {
            cmdbuf.CopyImageToBuffer(image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffers[index],
                                     vk_copies[index]);
        }

        const VkMemoryBarrier memory_write_barrier{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        };
        const VkImageMemoryBarrier image_write_barrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image,
            .subresourceRange{
                .aspectMask = aspect_mask_,
                .baseMipLevel = 0,
                .levelCount = VK_REMAINING_MIP_LEVELS,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                               0, memory_write_barrier, nullptr, image_write_barrier);
    });
    if (is_rescaled) {
        ScaleUp(true);
    }
}

void Image::DownloadMemory(const StagingBufferRef& map, std::span<const BufferImageCopy> copies) {
    std::array buffers{
        map.buffer,
    };
    std::array offsets{
        static_cast<size_t>(map.offset),
    };
    DownloadMemory(buffers, offsets, copies);
}

VkImageView Image::StorageImageView(s32 level) noexcept {
    auto& view = storage_image_views[level];
    if (!view) {
        const auto format_info =
            MaxwellToVK::SurfaceFormat(runtime->device, FormatType::Optimal, true, info.format);
        view =
            MakeStorageView(runtime->device.GetLogical(), level, current_image, format_info.format);
    }
    return *view;
}

bool Image::IsRescaled() const noexcept {
    return True(flags & ImageFlagBits::Rescaled);
}

bool Image::ScaleUp(bool ignore) {
    if (!runtime) {
        // The NULL_IMAGE_ID placeholder is never fully constructed; nothing to rescale.
        return false;
    }
    const auto& resolution = runtime->resolution;
    if (!resolution.active) {
        return false;
    }
    if (True(flags & ImageFlagBits::Rescaled)) {
        return false;
    }
    ASSERT(info.type != ImageType::Linear);
    flags |= ImageFlagBits::Rescaled;
    has_scaled = true;
    if (!scaled_image) {
        const bool is_2d = info.type == ImageType::e2D;
        const u32 scaled_width = resolution.ScaleUp(info.size.width);
        const u32 scaled_height = is_2d ? resolution.ScaleUp(info.size.height) : info.size.height;
        auto scaled_info = info;
        scaled_info.size.width = scaled_width;
        scaled_info.size.height = scaled_height;
        scaled_image = MakeImage(runtime->device, runtime->memory_allocator, scaled_info,
                                 runtime->ViewFormats(info.format));
        ignore = false;
    }
    current_image = *scaled_image;
    if (ignore) {
        return true;
    }
    if (aspect_mask == 0) {
        aspect_mask = ImageAspectMask(info.format);
    }
    if (NeedsScaleHelper()) {
        return BlitScaleHelper(true);
    } else {
        BlitScale(*scheduler, *original_image, *scaled_image, info, aspect_mask, resolution);
    }
    return true;
}

bool Image::ScaleDown(bool ignore) {
    if (!runtime) {
        // The NULL_IMAGE_ID placeholder is never fully constructed; nothing to rescale.
        return false;
    }
    const auto& resolution = runtime->resolution;
    if (!resolution.active) {
        return false;
    }
    if (False(flags & ImageFlagBits::Rescaled)) {
        return false;
    }
    ASSERT(info.type != ImageType::Linear);
    flags &= ~ImageFlagBits::Rescaled;
    current_image = *original_image;
    if (ignore) {
        return true;
    }
    if (aspect_mask == 0) {
        aspect_mask = ImageAspectMask(info.format);
    }
    if (NeedsScaleHelper()) {
        return BlitScaleHelper(false);
    } else {
        BlitScale(*scheduler, *scaled_image, *original_image, info, aspect_mask, resolution, false);
    }
    return true;
}

bool Image::BlitScaleHelper(bool scale_up) {
    using namespace VideoCommon;
    static constexpr auto BLIT_OPERATION = Tegra::Engines::Fermi2D::Operation::SrcCopy;
    const bool is_color{aspect_mask == VK_IMAGE_ASPECT_COLOR_BIT};
    const bool is_bilinear{is_color && !IsPixelFormatInteger(info.format)};
    const auto operation = is_bilinear ? Tegra::Engines::Fermi2D::Filter::Bilinear
                                       : Tegra::Engines::Fermi2D::Filter::Point;

    const bool is_2d = info.type == ImageType::e2D;
    const auto& resolution = runtime->resolution;
    const u32 scaled_width = resolution.ScaleUp(info.size.width);
    const u32 scaled_height = is_2d ? resolution.ScaleUp(info.size.height) : info.size.height;
    std::unique_ptr<ImageView>& blit_view = scale_up ? scale_view : normal_view;
    std::unique_ptr<Framebuffer>& blit_framebuffer =
        scale_up ? scale_framebuffer : normal_framebuffer;
    if (!blit_view) {
        const auto view_info = ImageViewInfo(ImageViewType::e2D, info.format);
        blit_view = std::make_unique<ImageView>(*runtime, view_info, NULL_IMAGE_ID, *this);
    }

    const u32 src_width = scale_up ? info.size.width : scaled_width;
    const u32 src_height = scale_up ? info.size.height : scaled_height;
    const u32 dst_width = scale_up ? scaled_width : info.size.width;
    const u32 dst_height = scale_up ? scaled_height : info.size.height;
    const Region2D src_region{
        .start = {0, 0},
        .end = {static_cast<s32>(src_width), static_cast<s32>(src_height)},
    };
    const Region2D dst_region{
        .start = {0, 0},
        .end = {static_cast<s32>(dst_width), static_cast<s32>(dst_height)},
    };
    const VkExtent2D extent{
        .width = std::max(scaled_width, info.size.width),
        .height = std::max(scaled_height, info.size.height),
    };

    auto* view_ptr = blit_view.get();
    if (aspect_mask == VK_IMAGE_ASPECT_COLOR_BIT) {
        if (!blit_framebuffer) {
            blit_framebuffer =
                std::make_unique<Framebuffer>(*runtime, view_ptr, nullptr, extent, scale_up);
        }
        const auto color_view = blit_view->Handle(Shader::TextureType::Color2D);

        runtime->blit_image_helper.BlitColor(blit_framebuffer.get(), color_view, dst_region,
                                             src_region, operation, BLIT_OPERATION);
    } else if (aspect_mask == (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)) {
        if (!blit_framebuffer) {
            blit_framebuffer =
                std::make_unique<Framebuffer>(*runtime, nullptr, view_ptr, extent, scale_up);
        }
        runtime->blit_image_helper.BlitDepthStencil(blit_framebuffer.get(), blit_view->DepthView(),
                                                    blit_view->StencilView(), dst_region,
                                                    src_region, operation, BLIT_OPERATION);
    } else {
        // TODO: Use helper blits where applicable
        flags &= ~ImageFlagBits::Rescaled;
        LOG_ERROR(Render_Vulkan, "Device does not support scaling format {}", info.format);
        return false;
    }
    return true;
}

bool Image::NeedsScaleHelper() const {
    const auto& device = runtime->device;
    const bool needs_msaa_helper = info.num_samples > 1 && device.CantBlitMSAA();
    if (needs_msaa_helper) {
        return true;
    }
    static constexpr auto OPTIMAL_FORMAT = FormatType::Optimal;
    const auto vk_format =
        MaxwellToVK::SurfaceFormat(device, OPTIMAL_FORMAT, false, info.format).format;
    const auto blit_usage = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    const bool needs_blit_helper = !device.IsFormatSupported(vk_format, blit_usage, OPTIMAL_FORMAT);
    return needs_blit_helper;
}

ImageView::ImageView(TextureCacheRuntime& runtime, const VideoCommon::ImageViewInfo& info,
                     ImageId image_id_, Image& image)
    : VideoCommon::ImageViewBase{info, image.info, image_id_, image.gpu_addr},
      device{&runtime.device}, image_handle{image.Handle()},
      samples(ConvertSampleCount(image.info.num_samples)) {
    using Shader::TextureType;

    const VkImageAspectFlags aspect_mask = ImageViewAspectMask(info);
    std::array<SwizzleSource, 4> swizzle{
        SwizzleSource::R,
        SwizzleSource::G,
        SwizzleSource::B,
        SwizzleSource::A,
    };
    if (!info.IsRenderTarget()) {
        swizzle = info.Swizzle();
        TryTransformSwizzleIfNeeded(format, swizzle, device->MustEmulateBGR565(),
                                    !device->IsExt4444FormatsSupported());
        if ((aspect_mask & (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)) != 0) {
            std::ranges::transform(swizzle, swizzle.begin(), ConvertGreenRed);
        }
    }
    const auto format_info = MaxwellToVK::SurfaceFormat(*device, FormatType::Optimal, true, format);
    supports_linear_filter = device->IsLinearFilterSupported(format_info.format);
    const VkImageViewUsageCreateInfo image_view_usage{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_USAGE_CREATE_INFO,
        .pNext = nullptr,
        .usage = ImageUsageFlags(format_info, format),
    };
    const VkImageViewCreateInfo create_info{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = &image_view_usage,
        .flags = 0,
        .image = image.Handle(),
        .viewType = VkImageViewType{},
        .format = format_info.format,
        .components{
            .r = ComponentSwizzle(swizzle[0]),
            .g = ComponentSwizzle(swizzle[1]),
            .b = ComponentSwizzle(swizzle[2]),
            .a = ComponentSwizzle(swizzle[3]),
        },
        .subresourceRange = MakeSubresourceRange(aspect_mask, info.range),
    };
    const auto create = [&](TextureType tex_type, std::optional<u32> num_layers) {
        VkImageViewCreateInfo ci{create_info};
        ci.viewType = ImageViewType(tex_type);
        if (num_layers) {
            ci.subresourceRange.layerCount = *num_layers;
        }
        vk::ImageView handle = device->GetLogical().CreateImageView(ci);
        if (device->HasDebuggingToolAttached()) {
            handle.SetObjectNameEXT(VideoCommon::Name(*this, gpu_addr).c_str());
        }
        image_views[static_cast<size_t>(tex_type)] = std::move(handle);
    };
    switch (info.type) {
    case VideoCommon::ImageViewType::e1D:
    case VideoCommon::ImageViewType::e1DArray:
        create(TextureType::Color1D, 1);
        create(TextureType::ColorArray1D, std::nullopt);
        render_target = Handle(TextureType::ColorArray1D);
        break;
    case VideoCommon::ImageViewType::e2D:
    case VideoCommon::ImageViewType::e2DArray:
    case VideoCommon::ImageViewType::Rect:
        create(TextureType::Color2D, 1);
        create(TextureType::ColorArray2D, std::nullopt);
        render_target = Handle(Shader::TextureType::ColorArray2D);
        break;
    case VideoCommon::ImageViewType::e3D:
        create(TextureType::Color3D, std::nullopt);
        render_target = Handle(Shader::TextureType::Color3D);
        break;
    case VideoCommon::ImageViewType::Cube:
    case VideoCommon::ImageViewType::CubeArray:
        create(TextureType::ColorCube, 6);
        create(TextureType::ColorArrayCube, std::nullopt);
        break;
    case VideoCommon::ImageViewType::Buffer:
        ASSERT(false);
        break;
    }
}

ImageView::ImageView(TextureCacheRuntime& runtime, const VideoCommon::ImageViewInfo& info,
                     ImageId image_id_, Image& image, const SlotVector<Image>& slot_imgs)
    : ImageView{runtime, info, image_id_, image} {
    slot_images = &slot_imgs;
}

ImageView::ImageView(TextureCacheRuntime&, const VideoCommon::ImageInfo& info,
                     const VideoCommon::ImageViewInfo& view_info, GPUVAddr gpu_addr_)
    : VideoCommon::ImageViewBase{info, view_info, gpu_addr_},
      buffer_size{VideoCommon::CalculateGuestSizeInBytes(info)} {}

ImageView::ImageView(TextureCacheRuntime& runtime, const VideoCommon::NullImageViewParams& params)
    : VideoCommon::ImageViewBase{params}, device{&runtime.device} {
    if (device->HasNullDescriptor()) {
        return;
    }

    // Without nullDescriptor every descriptor must point at a real view of the kind the shader
    // declares: one tiny black image per dimensionality and a view of each texture type on it.
    // (A single 1D image with 2D views of it for every type, as before, was invalid for most of
    // them, and Mali's driver crashed on such descriptors: Zelda TOTK.)
    const auto make_image = [&](ImageType image_type, s32 layers) {
        ImageInfo info{};
        info.format = PixelFormat::A8B8G8R8_UNORM;
        info.type = image_type;
        info.size = {1, 1, 1};
        info.resources.levels = 1;
        info.resources.layers = layers;
        return MakeImage(*device, runtime.memory_allocator, info, {});
    };
    null_image = make_image(ImageType::e1D, 1);
    null_image_2d = make_image(ImageType::e2D, 6); // six layers, so it can be seen as a cube
    null_image_3d = make_image(ImageType::e3D, 1);
    image_handle = *null_image_2d;

    const auto make_view = [&](VkImage image, VkImageViewType view_type, u32 layers) {
        return device->GetLogical().CreateImageView({
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .image = image,
            .viewType = view_type,
            .format = VK_FORMAT_A8B8G8R8_UNORM_PACK32,
            .components{
                .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                .a = VK_COMPONENT_SWIZZLE_IDENTITY,
            },
            .subresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = layers,
            },
        });
    };
    using Shader::TextureType;
    const auto set = [&](TextureType texture_type, vk::ImageView view) {
        image_views[static_cast<size_t>(texture_type)] = std::move(view);
    };
    set(TextureType::Color1D, make_view(*null_image, VK_IMAGE_VIEW_TYPE_1D, 1));
    set(TextureType::ColorArray1D, make_view(*null_image, VK_IMAGE_VIEW_TYPE_1D_ARRAY, 1));
    set(TextureType::Color2D, make_view(*null_image_2d, VK_IMAGE_VIEW_TYPE_2D, 1));
    set(TextureType::ColorArray2D, make_view(*null_image_2d, VK_IMAGE_VIEW_TYPE_2D_ARRAY, 1));
    set(TextureType::Color2DRect, make_view(*null_image_2d, VK_IMAGE_VIEW_TYPE_2D, 1));
    set(TextureType::Color3D, make_view(*null_image_3d, VK_IMAGE_VIEW_TYPE_3D, 1));
    set(TextureType::ColorCube, make_view(*null_image_2d, VK_IMAGE_VIEW_TYPE_CUBE, 6));
    set(TextureType::ColorArrayCube, make_view(*null_image_2d, VK_IMAGE_VIEW_TYPE_CUBE_ARRAY, 6));
    render_target = Handle(TextureType::ColorArray2D);

    // Black, and in the layout descriptors use.
    runtime.scheduler.RequestOutsideRenderPassOperationContext();
    runtime.scheduler.Record([images = std::array<VkImage, 3>{*null_image, *null_image_2d,
                                                              *null_image_3d}](
                                 vk::CommandBuffer cmdbuf) {
        static constexpr VkImageSubresourceRange range{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = VK_REMAINING_MIP_LEVELS,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        };
        std::array<VkImageMemoryBarrier, 3> to_general{};
        for (size_t i = 0; i < images.size(); ++i) {
            to_general[i] = VkImageMemoryBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = images[i],
                .subresourceRange = range,
            };
        }
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               0, {}, {}, to_general);
        for (const VkImage image : images) {
            cmdbuf.ClearColorImage(image, VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue{}, range);
        }
        static constexpr VkMemoryBarrier to_shaders{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
        };
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, to_shaders);
    });
}

ImageView::~ImageView() = default;

VkImageView ImageView::DepthView() {
    if (!image_handle) {
        return VK_NULL_HANDLE;
    }
    if (depth_view) {
        return *depth_view;
    }
    const auto& info = MaxwellToVK::SurfaceFormat(*device, FormatType::Optimal, true, format);
    depth_view = MakeView(info.format, VK_IMAGE_ASPECT_DEPTH_BIT);
    return *depth_view;
}

VkImageView ImageView::StencilView() {
    if (!image_handle) {
        return VK_NULL_HANDLE;
    }
    if (stencil_view) {
        return *stencil_view;
    }
    const auto& info = MaxwellToVK::SurfaceFormat(*device, FormatType::Optimal, true, format);
    stencil_view = MakeView(info.format, VK_IMAGE_ASPECT_STENCIL_BIT);
    return *stencil_view;
}

VkImageView ImageView::ColorView() {
    if (!image_handle) {
        return VK_NULL_HANDLE;
    }
    if (color_view) {
        return *color_view;
    }
    color_view = MakeView(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT);
    return *color_view;
}

VkImageView ImageView::StorageView(Shader::TextureType texture_type,
                                   Shader::ImageFormat image_format) {
    if (!image_handle) {
        return VK_NULL_HANDLE;
    }
    if (image_format == Shader::ImageFormat::Typeless) {
        return Handle(texture_type);
    }
    const bool is_signed{image_format == Shader::ImageFormat::R8_SINT ||
                         image_format == Shader::ImageFormat::R16_SINT};
    if (!storage_views) {
        storage_views = std::make_unique<StorageViews>();
    }
    auto& views{is_signed ? storage_views->signeds : storage_views->unsigneds};
    auto& view{views[static_cast<size_t>(texture_type)]};
    if (view) {
        return *view;
    }
    view = MakeView(Format(image_format), VK_IMAGE_ASPECT_COLOR_BIT);
    return *view;
}

bool ImageView::IsRescaled() const noexcept {
    if (!slot_images) {
        return false;
    }
    const auto& slots = *slot_images;
    const auto& src_image = slots[image_id];
    return src_image.IsRescaled();
}

vk::ImageView ImageView::MakeView(VkFormat vk_format, VkImageAspectFlags aspect_mask) {
    return device->GetLogical().CreateImageView({
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .image = image_handle,
        .viewType = ImageViewType(type),
        .format = vk_format,
        .components{
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = MakeSubresourceRange(aspect_mask, range),
    });
}

namespace {
vk::Sampler CreateSampler(const Device& device, const Tegra::Texture::TSCEntry& tsc, f32 anisotropy,
                          bool force_nearest) {
    const bool arbitrary_borders = device.IsExtCustomBorderColorSupported();
    const auto color = tsc.BorderColor();

    const VkSamplerCustomBorderColorCreateInfoEXT border_ci{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CUSTOM_BORDER_COLOR_CREATE_INFO_EXT,
        .pNext = nullptr,
        // TODO: Make use of std::bit_cast once libc++ supports it.
        .customBorderColor = Common::BitCast<VkClearColorValue>(color),
        .format = VK_FORMAT_UNDEFINED,
    };
    const void* pnext = nullptr;
    if (arbitrary_borders) {
        pnext = &border_ci;
    }
    const VkSamplerReductionModeCreateInfoEXT reduction_ci{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_REDUCTION_MODE_CREATE_INFO_EXT,
        .pNext = pnext,
        .reductionMode = MaxwellToVK::SamplerReduction(tsc.reduction_filter),
    };
    if (device.IsExtSamplerFilterMinmaxSupported()) {
        pnext = &reduction_ci;
    } else if (reduction_ci.reductionMode != VK_SAMPLER_REDUCTION_MODE_WEIGHTED_AVERAGE_EXT) {
        LOG_WARNING(Render_Vulkan, "VK_EXT_sampler_filter_minmax is required");
    }
    const VkFilter mag_filter =
        force_nearest ? VK_FILTER_NEAREST : MaxwellToVK::Sampler::Filter(tsc.mag_filter);
    const VkFilter min_filter =
        force_nearest ? VK_FILTER_NEAREST : MaxwellToVK::Sampler::Filter(tsc.min_filter);
    const VkSamplerMipmapMode mipmap_mode = force_nearest
                                                ? VK_SAMPLER_MIPMAP_MODE_NEAREST
                                                : MaxwellToVK::Sampler::MipmapMode(tsc.mipmap_filter);
    if (force_nearest) {
        anisotropy = 1.0f;
    }
    return device.GetLogical().CreateSampler(VkSamplerCreateInfo{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = pnext,
        .flags = 0,
        .magFilter = mag_filter,
        .minFilter = min_filter,
        .mipmapMode = mipmap_mode,
        .addressModeU = MaxwellToVK::Sampler::WrapMode(device, tsc.wrap_u, tsc.mag_filter),
        .addressModeV = MaxwellToVK::Sampler::WrapMode(device, tsc.wrap_v, tsc.mag_filter),
        .addressModeW = MaxwellToVK::Sampler::WrapMode(device, tsc.wrap_p, tsc.mag_filter),
        .mipLodBias = tsc.LodBias(),
        .anisotropyEnable = static_cast<VkBool32>(anisotropy > 1.0f ? VK_TRUE : VK_FALSE),
        .maxAnisotropy = anisotropy,
        .compareEnable = tsc.depth_compare_enabled,
        .compareOp = MaxwellToVK::Sampler::DepthCompareFunction(tsc.depth_compare_func),
        .minLod = tsc.mipmap_filter == TextureMipmapFilter::None ? 0.0f : tsc.MinLod(),
        .maxLod = tsc.mipmap_filter == TextureMipmapFilter::None ? 0.25f : tsc.MaxLod(),
        .borderColor =
            arbitrary_borders ? VK_BORDER_COLOR_FLOAT_CUSTOM_EXT : ConvertBorderColor(color),
        .unnormalizedCoordinates = VK_FALSE,
    });
}
} // Anonymous namespace

Sampler::Sampler(TextureCacheRuntime& runtime, const Tegra::Texture::TSCEntry& tsc_)
    : device{&runtime.device}, tsc{tsc_} {
    // Some games have samplers with garbage. Sanitize them here.
    const f32 max_anisotropy = std::clamp(tsc.MaxAnisotropy(), 1.0f, 16.0f);
    sampler = CreateSampler(*device, tsc, max_anisotropy, false);

    const f32 max_anisotropy_default = static_cast<f32>(1U << tsc.max_anisotropy);
    if (max_anisotropy > max_anisotropy_default) {
        sampler_default_anisotropy = CreateSampler(*device, tsc, max_anisotropy_default, false);
    }
    uses_linear_filter =
        MaxwellToVK::Sampler::Filter(tsc.mag_filter) == VK_FILTER_LINEAR ||
        MaxwellToVK::Sampler::Filter(tsc.min_filter) == VK_FILTER_LINEAR ||
        MaxwellToVK::Sampler::MipmapMode(tsc.mipmap_filter) == VK_SAMPLER_MIPMAP_MODE_LINEAR ||
        max_anisotropy > 1.0f;
}

VkSampler Sampler::HandleWithoutLinearFilter() {
    if (!sampler_without_linear_filter) {
        sampler_without_linear_filter = CreateSampler(*device, tsc, 1.0f, true);
    }
    return *sampler_without_linear_filter;
}

Framebuffer::Framebuffer(TextureCacheRuntime& runtime, std::span<ImageView*, NUM_RT> color_buffers,
                         ImageView* depth_buffer, const VideoCommon::RenderTargets& key)
    : render_area{VkExtent2D{
          .width = key.size.width,
          .height = key.size.height,
      }} {
    CreateFramebuffer(runtime, color_buffers, depth_buffer, key.is_rescaled);
    if (runtime.device.HasDebuggingToolAttached()) {
        framebuffer.SetObjectNameEXT(VideoCommon::Name(key).c_str());
    }
}

Framebuffer::Framebuffer(TextureCacheRuntime& runtime, ImageView* color_buffer,
                         ImageView* depth_buffer, VkExtent2D extent, bool is_rescaled_)
    : render_area{extent} {
    std::array<ImageView*, NUM_RT> color_buffers{color_buffer};
    CreateFramebuffer(runtime, color_buffers, depth_buffer, is_rescaled_);
}

Framebuffer::~Framebuffer() = default;

void Framebuffer::CreateFramebuffer(TextureCacheRuntime& runtime,
                                    std::span<ImageView*, NUM_RT> color_buffers,
                                    ImageView* depth_buffer, bool is_rescaled_) {
    boost::container::small_vector<VkImageView, NUM_RT + 1> attachments;
    RenderPassKey renderpass_key{};
    s32 num_layers = 1;

    is_rescaled = is_rescaled_;
    const auto& resolution = runtime.resolution;

    u32 width = std::numeric_limits<u32>::max();
    u32 height = std::numeric_limits<u32>::max();
    for (size_t index = 0; index < NUM_RT; ++index) {
        const ImageView* const color_buffer = color_buffers[index];
        if (!color_buffer) {
            renderpass_key.color_formats[index] = PixelFormat::Invalid;
            continue;
        }
        width = std::min(width, is_rescaled ? resolution.ScaleUp(color_buffer->size.width)
                                            : color_buffer->size.width);
        height = std::min(height, is_rescaled ? resolution.ScaleUp(color_buffer->size.height)
                                              : color_buffer->size.height);
        attachments.push_back(color_buffer->RenderTarget());
        renderpass_key.color_formats[index] = color_buffer->format;
        num_layers = std::max(num_layers, color_buffer->range.extent.layers);
        images[num_images] = color_buffer->ImageHandle();
        image_ranges[num_images] = MakeSubresourceRange(color_buffer);
        rt_map[index] = num_images;
        samples = color_buffer->Samples();
        ++num_images;
    }
    const size_t num_colors = attachments.size();
    if (depth_buffer) {
        width = std::min(width, is_rescaled ? resolution.ScaleUp(depth_buffer->size.width)
                                            : depth_buffer->size.width);
        height = std::min(height, is_rescaled ? resolution.ScaleUp(depth_buffer->size.height)
                                              : depth_buffer->size.height);
        attachments.push_back(depth_buffer->RenderTarget());
        renderpass_key.depth_format = depth_buffer->format;
        num_layers = std::max(num_layers, depth_buffer->range.extent.layers);
        images[num_images] = depth_buffer->ImageHandle();
        const VkImageSubresourceRange subresource_range = MakeSubresourceRange(depth_buffer);
        image_ranges[num_images] = subresource_range;
        samples = depth_buffer->Samples();
        ++num_images;
        has_depth = (subresource_range.aspectMask & VK_IMAGE_ASPECT_DEPTH_BIT) != 0;
        has_stencil = (subresource_range.aspectMask & VK_IMAGE_ASPECT_STENCIL_BIT) != 0;
    } else {
        renderpass_key.depth_format = PixelFormat::Invalid;
    }
    renderpass_key.samples = samples;

    renderpass = runtime.render_pass_cache.Get(renderpass_key);
    render_area.width = std::min(render_area.width, width);
    render_area.height = std::min(render_area.height, height);

    num_color_buffers = static_cast<u32>(num_colors);
    framebuffer = runtime.device.GetLogical().CreateFramebuffer({
        .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .renderPass = renderpass,
        .attachmentCount = static_cast<u32>(attachments.size()),
        .pAttachments = attachments.data(),
        .width = render_area.width,
        .height = render_area.height,
        .layers = static_cast<u32>(std::max(num_layers, 1)),
    });
}

void TextureCacheRuntime::AccelerateImageUpload(
    Image& image, const StagingBufferRef& map,
    std::span<const VideoCommon::SwizzleParameters> swizzles) {
    if (IsPixelFormatASTC(image.info.format)) {
        return astc_decoder_pass->Assemble(image, map, swizzles);
    }
    ASSERT(false);
}

void TextureCacheRuntime::TransitionImageLayout(Image& image) {
    if (!image.ExchangeInitialization()) {
        VkImageMemoryBarrier barrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_NONE,
            .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image.Handle(),
            .subresourceRange{
                .aspectMask = image.AspectMask(),
                .baseMipLevel = 0,
                .levelCount = VK_REMAINING_MIP_LEVELS,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        };
        scheduler.RequestOutsideRenderPassOperationContext();
        scheduler.Record([barrier](vk::CommandBuffer cmdbuf) {
            cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                   VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier);
        });
    }
}

} // namespace Vulkan
