// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <fmt/format.h>
#include "common/net/net.h"
#include "common/scm_rev.h"
#include "update_checker.h"

#include "common/logging.h"

std::optional<Common::Net::Release> UpdateChecker::GetUpdate() {
    const auto latest = Common::Net::GetLatestRelease();
    if (!latest) return std::nullopt;

    LOG_INFO(Frontend, "Received update {}", latest->title);

    // Previously, nightly builds compared versions by splitting the tag on "." and requiring
    // exactly two segments - a scheme borrowed from upstream (Citra/Azahar) that doesn't fit
    // Lemon's own tags (e.g. "v0.2.6-experimental.2", "v0.3" split into 3 and 2 segments
    // respectively), so that comparison silently bailed out and never reported an update.
    // Compare the tag and build strings directly instead.
    const std::string tag = latest->tag;
    const std::string build = Common::g_build_version;

    if (tag != build)
        return latest;

    return std::nullopt;
}
