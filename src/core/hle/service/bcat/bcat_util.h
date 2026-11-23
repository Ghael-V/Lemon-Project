// SPDX-FileCopyrightText: Copyright 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <algorithm>
#include <array>
#include <cctype>
#include <mbedtls/md5.h>

#include "core/hle/service/bcat/bcat_result.h"
#include "core/hle/service/bcat/bcat_types.h"

namespace Service::BCAT {

// A valid name is non-empty and ends with a null character inside the buffer; up to that null it
// holds only numbers, letters, underscores, and a hyphen for a directory or a period for a file.
// Whatever follows the null is not part of the name: games may leave old bytes there (Splatoon 2).
constexpr Result VerifyNameValidInternal(std::array<char, 0x20> name, char match_char) {
    const auto end = std::find(name.begin(), name.end(), '\0');
    const auto bad_chars = std::count_if(name.begin(), end, [match_char](char c) {
        return !std::isalnum(static_cast<u8>(c)) && c != '_' && c != match_char;
    });
    if (end == name.end() || end == name.begin() || bad_chars != 0) {
        LOG_ERROR(Service_BCAT, "Name passed was invalid!");
        return ResultInvalidArgument;
    }

    return ResultSuccess;
}

constexpr Result VerifyNameValidDir(DirectoryName name) {
    return VerifyNameValidInternal(name, '-');
}

constexpr Result VerifyNameValidFile(FileName name) {
    return VerifyNameValidInternal(name, '.');
}

} // namespace Service::BCAT
