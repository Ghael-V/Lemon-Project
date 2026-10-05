// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <cctype>
#include <optional>
#include <string_view>
#include <vector>

#include <fmt/format.h>
#include "common/net/net.h"
#include "common/scm_rev.h"
#include "update_checker.h"

#include "common/logging.h"

namespace {

// A Lemon version tag: "v1.0.1", "v0.3.2-nightly.1", "v0.3". The numbers before the first '-',
// and whether a suffix ("-nightly.1") follows them. Nothing if it is not one (a development build
// reports "<commit>-<branch>").
struct Version {
    std::vector<unsigned> numbers;
    bool has_suffix;
};

std::optional<Version> ParseVersion(std::string_view text) {
    if (!text.empty() && (text.front() == 'v' || text.front() == 'V')) {
        text.remove_prefix(1);
    }
    const auto dash = text.find('-');
    const std::string_view core = text.substr(0, dash);
    Version version{{}, dash != std::string_view::npos};
    unsigned value = 0;
    bool digits = false;
    for (const char c : core) {
        if (std::isdigit(static_cast<unsigned char>(c))) {
            value = value * 10 + static_cast<unsigned>(c - '0');
            digits = true;
        } else if (c == '.' && digits) {
            version.numbers.push_back(value);
            value = 0;
            digits = false;
        } else {
            return std::nullopt;
        }
    }
    if (!digits) {
        return std::nullopt;
    }
    version.numbers.push_back(value);
    return version;
}

// Whether `tag` is a newer version than `build`. A tag or a build that is not a version is
// compared the old way, as different-means-newer.
bool IsNewer(std::string_view tag, std::string_view build) {
    const auto t = ParseVersion(tag);
    const auto b = ParseVersion(build);
    if (!t || !b) {
        return tag != build;
    }
    const size_t count = std::max(t->numbers.size(), b->numbers.size());
    for (size_t i = 0; i < count; i++) {
        const unsigned tv = i < t->numbers.size() ? t->numbers[i] : 0;
        const unsigned bv = i < b->numbers.size() ? b->numbers[i] : 0;
        if (tv != bv) {
            return tv > bv;
        }
    }
    // Same numbers: the release (no suffix) is newer than a prerelease of it ("-nightly.1").
    return !t->has_suffix && b->has_suffix;
}

} // namespace

std::optional<Common::Net::Release> UpdateChecker::GetUpdate() {
    const auto latest = Common::Net::GetLatestRelease();
    if (!latest) return std::nullopt;

    LOG_INFO(Frontend, "Received update {}", latest->title);

    // Every build variant (standard, Lite, ...) follows only its own APK, which the release carries
    // under a name ending in that variant's suffix (see Release::GetPlatformAssets). A release with
    // no APK for this variant - published for another one, or still being uploaded - is not an
    // update for it, and must not send its users to a page with nothing for them to install.
    if (latest->GetPlatformAssets().empty()) {
        LOG_INFO(Frontend, "Release {} has no APK for this build variant, not offering it",
                 latest->tag);
        return std::nullopt;
    }

    // Only a newer version is an update. Comparing for "different" offered a build newer than the
    // latest release (a nightly, a test build) a "new version" that was older than it. The
    // upstream nightly scheme (split on ".", exactly two parts) never matched Lemon's tags at all.
    const std::string tag = latest->tag;
    const std::string build = Common::g_build_version;

    if (IsNewer(tag, build))
        return latest;

    return std::nullopt;
}
