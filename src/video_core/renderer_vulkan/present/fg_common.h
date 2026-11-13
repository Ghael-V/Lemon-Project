// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <deque>
#include <initializer_list>
#include <span>
#include <vector>

#include "common/common_types.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;

/// Most generated frames per presented frame the frame generator handles.
constexpr size_t FG_MAX_TARGETS = 4;

/// Where generated frame [generation] (0-based) of [generation_count] sits between two
/// presented frames, from 0 (the previous one) to 1 (the new one).
[[nodiscard]] constexpr f32 FgTimestamp(size_t generation, size_t generation_count) {
    return static_cast<f32>(generation + 1) / static_cast<f32>(generation_count + 1);
}

/// A 2D image the frame generator's compute passes read and write (sampled and storage), with
/// its view and the layout it was last left in.
class FgImage {
public:
    FgImage() = default;
    FgImage(const Device& device, MemoryAllocator& memory_allocator, VkExtent2D extent,
            VkFormat format);

    [[nodiscard]] VkImage Handle() const {
        return *image;
    }
    [[nodiscard]] VkImageView View() const {
        return *view;
    }
    [[nodiscard]] VkExtent2D Extent() const {
        return extent;
    }
    [[nodiscard]] VkFormat Format() const {
        return format;
    }
    [[nodiscard]] VkImageLayout Layout() const {
        return layout;
    }
    void SetLayout(VkImageLayout new_layout) {
        layout = new_layout;
    }

private:
    vk::Image image;
    vk::ImageView view;
    VkExtent2D extent{};
    VkFormat format{VK_FORMAT_UNDEFINED};
    VkImageLayout layout{VK_IMAGE_LAYOUT_UNDEFINED};
};

/// Collects image barriers between compute passes and records them in one call. Every image
/// ends in GENERAL.
class FgBarriers {
public:
    explicit FgBarriers(vk::CommandBuffer cmdbuf_) : cmdbuf{cmdbuf_} {}

    /// Earlier shader reads finish before the next pass writes it.
    FgBarriers& ReadToWrite(FgImage& image);

    /// Earlier shader writes are visible to the next pass's reads.
    FgBarriers& WriteToRead(FgImage& image);

    /// Its old contents don't matter: the next pass overwrites it.
    FgBarriers& DiscardToWrite(VkImage image);

    template <typename Range>
    FgBarriers& ReadToWriteAll(Range& images) {
        for (auto& image : images) {
            ReadToWrite(image);
        }
        return *this;
    }

    void Build();

private:
    FgBarriers& Push(FgImage& image, VkAccessFlags src_access, VkAccessFlags dst_access);

    vk::CommandBuffer cmdbuf;
    std::vector<VkImageMemoryBarrier> barriers;
};

/// Writes a descriptor set binding by binding, in the order the calls are made.
class FgDescriptorWriter {
public:
    explicit FgDescriptorWriter(VkDescriptorSet set_) : set{set_} {}

    FgDescriptorWriter& AddSampler(VkSampler sampler);
    FgDescriptorWriter& AddSampledImage(const FgImage& image);
    FgDescriptorWriter& AddStorageImage(const FgImage& image);
    FgDescriptorWriter& AddStorageView(VkImageView view);

    void Build(const Device& device);

private:
    FgDescriptorWriter& PushImage(VkDescriptorType type, VkSampler sampler, VkImageView view);

    VkDescriptorSet set;
    u32 binding{};
    // A deque keeps the infos where the writes point to as more are added.
    std::deque<VkDescriptorImageInfo> image_infos;
    std::vector<VkWriteDescriptorSet> writes;
};

/// A linear sampler with the given addressing.
[[nodiscard]] vk::Sampler CreateFgSampler(const Device& device, VkSamplerAddressMode address_mode);

/// A set layout with one binding per type, in order, for the given stage.
[[nodiscard]] vk::DescriptorSetLayout CreateFgDescriptorSetLayout(
    const Device& device, std::span<const VkDescriptorType> types, VkShaderStageFlags stage);

/// A compute pipeline running [module]'s main.
[[nodiscard]] vk::Pipeline CreateFgComputePipeline(const Device& device,
                                                   const vk::PipelineLayout& layout,
                                                   const vk::ShaderModule& module);

} // namespace Vulkan
