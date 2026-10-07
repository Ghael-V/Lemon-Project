// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <vector>

#include "common/common_types.h"

namespace Core {
class System;
}

// Nextendo Network account identity, as the account service hands it to the games.
namespace Service::Account::Nextendo {

/// Stub network service account ID for a profile with no Nextendo session: the servers turn
/// it away, so that profile simply stays offline.
constexpr u64 UnlinkedAccountId = 0xcafe;

/// The network service account ID the games log in with: the Nextendo PID when signed in.
u64 GetNetworkServiceAccountId();

/// The BAAS id_token the games forward in their NEX login. It is a real RS256 JWT carrying
/// the signed Nextendo game token in its "nnex" claim, which the servers verify.
std::vector<u8> GetIdToken(Core::System& system);

} // namespace Service::Account::Nextendo
