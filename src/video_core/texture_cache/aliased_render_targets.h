// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <cstddef>

#include "common/common_types.h"
#include "video_core/engines/maxwell_3d.h"

namespace VideoCommon {

/**
 * Color slots to leave unbound because another slot targets the same surface.
 *
 * Games can point two color slots at the same surface and mask the writes of one (Zelda BOTW's
 * G-buffer binds slots 1-2 and 3-4 that way). The GPU writes the enabled slot and nothing else,
 * but a Vulkan render pass stores every attachment when it ends, and two attachments on one image
 * are undefined: tilers (Mali) store the stale tile of the unused slot over the written one. One
 * slot per surface is kept: the one being cleared, or one with color writes, else the first.
 *
 * The texture cache leaves these slots out of the framebuffer and the pipeline key gives them no
 * format, so both render passes stay compatible (Mali maps attachments by position).
 *
 * @returns A bit per render target slot.
 */
[[nodiscard]] inline u32 AliasedColorTargets(const Tegra::Engines::Maxwell3D::Regs& regs,
                                             bool for_clear) {
    using RenderTargetFormat = Tegra::RenderTargetFormat;
    const auto active = [&](size_t index) {
        const auto& rt = regs.rt[index];
        return index < regs.rt_control.count && rt.Address() != 0 &&
               rt.format != RenderTargetFormat::NONE;
    };
    const auto writes = [&](size_t index) {
        if (for_clear) {
            return index == regs.clear_surface.RT.Value();
        }
        return regs.color_mask[regs.color_mask_common ? 0 : index].raw != 0;
    };
    u32 dropped = 0;
    for (size_t index = 1; index < regs.rt.size(); ++index) {
        if (!active(index)) {
            continue;
        }
        for (size_t other = 0; other < index; ++other) {
            if ((dropped & (1U << other)) != 0 || !active(other) ||
                regs.rt[other].Address() != regs.rt[index].Address()) {
                continue;
            }
            if (writes(index) && !writes(other)) {
                dropped |= 1U << other;
            } else {
                dropped |= 1U << index;
            }
            break;
        }
    }
    return dropped;
}

} // namespace VideoCommon
