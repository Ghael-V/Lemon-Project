// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <cstring>
#include <zlib.h>
#include "common/logging/log.h"
#include "common/settings.h"
#include "common/string_util.h"
#include "common/swap.h"
#include "core/file_sys/control_metadata.h"
#include "core/file_sys/vfs/vfs.h"

namespace FileSys {

const std::array<const char*, 16> LANGUAGE_NAMES{{
    "AmericanEnglish",
    "BritishEnglish",
    "Japanese",
    "French",
    "German",
    "LatinAmericanSpanish",
    "Spanish",
    "Italian",
    "Dutch",
    "CanadianFrench",
    "Portuguese",
    "Russian",
    "Korean",
    "TraditionalChinese",
    "SimplifiedChinese",
    "BrazilianPortuguese",
}};

std::string LanguageEntry::GetApplicationName() const {
    return Common::StringFromFixedZeroTerminatedBuffer(application_name.data(),
                                                       application_name.size());
}

std::string LanguageEntry::GetDeveloperName() const {
    return Common::StringFromFixedZeroTerminatedBuffer(developer_name.data(),
                                                       developer_name.size());
}

constexpr std::array<Language, 18> language_to_codes = {{
    Language::Japanese,
    Language::AmericanEnglish,
    Language::French,
    Language::German,
    Language::Italian,
    Language::Spanish,
    Language::SimplifiedChinese,
    Language::Korean,
    Language::Dutch,
    Language::Portuguese,
    Language::Russian,
    Language::TraditionalChinese,
    Language::BritishEnglish,
    Language::CanadianFrench,
    Language::LatinAmericanSpanish,
    Language::SimplifiedChinese,
    Language::TraditionalChinese,
    Language::BrazilianPortuguese,
}};

NACP::NACP() = default;

NACP::NACP(VirtualFile file) {
    file->ReadObject(&raw);
    ResolveTitles();
}

void NACP::ResolveTitles() {
    constexpr std::size_t TitleBlockSize = sizeof(RawNACP::language_entries);
    const auto* block = reinterpret_cast<const u8*>(raw.language_entries.data());

    // 20.0.0+: a u16 compressed size, then a raw deflate stream of up to 32 entries.
    if (raw.title_compression == 1) {
        u16 compressed_size{};
        std::memcpy(&compressed_size, block, sizeof(compressed_size));
        if (compressed_size != 0 && compressed_size <= TitleBlockSize - sizeof(compressed_size)) {
            z_stream stream{};
            if (inflateInit2(&stream, -MAX_WBITS) == Z_OK) {
                stream.next_in = const_cast<Bytef*>(block + sizeof(compressed_size));
                stream.avail_in = compressed_size;
                stream.next_out = reinterpret_cast<Bytef*>(titles.data());
                stream.avail_out = static_cast<uInt>(sizeof(titles));
                const int result = inflate(&stream, Z_FINISH);
                const std::size_t produced = sizeof(titles) - stream.avail_out;
                inflateEnd(&stream);
                if ((result == Z_STREAM_END || result == Z_BUF_ERROR) &&
                    produced >= sizeof(LanguageEntry)) {
                    title_count = produced / sizeof(LanguageEntry);
                    return;
                }
            }
        }
        LOG_WARNING(Service_FS, "NACP title block marked compressed but could not be inflated");
        titles = {};
    }

    std::memcpy(titles.data(), block, TitleBlockSize);
    title_count = raw.language_entries.size();
}

NACP::~NACP() = default;

const LanguageEntry& NACP::GetLanguageEntry() const {
    Language language =
        language_to_codes[static_cast<s32>(Settings::values.language_index.GetValue())];

    if (static_cast<std::size_t>(language) < title_count) {
        const auto& language_entry = titles[static_cast<u8>(language)];
        if (!language_entry.GetApplicationName().empty())
            return language_entry;
    }

    for (std::size_t i = 0; i < title_count; ++i) {
        if (!titles[i].GetApplicationName().empty())
            return titles[i];
    }

    return titles[static_cast<u8>(Language::AmericanEnglish)];
}

std::string NACP::GetApplicationName() const {
    return GetLanguageEntry().GetApplicationName();
}

std::string NACP::GetDeveloperName() const {
    return GetLanguageEntry().GetDeveloperName();
}

u64 NACP::GetTitleId() const {
    return raw.save_data_owner_id;
}

u64 NACP::GetDLCBaseTitleId() const {
    return raw.dlc_base_title_id;
}

std::string NACP::GetVersionString() const {
    return Common::StringFromFixedZeroTerminatedBuffer(raw.version_string.data(),
                                                       raw.version_string.size());
}

u64 NACP::GetDefaultNormalSaveSize() const {
    return raw.user_account_save_data_size;
}

u64 NACP::GetDefaultJournalSaveSize() const {
    return raw.user_account_save_data_journal_size;
}

bool NACP::GetUserAccountSwitchLock() const {
    return raw.user_account_switch_lock != 0;
}

u32 NACP::GetSupportedLanguages() const {
    return raw.supported_languages;
}

u64 NACP::GetDeviceSaveDataSize() const {
    return raw.device_save_data_size;
}

u32 NACP::GetParentalControlFlag() const {
    return raw.parental_control;
}

const std::array<u8, 0x20>& NACP::GetRatingAge() const {
    return raw.rating_age;
}

std::vector<u8> NACP::GetRawBytes() const {
    std::vector<u8> out(sizeof(RawNACP));
    std::memcpy(out.data(), &raw, sizeof(RawNACP));
    return out;
}
} // namespace FileSys
