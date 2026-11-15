// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <cstddef>
#include <string>
#include "common/common_funcs.h"
#include "common/common_types.h"
#include "common/swap.h"
#include "core/file_sys/vfs/vfs_types.h"

namespace FileSys {

// A localized entry containing strings within the NACP.
// One for each language of type Language.
struct LanguageEntry {
    std::array<char, 0x200> application_name;
    std::array<char, 0x100> developer_name;

    std::string GetApplicationName() const;
    std::string GetDeveloperName() const;
};
static_assert(sizeof(LanguageEntry) == 0x300, "LanguageEntry has incorrect size.");

// The raw file format of a NACP file.
struct RawNACP {
    std::array<LanguageEntry, 16> language_entries;
    std::array<u8, 0x25> isbn;
    u8 startup_user_account;
    u8 user_account_switch_lock;
    u8 addon_content_registration_type;
    u32_le application_attribute;
    u32_le supported_languages;
    u32_le parental_control;
    bool screenshot_enabled;
    u8 video_capture_mode;
    bool data_loss_confirmation;
    INSERT_PADDING_BYTES(1);
    u64_le presence_group_id;
    std::array<u8, 0x20> rating_age;
    std::array<char, 0x10> version_string;
    u64_le dlc_base_title_id;
    u64_le save_data_owner_id;
    u64_le user_account_save_data_size;
    u64_le user_account_save_data_journal_size;
    u64_le device_save_data_size;
    u64_le device_save_data_journal_size;
    u64_le bcat_delivery_cache_storage_size;
    char application_error_code_category[8];
    std::array<u64_le, 0x8> local_communication;
    u8 logo_type;
    u8 logo_handling;
    bool runtime_add_on_content_install;
    INSERT_PADDING_BYTES(5);
    u64_le seed_for_pseudo_device_id;
    std::array<u8, 0x41> bcat_passphrase;
    INSERT_PADDING_BYTES(7);
    u64_le user_account_save_data_max_size;
    u64_le user_account_save_data_max_journal_size;
    u64_le device_save_data_max_size;
    u64_le device_save_data_max_journal_size;
    u64_le temporary_storage_size;
    u64_le cache_storage_size;
    u64_le cache_storage_journal_size;
    u64_le cache_storage_data_and_journal_max_size;
    u16_le cache_storage_max_index;
    INSERT_PADDING_BYTES(1);
    u8 runtime_upgrade;
    u32_le supporting_limited_licenses;
    std::array<u64_le, 0x10> play_log_queryable_application_id;
    u8 play_log_query_capability;
    u8 repair_flag;
    u8 program_index;
    u8 required_network_service_license_on_launch;
    u8 application_error_code_prefix;
    // 20.0.0+: 1 when the title block holds a raw deflate stream of 32 entries (see NACP).
    u8 title_compression;
    INSERT_PADDING_BYTES(0xDEA);
};
static_assert(sizeof(RawNACP) == 0x4000, "RawNACP has incorrect size.");
static_assert(offsetof(RawNACP, title_compression) == 0x3215,
              "RawNACP title_compression has incorrect offset.");

// A language on the NX. These are for names and icons.
enum class Language : u8 {
    AmericanEnglish = 0,
    BritishEnglish = 1,
    Japanese = 2,
    French = 3,
    German = 4,
    LatinAmericanSpanish = 5,
    Spanish = 6,
    Italian = 7,
    Dutch = 8,
    CanadianFrench = 9,
    Portuguese = 10,
    Russian = 11,
    Korean = 12,
    TraditionalChinese = 13,
    SimplifiedChinese = 14,
    BrazilianPortuguese = 15,

    Default = 255,
};

extern const std::array<const char*, 16> LANGUAGE_NAMES;

// A class representing the format used by NX metadata files, typically named Control.nacp.
// These store application name, dev name, title id, and other miscellaneous data.
class NACP {
public:
    explicit NACP();
    explicit NACP(VirtualFile file);
    ~NACP();

    const LanguageEntry& GetLanguageEntry() const;
    std::string GetApplicationName() const;
    std::string GetDeveloperName() const;
    u64 GetTitleId() const;
    u64 GetDLCBaseTitleId() const;
    std::string GetVersionString() const;
    u64 GetDefaultNormalSaveSize() const;
    u64 GetDefaultJournalSaveSize() const;
    u32 GetSupportedLanguages() const;
    std::vector<u8> GetRawBytes() const;
    bool GetUserAccountSwitchLock() const;
    u64 GetDeviceSaveDataSize() const;
    u32 GetParentalControlFlag() const;
    const std::array<u8, 0x20>& GetRatingAge() const;

private:
    void ResolveTitles();

    RawNACP raw{};

    // The title entries: the 16 of the title block, or the 32 its compressed form holds.
    std::array<LanguageEntry, 32> titles{};
    std::size_t title_count = 0;
};

} // namespace FileSys
