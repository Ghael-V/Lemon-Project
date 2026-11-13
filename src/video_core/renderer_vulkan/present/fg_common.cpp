// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>

#include "video_core/renderer_vulkan/present/fg_common.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

constexpr VkImageSubresourceRange COLOR_RANGE{
    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
    .baseMipLevel = 0,
    .levelCount = 1,
    .baseArrayLayer = 0,
    .layerCount = 1,
};

vk::Image CreateFgImage(MemoryAllocator& allocator, VkExtent2D extent, VkFormat format) {
    return allocator.CreateImage(VkImageCreateInfo{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {.width = extent.width, .height = extent.height, .depth = 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    });
}

} // Anonymous namespace

FgImage::FgImage(const Device& device, MemoryAllocator& memory_allocator, VkExtent2D extent_,
                 VkFormat format_)
    : extent{std::max(1u, extent_.width), std::max(1u, extent_.height)}, format{format_} {
    image = CreateFgImage(memory_allocator, extent, format);
    view = CreateWrappedImageView(device, image, format);
}

FgBarriers& FgBarriers::Push(FgImage& image, VkAccessFlags src_access, VkAccessFlags dst_access) {
    barriers.push_back(VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = src_access,
        .dstAccessMask = dst_access,
        .oldLayout = image.Layout(),
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image.Handle(),
        .subresourceRange = COLOR_RANGE,
    });
    image.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
    return *this;
}

FgBarriers& FgBarriers::ReadToWrite(FgImage& image) {
    return Push(image, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
}

FgBarriers& FgBarriers::WriteToRead(FgImage& image) {
    return Push(image, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
}

FgBarriers& FgBarriers::DiscardToWrite(VkImage image) {
    barriers.push_back(VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = COLOR_RANGE,
    });
    return *this;
}

void FgBarriers::Build() {
    if (barriers.empty()) {
        return;
    }
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, {}, {}, barriers);
    barriers.clear();
}

FgDescriptorWriter& FgDescriptorWriter::PushImage(VkDescriptorType type, VkSampler sampler,
                                                  VkImageView view) {
    image_infos.push_back(VkDescriptorImageInfo{
        .sampler = sampler,
        .imageView = view,
        .imageLayout = view == VK_NULL_HANDLE ? VK_IMAGE_LAYOUT_UNDEFINED
                                              : VK_IMAGE_LAYOUT_GENERAL,
    });
    writes.push_back(VkWriteDescriptorSet{
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = nullptr,
        .dstSet = set,
        .dstBinding = binding++,
        .dstArrayElement = 0,
        .descriptorCount = 1,
        .descriptorType = type,
        .pImageInfo = &image_infos.back(),
        .pBufferInfo = nullptr,
        .pTexelBufferView = nullptr,
    });
    return *this;
}

FgDescriptorWriter& FgDescriptorWriter::AddSampler(VkSampler sampler) {
    return PushImage(VK_DESCRIPTOR_TYPE_SAMPLER, sampler, VK_NULL_HANDLE);
}

FgDescriptorWriter& FgDescriptorWriter::AddSampledImage(const FgImage& image) {
    return PushImage(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_NULL_HANDLE, image.View());
}

FgDescriptorWriter& FgDescriptorWriter::AddStorageImage(const FgImage& image) {
    return PushImage(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, image.View());
}

FgDescriptorWriter& FgDescriptorWriter::AddStorageView(VkImageView view) {
    return PushImage(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, view);
}

void FgDescriptorWriter::Build(const Device& device) {
    if (writes.empty()) {
        return;
    }
    device.GetLogical().UpdateDescriptorSets(writes, {});
    writes.clear();
}

vk::Sampler CreateFgSampler(const Device& device, VkSamplerAddressMode address_mode) {
    return device.GetLogical().CreateSampler(VkSamplerCreateInfo{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = address_mode,
        .addressModeV = address_mode,
        .addressModeW = address_mode,
        .mipLodBias = 0.0f,
        .anisotropyEnable = VK_FALSE,
        .maxAnisotropy = 0.0f,
        .compareEnable = VK_FALSE,
        .compareOp = VK_COMPARE_OP_NEVER,
        .minLod = 0.0f,
        .maxLod = 0.0f,
        .borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
        .unnormalizedCoordinates = VK_FALSE,
    });
}

vk::DescriptorSetLayout CreateFgDescriptorSetLayout(const Device& device,
                                                    std::span<const VkDescriptorType> types,
                                                    VkShaderStageFlags stage) {
    std::vector<VkDescriptorSetLayoutBinding> bindings(types.size());
    for (size_t i = 0; i < types.size(); ++i) {
        bindings[i] = VkDescriptorSetLayoutBinding{
            .binding = static_cast<u32>(i),
            .descriptorType = types[i],
            .descriptorCount = 1,
            .stageFlags = stage,
            .pImmutableSamplers = nullptr,
        };
    }
    return device.GetLogical().CreateDescriptorSetLayout(VkDescriptorSetLayoutCreateInfo{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .bindingCount = static_cast<u32>(bindings.size()),
        .pBindings = bindings.data(),
    });
}

vk::Pipeline CreateFgComputePipeline(const Device& device, const vk::PipelineLayout& layout,
                                     const vk::ShaderModule& module) {
    return device.GetLogical().CreateComputePipeline(VkComputePipelineCreateInfo{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = *module,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
        .layout = *layout,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = 0,
    });
}

} // namespace Vulkan
