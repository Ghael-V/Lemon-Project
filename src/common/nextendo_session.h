// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <optional>
#include <string>

#include "common/common_types.h"

// The signed-in Nextendo Network account, as handed over by the frontend before a game boots.
// Kept in memory only: the frontend owns the sign-in and refreshes the game token itself.
// The PID and the token both act as credentials, so neither may ever be logged.
namespace Common::Nextendo {

struct Session {
    u64 pid{};
    std::string username;
    std::string nex_token; ///< Signed "nx2" game-login token, valid for 24 hours.
};

void SetSession(Session session);
void ClearSession();
std::optional<Session> GetSession();

/// Bumped on every SetSession/ClearSession, so callers caching derived data can tell it changed.
u64 GetGeneration();

/// True when the user turned Nextendo Network on, signed in or not.
bool IsEnabled();

} // namespace Common::Nextendo
