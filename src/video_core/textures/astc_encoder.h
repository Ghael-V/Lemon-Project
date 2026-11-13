// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <span>

#include "common/common_types.h"

namespace Tegra::Texture::ASTC {

/// Encodes one 4x4 block of RGBA8 texels (row-major) into a 16-byte ASTC 4x4 LDR block.
/// Fast single-partition encoder: opaque blocks use RGB endpoints with 8 weight levels; blocks
/// with alpha pick the better of shared RGBA weights or a separate alpha plane.
void EncodeBlock4x4(const std::array<std::array<u8, 4>, 16>& texels, std::span<u8, 16> block);

/// Encodes a tightly packed RGBA8 image (width x height x depth) into ASTC 4x4 blocks, written
/// row by row and slice by slice. Edge blocks repeat the last row and column.
void Encode4x4(std::span<const u8> rgba, u32 width, u32 height, u32 depth, std::span<u8> output);

} // namespace Tegra::Texture::ASTC
