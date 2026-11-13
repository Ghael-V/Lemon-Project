// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <common/settings_common.h>
#include "common/common_types.h"
#include "common/settings_setting.h"

namespace AndroidSettings {

struct GameDir {
    std::string path;
    bool deep_scan = false;
};

struct OverlayControlData {
    std::string id;
    bool enabled;
    std::pair<double, double> landscape_position;
    std::pair<double, double> portrait_position;
    std::pair<double, double> foldable_position;
    // 0 normal, 1 toggle (a tap holds it, the next lets go), 2 turbo (repeats while held).
    s32 press_mode{0};
};

struct Values {
    Settings::Linkage linkage;

    // Path settings
    std::vector<GameDir> game_dirs;

    // Android
    Settings::Setting<bool> picture_in_picture{linkage, false, "picture_in_picture",
                                               Settings::Category::Android};
    Settings::Setting<s32> screen_layout{linkage,
                                         5,
                                         "screen_layout",
                                         Settings::Category::Android,
                                         Settings::Specialization::Default,
                                         true,
                                         true};
    Settings::Setting<s32> vertical_alignment{linkage,
                                              0,
                                              "vertical_alignment",
                                              Settings::Category::Android,
                                              Settings::Specialization::Default,
                                              true,
                                              true};

    Settings::SwitchableSetting<std::string, false> driver_path{linkage, "", "driver_path",
                                                                Settings::Category::GpuDriver};

    Settings::Setting<s32> theme{linkage, 0, "theme", Settings::Category::Android};
    Settings::Setting<s32> theme_mode{linkage, -1, "theme_mode", Settings::Category::Android};
    Settings::Setting<bool> black_backgrounds{linkage, false, "black_backgrounds",
                                              Settings::Category::Android};

    // Input/performance overlay settings
    std::vector<OverlayControlData> overlay_control_data;
    // A game's own layout for the touch controls. While it is active (the game runs on its
    // custom config and has one saved) it is used instead of the global layout, and saving the
    // global config leaves the global layout alone.
    std::vector<OverlayControlData> custom_overlay_control_data;
    bool use_custom_overlay{false};
    std::vector<OverlayControlData>& ActiveOverlayControlData() {
        return use_custom_overlay ? custom_overlay_control_data : overlay_control_data;
    }
    Settings::Setting<s32> overlay_scale{linkage, 50, "control_scale", Settings::Category::Overlay};
    Settings::Setting<s32> overlay_opacity{linkage, 100, "control_opacity",
                                           Settings::Category::Overlay};

    Settings::Setting<bool> joystick_rel_center{linkage, true, "joystick_rel_center",
                                                Settings::Category::Overlay};
    Settings::Setting<bool> dpad_slide{linkage, true, "dpad_slide", Settings::Category::Overlay};
    // A finger that started on a button moves to another button it reaches.
    Settings::Setting<bool> button_slide{linkage, false, "button_slide",
                                         Settings::Category::Overlay};
    Settings::Setting<bool> haptic_feedback{linkage, true, "haptic_feedback",
                                            Settings::Category::Overlay};
    Settings::Setting<bool> show_performance_overlay{linkage, true, "show_performance_overlay",
                                                     Settings::Category::Overlay};
    Settings::Setting<bool> show_thermal_overlay{linkage, false, "show_thermal_overlay",
                                                 Settings::Category::Overlay};
    Settings::Setting<bool> show_input_overlay{linkage, true, "show_input_overlay",
                                               Settings::Category::Overlay};
    Settings::Setting<bool> touchscreen{linkage, true, "touchscreen", Settings::Category::Overlay};
    Settings::Setting<s32> lock_drawer{linkage, false, "lock_drawer", Settings::Category::Overlay};

    // How many Quick Saves are kept (1 to 4), each about the size of the game's memory.
    Settings::Setting<s32> quick_save_slots{linkage, 2, "quick_save_slots",
                                            Settings::Category::Android};

    // When the on-screen controller shows: 0 automatic (no physical controller), 1 always, 2 never.
    Settings::Setting<s32> input_overlay_mode{linkage, 0, "input_overlay_mode",
                                              Settings::Category::Overlay};

    // Opening the app from the launcher boots QLaunch (the console's home menu).
    Settings::Setting<bool> boot_into_qlaunch{linkage, false, "boot_into_qlaunch",
                                              Settings::Category::Android};

    // Gamepad hotkeys: the Android key codes of the buttons held together, comma separated
    // (empty: none).
    Settings::Setting<std::string> hotkey_quick_save{linkage, "", "hotkey_quick_save",
                                                     Settings::Category::Android};
    Settings::Setting<std::string> hotkey_quick_load{linkage, "", "hotkey_quick_load",
                                                     Settings::Category::Android};
    Settings::Setting<std::string> hotkey_toggle_stats_overlay{
        linkage, "", "hotkey_toggle_stats_overlay", Settings::Category::Android};
    Settings::Setting<std::string> hotkey_toggle_soc_overlay{
        linkage, "", "hotkey_toggle_soc_overlay", Settings::Category::Android};
    Settings::Setting<std::string> hotkey_pause_emulation{linkage, "", "hotkey_pause_emulation",
                                                          Settings::Category::Android};
    Settings::Setting<std::string> hotkey_exit_emulation{linkage, "", "hotkey_exit_emulation",
                                                         Settings::Category::Android};
    // The exit hotkey takes a second press.
    Settings::Setting<bool> hotkey_confirm_exit{linkage, true, "hotkey_confirm_exit",
                                                Settings::Category::Android};
    // Held for every hotkey and held back from the game while pressed (empty: none).
    Settings::Setting<std::string> hotkey_enable_button{linkage, "", "hotkey_enable_button",
                                                        Settings::Category::Android};
};

extern Values values;

} // namespace AndroidSettings
