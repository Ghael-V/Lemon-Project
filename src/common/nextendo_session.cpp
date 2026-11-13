// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <mutex>

#include "common/nextendo_session.h"
#include "common/settings.h"

namespace Common::Nextendo {

namespace {
std::mutex g_mutex;
std::optional<Session> g_session;
u64 g_generation = 0;
} // Anonymous namespace

void SetSession(Session session) {
    std::scoped_lock lock{g_mutex};
    if (session.pid == 0 || session.nex_token.empty()) {
        g_session.reset();
    } else {
        g_session = std::move(session);
    }
    ++g_generation;
}

void ClearSession() {
    std::scoped_lock lock{g_mutex};
    g_session.reset();
    ++g_generation;
}

std::optional<Session> GetSession() {
    std::scoped_lock lock{g_mutex};
    return g_session;
}

u64 GetGeneration() {
    std::scoped_lock lock{g_mutex};
    return g_generation;
}

bool IsEnabled() {
    return Settings::values.enable_nextendo.GetValue();
}

} // namespace Common::Nextendo
