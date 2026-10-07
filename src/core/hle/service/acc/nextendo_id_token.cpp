// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Token layout follows Nextendo's reference emulator (Ryujinx-Nextendo ManagerServer.cs) and
// citron-nextendo (Copyright 2026 citron Emulator Project, GPL).

#include <chrono>
#include <mutex>
#include <span>
#include <string>
#include <string_view>

#include <fmt/format.h>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/rsa.h>

#include "common/hex_util.h"
#include "common/logging.h"
#include "common/nextendo_session.h"
#include "common/uuid.h"
#include "core/core.h"
#include "core/file_sys/control_metadata.h"
#include "core/file_sys/patch_manager.h"
#include "core/hle/service/acc/nextendo_id_token.h"

namespace Service::Account::Nextendo {

namespace {

constexpr std::string_view BaasIssuer =
    "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com";
constexpr std::string_view BaasJku =
    "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com/1.0.0/certificates";
constexpr std::string_view BaasAudience = "ed9e2f05d286f7b8";
constexpr std::string_view BaasKeyId = "nextendo-baas-key-1";
constexpr u64 PokemonScarlet = 0x0100A3D008C5C000ULL;

std::string Base64Url(std::span<const u8> data) {
    static constexpr std::string_view alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    out.reserve((data.size() + 2) / 3 * 4);
    for (size_t i = 0; i < data.size(); i += 3) {
        const size_t remaining = data.size() - i;
        const u32 triple = (u32{data[i]} << 16) | (remaining > 1 ? u32{data[i + 1]} << 8 : 0) |
                           (remaining > 2 ? u32{data[i + 2]} : 0);
        out += alphabet[(triple >> 18) & 0x3F];
        out += alphabet[(triple >> 12) & 0x3F];
        if (remaining > 1) {
            out += alphabet[(triple >> 6) & 0x3F];
        }
        if (remaining > 2) {
            out += alphabet[triple & 0x3F];
        }
    }
    return out;
}

std::string Base64Url(std::string_view text) {
    return Base64Url(std::span{reinterpret_cast<const u8*>(text.data()), text.size()});
}

std::string RandomHex(size_t bytes) {
    std::vector<u8> buffer(bytes);
    RAND_bytes(buffer.data(), static_cast<int>(buffer.size()));
    return Common::HexToString(buffer, false);
}

// Only titles that verify the token locally (Splatoon 2) need Nextendo's own key, which is not
// public. Everything else accepts any key, so a per-run one does.
EVP_PKEY* SigningKey() {
    static EVP_PKEY* const key = [] {
        EVP_PKEY* generated = EVP_RSA_gen(2048);
        if (generated == nullptr) {
            LOG_ERROR(Service_ACC, "Nextendo: could not generate the id_token signing key");
        }
        return generated;
    }();
    return key;
}

std::string SignRs256(std::string_view input) {
    EVP_PKEY* key = SigningKey();
    if (key == nullptr) {
        return {};
    }
    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    if (ctx == nullptr) {
        return {};
    }
    std::string signature;
    size_t length = 0;
    const auto* data = reinterpret_cast<const u8*>(input.data());
    if (EVP_DigestSignInit(ctx, nullptr, EVP_sha256(), nullptr, key) == 1 &&
        EVP_DigestSign(ctx, nullptr, &length, data, input.size()) == 1) {
        std::vector<u8> raw(length);
        if (EVP_DigestSign(ctx, raw.data(), &length, data, input.size()) == 1) {
            raw.resize(length);
            signature = Base64Url(raw);
        }
    }
    EVP_MD_CTX_free(ctx);
    if (signature.empty()) {
        LOG_ERROR(Service_ACC, "Nextendo: could not sign the id_token");
    }
    return signature;
}

std::string InstalledVersion(Core::System& system, u64 program_id) {
    if (program_id == 0) {
        return {};
    }
    const FileSys::PatchManager pm{program_id, system.GetFileSystemController(),
                                   system.GetContentProvider()};
    const auto nacp = pm.GetControlMetadata().first;
    return nacp != nullptr ? nacp->GetVersionString() : std::string{};
}

std::string BuildIdToken(const std::string& nex_token, const std::string& version,
                         u64 program_id) {
    const auto now = std::chrono::duration_cast<std::chrono::seconds>(
                         std::chrono::system_clock::now().time_since_epoch())
                         .count();
    const std::string device_id = RandomHex(0x10);

    const nlohmann::json header{
        {"alg", "RS256"}, {"kid", BaasKeyId}, {"typ", "id_token"}, {"jku", BaasJku}};

    nlohmann::json payload{
        {"sub", RandomHex(0x10)},
        {"aud", BaasAudience},
        {"iss", BaasIssuer},
        {"typ", "id_token"},
        {"iat", now},
        {"exp", now + 3 * 60 * 60},
        {"jku", BaasJku},
        {"jti", Common::UUID::MakeRandom().FormattedString()},
        {"di", device_id},
        {"sn", "XAW10000000000"},
        {"bs:did", RandomHex(0x10)},
        // NSO membership; Splatoon 2 checks it locally.
        {"hm", true},
        // nnAccount's own schema check wants the device block.
        {"nintendo",
         {{"dt", "NX Prod 1"},
          {"pc", "HAC"},
          {"di", device_id},
          {"sn", "XAW10000000000"},
          {"ist", false}}},
        // Binds the NEX login to the account: the servers check this token's signature,
        // expiry and PID, and refuse a bare PID.
        {"nnex", nex_token},
    };
    if (!version.empty()) {
        payload["tv"] = version;
    }
    // Scarlet shares Violet's NPLN tenant but needs its own app_id.
    if (program_id == PokemonScarlet) {
        payload["app_id"] = fmt::format("{:016X}", program_id);
    }

    const std::string signing_input = Base64Url(header.dump()) + "." + Base64Url(payload.dump());
    return signing_input + "." + SignRs256(signing_input);
}

} // Anonymous namespace

u64 GetNetworkServiceAccountId() {
    const auto session = Common::Nextendo::GetSession();
    return session ? session->pid : UnlinkedAccountId;
}

std::vector<u8> GetIdToken(Core::System& system) {
    static std::mutex mutex;
    static std::vector<u8> cached;
    static std::chrono::steady_clock::time_point expiry{};
    static u64 cached_generation = ~u64{0};
    static u64 cached_program_id = 0;

    const auto session = Common::Nextendo::GetSession();
    if (!session) {
        return {};
    }

    std::scoped_lock lock{mutex};
    const u64 generation = Common::Nextendo::GetGeneration();
    const u64 program_id = system.GetApplicationProcessProgramID();
    const auto now = std::chrono::steady_clock::now();
    if (cached.empty() || now >= expiry || generation != cached_generation ||
        program_id != cached_program_id) {
        const std::string token =
            BuildIdToken(session->nex_token, InstalledVersion(system, program_id), program_id);
        cached.assign(token.begin(), token.end());
        expiry = now + std::chrono::hours{2};
        cached_generation = generation;
        cached_program_id = program_id;
        LOG_INFO(Service_ACC, "Nextendo: issued an id_token ({} bytes)", cached.size());
    }
    return cached;
}

} // namespace Service::Account::Nextendo
