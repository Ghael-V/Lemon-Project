// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include "core/hle/service/service.h"

namespace Core {
class System;
}

namespace Service::Sockets {

class SFDNSRES final : public ServiceFramework<SFDNSRES> {
public:
    explicit SFDNSRES(Core::System& system_);
    ~SFDNSRES() override;

private:
    void GetHostByNameRequest(HLERequestContext& ctx);
    void GetGaiStringErrorRequest(HLERequestContext& ctx);
    void GetHostByNameRequestWithOptions(HLERequestContext& ctx);
    void GetAddrInfoRequest(HLERequestContext& ctx);
    void GetAddrInfoRequestWithOptions(HLERequestContext& ctx);
    void RequestCancelHandleRequest(HLERequestContext& ctx);
    void CancelRequest(HLERequestContext& ctx);
    void ResolverSetOptionRequest(HLERequestContext& ctx);
};

/// Nextendo Network: the Nintendo host whose lookup was redirected to this address, so TLS can
/// still present the right SNI when the game only hands over the address. Empty if none.
std::string GetRedirectedHostForIp(const std::string& ip);

class DNS_PRIV final : public ServiceFramework<DNS_PRIV> {
public:
    explicit DNS_PRIV(Core::System& system_);
    ~DNS_PRIV() override;
};

} // namespace Service::Sockets
