// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <cstring>
#include <filesystem>
#include <functional>
#include <limits>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "common/div_ceil.h"
#include "common/fs/file.h"
#include "common/fs/fs.h"
#include "common/fs/path_util.h"
#include "common/logging.h"
#include "common/zstd_compression.h"
#include "core/crypto/aes_util.h"
#include "core/crypto/key_manager.h"
#include "core/crypto/ncz_decompressor.h"
#include "core/file_sys/vfs/vfs.h"
#include "core/file_sys/vfs/vfs_real.h"

namespace Core::Crypto {

namespace {

constexpr u64 IncompressibleHeaderSize = 0x4000;
constexpr std::size_t SectionEntrySize = 0x40; // offset+size+cryptoType+padding (8 each) + key+counter (16 each)
constexpr std::size_t MaxChunkSize = 0x10000;
constexpr std::size_t CompressedReadSize = 0x100000;
constexpr std::size_t BlockHeaderSize = 0x18; // magic, version, type, unused, exponent, count, size
constexpr u32 MaxBlockSizeExponent = 28;      // 256 MiB; nsz defaults to 20 (1 MiB)
// Decompressed blocks kept around per open NCZBLOCK file; games read assets sequentially, so this
// mostly saves re-decoding a block that one read ended in and the next one starts in.
constexpr u64 BlockCacheBudget = 16ULL * 1024 * 1024;
constexpr u64 ProgressLogInterval = 512ULL * 1024 * 1024;
// Fully decoded solid NCAs are kept so later sessions boot without decompressing again, but they
// live in persistent storage that the OS never trims - past this total the least recently used go.
constexpr u64 MaxCompleteCacheSize = 24ULL * 1024 * 1024 * 1024;
constexpr std::string_view MarkerSuffix = ".complete";
constexpr std::array<u8, 8> SectionsMagic{'N', 'C', 'Z', 'S', 'E', 'C', 'T', 'N'};
constexpr std::array<u8, 8> BlockMagic{'N', 'C', 'Z', 'B', 'L', 'O', 'C', 'K'};

struct NczSection {
    u64 offset;
    u64 size;
    u64 crypto_type;
    Key128 crypto_key;
    Key128 crypto_counter;

    // Where this section's body bytes land in the NCA, and where they start in the decompressed
    // body stream. Bytes before 0x4000 come from the raw header copy instead, never the stream.
    u64 data_begin;
    u64 data_end;
    u64 stream_offset;

    [[nodiscard]] bool UsesCtrCrypto() const {
        // Matches the reference nsz tool's Decompressor.py: only these two crypto types are
        // CTR-encrypted NCA bodies. Anything else (e.g. plaintext sections) needs no
        // re-encryption pass.
        return crypto_type == 3 || crypto_type == 4;
    }
};

struct NczLayout {
    std::vector<NczSection> sections;
    u64 stream_start;
    u64 body_size; // total decompressed body stream bytes
    u64 nca_size;
};

u64 LoadLE(const u8* bytes, std::size_t size) {
    u64 value = 0;
    for (std::size_t i = size; i-- > 0;) {
        value = (value << 8) | bytes[i];
    }
    return value;
}

u64 ReadU64LE(const FileSys::VirtualFile& file, std::size_t offset) {
    const auto bytes = file->ReadBytes(8, offset);
    return bytes.size() == 8 ? LoadLE(bytes.data(), 8) : 0;
}

// Mirrors CTREncryptionLayer::UpdateIV exactly (src/core/crypto/ctr_encryption_layer.cpp): the
// upper 8 bytes of the 16-byte IV are the section's stored counter, left untouched; the lower 8
// bytes are the absolute NCA byte offset (not section-relative) divided by the AES block size,
// big-endian. This is also exactly what the reference nsz tool's AESCTR.seek() computes.
void UpdateCtrIv(Key128& iv, const Key128& base_counter, std::size_t absolute_offset) {
    iv = base_counter;
    std::size_t block_index = absolute_offset >> 4;
    for (std::size_t i = 0; i < 8; ++i) {
        iv[16 - i - 1] = static_cast<u8>(block_index & 0xFF);
        block_index >>= 8;
    }
}

// NCZ stores CTR sections decrypted (encrypted data doesn't compress); the rest of the pipeline
// expects real NCA bytes and decrypts them itself. `data` holds the NCA bytes starting at
// `nca_offset`, which may sit anywhere inside an AES block.
void ReencryptCtr(AESCipher<Key128>& cipher, const NczSection& section, std::span<u8> data,
                  u64 nca_offset, std::vector<u8>& scratch) {
    const std::size_t lead = static_cast<std::size_t>(nca_offset & 0xF);
    Key128 iv{};
    UpdateCtrIv(iv, section.crypto_counter, nca_offset - lead);
    cipher.SetIV(iv);
    if (lead == 0) {
        cipher.Transcode(data.data(), data.size(), data.data(), Op::Encrypt);
        return;
    }
    // Start the keystream at the enclosing block boundary; the lead bytes are discarded.
    scratch.assign(lead, 0);
    scratch.insert(scratch.end(), data.begin(), data.end());
    cipher.Transcode(scratch.data(), scratch.size(), scratch.data(), Op::Encrypt);
    std::memcpy(data.data(), scratch.data() + lead, data.size());
}

std::optional<NczLayout> ParseLayout(const FileSys::VirtualFile& ncz_file) {
    const auto magic = ncz_file->ReadBytes(8, IncompressibleHeaderSize);
    if (magic.size() != 8 || !std::equal(magic.begin(), magic.end(), SectionsMagic.begin())) {
        LOG_ERROR(Crypto, "NCZ file is missing the NCZSECTN magic - not a valid NCZ payload");
        return std::nullopt;
    }

    const u64 section_count = ReadU64LE(ncz_file, IncompressibleHeaderSize + 8);
    if (section_count == 0 || section_count > 64) {
        // Real NCAs have a handful of sections at most; a huge count means we misparsed
        // something upstream rather than a legitimate file.
        LOG_ERROR(Crypto, "NCZ file reports an implausible section count ({})", section_count);
        return std::nullopt;
    }

    NczLayout layout{};
    layout.sections.reserve(section_count + 1);
    std::size_t entry_offset = IncompressibleHeaderSize + 16;
    for (u64 i = 0; i < section_count; ++i) {
        NczSection section{};
        section.offset = ReadU64LE(ncz_file, entry_offset);
        section.size = ReadU64LE(ncz_file, entry_offset + 8);
        section.crypto_type = ReadU64LE(ncz_file, entry_offset + 16);
        // +24: 8 bytes of padding, unused.
        const auto key = ncz_file->ReadBytes(16, entry_offset + 32);
        const auto counter = ncz_file->ReadBytes(16, entry_offset + 48);
        if (key.size() != 16 || counter.size() != 16) {
            LOG_ERROR(Crypto, "NCZ section table is truncated");
            return std::nullopt;
        }
        if (section.size > std::numeric_limits<u64>::max() - section.offset) {
            LOG_ERROR(Crypto, "NCZ section {} overflows the NCA address space", i);
            return std::nullopt;
        }
        std::ranges::copy(key, section.crypto_key.begin());
        std::ranges::copy(counter, section.crypto_counter.begin());
        layout.sections.push_back(section);
        entry_offset += SectionEntrySize;
    }
    layout.stream_start = entry_offset;

    if (layout.sections.front().offset > IncompressibleHeaderSize) {
        // A gap between the raw header and the first real section - still part of the
        // decompressed stream, just never CTR-encrypted (crypto_type 1 below matches
        // FakeSection's default in the reference tool, i.e. "no crypto applied").
        NczSection fake{};
        fake.offset = IncompressibleHeaderSize;
        fake.size = layout.sections.front().offset - IncompressibleHeaderSize;
        fake.crypto_type = 1;
        layout.sections.insert(layout.sections.begin(), fake);
    }

    // The body stream is every section's bytes back to back, minus whatever the first one shares
    // with the raw header (a section's offset can be *before* 0x4000). The reference tool writes
    // the sections out sequentially, so they must be in order and must not overlap.
    u64 previous_end = IncompressibleHeaderSize;
    u64 stream_offset = 0;
    for (auto& section : layout.sections) {
        const u64 end = section.offset + section.size;
        section.data_begin = std::max(section.offset, IncompressibleHeaderSize);
        section.data_end = std::max(end, section.data_begin);
        if (section.data_begin < previous_end) {
            LOG_ERROR(Crypto, "NCZ sections are out of order or overlap");
            return std::nullopt;
        }
        section.stream_offset = stream_offset;
        stream_offset += section.data_end - section.data_begin;
        previous_end = section.data_end;
    }
    layout.body_size = stream_offset;
    layout.nca_size = previous_end;
    return layout;
}

// One zstd stream over the whole body (nsz's default for .nsz). Strictly sequential.
class SolidBodyReader {
public:
    SolidBodyReader(FileSys::VirtualFile source_, u64 stream_start)
        : source{std::move(source_)}, read_pos{stream_start} {}

    /// Fills all of `dst` with the next body bytes. False on corrupt or truncated input.
    bool Read(std::span<u8> dst) {
        std::size_t produced = 0;
        while (produced < dst.size()) {
            produced += decompressor.Decompress(dst.subspan(produced));
            if (produced >= dst.size()) {
                break;
            }
            if (decompressor.HasError()) {
                LOG_ERROR(Crypto, "zstd decompression error while decoding NCZ body");
                return false;
            }
            if (!decompressor.NeedsMoreInput()) {
                // Zero progress with unconsumed input still buffered: zstd can't do anything
                // more with it (e.g. a finished frame with trailing bytes that don't start a new
                // one). FeedInput() would silently discard those bytes, desyncing everything
                // after this point - a malformed payload, not something to paper over.
                LOG_ERROR(Crypto, "NCZ zstd stream stalled with unconsumed input still buffered - "
                                  "malformed or truncated NCZ payload");
                return false;
            }
            std::vector<u8> chunk = source->ReadBytes(CompressedReadSize, read_pos);
            if (chunk.empty()) {
                LOG_ERROR(Crypto, "NCZ compressed stream ended before the expected NCA size");
                return false;
            }
            read_pos += chunk.size();
            decompressor.FeedInput(chunk);
        }
        return true;
    }

private:
    FileSys::VirtualFile source;
    u64 read_pos;
    Common::Compression::ZSTDStreamDecompressor decompressor;
};

// Independently compressed fixed-size blocks (nsz's default for .xcz, -B for .nsz). Layout per the
// reference tool's Header.Block/BlockDecompressorReader: a header, one u32 compressed size per
// block, then the blocks. Every block decompresses to 2^exponent bytes except a shorter last one,
// and a block whose compressed size isn't smaller than that is stored raw. Random access, which is
// the whole point of the format: any body offset only needs its own block decoded.
class BlockBodyReader {
public:
    static std::unique_ptr<BlockBodyReader> Create(FileSys::VirtualFile source, u64 header_offset,
                                                    u64 required_body_size) {
        const u64 source_size = source->GetSize();
        const auto header = source->ReadBytes(BlockHeaderSize, header_offset);
        if (header.size() != BlockHeaderSize) {
            LOG_ERROR(Crypto, "NCZBLOCK header is truncated");
            return nullptr;
        }
        const u32 exponent = header[11];
        const u64 block_count = LoadLE(header.data() + 12, 4);
        const u64 decompressed_size = LoadLE(header.data() + 16, 8);
        if (exponent < 14 || exponent > 32) {
            LOG_ERROR(Crypto, "Corrupt NCZBLOCK header: block size exponent {}", exponent);
            return nullptr;
        }
        if (exponent > MaxBlockSizeExponent) {
            LOG_ERROR(Crypto, "NCZBLOCK block size 2^{} is larger than supported", exponent);
            return nullptr;
        }
        const u64 block_size = 1ULL << exponent;
        const u64 sizes_offset = header_offset + BlockHeaderSize;
        if (decompressed_size < required_body_size || block_count == 0 ||
            block_count != Common::DivCeil(decompressed_size, block_size) ||
            sizes_offset > source_size || block_count > (source_size - sizes_offset) / 4) {
            LOG_ERROR(Crypto, "Corrupt NCZBLOCK header ({} blocks of 2^{}, {} bytes)",
                      block_count, exponent, decompressed_size);
            return nullptr;
        }

        auto reader = std::unique_ptr<BlockBodyReader>(new BlockBodyReader(std::move(source)));
        const auto sizes = reader->source->ReadBytes(block_count * 4, sizes_offset);
        if (sizes.size() != block_count * 4) {
            LOG_ERROR(Crypto, "NCZBLOCK size table is truncated");
            return nullptr;
        }
        reader->block_size = block_size;
        reader->decompressed_size = decompressed_size;
        reader->cache_capacity = std::max<u64>(1, BlockCacheBudget / block_size);
        reader->offsets.reserve(block_count);
        reader->compressed_sizes.reserve(block_count);
        u64 offset = sizes_offset + block_count * 4;
        for (u64 i = 0; i < block_count; ++i) {
            const u32 compressed_size = static_cast<u32>(LoadLE(sizes.data() + i * 4, 4));
            if (compressed_size > source_size - std::min(offset, source_size)) {
                LOG_ERROR(Crypto, "NCZBLOCK block {} runs past the end of the file", i);
                return nullptr;
            }
            reader->offsets.push_back(offset);
            reader->compressed_sizes.push_back(compressed_size);
            offset += compressed_size;
        }
        return reader;
    }

    /// Fills `dst` with body bytes starting at `stream_offset`. False on corrupt input. Not
    /// thread-safe; the owner serializes calls.
    bool ReadAt(u64 stream_offset, std::span<u8> dst) {
        std::size_t done = 0;
        while (done < dst.size()) {
            const u64 position = stream_offset + done;
            const u64 block_id = position / block_size;
            const std::vector<u8>* block = GetBlock(block_id);
            if (block == nullptr) {
                return false;
            }
            const u64 in_block = position - block_id * block_size;
            if (in_block >= block->size()) {
                LOG_ERROR(Crypto, "NCZBLOCK body ended before the expected NCA size");
                return false;
            }
            const std::size_t count =
                static_cast<std::size_t>(std::min<u64>(dst.size() - done, block->size() - in_block));
            std::memcpy(dst.data() + done, block->data() + in_block, count);
            done += count;
        }
        return true;
    }

private:
    explicit BlockBodyReader(FileSys::VirtualFile source_) : source{std::move(source_)} {}

    const std::vector<u8>* GetBlock(u64 block_id) {
        for (auto it = cache.begin(); it != cache.end(); ++it) {
            if (it->first == block_id) {
                cache.splice(cache.begin(), cache, it);
                return &cache.front().second;
            }
        }
        if (block_id >= offsets.size()) {
            LOG_ERROR(Crypto, "NCZBLOCK body ended before the expected NCA size");
            return nullptr;
        }

        // Recycle the least recently used buffer once the budget is reached.
        std::vector<u8> buffer;
        if (cache.size() >= cache_capacity) {
            buffer = std::move(cache.back().second);
            cache.pop_back();
        }
        const u64 remainder = decompressed_size % block_size;
        const u64 expected =
            (block_id == offsets.size() - 1 && remainder != 0) ? remainder : block_size;
        try {
            buffer.resize(static_cast<std::size_t>(expected));
        } catch (const std::bad_alloc&) {
            LOG_ERROR(Crypto, "Out of memory allocating a {} byte NCZBLOCK buffer", expected);
            return nullptr;
        }
        const u32 compressed_size = compressed_sizes[block_id];
        if (compressed_size < expected) {
            const auto compressed = source->ReadBytes(compressed_size, offsets[block_id]);
            if (compressed.size() != compressed_size ||
                Common::Compression::DecompressDataZSTD(compressed, buffer) != expected) {
                LOG_ERROR(Crypto, "Failed to decompress NCZBLOCK block {}", block_id);
                return nullptr;
            }
        } else if (source->Read(buffer.data(), buffer.size(), offsets[block_id]) !=
                   buffer.size()) {
            LOG_ERROR(Crypto, "NCZBLOCK stored block {} is truncated", block_id);
            return nullptr;
        }
        cache.emplace_front(block_id, std::move(buffer));
        return &cache.front().second;
    }

    FileSys::VirtualFile source;
    u64 block_size{};
    u64 decompressed_size{};
    u64 cache_capacity{};
    std::vector<u64> offsets;
    std::vector<u32> compressed_sizes;
    std::list<std::pair<u64, std::vector<u8>>> cache;
};

// Serves an NCZBLOCK NCA straight from its blocks: the raw header from memory, each section's
// bytes decoded and re-encrypted on demand, zeros in any gap between sections (what the reference
// tool leaves there too). Nothing is written to disk, and a game-list scan - which touches the NCA
// header plus each section's filesystem header, the ExeFS one usually near the very end - only
// decodes the handful of blocks it actually reads.
class BlockNczFile final : public FileSys::VfsFile {
public:
    BlockNczFile(std::string name_, std::vector<u8> header_, NczLayout layout_,
                 std::unique_ptr<BlockBodyReader> body_)
        : name{std::move(name_)}, header{std::move(header_)}, layout{std::move(layout_)},
          body{std::move(body_)} {
        ciphers.reserve(layout.sections.size());
        for (const auto& section : layout.sections) {
            ciphers.push_back(section.UsesCtrCrypto()
                                  ? std::make_unique<AESCipher<Key128>>(section.crypto_key,
                                                                        Mode::CTR)
                                  : nullptr);
        }
    }

    std::string GetName() const override {
        return name;
    }
    std::size_t GetSize() const override {
        return static_cast<std::size_t>(layout.nca_size);
    }
    bool Resize(std::size_t) override {
        return false;
    }
    FileSys::VirtualDir GetContainingDirectory() const override {
        return nullptr;
    }
    bool IsWritable() const override {
        return false;
    }
    bool IsReadable() const override {
        return true;
    }
    std::size_t Read(u8* data, std::size_t length, std::size_t offset) const override {
        if (offset >= layout.nca_size) {
            return 0;
        }
        length = static_cast<std::size_t>(std::min<u64>(length, layout.nca_size - offset));
        const u64 end = offset + length;
        u64 pos = offset;

        if (pos < IncompressibleHeaderSize) {
            const u64 count = std::min(end, IncompressibleHeaderSize) - pos;
            std::memcpy(data, header.data() + pos, static_cast<std::size_t>(count));
            pos += count;
        }

        std::scoped_lock lock{mutex};
        for (std::size_t i = 0; i < layout.sections.size() && pos < end; ++i) {
            const NczSection& section = layout.sections[i];
            if (section.data_end <= pos) {
                continue;
            }
            if (pos < section.data_begin) {
                const u64 gap_end = std::min(end, section.data_begin);
                std::memset(data + (pos - offset), 0, static_cast<std::size_t>(gap_end - pos));
                pos = gap_end;
                if (pos >= end) {
                    break;
                }
            }
            const u64 piece_end = std::min(end, section.data_end);
            const std::span<u8> piece(data + (pos - offset),
                                      static_cast<std::size_t>(piece_end - pos));
            if (!body->ReadAt(section.stream_offset + (pos - section.data_begin), piece)) {
                LOG_ERROR(Crypto, "NCZ {}: failed to decode NCA bytes at {:#x}", name, pos);
                return static_cast<std::size_t>(pos - offset);
            }
            if (ciphers[i]) {
                ReencryptCtr(*ciphers[i], section, piece, pos, scratch);
            }
            pos = piece_end;
        }
        return length;
    }
    std::size_t Write(const u8*, std::size_t, std::size_t) override {
        return 0;
    }
    bool Rename(std::string_view) override {
        return false;
    }

private:
    const std::string name;
    const std::vector<u8> header;
    const NczLayout layout;
    mutable std::mutex mutex;
    const std::unique_ptr<BlockBodyReader> body;
    std::vector<std::unique_ptr<AESCipher<Key128>>> ciphers;
    mutable std::vector<u8> scratch;
};

FileSys::RealVfsFilesystem& CacheFilesystem() {
    // Must outlive every file it hands out: RealVfsFile keeps a reference to its owning
    // filesystem and links itself into that filesystem's intrusive reference lists.
    static FileSys::RealVfsFilesystem filesystem;
    return filesystem;
}

// Decode of one solid NCZ into its cache file. A solid body is a single zstd stream, so reaching
// any byte means decoding everything before it - and constructing the NCA already reads its
// ExeFS, which Nintendo places at the end. The whole file is therefore decoded on the first body
// read (once per content, thanks to the cache).
class SolidNczState {
public:
    SolidNczState(std::string name_, NczLayout layout_, std::unique_ptr<SolidBodyReader> body_,
                  FileSys::VirtualFile output_, std::filesystem::path marker_path_,
                  std::string fingerprint_)
        : name{std::move(name_)}, layout{std::move(layout_)}, body{std::move(body_)},
          output{std::move(output_)}, marker_path{std::move(marker_path_)},
          fingerprint{std::move(fingerprint_)}, chunk_buffer(MaxChunkSize) {}

    [[nodiscard]] u64 Size() const {
        return layout.nca_size;
    }

    std::size_t Read(u8* data, std::size_t length, std::size_t offset) {
        std::scoped_lock lock{mutex};
        if (offset >= layout.nca_size) {
            return 0;
        }
        length = static_cast<std::size_t>(std::min<u64>(length, layout.nca_size - offset));
        const u64 needed = offset + length;
        if (needed > frontier) {
            Decode();
            if (frontier < needed) {
                // Decoding failed partway - only serve what is actually valid.
                if (offset >= frontier) {
                    return 0;
                }
                length = static_cast<std::size_t>(frontier - offset);
            }
        }
        return output->Read(data, length, offset);
    }

private:
    void Decode() {
        if (failed || frontier >= layout.nca_size) {
            return;
        }
        LOG_INFO(Crypto, "Decompressing NCZ {} ({} MiB)...", name, layout.nca_size >> 20);
        u64 next_progress_log = ProgressLogInterval;
        std::optional<AESCipher<Key128>> cipher;
        std::vector<u8> scratch;
        for (const NczSection& section : layout.sections) {
            if (section.UsesCtrCrypto()) {
                cipher.emplace(section.crypto_key, Mode::CTR);
            }
            for (u64 cursor = section.data_begin; cursor < section.data_end;) {
                const auto chunk_size =
                    static_cast<std::size_t>(std::min<u64>(MaxChunkSize, section.data_end - cursor));
                const std::span<u8> chunk(chunk_buffer.data(), chunk_size);
                if (!body->Read(chunk)) {
                    failed = true;
                    return;
                }
                if (section.UsesCtrCrypto()) {
                    ReencryptCtr(*cipher, section, chunk, cursor, scratch);
                }
                if (output->Write(chunk.data(), chunk_size, cursor) != chunk_size) {
                    LOG_ERROR(Crypto, "NCZ {}: failed writing decompressed bytes", name);
                    failed = true;
                    return;
                }
                cursor += chunk_size;
                // Gaps between sections stay zero from the up-front Resize.
                frontier = cursor;
                if (frontier >= next_progress_log) {
                    LOG_INFO(Crypto, "Decompressing NCZ {}: {}/{} MiB", name, frontier >> 20,
                             layout.nca_size >> 20);
                    next_progress_log += ProgressLogInterval;
                }
            }
        }
        frontier = layout.nca_size;
        // Only a fully decoded file gets the marker that lets later sessions skip all this.
        if (Common::FS::WriteStringToFile(marker_path, Common::FS::FileType::BinaryFile,
                                          fingerprint) != fingerprint.size()) {
            LOG_WARNING(Crypto, "NCZ {}: could not write the completion marker", name);
        }
        LOG_INFO(Crypto, "Decompressed NCZ {} ({} bytes)", name, layout.nca_size);
    }

    std::mutex mutex;
    const std::string name;
    const NczLayout layout;
    const std::unique_ptr<SolidBodyReader> body;
    const FileSys::VirtualFile output;
    const std::filesystem::path marker_path;
    const std::string fingerprint;
    std::vector<u8> chunk_buffer;
    u64 frontier = IncompressibleHeaderSize;
    bool failed = false;
};

class SolidNczFile final : public FileSys::VfsFile {
public:
    SolidNczFile(std::shared_ptr<SolidNczState> state_, std::string name_)
        : state{std::move(state_)}, name{std::move(name_)} {}

    std::string GetName() const override {
        return name;
    }
    std::size_t GetSize() const override {
        return static_cast<std::size_t>(state->Size());
    }
    bool Resize(std::size_t) override {
        return false;
    }
    FileSys::VirtualDir GetContainingDirectory() const override {
        return nullptr;
    }
    bool IsWritable() const override {
        return false;
    }
    bool IsReadable() const override {
        return true;
    }
    std::size_t Read(u8* data, std::size_t length, std::size_t offset) const override {
        return state->Read(data, length, offset);
    }
    std::size_t Write(const u8*, std::size_t, std::size_t) override {
        return 0;
    }
    bool Rename(std::string_view) override {
        return false;
    }

private:
    const std::shared_ptr<SolidNczState> state;
    const std::string name;
};

using Registry = std::map<std::string, std::weak_ptr<SolidNczState>>;

// Drops interrupted decodes (no marker) and, least recently used first, complete ones beyond
// MaxCompleteCacheSize. Runs under the registry lock, so files still being decoded are known.
void PruneCache(const std::filesystem::path& cache_dir, const Registry& registry) {
    struct CompleteEntry {
        std::filesystem::file_time_type last_used;
        std::filesystem::path path;
        u64 size;
    };
    std::vector<CompleteEntry> complete;
    std::error_code ec;
    for (const auto& entry : std::filesystem::directory_iterator(cache_dir, ec)) {
        std::error_code entry_ec;
        if (!entry.is_regular_file(entry_ec)) {
            continue;
        }
        const auto& path = entry.path();
        const std::string file_name = path.filename().string();
        if (file_name.ends_with(MarkerSuffix)) {
            const auto data_path =
                path.parent_path() / file_name.substr(0, file_name.size() - MarkerSuffix.size());
            if (!std::filesystem::exists(data_path, entry_ec)) {
                std::filesystem::remove(path, entry_ec);
            }
            continue;
        }
        if (registry.contains(file_name)) {
            continue;
        }
        auto marker_path = path;
        marker_path += MarkerSuffix;
        const auto last_used = std::filesystem::last_write_time(marker_path, entry_ec);
        if (entry_ec) {
            // Interrupted decode - it restarts from scratch anyway, so it's only taking space.
            std::filesystem::remove(path, entry_ec);
            continue;
        }
        complete.push_back({last_used, path, static_cast<u64>(entry.file_size(entry_ec))});
    }

    std::ranges::sort(complete, std::ranges::greater{}, &CompleteEntry::last_used);
    u64 total = 0;
    for (const auto& entry : complete) {
        total += entry.size;
        if (total <= MaxCompleteCacheSize) {
            continue;
        }
        LOG_INFO(Crypto, "Evicting decompressed NCZ {} from the cache",
                 entry.path.filename().string());
        auto marker_path = entry.path;
        marker_path += MarkerSuffix;
        std::filesystem::remove(marker_path, ec);
        std::filesystem::remove(entry.path, ec);
    }
}

FileSys::VirtualFile OpenSolid(const FileSys::VirtualFile& ncz_file, std::string name,
                               NczLayout layout) {
    const auto cache_dir = Common::FS::GetEdenPath(Common::FS::EdenPath::CacheDir) / "ncz";
    const auto output_path = cache_dir / name;
    auto marker_path = output_path;
    marker_path += MarkerSuffix;

    // NCA names are meant to be content hashes, but dumps whose NCA was modified without renaming
    // it exist, so a cached file is only trusted if it was decoded from this very NCZ: raw header,
    // section table and compressed size all identical.
    std::string fingerprint;
    {
        const auto head = ncz_file->ReadBytes(layout.stream_start, 0);
        const u64 ncz_size = ncz_file->GetSize();
        fingerprint.assign(head.begin(), head.end());
        fingerprint.append(reinterpret_cast<const char*>(&ncz_size), sizeof(ncz_size));
    }

    // One decoder per NCA: game-list scanning runs on multiple threads, and two decoders writing
    // the same cache file would each truncate the other's output.
    static std::mutex registry_mutex;
    static Registry registry;
    std::scoped_lock lock{registry_mutex};

    if (const auto it = registry.find(name); it != registry.end()) {
        if (auto state = it->second.lock()) {
            return std::make_shared<SolidNczFile>(std::move(state), name);
        }
        registry.erase(it);
    }

    if (Common::FS::IsFile(output_path) && Common::FS::GetSize(output_path) == layout.nca_size &&
        Common::FS::IsFile(marker_path) &&
        Common::FS::ReadStringFromFile(marker_path, Common::FS::FileType::BinaryFile) ==
            fingerprint) {
        if (auto cached =
                CacheFilesystem().OpenFile(output_path.string(), FileSys::OpenMode::Read)) {
            // The marker's timestamp is the LRU key for PruneCache.
            std::error_code ec;
            std::filesystem::last_write_time(
                marker_path, std::filesystem::file_time_type::clock::now(), ec);
            return cached;
        }
    }

    if (!Common::FS::CreateDirs(cache_dir)) {
        LOG_ERROR(Crypto, "Failed to create NCZ decompression cache directory");
        return nullptr;
    }
    Common::FS::RemoveFile(marker_path);
    std::erase_if(registry, [](const auto& entry) { return entry.second.expired(); });
    PruneCache(cache_dir, registry);
    auto output = CacheFilesystem().CreateFile(output_path.string(), FileSys::OpenMode::ReadWrite);
    if (!output || !output->Resize(layout.nca_size)) {
        LOG_ERROR(Crypto, "Failed to create NCZ decompression output file");
        return nullptr;
    }

    const auto header_bytes = ncz_file->ReadBytes(IncompressibleHeaderSize, 0);
    if (header_bytes.size() != IncompressibleHeaderSize ||
        output->WriteBytes(header_bytes, 0) != IncompressibleHeaderSize) {
        LOG_ERROR(Crypto, "Failed to write NCZ incompressible header to output");
        return nullptr;
    }

    auto body = std::make_unique<SolidBodyReader>(ncz_file, layout.stream_start);
    auto state = std::make_shared<SolidNczState>(name, std::move(layout), std::move(body),
                                                 std::move(output), std::move(marker_path),
                                                 std::move(fingerprint));
    registry[name] = state;
    return std::make_shared<SolidNczFile>(std::move(state), std::move(name));
}

} // Anonymous namespace

FileSys::VirtualFile DecompressNCZ(const FileSys::VirtualFile& ncz_file,
                                   std::string_view output_name) {
    if (!ncz_file || ncz_file->GetSize() < IncompressibleHeaderSize + 16) {
        LOG_ERROR(Crypto, "NCZ file is too small to contain a valid header");
        return nullptr;
    }

    auto layout = ParseLayout(ncz_file);
    if (!layout) {
        return nullptr;
    }

    std::string name{output_name};
    const auto block_check = ncz_file->ReadBytes(BlockMagic.size(), layout->stream_start);
    if (block_check.size() != BlockMagic.size() ||
        !std::equal(block_check.begin(), block_check.end(), BlockMagic.begin())) {
        return OpenSolid(ncz_file, std::move(name), std::move(*layout));
    }

    auto body = BlockBodyReader::Create(ncz_file, layout->stream_start, layout->body_size);
    auto header = ncz_file->ReadBytes(IncompressibleHeaderSize, 0);
    if (!body || header.size() != IncompressibleHeaderSize) {
        return nullptr;
    }
    return std::make_shared<BlockNczFile>(std::move(name), std::move(header), std::move(*layout),
                                          std::move(body));
}

} // namespace Core::Crypto
