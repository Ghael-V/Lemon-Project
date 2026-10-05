// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <cstddef>

#include "video_core/renderer_vulkan/vk_command_pool.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

constexpr size_t COMMAND_BUFFER_POOL_SIZE = 4;

struct CommandPool::Pool {
    vk::CommandPool handle;
    vk::CommandBuffers cmdbufs;
};

CommandPool::CommandPool(MasterSemaphore& master_semaphore_, const Device& device_)
    : ResourcePool(master_semaphore_, COMMAND_BUFFER_POOL_SIZE), device{device_} {}

CommandPool::~CommandPool() = default;

void CommandPool::Allocate(size_t begin, size_t end) {
    // Command buffers are committed, recorded and executed every single usage cycle. Each one has
    // a pool of its own, reset as a whole when the buffer is committed again: an implicit
    // per-buffer reset (vkBeginCommandBuffer on a RESET_COMMAND_BUFFER pool) made Qualcomm's
    // driver free all of the buffer's memory and allocate it again during the next recording.
    for (size_t i = begin; i < end; ++i) {
        Pool& pool = pools.emplace_back();
        pool.handle = device.GetLogical().CreateCommandPool({
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,
            .queueFamilyIndex = device.GetGraphicsFamily(),
        });
        pool.cmdbufs = pool.handle.Allocate(1);
    }
}

VkCommandBuffer CommandPool::Commit() {
    const size_t index = CommitResource();
    Pool& pool = pools[index];
    pool.handle.Reset();
    return pool.cmdbufs[0];
}

} // namespace Vulkan
