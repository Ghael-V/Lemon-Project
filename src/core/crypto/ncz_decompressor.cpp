// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <mutex>
#include <optional>
#include <vector>

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

constexpr std::size_t IncompressibleHeaderSize = 0x4000;
constexpr std::size_t SectionEntrySize = 0x40; // offset+size+cryptoType+padding (8 each) + key+counter (16 each)
constexpr std::size_t MaxChunkSize = 0x10000;
constexpr std::array<u8, 8> SectionsMagic{'N', 'C', 'Z', 'S', 'E', 'C', 'T', 'N'};
constexpr std::array<u8, 8> BlockMagic{'N', 'C', 'Z', 'B', 'L', 'O', 'C', 'K'};

struct NczSection {
    u64 offset;
    u64 size;
    u64 crypto_type;
    Key128 crypto_key;
    Key128 crypto_counter;

    [[nodiscard]] bool UsesCtrCrypto() const {
        // Matches the reference nsz tool's Decompressor.py: only these two crypto types are
        // CTR-encrypted NCA bodies. Anything else (e.g. plaintext sections) needs no
        // re-encryption pass.
        return crypto_type == 3 || crypto_type == 4;
    }
};

u64 ReadU64LE(const FileSys::VirtualFile& file, std::size_t offset) {
    const auto bytes = file->ReadBytes(8, offset);
    if (bytes.size() != 8) {
        return 0;
    }
    u64 value = 0;
    for (std::size_t i = 8; i-- > 0;) {
        value = (value << 8) | bytes[i];
    }
    return value;
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

std::optional<std::vector<NczSection>> ParseSections(const FileSys::VirtualFile& ncz_file,
                                                      std::size_t& out_stream_start) {
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

    std::vector<NczSection> sections;
    sections.reserve(section_count);
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
        std::ranges::copy(key, section.crypto_key.begin());
        std::ranges::copy(counter, section.crypto_counter.begin());
        sections.push_back(section);
        entry_offset += SectionEntrySize;
    }

    if (sections.front().offset > IncompressibleHeaderSize) {
        // A gap between the raw header and the first real section - still part of the
        // decompressed stream, just never CTR-encrypted (crypto_type 1 below matches
        // FakeSection's default in the reference tool, i.e. "no crypto applied").
        NczSection fake{};
        fake.offset = IncompressibleHeaderSize;
        fake.size = sections.front().offset - IncompressibleHeaderSize;
        fake.crypto_type = 1;
        sections.insert(sections.begin(), fake);
    }

    out_stream_start = entry_offset;
    return sections;
}

} // Anonymous namespace

FileSys::VirtualFile DecompressNCZ(const FileSys::VirtualFile& ncz_file,
                                   std::string_view output_name) {
    if (!ncz_file || ncz_file->GetSize() < IncompressibleHeaderSize + 16) {
        LOG_ERROR(Crypto, "NCZ file is too small to contain a valid header");
        return nullptr;
    }

    std::size_t stream_start = 0;
    const auto sections_opt = ParseSections(ncz_file, stream_start);
    if (!sections_opt) {
        return nullptr;
    }
    const auto& sections = *sections_opt;

    const auto block_check = ncz_file->ReadBytes(8, stream_start);
    if (block_check.size() == 8 && std::equal(block_check.begin(), block_check.end(), BlockMagic.begin())) {
        // Block-mode NCZ (NCZBLOCK) allows random-access decompression but uses a different
        // per-block framing this hasn't been implemented/verified against a real file for yet -
        // fail loudly rather than silently mis-decode. Solid-mode NCZ (the common case, and what
        // nsz produces by default for .nsz) is unaffected.
        LOG_ERROR(Crypto, "NCZBLOCK (block-compressed) NCZ sections are not yet supported");
        return nullptr;
    }

    // A section's offset can be *before* the incompressible-header boundary (its bytes there
    // overlap the raw header copy already written, rather than extending past it) - the true NCA
    // size is the furthest extent any section actually reaches, not header size + sum(sizes).
    u64 nca_size = IncompressibleHeaderSize;
    for (const auto& section : sections) {
        nca_size = std::max<u64>(nca_size, section.offset + section.size);
    }

    const auto cache_dir = Common::FS::GetEdenPath(Common::FS::EdenPath::CacheDir) / "ncz";
    if (!Common::FS::CreateDirs(cache_dir)) {
        LOG_ERROR(Crypto, "Failed to create NCZ decompression cache directory");
        return nullptr;
    }

    // Must outlive the returned file: RealVfsFile keeps a reference to its owning filesystem and
    // links itself into that filesystem's intrusive reference lists, so a local instance would
    // leave the caller holding a dangling reference (first use then walks freed list memory).
    static FileSys::RealVfsFilesystem real_fs;
    // Game-list scanning runs on multiple threads; two of them decompressing the same NCZ would
    // each truncate the other's output file mid-write.
    static std::mutex decompress_mutex;
    std::scoped_lock lock{decompress_mutex};

    const auto output_path = cache_dir / std::string(output_name);
    const auto output_file = real_fs.CreateFile(output_path.string(), FileSys::OpenMode::ReadWrite);
    if (!output_file || !output_file->Resize(nca_size)) {
        LOG_ERROR(Crypto, "Failed to create NCZ decompression output file");
        return nullptr;
    }

    const auto header_bytes = ncz_file->ReadBytes(IncompressibleHeaderSize, 0);
    if (header_bytes.size() != IncompressibleHeaderSize ||
        output_file->WriteBytes(header_bytes, 0) != IncompressibleHeaderSize) {
        LOG_ERROR(Crypto, "Failed to write NCZ incompressible header to output");
        return nullptr;
    }

    Common::Compression::ZSTDStreamDecompressor decompressor;
    std::size_t compressed_read_pos = stream_start;
    const auto pull_input = [&] {
        std::vector<u8> chunk = ncz_file->ReadBytes(0x100000, compressed_read_pos);
        compressed_read_pos += chunk.size();
        decompressor.FeedInput(chunk);
        return !chunk.empty();
    };

    std::vector<u8> chunk_buffer(MaxChunkSize);
    std::optional<AESCipher<Key128>> cipher;
    bool first_section = true;

    for (const auto& section : sections) {
        std::size_t i = section.offset;
        const std::size_t end = section.offset + section.size;

        if (first_section) {
            first_section = false;
            // The bytes already written verbatim as the raw header must not be re-derived from
            // the compressed stream (they were never part of it to begin with).
            const std::size_t already_covered =
                IncompressibleHeaderSize > sections.front().offset
                    ? IncompressibleHeaderSize - sections.front().offset
                    : 0;
            i += already_covered;
        }

        if (section.UsesCtrCrypto()) {
            cipher.emplace(section.crypto_key, Mode::CTR);
        }

        while (i < end) {
            const std::size_t chunk_size = std::min(MaxChunkSize, end - i);
            std::span<u8> dst(chunk_buffer.data(), chunk_size);

            std::size_t produced = 0;
            while (produced < chunk_size) {
                const std::size_t got = decompressor.Decompress(dst.subspan(produced));
                produced += got;
                if (produced >= chunk_size) {
                    break;
                }
                if (decompressor.HasError()) {
                    LOG_ERROR(Crypto, "zstd decompression error while decoding NCZ body");
                    return nullptr;
                }
                if (!decompressor.NeedsMoreInput()) {
                    // Zero progress with unconsumed input still buffered: zstd genuinely can't do
                    // anything more with what it has (e.g. a finished frame with trailing bytes
                    // that don't start a new one). FeedInput() would silently discard those
                    // unconsumed bytes, desyncing every byte after this point - a real,
                    // unrecoverable mismatch between our section bookkeeping and the actual
                    // stream, not something to paper over by pulling more.
                    LOG_ERROR(Crypto,
                             "NCZ zstd stream stalled with unconsumed input still buffered - "
                             "malformed or truncated NCZ payload");
                    return nullptr;
                }
                if (!pull_input()) {
                    LOG_ERROR(Crypto, "NCZ compressed stream ended before expected NCA size was "
                                      "reached");
                    return nullptr;
                }
            }

            if (section.UsesCtrCrypto()) {
                Key128 iv{};
                UpdateCtrIv(iv, section.crypto_counter, i);
                cipher->SetIV(iv);
                cipher->Transcode(chunk_buffer.data(), chunk_size, chunk_buffer.data(), Op::Encrypt);
            }

            if (output_file->Write(chunk_buffer.data(), chunk_size, i) != chunk_size) {
                LOG_ERROR(Crypto, "Failed writing decompressed NCZ bytes to output file");
                return nullptr;
            }
            i += chunk_size;
        }
    }

    LOG_INFO(Crypto, "Decompressed NCZ {} ({} bytes)", output_name, nca_size);
    return real_fs.OpenFile(output_path.string(), FileSys::OpenMode::Read);
}

} // namespace Core::Crypto
