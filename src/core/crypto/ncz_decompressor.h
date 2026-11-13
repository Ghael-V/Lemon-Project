// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string_view>

#include "core/file_sys/vfs/vfs_types.h"

namespace Core::Crypto {

/**
 * Opens an NCZ-formatted NCA (the per-NCA payload format inside .nsz/.xcz containers, solid or
 * NCZBLOCK) as the real NCA it encodes: the incompressible 0x4000-byte header is copied verbatim,
 * the zstd body is decoded, and CTR sections (crypto type 3/4) are re-encrypted with their stored
 * key/counter - NCZ stores them decrypted since encrypted data doesn't compress, while the rest of
 * the pipeline expects real NCA bytes and decrypts them itself.
 *
 * NCZBLOCK payloads are read in place: each read decodes only the blocks it covers, nothing is
 * written to disk. A solid payload is one zstd stream with no random access, and building the NCA
 * already reads its ExeFS (placed at the end), so it is decoded whole on first use into a file
 * under the cache directory. That file is reused by later calls as long as it was decoded from an
 * identical NCZ; the least recently used ones are evicted once the cache grows past its size cap.
 *
 * @param ncz_file    The raw NCZ payload (e.g. a "*.ncz" entry read out of an NSZ's PFS0).
 * @param output_name File name (not a full path) to give the decompressed NCA under the cache
 *                    directory - the original entry's name with ".ncz" replaced by ".nca".
 *
 * @return a VirtualFile for the NCA, or nullptr if the NCZ is malformed or the cache file can't be
 *         created. Decoding errors past that point surface as short reads.
 */
[[nodiscard]] FileSys::VirtualFile DecompressNCZ(const FileSys::VirtualFile& ncz_file,
                                                 std::string_view output_name);

} // namespace Core::Crypto
