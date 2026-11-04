// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <array>
#include <optional>
#include <span>
#include <vector>

#include <android/native_window_jni.h>
#include "audio_core/renderer/system.h"
#include "common/android/applets/software_keyboard.h"
#include "core/core.h"
#include "core/file_sys/registered_cache.h"
#include "core/hle/service/acc/profile_manager.h"
#include "core/hle/service/nvnflinger/buffer_queue_core.h"
#include "core/memory_search.h"
#include "video_core/host1x/syncpoint_manager.h"
#include "core/perf_stats.h"
#include "core/savestate.h"
#include "frontend_common/content_manager.h"
#include "jni/emu_window/emu_window.h"
#include "video_core/gpu.h"
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
        NoSpace = 3,      // not enough free storage for the save; nothing was saved
    };
    /// Saves the game into a new slot after the last one, which Quick Load then loads. The oldest
    /// slots are deleted to keep as many as the quick_save_slots setting says.
    QuickSaveResult QuickSaveState();

    /// The most Quick Save slots kept.
    static constexpr int MaxQuickSaveSlots = 4;
    /// A Quick Save slot, as the load dialog shows it.
    struct QuickSaveSlotInfo {
        u64 id;          // names its files (see QuickSaveThumbnailPath)
        s64 saved_at_ms; // when it was made, in Unix time
        bool loadable;   // a load of it would not be refused for what changed since
    };
    /// The slots, oldest first: Quick Load loads the last one. Does not wait for a Quick Save or
    /// Load.
    std::vector<QuickSaveSlotInfo> QuickSaveSlots() const;
    /// Where the picture of the slot `id` is kept (written by the app, see TakeQuickSaveThumbnail).
    static std::string QuickSaveThumbnailPath(u64 id);
    /// The game's picture at the last Quick Save (linear RGBA8, VideoCore::Capture's size), handed
    /// over once; empty if there is none.
    std::vector<u8> TakeQuickSaveThumbnail();

    /// What Quick Load did. The values are what the JNI call returns to Kotlin.
    enum class QuickLoadResult : int {
        Failed = 0,       // nothing was restored (no quicksave, or it does not fit this game)
        Loaded = 1,       // restored
        Incompatible = 2, // refused: a change since the save cannot be undone
        NoSafeMoment = 3, // a lock handoff was in progress for the whole wait; nothing was changed
        GameLoading = 4,  // threads of the save ended and are not back yet; nothing was changed
        NotThisSession = 5, // no save made since the game started; nothing was changed
    };
    /// Loads the slot with id `id`, or the last slot if `id` is 0. Once loaded, the slots after it
    /// are deleted: what changed between them was undone, not kept to be done again.
    QuickLoadResult QuickLoadState(u64 id = 0);
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
    void RequestDiskShaderCacheReload(u64 program_id);

    const Core::PerfStatsResults& PerfStats();
    int ShadersBuilding();
    void ConfigureFilesystemProvider(const std::string& filepath);
    void ConfigureFilesystemProviderFromGameFolder(const std::string& filepath);
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
    void ReloadDiskShaderCache(u64 program_id);

private:
    // Window management
    std::unique_ptr<EmuWindow_Android> m_window;
    ANativeWindow* m_native_window{};

    // Core emulation
    Core::System m_system;
    InputCommon::InputSubsystem m_input_subsystem;
    Core::PerfStatsResults m_perf_stats{};
    int m_shaders_building{0};
    std::shared_ptr<FileSys::VfsFilesystem> m_vfs;
    Core::SystemResultStatus m_load_result{Core::SystemResultStatus::ErrorNotInitialized};
    std::atomic<bool> m_is_running = false;
    /// With the system paused: lets the game run on in short steps (when `can_run`) for up to
    /// about three seconds, or until `deadline`, until it is at a safe moment, and returns whether
    /// it got there. The threads' wait signature must either match `target` (a load: the one
    /// stored in the save) or, with no target (a save), be each thread's usual state, an
    /// address-arbiter wait only where that is the usual state. See
    /// Core::SaveState::WaitSignature. For a load, `target_tls` leaves out the threads the game
    /// started after the save, which the restore ends. With `gate`, each look is made with the
    /// service reply gate closed, and it stays closed when this returns true, for
    /// Core::SaveState::Restore: a thread waiting for a service's answer cannot get it between the
    /// look that matched and the restore. For a load, `check` receives the last look's
    /// Core::SaveState::CheckLoad: whether threads have to be put back (Restore's allow_rebuild),
    /// and whether threads of the save have ended for good; `target_svc` holds the kernel call
    /// each saved thread was in.
    /// Lets the game run for a moment between WaitForSafeMoment's looks, and pauses it again.
    void RunForAMoment(std::chrono::milliseconds duration);
    bool WaitForSafeMoment(bool can_run, const std::vector<u32>* target = nullptr,
                           std::chrono::steady_clock::time_point deadline =
                               std::chrono::steady_clock::time_point::max(),
                           const std::vector<u64>* target_tls = nullptr,
                           std::unique_lock<std::shared_timed_mutex>* gate = nullptr,
                           Core::SaveState::LoadCheck* check = nullptr,
                           const std::vector<u32>* target_svc = nullptr);
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
    /// After a Quick Load, moves each GPU syncpoint back to its value at the Quick Save, as kept
    /// in `saved_syncpoints`.
    /// The game counts its own fences, in the memory a load rewinds, so without this they stay
    /// behind the syncpoints by however far the GPU got between the save and the load, and every
    /// wait for the GPU passes at once from then on.
    void RewindSyncpoints(
        const std::optional<std::array<u32, Tegra::Host1x::SyncpointManager::NumSyncpoints>>&
            saved_syncpoints);
    /// What a Quick Save keeps on the host besides its file.
    struct QuickSaveHostState {
        /// The syncpoint values at the save.
        std::optional<std::array<u32, Tegra::Host1x::SyncpointManager::NumSyncpoints>> syncpoints;
        /// The audio renderers' working memory at the save.
        std::vector<AudioCore::Renderer::System::WorkbufferSnapshot> audio;
        /// The display queue slots the game held at the save.
        std::vector<Service::android::BufferQueueCore::ProducerSlots> display_slots;
        /// The GPU engines' state at the save: memory does not hold it, and a load that left it as
        /// the game set it later drew a black screen (the 3D engine read its textures from a later
        /// scene's texture table).
        std::shared_ptr<const Tegra::GPU::EngineSnapshot> engines;
    };
    /// A Quick Save made since the game started: its file is quicksave-<id>.bin.
    struct QuickSaveSlot {
        u64 id;
        s64 saved_at_ms; // Unix time
        QuickSaveHostState host;
    };
    /// Deletes the slot at `index` in m_slots, files included. Needs m_slots_mutex.
    void DiscardQuickSaveSlot(size_t index);
    /// Deletes the oldest slots (down to keeping `limit` before the new save) while the free
    /// storage is short of what a new save needs. False if it is still short.
    bool MakeRoomForQuickSave(size_t limit);
    /// The Quick Save slots of this session, oldest first. Changed with m_mutex and m_slots_mutex
    /// held, so read with either.
    std::vector<QuickSaveSlot> m_slots;
    u64 m_next_slot_id{1};
    /// The picture taken at the last Quick Save, until the app takes it. Guarded by m_slots_mutex.
    std::vector<u8> m_thumbnail;
    mutable std::mutex m_slots_mutex;

    std::atomic<bool> m_is_paused = false;
    Common::Android::SoftwareKeyboard::AndroidKeyboard* m_software_keyboard{};
    std::unique_ptr<FileSys::ManualContentProvider> m_manual_provider;
    int m_applet_id{1};
    std::optional<u64> m_pending_shader_cache_title;

    // GPU driver parameters
    std::shared_ptr<Common::DynamicLibrary> m_vulkan_library;

    // Synchronization
    std::condition_variable_any m_cv;
    mutable std::mutex m_mutex;

    // Program index for next boot
    std::atomic<s32> m_next_program_index = -1;
    std::atomic<u64> m_next_program_id = 0;
};
