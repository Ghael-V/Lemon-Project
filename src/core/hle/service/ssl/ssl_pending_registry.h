// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Based on citron-nextendo (Copyright 2026 citron Emulator Project, GPL).

#pragma once

#include <functional>

namespace Network {
class SocketBase;
}

namespace Service::SSL {

// A TLS backend can decrypt a whole record in one Read and keep the plaintext the game did not
// ask for yet. bsd's Poll only looks at the raw socket, so it would never report that data as
// readable and the game would wait forever (ACNH hosting, error 2219-1028). The SSL connection
// registers its socket here so bsd can ask about it without depending on the SSL service.
void RegisterPendingCheck(Network::SocketBase* socket, std::function<bool()> has_pending);
void UnregisterPendingCheck(Network::SocketBase* socket);
bool HasSslPendingData(Network::SocketBase* socket);

} // namespace Service::SSL
