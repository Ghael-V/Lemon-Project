// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <vector>
#include <zstd.h>

#include "common/zstd_compression.h"

namespace Common::Compression {

std::vector<u8> CompressDataZSTD(const u8* source, std::size_t source_size, s32 compression_level) {
    compression_level = std::clamp(compression_level, 1, ZSTD_maxCLevel());

    const std::size_t max_compressed_size = ZSTD_compressBound(source_size);
    std::vector<u8> compressed(max_compressed_size);

    const std::size_t compressed_size =
        ZSTD_compress(compressed.data(), compressed.size(), source, source_size, compression_level);

    if (ZSTD_isError(compressed_size)) {
        // Compression failed
        return {};
    }

    compressed.resize(compressed_size);

    return compressed;
}

std::vector<u8> CompressDataZSTDDefault(const u8* source, std::size_t source_size) {
    return CompressDataZSTD(source, source_size, ZSTD_CLEVEL_DEFAULT);
}

std::vector<u8> DecompressDataZSTD(std::span<const u8> compressed) {
    const std::size_t decompressed_size =
        ZSTD_getFrameContentSize(compressed.data(), compressed.size());
    std::vector<u8> decompressed(decompressed_size);

    const std::size_t uncompressed_result_size = ZSTD_decompress(
        decompressed.data(), decompressed.size(), compressed.data(), compressed.size());

    if (decompressed_size != uncompressed_result_size || ZSTD_isError(uncompressed_result_size)) {
        // Decompression failed
        return {};
    }
    return decompressed;
}

struct ZSTDStreamDecompressor::Impl {
    Impl() : stream(ZSTD_createDStream()) {
        // NCZ files may be compressed with --long (large window mode); this is cheap to allow
        // unconditionally and matches what the reference nsz tool's decompressor accepts.
        ZSTD_DCtx_setParameter(stream, ZSTD_d_windowLogMax, 31);
    }

    ~Impl() {
        ZSTD_freeDStream(stream);
    }

    ZSTD_DStream* stream;
    std::vector<u8> input_buffer;
    ZSTD_inBuffer input{};
    bool error = false;
};

ZSTDStreamDecompressor::ZSTDStreamDecompressor() : impl(std::make_unique<Impl>()) {}

ZSTDStreamDecompressor::~ZSTDStreamDecompressor() = default;

void ZSTDStreamDecompressor::FeedInput(std::span<const u8> compressed) {
    impl->input_buffer.assign(compressed.begin(), compressed.end());
    impl->input.src = impl->input_buffer.data();
    impl->input.size = impl->input_buffer.size();
    impl->input.pos = 0;
}

bool ZSTDStreamDecompressor::NeedsMoreInput() const {
    return impl->input.pos >= impl->input.size;
}

std::size_t ZSTDStreamDecompressor::Decompress(std::span<u8> output) {
    if (impl->error || output.empty()) {
        return 0;
    }

    ZSTD_outBuffer out{};
    out.dst = output.data();
    out.size = output.size();
    out.pos = 0;

    while (out.pos < out.size && !NeedsMoreInput()) {
        // ZSTD_decompressStream's return value is a hint (bytes suggested for the next call, or
        // 0 for "frame complete") - it is NOT a progress count, so detect stalls by comparing the
        // buffer positions directly rather than trusting the return value's magnitude.
        const std::size_t in_pos_before = impl->input.pos;
        const std::size_t out_pos_before = out.pos;

        const std::size_t result = ZSTD_decompressStream(impl->stream, &out, &impl->input);
        if (ZSTD_isError(result)) {
            impl->error = true;
            break;
        }
        if (impl->input.pos == in_pos_before && out.pos == out_pos_before) {
            // No forward progress at all (e.g. a completed frame with input left over that
            // doesn't start a new one) - stop here rather than spin; the caller feeds more input
            // or treats this as the end of the stream.
            break;
        }
    }
    return out.pos;
}

bool ZSTDStreamDecompressor::HasError() const {
    return impl->error;
}

} // namespace Common::Compression
