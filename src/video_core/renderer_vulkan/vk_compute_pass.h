// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <optional>
#include <span>
#include <utility>

#include "common/common_types.h"
#include "video_core/engines/maxwell_3d.h"
#include "video_core/renderer_vulkan/vk_descriptor_pool.h"
#include "video_core/renderer_vulkan/vk_update_descriptor.h"
#include "video_core/surface.h"
#include "video_core/texture_cache/types.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"
#include "video_core/texture_cache/accelerated_swizzle.h"

namespace VideoCommon {
struct SwizzleParameters;
}

namespace Vulkan {

using VideoCommon::Accelerated::BlockLinearSwizzle3DParams;

class Device;
class StagingBufferPool;
class Scheduler;
class Image;
struct StagingBufferRef;

class ComputePass {
public:
    explicit ComputePass(const Device& device, Scheduler& scheduler, DescriptorPool& descriptor_pool,
                         vk::Span<VkDescriptorSetLayoutBinding> bindings,
                         vk::Span<VkDescriptorUpdateTemplateEntry> templates,
                         const DescriptorBankInfo& bank_info,
                         vk::Span<VkPushConstantRange> push_constants, std::span<const u32> code,
                         std::optional<u32> optional_subgroup_size = std::nullopt);
    ~ComputePass();

protected:
    const Device& device;
    vk::DescriptorUpdateTemplate descriptor_template;
    vk::PipelineLayout layout;
    vk::Pipeline pipeline;
    vk::DescriptorSetLayout descriptor_set_layout;
    DescriptorAllocator descriptor_allocator;

private:
    vk::ShaderModule module;
};

class Uint8Pass final : public ComputePass {
public:
    explicit Uint8Pass(const Device& device_, Scheduler& scheduler_,
                       DescriptorPool& descriptor_pool_, StagingBufferPool& staging_buffer_pool_,
                       ComputePassDescriptorQueue& compute_pass_descriptor_queue_);
    ~Uint8Pass();

    /// Assemble uint8 indices into an uint16 index buffer
    /// Returns a pair with the staging buffer, and the offset where the assembled data is
    std::pair<VkBuffer, VkDeviceSize> Assemble(u32 num_vertices, VkBuffer src_buffer,
                                               u32 src_offset);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

class QuadIndexedPass final : public ComputePass {
public:
    explicit QuadIndexedPass(const Device& device_, Scheduler& scheduler_,
                             DescriptorPool& descriptor_pool_,
                             StagingBufferPool& staging_buffer_pool_,
                             ComputePassDescriptorQueue& compute_pass_descriptor_queue_);
    ~QuadIndexedPass();

    std::pair<VkBuffer, VkDeviceSize> Assemble(
        Tegra::Engines::Maxwell3D::Regs::IndexFormat index_format, u32 num_vertices,
        u32 base_vertex, VkBuffer src_buffer, u32 src_offset, bool is_strip);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

class ConditionalRenderingResolvePass final : public ComputePass {
public:
    explicit ConditionalRenderingResolvePass(
        const Device& device_, Scheduler& scheduler_, DescriptorPool& descriptor_pool_,
        ComputePassDescriptorQueue& compute_pass_descriptor_queue_);

    void Resolve(VkBuffer dst_buffer, VkBuffer src_buffer, u32 src_offset, bool compare_to_zero);

private:
    Scheduler& scheduler;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

class QueriesPrefixScanPass final : public ComputePass {
public:
    explicit QueriesPrefixScanPass(const Device& device_, Scheduler& scheduler_,
                                   DescriptorPool& descriptor_pool_,
                                   ComputePassDescriptorQueue& compute_pass_descriptor_queue_);

    void Run(VkBuffer accumulation_buffer, VkBuffer dst_buffer, VkBuffer src_buffer,
             size_t number_of_sums, size_t min_accumulation_limit, size_t max_accumulation_limit);

private:
    Scheduler& scheduler;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

class ASTCDecoderPass final : public ComputePass {
public:
    explicit ASTCDecoderPass(const Device& device_, Scheduler& scheduler_,
                             DescriptorPool& descriptor_pool_,
                             StagingBufferPool& staging_buffer_pool_,
                             ComputePassDescriptorQueue& compute_pass_descriptor_queue_,
                             MemoryAllocator& memory_allocator_);
    ~ASTCDecoderPass();

    void Assemble(Image& image, const StagingBufferRef& map,
                  std::span<const VideoCommon::SwizzleParameters> swizzles);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
    MemoryAllocator& memory_allocator;
};

/// Encodes RGBA8 texels (BCn textures decoded on the CPU) into ASTC 4x4 blocks, for GPUs that
/// can't sample BCn. See video_core/textures/astc_encoder.cpp.
class BcnAstcEncodePass final : public ComputePass {
public:
    explicit BcnAstcEncodePass(const Device& device_, Scheduler& scheduler_,
                               DescriptorPool& descriptor_pool_,
                               StagingBufferPool& staging_buffer_pool_,
                               ComputePassDescriptorQueue& compute_pass_descriptor_queue_,
                               MemoryAllocator& memory_allocator_);
    ~BcnAstcEncodePass();

    /// Encodes the tightly packed RGBA8 levels described by `copies` (at src_offset +
    /// buffer_offset) and rewrites the copies to describe the ASTC blocks in the returned buffer.
    std::pair<VkBuffer, VkDeviceSize> Encode(VkBuffer src_buffer, VkDeviceSize src_offset,
                                             std::span<VideoCommon::BufferImageCopy> copies);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
    vk::Buffer tables;
    VkDeviceSize tables_size;
};

/// Encodes BC4 / BC5 textures decoded on the CPU (R8 / RG8) into EAC R11 / RG11 blocks, for GPUs
/// that can't sample BCn but read EAC. See host_shaders/vulkan_bcn_eac_encode.comp.
class BcnEacEncodePass final : public ComputePass {
public:
    explicit BcnEacEncodePass(const Device& device_, Scheduler& scheduler_,
                              DescriptorPool& descriptor_pool_,
                              StagingBufferPool& staging_buffer_pool_,
                              ComputePassDescriptorQueue& compute_pass_descriptor_queue_);
    ~BcnEacEncodePass();

    /// Encodes the tightly packed R8 (channels = 1) or RG8 (channels = 2) levels described by
    /// `copies` and rewrites the copies to describe the EAC blocks in the returned buffer.
    std::pair<VkBuffer, VkDeviceSize> Encode(VkBuffer src_buffer, VkDeviceSize src_offset,
                                             std::span<VideoCommon::BufferImageCopy> copies,
                                             u32 channels, bool is_signed);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

/// Decodes raw BC1-7 blocks into the texels the CPU decoder produces (RGBA8, R8, RG8 or RGBA16F
/// for BC6H), for BCn images stored decoded or recompressed that receive raw blocks from the GPU.
/// See host_shaders/vulkan_bcn_decode.comp.
class BcnDecodePass final : public ComputePass {
public:
    explicit BcnDecodePass(const Device& device_, Scheduler& scheduler_,
                           DescriptorPool& descriptor_pool_,
                           StagingBufferPool& staging_buffer_pool_,
                           ComputePassDescriptorQueue& compute_pass_descriptor_queue_);
    ~BcnDecodePass();

    [[nodiscard]] static bool IsSupported(VideoCore::Surface::PixelFormat format);

    /// Decodes the raw blocks described by `copies` (tightly packed, as UnswizzleImage lays them
    /// out, at src_offset + buffer_offset) and rewrites the copies to describe the decoded
    /// texels in the returned buffer, whose rows are padded to whole blocks.
    std::pair<VkBuffer, VkDeviceSize> Decode(VkBuffer src_buffer, VkDeviceSize src_offset,
                                             VkDeviceSize src_size,
                                             std::span<VideoCommon::BufferImageCopy> copies,
                                             VideoCore::Surface::PixelFormat format);

private:
    /// The pipeline for one shader format number, built the first time it is needed.
    VkPipeline FormatPipeline(u32 format_number);

    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
    vk::ShaderModule decode_module;
    // One pipeline per format (a specialization constant): a run-time switch over every format
    // made the Adreno compiler emit a BC1/BC3 path that hangs the GPU.
    std::array<vk::Pipeline, 8> format_pipelines;
};

class BlockLinearUnswizzle3DPass final : public ComputePass {
public:
    explicit BlockLinearUnswizzle3DPass(const Device& device_, Scheduler& scheduler_,
                             DescriptorPool& descriptor_pool_,
                             StagingBufferPool& staging_buffer_pool_,
                             ComputePassDescriptorQueue& compute_pass_descriptor_queue_);
    ~BlockLinearUnswizzle3DPass();

    void Unswizzle(Image& image,
                   const StagingBufferRef& swizzled,
                   std::span<const VideoCommon::SwizzleParameters> swizzles,
                   u32 z_start, u32 z_count);

    void UnswizzleChunk(
        Image& image,
        const StagingBufferRef& swizzled,
        const VideoCommon::SwizzleParameters& sw,
        const BlockLinearSwizzle3DParams& params,
        u32 blocks_x, u32 blocks_y,
        u32 z_start, u32 z_count);

private:
    Scheduler& scheduler;
    StagingBufferPool& staging_buffer_pool;
    ComputePassDescriptorQueue& compute_pass_descriptor_queue;
};

} // namespace Vulkan
