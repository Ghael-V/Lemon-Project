// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <array>
#include <optional>
#include <span>
#include <vector>

#include <android/native_window_jni.h>
#include "audio_core/renderer/system.h"
#include "common/android/applets/software_keyboard.h"
#include "common/detached_tasks.h"
#include "core/core.h"
#include "core/file_sys/registered_cache.h"
#include "core/hle/service/acc/profile_manager.h"
#include "core/hle/service/nvnflinger/buffer_queue_core.h"
#include "core/memory_search.h"
#include "core/perf_stats.h"
#include "frontend_common/content_manager.h"
#include "jni/emu_window/emu_window.h"
#include "video_core/rasterizer_interface.h"

#pragma once

#include <chrono>

class EmulationSession final {
public:
    explicit EmulationSession();
    ~EmulationSession() = default;

    static EmulationSession& GetInstance();
    const Core::System& System() const;
    Core::System& System();
    FileSys::ManualContentProvider* GetContentProvider();
    InputCommon::InputSubsystem& GetInputSubsystem();

    const EmuWindow_Android& Window() const;
    EmuWindow_Android& Window();
    ANativeWindow* NativeWindow() const;
    void SetNativeWindow(ANativeWindow* native_window);
    void SurfaceChanged();

    void InitializeGpuDriver(const std::string& hook_lib_dir, const std::string& custom_driver_dir,
                             const std::string& custom_driver_name,
                             const std::string& file_redirect_dir);

    bool IsRunning() const;
    bool IsPaused() const;
    void PauseEmulation();
    void UnPauseEmulation();
    /// What Quick Save did. The values are what the JNI call returns to Kotlin.
    enum class QuickSaveResult : int {
        Failed = 0,       // the save could not be written
        Saved = 1,        // saved
        NoSafeMoment = 2, // a lock handoff was in progress for the whole wait; nothing was saved
    };
    QuickSaveResult QuickSaveState();

    /// What Quick Load did. The values are what the JNI call returns to Kotlin.
    enum class QuickLoadResult : int {
        Failed = 0,       // nothing was restored (no quicksave, or it does not fit this game)
        Loaded = 1,       // restored
        Incompatible = 2, // refused: a change since the save cannot be undone
        NoSafeMoment = 3, // a lock handoff was in progress for the whole wait; nothing was changed
    };
    QuickLoadResult QuickLoadState();
    std::vector<Core::MemorySearch::Match> CheatSearch(s32 needle_value,
                                                       Core::MemorySearch::ValueType type,
                                                       float tolerance);
    void CheatTakeSnapshot();
    std::vector<Core::MemorySearch::Match> CheatCompareSnapshot(
        Core::MemorySearch::Comparison comparison, Core::MemorySearch::ValueType type);
    std::vector<Core::MemorySearch::Match> CheatRefine(
        std::span<const Core::MemorySearch::Match> candidates,
        std::optional<Core::MemorySearch::Comparison> comparison, s32 needle_value,
        Core::MemorySearch::ValueType type, float tolerance);
    bool CheatRead(u64 address, std::span<u8> out);
    bool CheatWrite(u64 address, std::span<const u8> value);
    void CheatSetFrozen(u64 address, s32 value);
    void CheatClearFrozen(u64 address);
    std::vector<Core::MemorySearch::Match> CheatGetFrozen();
    void HaltEmulation();
    void RunEmulation();
    void ShutdownEmulation();

    const Core::PerfStatsResults& PerfStats();
    void ConfigureFilesystemProvider(const std::string& filepath);
    void InitializeSystem(bool reload);
    void SetAppletId(int applet_id);
    Core::SystemResultStatus InitializeEmulation(const std::string& filepath,
                                                 const std::size_t program_index,
                                                 const bool frontend_initiated);

    Common::Android::SoftwareKeyboard::AndroidKeyboard* SoftwareKeyboard();

    static void OnEmulationStarted();

    static u64 GetProgramId(JNIEnv* env, jstring jprogramId);

private:
    static void LoadDiskCacheProgress(VideoCore::LoadCallbackStage stage, int progress, int max);
    static void OnEmulationStopped(Core::SystemResultStatus result);
    static void ChangeProgram(std::size_t program_index);

private:
    // Window management
    std::unique_ptr<EmuWindow_Android> m_window;
    ANativeWindow* m_native_window{};

    // Core emulation
    Core::System m_system;
    InputCommon::InputSubsystem m_input_subsystem;
    Common::DetachedTasks m_detached_tasks;
    Core::PerfStatsResults m_perf_stats{};
    std::shared_ptr<FileSys::VfsFilesystem> m_vfs;
    Core::SystemResultStatus m_load_result{Core::SystemResultStatus::ErrorNotInitialized};
    std::atomic<bool> m_is_running = false;
    /// With the system paused: lets the game run on in short steps (when `can_run`) for up to
    /// about three seconds, or until `deadline`, until it is at a safe moment, and returns whether
    /// it got there. No thread may be in an address-arbiter wait, and the threads' wait signature
    /// must either match `target` (a load: the one stored in the save) or, with no target (a
    /// save), be each thread's usual state. See Core::SaveState::WaitSignature.
    bool WaitForSafeMoment(bool can_run, const std::vector<u32>* target = nullptr,
                           std::chrono::steady_clock::time_point deadline =
                               std::chrono::steady_clock::time_point::max());
    /// For the log: how often one thread's wait changed between WaitForSafeMoment's looks, and
    /// its last change.
    struct ThreadChurn {
        int changes{};
        u32 from{};
        u32 to{};
    };
    /// Logs what kept WaitForSafeMoment from finding a moment.
    void LogNoSafeMoment(const std::vector<u32>* target, const std::vector<u32>& last, int looks,
                         int arbiter_looks, const std::vector<ThreadChurn>& churn, const char* why);
    /// With the system paused: waits (up to two seconds) for the GPU to finish the commands the
    /// game gave it, so a save does not read memory the GPU is still writing and a load is not
    /// followed by GPU work from after the save. `what` names the caller in the log.
    void WaitForGpuIdle(const char* what);
    /// After a Quick Load, moves each GPU syncpoint back to its value at the Quick Save.
    /// The game counts its own fences, in the memory a load rewinds, so without this they stay
    /// behind the syncpoints by however far the GPU got between the save and the load, and every
    /// wait for the GPU passes at once from then on.
    void RewindSyncpoints();
    /// The syncpoint values at the last Quick Save of this session.
    std::optional<std::array<u32, 192>> m_saved_syncpoints;
    /// The audio renderers' working memory at the last Quick Save of this session.
    std::vector<AudioCore::Renderer::System::WorkbufferSnapshot> m_saved_audio;
    /// The display queue slots the game held at the last Quick Save of this session.
    std::vector<Service::android::BufferQueueCore::ProducerSlots> m_saved_display_slots;

    std::atomic<bool> m_is_paused = false;
    Common::Android::SoftwareKeyboard::AndroidKeyboard* m_software_keyboard{};
    std::unique_ptr<FileSys::ManualContentProvider> m_manual_provider;
    int m_applet_id{1};

    // GPU driver parameters
    std::shared_ptr<Common::DynamicLibrary> m_vulkan_library;

    // Synchronization
    std::condition_variable_any m_cv;
    mutable std::mutex m_mutex;

    // Program index for next boot
    std::atomic<s32> m_next_program_index = -1;
};
