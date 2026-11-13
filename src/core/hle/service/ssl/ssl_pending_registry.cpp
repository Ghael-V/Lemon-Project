// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Based on citron-nextendo (Copyright 2026 citron Emulator Project, GPL).

#include <mutex>
#include <unordered_map>

#include "core/hle/service/ssl/ssl_pending_registry.h"

namespace Service::SSL {

namespace {
std::mutex g_mutex;
std::unordered_map<Network::SocketBase*, std::function<bool()>> g_checks;
} // Anonymous namespace

void RegisterPendingCheck(Network::SocketBase* socket, std::function<bool()> has_pending) {
    if (socket == nullptr) {
        return;
    }
    std::scoped_lock lock{g_mutex};
    g_checks[socket] = std::move(has_pending);
}

void UnregisterPendingCheck(Network::SocketBase* socket) {
    std::scoped_lock lock{g_mutex};
    g_checks.erase(socket);
}

bool HasSslPendingData(Network::SocketBase* socket) {
    std::scoped_lock lock{g_mutex};
    const auto it = g_checks.find(socket);
    return it != g_checks.end() && it->second();
}

} // namespace Service::SSL
