// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "core/hle/service/ipc_helpers.h"
#include "core/hle/service/sockets/nsd.h"

#include "common/string_util.h"

namespace Service::Sockets {

constexpr Result ResultOverflow{ErrorModule::NSD, 6};

// This is nn::oe::ServerEnvironmentType
enum class ServerEnvironmentType : u8 {
    Dd,
    Lp,
    Sd,
    Sp,
    Dp,
};

// This is nn::nsd::EnvironmentIdentifier
struct EnvironmentIdentifier {
    std::array<u8, 8> identifier;
};
static_assert(sizeof(EnvironmentIdentifier) == 0x8);

NSD::NSD(Core::System& system_, const char* name) : ServiceFramework{system_, name} {
    // clang-format off
    static const FunctionInfo functions[] = {
        {5, nullptr, "GetSettingUrl"},
        {10, nullptr, "GetSettingName"},
        {11, &NSD::GetEnvironmentIdentifier, "GetEnvironmentIdentifier"},
        {12, nullptr, "GetDeviceId"},
        {13, nullptr, "DeleteSettings"},
        {14, nullptr, "ImportSettings"},
        {15, &NSD::SetChangeEnvironmentIdentifierDisabled, "SetChangeEnvironmentIdentifierDisabled"},
        {20, &NSD::Resolve, "Resolve"},
        {21, &NSD::ResolveEx, "ResolveEx"},
        {30, nullptr, "GetNasServiceSetting"},
        {31, nullptr, "GetNasServiceSettingEx"},
        {40, nullptr, "GetNasRequestFqdn"},
        {41, nullptr, "GetNasRequestFqdnEx"},
        {42, nullptr, "GetNasApiFqdn"},
        {43, nullptr, "GetNasApiFqdnEx"},
        {50, nullptr, "GetCurrentSetting"},
        {51, nullptr, "WriteTestParameter"},
        {52, nullptr, "ReadTestParameter"},
        {60, nullptr, "ReadSaveDataFromFsForTest"},
        {61, nullptr, "WriteSaveDataToFsForTest"},
        {62, nullptr, "DeleteSaveDataOfFsForTest"},
        {63, nullptr, "IsChangeEnvironmentIdentifierDisabled"},
        {64, nullptr, "SetWithoutDomainExchangeFqdns"},
        {100, &NSD::GetApplicationServerEnvironmentType, "GetApplicationServerEnvironmentType"},
        {101, nullptr, "SetApplicationServerEnvironmentType"},
        {102, nullptr, "DeleteApplicationServerEnvironmentType"},
    };
    // clang-format on

    RegisterHandlers(functions);
}

std::string ResolveNsdFqdn(const std::string& fqdn_in) {
    // The real implementation substitutes the environment ("lp1" on retail) for the '%'
    // placeholder and points the account front-ends at the BAAS host behind them.
    // Mapping taken from citron-nextendo (Copyright 2026 citron Emulator Project).
    if (fqdn_in == "api.accounts.nintendo.com" || fqdn_in == "accounts.nintendo.com") {
        return "e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com";
    }
    if (fqdn_in == "e97b8a9d672e4ce4845ec6947cd66ef6-sb-api.accounts.nintendo.com" ||
        fqdn_in == "e97b8a9d672e4ce4845ec6947cd66ef6-sb.accounts.nintendo.com") {
        return "e97b8a9d672e4ce4845ec6947cd66ef6-sb.baas.nintendo.com";
    }
    std::string fqdn = fqdn_in;
    if (const auto pos = fqdn.find('%'); pos != std::string::npos) {
        fqdn.replace(pos, 1, "lp1");
    }
    return fqdn;
}

static std::string ResolveImpl(const std::string& fqdn_in) {
    const std::string fqdn = ResolveNsdFqdn(fqdn_in);
    LOG_DEBUG(Service, "called, fqdn_in={} fqdn_out={}", fqdn_in, fqdn);
    return fqdn;
}

static Result ResolveCommon(const std::string& fqdn_in, std::array<char, 0x100>& fqdn_out) {
    const auto res = ResolveImpl(fqdn_in);
    if (res.size() >= fqdn_out.size()) {
        return ResultOverflow;
    }
    std::memcpy(fqdn_out.data(), res.c_str(), res.size() + 1);
    return ResultSuccess;
}

void NSD::SetChangeEnvironmentIdentifierDisabled(HLERequestContext& ctx) {
    IPC::RequestParser rp{ctx};
    const bool disabled = rp.Pop<bool>();

    LOG_WARNING(Service, "(STUBBED) called, disabled={}", disabled);

    IPC::ResponseBuilder rb{ctx, 1};
    rb.Push(ResultSuccess);
}

void NSD::Resolve(HLERequestContext& ctx) {
    const std::string fqdn_in = Common::StringFromBuffer(ctx.ReadBuffer(0));

    std::array<char, 0x100> fqdn_out{};
    const Result res = ResolveCommon(fqdn_in, fqdn_out);

    ctx.WriteBuffer(fqdn_out);
    IPC::ResponseBuilder rb{ctx, 2};
    rb.Push(res);
}

void NSD::ResolveEx(HLERequestContext& ctx) {
    const std::string fqdn_in = Common::StringFromBuffer(ctx.ReadBuffer(0));

    std::array<char, 0x100> fqdn_out;
    const Result res = ResolveCommon(fqdn_in, fqdn_out);

    if (res.IsError()) {
        IPC::ResponseBuilder rb{ctx, 2};
        rb.Push(res);
        return;
    }

    ctx.WriteBuffer(fqdn_out);
    IPC::ResponseBuilder rb{ctx, 4};
    rb.Push(ResultSuccess);
    rb.Push(ResultSuccess);
}

void NSD::GetEnvironmentIdentifier(HLERequestContext& ctx) {
    constexpr EnvironmentIdentifier lp1 = {
        .identifier = {'l', 'p', '1', '\0', '\0', '\0', '\0', '\0'}};
    ctx.WriteBuffer(lp1);

    IPC::ResponseBuilder rb{ctx, 2};
    rb.Push(ResultSuccess);
}

void NSD::GetApplicationServerEnvironmentType(HLERequestContext& ctx) {
    IPC::ResponseBuilder rb{ctx, 3};
    rb.Push(ResultSuccess);
    rb.Push(static_cast<u32>(ServerEnvironmentType::Lp));
}

NSD::~NSD() = default;

} // namespace Service::Sockets
