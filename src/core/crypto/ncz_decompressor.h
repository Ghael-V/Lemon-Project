// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string_view>

#include "core/file_sys/vfs/vfs_types.h"

namespace Core::Crypto {

/**
 * Decompresses an NCZ-formatted NCA (the per-NCA payload format used inside .nsz/.xcz containers)
 * into a real, fully-formed NCA file: the incompressible 0x4000-byte header is copied verbatim,
 * the zstd-compressed body is streamed out, and any section whose crypto type is CTR-encrypted
 * (type 3 or 4) is re-encrypted with its stored per-section key/counter - NCZ stores sections
 * decrypted because encrypted data doesn't compress, so the rest of the pipeline (which expects
 * genuinely-encrypted NCA bytes and decrypts them itself via the normal titlekey-driven path)
 * needs that encryption put back.
 *
 * The result is written to a real file under the cache directory rather than held in memory or
 * exposed as a lazy streaming VfsFile, since decompressed NCAs are commonly multiple gigabytes.
 *
 * @param ncz_file    The raw NCZ payload (e.g. a "*.ncz" entry read out of an NSZ's PFS0).
 * @param output_name File name (not a full path) to give the decompressed NCA under the cache
 *                     directory, e.g. the original entry's name with ".ncz" replaced by ".nca".
 *
 * @return a VirtualFile for the decompressed NCA, or nullptr on failure (malformed NCZ header,
 *         zstd error, unsupported NCZBLOCK layout, or I/O failure writing the output).
 */
[[nodiscard]] FileSys::VirtualFile DecompressNCZ(const FileSys::VirtualFile& ncz_file,
                                                 std::string_view output_name);

} // namespace Core::Crypto
