// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <memory>
#include <span>
#include <vector>

#include "common/common_funcs.h"
#include "common/common_types.h"

namespace Common::Compression {

/**
 * Compresses a source memory region with Zstandard and returns the compressed data in a vector.
 *
 * @param source            The uncompressed source memory region.
 * @param source_size       The size of the uncompressed source memory region.
 * @param compression_level The used compression level. Should be between 1 and 22.
 *
 * @return the compressed data.
 */
[[nodiscard]] std::vector<u8> CompressDataZSTD(const u8* source, std::size_t source_size,
                                               s32 compression_level);

/**
 * Compresses a source memory region with Zstandard with the default compression level and returns
 * the compressed data in a vector.
 *
 * @param source      The uncompressed source memory region.
 * @param source_size The size of the uncompressed source memory region.
 *
 * @return the compressed data.
 */
[[nodiscard]] std::vector<u8> CompressDataZSTDDefault(const u8* source, std::size_t source_size);

/**
 * Decompresses a source memory region with Zstandard and returns the uncompressed data in a vector.
 *
 * @param compressed the compressed source memory region.
 *
 * @return the decompressed data.
 */
[[nodiscard]] std::vector<u8> DecompressDataZSTD(std::span<const u8> compressed);

/**
 * Incremental Zstandard decompressor for sources too large to hold fully in memory (e.g.
 * multi-gigabyte NCZ payloads). The caller feeds compressed bytes via FeedInput() and pulls
 * decompressed bytes via Decompress(); FeedInput() must only be called again once NeedsMoreInput()
 * is true, i.e. once the previously fed chunk has been fully consumed.
 */
class ZSTDStreamDecompressor {
public:
    ZSTDStreamDecompressor();
    ~ZSTDStreamDecompressor();

    YUZU_NON_COPYABLE(ZSTDStreamDecompressor);
    YUZU_NON_MOVEABLE(ZSTDStreamDecompressor);

    /// Appends more compressed source bytes. Only valid to call when NeedsMoreInput() is true.
    void FeedInput(std::span<const u8> compressed);

    /// True once all bytes previously passed to FeedInput() have been consumed.
    [[nodiscard]] bool NeedsMoreInput() const;

    /**
     * Writes decompressed bytes into `output`.
     *
     * @return the number of bytes actually written. This is less than output.size() only when
     *         NeedsMoreInput() becomes true (more input is required) or the compressed stream
     *         has ended; check NeedsMoreInput() / HasError() to tell those cases apart.
     */
    [[nodiscard]] std::size_t Decompress(std::span<u8> output);

    /// True if the underlying zstd stream reported a decoding error.
    [[nodiscard]] bool HasError() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

} // namespace Common::Compression
