// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>
#include "common/common_types.h"
#include "core/file_sys/nca_metadata.h"
#include "core/file_sys/vfs/vfs_types.h"
#include "core/memory/dmnt_cheat_types.h"

namespace Core {
class System;
}

namespace Service::FileSystem {
class FileSystemController;
}

namespace FileSys {

class ContentProvider;
class NCA;
class NACP;

enum class PatchType { Update, DLC, Mod };

enum class PatchSource {
    Unknown,
    NAND,
    SDMC,
    External,
    Packed,
};

struct Patch {
    bool enabled;
    std::string name;
    std::string version;
    PatchType type;
    u64 program_id;
    u64 title_id;
    PatchSource source;
    std::string location;
    u32 numeric_version{0};
};

// A centralized class to manage patches to games.
class PatchManager {
public:
    using BuildID = std::array<u8, 0x20>;
    using Metadata = std::pair<std::unique_ptr<NACP>, VirtualFile>;

    explicit PatchManager(u64 title_id_,
                          const Service::FileSystem::FileSystemController& fs_controller_,
                          const ContentProvider& content_provider_);
    ~PatchManager();

    [[nodiscard]] u64 GetTitleID() const;

    // Currently tracked ExeFS patches:
    // - Game Updates
    [[nodiscard]] VirtualDir PatchExeFS(VirtualDir exefs) const;

    // Currently tracked NSO patches:
    // - IPS
    // - IPSwitch
    [[nodiscard]] std::vector<u8> PatchNSO(const std::vector<u8>& nso,
                                           const std::string& name) const;

    // Checks to see if PatchNSO() will have any effect given the NSO's build ID.
    // Used to prevent expensive copies in NSO loader.
    [[nodiscard]] bool HasNSOPatch(const BuildID& build_id, std::string_view name) const;

    // Creates a CheatList object with all
    // With `out_folders`, also the mod folder each entry comes from ("" for a cheat_*.txt file at
    // the root of the title's mod directory), one per entry.
    [[nodiscard]] std::vector<Core::Memory::CheatEntry> CreateCheatList(
        const BuildID& build_id, std::vector<std::string>* out_folders = nullptr) const;

    // Lemon's cheat manager (Atmosphere cheat files under the title's mod directory).
    struct CheatInfo {
        std::string folder; // the mod folder; empty for a root cheat_*.txt, which can't be switched
        std::string name;
        bool enabled;
        bool master; // the master code ({...}): always on while its file is
    };

    // The cheats CreateCheatList loads for `build_id`, in the same order, without empty entries.
    [[nodiscard]] std::vector<CheatInfo> ListCheats(const BuildID& build_id) const;

    enum class CheatToggleResult {
        Saved,
        Failed,  // the folder has no cheats directory, or toggles.txt can't be written
        TooMany, // switching it on would overflow the cheat VM: left off
    };

    // Switches one cheat of a mod folder on or off, in the folder's cheats/toggles.txt (Atmosphere's
    // format). The cheat VM runs at most DmntCheatVm::MaximumProgramOpcodeCount instructions of all
    // the cheats that are on together, and loads none of them past that, so a cheat that would
    // overflow it is left off. `vfs` opens the folder for writing (the mod directory itself is opened
    // read-only).
    CheatToggleResult SetCheatEnabled(const VirtualFilesystem& vfs, const BuildID& build_id,
                                      std::string_view folder, std::string_view name,
                                      bool enabled) const;

    // Saves cheat codes pasted by the user for `build_id` in a new "Lemon cheats" mod folder, every
    // cheat off (the user picks the ones to use; all of a long list together overflow the VM). The
    // text may be an Atmosphere cheat file, or codes copied from a web page with the cheat names on
    // their own lines and no brackets. Returns how many cheats it holds (master code included); 0
    // when it holds none and -1 when they could not be saved, and nothing is written then.
    // With a `folder_name` (downloaded cheats), they go in that folder instead, replacing its file
    // for this build: cheats it already had keep their switch, new ones start off.
    [[nodiscard]] long AddCheats(const VirtualFilesystem& vfs, const BuildID& build_id,
                                 std::string_view text, std::string_view folder_name = {}) const;

    // Currently tracked RomFS patches:
    // - Game Updates
    // - LayeredFS
    [[nodiscard]] VirtualFile PatchRomFS(const NCA* base_nca, VirtualFile base_romfs,
                                         ContentRecordType type = ContentRecordType::Program,
                                         VirtualFile packed_update_raw = nullptr,
                                         bool apply_layeredfs = true) const;

    // Returns a vector of patches
    [[nodiscard]] std::vector<Patch> GetPatches(VirtualFile update_raw = nullptr) const;

    // If the game update exists, returns the u32 version field in its Meta-type NCA. If that fails,
    // it will fallback to the Meta-type NCA of the base game. If that fails, the result will be
    // std::nullopt
    [[nodiscard]] std::optional<u32> GetGameVersion() const;

    // Given title_id of the program, attempts to get the control data of the update and parse
    // it, falling back to the base control data.
    [[nodiscard]] Metadata GetControlMetadata() const;

    // Version of GetControlMetadata that takes an arbitrary NCA
    [[nodiscard]] Metadata ParseControlNCA(const NCA& nca) const;

    /// @brief Gets NACP metadata (accounting for any patches or updates)
    [[nodiscard]] static PatchManager::Metadata GetMetadataFromBaseOrUpdate(Core::System& system, u64 application_id) noexcept;

private:
    [[nodiscard]] VirtualDir GetModificationLoadRoot(bool sdmc = false) const;
    [[nodiscard]] std::vector<std::string> GetDisabledAddons() const;
    void ApplyLayeredFS(VirtualFile& romfs, ContentRecordType type) const;
    [[nodiscard]] std::vector<VirtualFile> CollectPatches(const std::vector<VirtualDir>& patch_dirs,
                                                          const std::string& build_id) const;

    u64 title_id;
    std::optional<u64> parent_title_id;
    const Service::FileSystem::FileSystemController& fs_controller;
    const ContentProvider& content_provider;
};

} // namespace FileSys
