// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <chrono>
#include <random>
#include <unordered_map>
#include <codecvt>
#include <locale>
#include <string>
#include <string_view>
#include <dlfcn.h>

#ifdef ARCHITECTURE_arm64
#include <adrenotools/driver.h>
#endif

#include <android/api-level.h>
#include <android/native_window_jni.h>
#include <common/fs/fs.h>
#include <core/file_sys/patch_manager.h>
#include <core/file_sys/savedata_factory.h>
#include <core/loader/nro.h>
#include <frontend_common/content_manager.h>
#include <jni.h>

#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/detached_tasks.h"
#include "common/dynamic_library.h"
#include "common/fs/path_util.h"
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/microprofile.h"
#include "common/scm_rev.h"
#include "common/scope_exit.h"
#include "common/settings.h"
#include "common/string_util.h"
#include "core/core.h"
#include "core/cpu_manager.h"
#include "core/crypto/key_manager.h"
#include "core/file_sys/card_image.h"
#include "core/file_sys/content_archive.h"
#include "core/file_sys/fs_filesystem.h"
#include "core/file_sys/submission_package.h"
#include "core/file_sys/vfs/vfs.h"
#include "core/file_sys/vfs/vfs_real.h"
#include "core/frontend/applets/cabinet.h"
#include "core/frontend/applets/controller.h"
#include "core/frontend/applets/error.h"
#include "core/frontend/applets/general.h"
#include "core/frontend/applets/mii_edit.h"
#include "core/frontend/applets/profile_select.h"
#include "core/frontend/applets/software_keyboard.h"
#include "core/frontend/applets/web_browser.h"
#include "core/hle/service/am/applet_manager.h"
#include "core/hle/service/am/frontend/applets.h"
#include "core/hle/service/filesystem/filesystem.h"
#include "core/hle/service/nvdrv/core/container.h"
#include "core/hle/service/nvdrv/core/syncpoint_manager.h"
#include "core/hle/service/nvdrv/nvdrv.h"
#include "core/hle/service/nvdrv/nvdrv_interface.h"
#include "core/hle/service/nvnflinger/buffer_queue_core.h"
#include "core/hle/service/sm/sm.h"
#include "core/loader/loader.h"
#include "core/memory_search.h"
#include "core/savestate.h"
#include "frontend_common/config.h"
#include "hid_core/frontend/emulated_controller.h"
#include "hid_core/hid_core.h"
#include "hid_core/hid_types.h"
#include "jni/native.h"
#include "video_core/gpu.h"
#include "video_core/host1x/host1x.h"
#include "video_core/renderer_base.h"
#include "video_core/renderer_vulkan/renderer_vulkan.h"
#include "video_core/vulkan_common/vulkan_instance.h"
#include "video_core/vulkan_common/vulkan_surface.h"

#define jconst [[maybe_unused]] const auto
#define jauto [[maybe_unused]] auto

static EmulationSession s_instance;

EmulationSession::EmulationSession() {
    m_vfs = std::make_shared<FileSys::RealVfsFilesystem>();
}

EmulationSession& EmulationSession::GetInstance() {
    return s_instance;
}

const Core::System& EmulationSession::System() const {
    return m_system;
}

Core::System& EmulationSession::System() {
    return m_system;
}

FileSys::ManualContentProvider* EmulationSession::GetContentProvider() {
    return m_manual_provider.get();
}

InputCommon::InputSubsystem& EmulationSession::GetInputSubsystem() {
    return m_input_subsystem;
}

const EmuWindow_Android& EmulationSession::Window() const {
    return *m_window;
}

EmuWindow_Android& EmulationSession::Window() {
    return *m_window;
}

ANativeWindow* EmulationSession::NativeWindow() const {
    return m_native_window;
}

void EmulationSession::SetNativeWindow(ANativeWindow* native_window) {
    m_native_window = native_window;
}

void EmulationSession::InitializeGpuDriver(const std::string& hook_lib_dir,
                                           const std::string& custom_driver_dir,
                                           const std::string& custom_driver_name,
                                           const std::string& file_redirect_dir) {
#ifdef ARCHITECTURE_arm64
    void* handle{};
    const char* file_redirect_dir_{};
    int featureFlags{};

    // Enable driver file redirection when renderer debugging is enabled.
    if (Settings::values.renderer_debug && file_redirect_dir.size()) {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
        file_redirect_dir_ = file_redirect_dir.c_str();
    }

    // Try to load a custom driver.
    if (custom_driver_name.size()) {
        // Confirmed via a real device log (Samsung S21+/Snapdragon 888): the first 1-2 calls
        // here returned null (falling through to the system driver below, which then failed
        // emulation almost immediately - "Unsuitable driver" - each time) before a later call
        // finally succeeded and ran the game fine for 40+ seconds. The system driver fallback
        // is not a safe first response on this device class, so retry the custom driver briefly
        // instead of giving up after one attempt.
        static constexpr int MaxAttempts = 3;
        static constexpr auto RetryDelay = std::chrono::milliseconds(50);
        for (int attempt = 0; attempt < MaxAttempts && !handle; ++attempt) {
            if (attempt > 0) {
                std::this_thread::sleep_for(RetryDelay);
            }
            handle = adrenotools_open_libvulkan(
                RTLD_NOW, featureFlags | ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hook_lib_dir.c_str(),
                custom_driver_dir.c_str(), custom_driver_name.c_str(), file_redirect_dir_, nullptr);
        }
    }

    // Try to load the system driver.
    if (!handle) {
        handle = adrenotools_open_libvulkan(RTLD_NOW, featureFlags, nullptr, hook_lib_dir.c_str(),
                                            nullptr, nullptr, file_redirect_dir_, nullptr);
    }

    m_vulkan_library = std::make_shared<Common::DynamicLibrary>(handle);
#endif
}

bool EmulationSession::IsRunning() const {
    return m_is_running;
}

bool EmulationSession::IsPaused() const {
    return m_is_running && m_is_paused;
}

const Core::PerfStatsResults& EmulationSession::PerfStats() {
    m_perf_stats = m_system.GetAndResetPerfStats();
    return m_perf_stats;
}

void EmulationSession::SurfaceChanged() {
    if (!IsRunning()) {
        return;
    }
    m_window->OnSurfaceChanged(m_native_window);
}

void EmulationSession::ConfigureFilesystemProvider(const std::string& filepath) {
    const auto file = m_system.GetFilesystem()->OpenFile(filepath, FileSys::OpenMode::Read);
    if (!file) {
        return;
    }

    auto loader = Loader::GetLoader(m_system, file);
    if (!loader) {
        return;
    }

    const auto file_type = loader->GetFileType();
    if (file_type == Loader::FileType::Unknown || file_type == Loader::FileType::Error) {
        return;
    }

    u64 program_id = 0;
    const auto res2 = loader->ReadProgramId(program_id);
    if (res2 == Loader::ResultStatus::Success && file_type == Loader::FileType::NCA) {
        m_manual_provider->AddEntry(FileSys::TitleType::Application,
                                    FileSys::GetCRTypeFromNCAType(FileSys::NCA{file}.GetType()),
                                    program_id, file);
    } else if (res2 == Loader::ResultStatus::Success &&
               (file_type == Loader::FileType::XCI || file_type == Loader::FileType::NSP)) {
        const auto nsp = file_type == Loader::FileType::NSP
                             ? std::make_shared<FileSys::NSP>(file)
                             : FileSys::XCI{file}.GetSecurePartitionNSP();
        for (const auto& title : nsp->GetNCAs()) {
            for (const auto& entry : title.second) {
                m_manual_provider->AddEntry(entry.first.first, entry.first.second, title.first,
                                            entry.second->GetBaseFile());
            }
        }
    }
}

void EmulationSession::InitializeSystem(bool reload) {
    if (!reload) {
        // Initialize logging system
        Common::Log::Initialize();
        Common::Log::SetColorConsoleBackendEnabled(true);
        Common::Log::Start();

        m_input_subsystem.Initialize();
    }

    // Initialize filesystem.
    m_system.SetFilesystem(m_vfs);
    m_system.GetUserChannel().clear();
    m_manual_provider = std::make_unique<FileSys::ManualContentProvider>();
    m_system.SetContentProvider(std::make_unique<FileSys::ContentProviderUnion>());
    m_system.RegisterContentProvider(FileSys::ContentProviderUnionSlot::FrontendManual,
                                     m_manual_provider.get());
    m_system.GetFileSystemController().CreateFactories(*m_vfs);
}

void EmulationSession::SetAppletId(int applet_id) {
    m_applet_id = applet_id;
    m_system.GetFrontendAppletHolder().SetCurrentAppletId(
        static_cast<Service::AM::AppletId>(m_applet_id));
}

Core::SystemResultStatus EmulationSession::InitializeEmulation(const std::string& filepath,
                                                               const std::size_t program_index,
                                                               const bool frontend_initiated) {
    std::scoped_lock lock(m_mutex);

    // Create the render window.
    m_window = std::make_unique<EmuWindow_Android>(m_native_window, m_vulkan_library);

    // Initialize system.
    jauto android_keyboard = std::make_unique<Common::Android::SoftwareKeyboard::AndroidKeyboard>();
    m_software_keyboard = android_keyboard.get();
    m_system.SetShuttingDown(false);
    m_system.ApplySettings();
    Settings::LogSettings();
    m_system.HIDCore().ReloadInputDevices();
    m_system.SetFrontendAppletSet({
        nullptr,                     // Amiibo Settings
        nullptr,                     // Controller Selector
        nullptr,                     // Error Display
        nullptr,                     // Mii Editor
        nullptr,                     // Parental Controls
        nullptr,                     // Photo Viewer
        nullptr,                     // Profile Selector
        std::move(android_keyboard), // Software Keyboard
        nullptr,                     // Web Browser
    });

    // Initialize filesystem.
    ConfigureFilesystemProvider(filepath);

    // Load the ROM.
    Service::AM::FrontendAppletParameters params{
        .applet_id = static_cast<Service::AM::AppletId>(m_applet_id),
        .launch_type = frontend_initiated ? Service::AM::LaunchType::FrontendInitiated
                                          : Service::AM::LaunchType::ApplicationInitiated,
        .program_index = static_cast<s32>(program_index),
    };
    m_load_result = m_system.Load(EmulationSession::GetInstance().Window(), filepath, params);
    if (m_load_result != Core::SystemResultStatus::Success) {
        return m_load_result;
    }

    // Complete initialization.
    m_system.GPU().Start();
    m_system.GetCpuManager().OnGpuReady();
    m_system.RegisterExitCallback([&] { HaltEmulation(); });

    // Register an ExecuteProgram callback such that Core can execute a sub-program
    m_system.RegisterExecuteProgramCallback([&](std::size_t program_index_) {
        m_next_program_index = program_index_;
        EmulationSession::GetInstance().HaltEmulation();
    });

    OnEmulationStarted();
    return Core::SystemResultStatus::Success;
}

void EmulationSession::ShutdownEmulation() {
    std::scoped_lock lock(m_mutex);

    // Frozen Cheater addresses are only meaningful for the process they were found in - clear
    // them (and stop the background rewrite thread) before anything else here, so they can
    // never bleed into whatever game loads next.
    Core::MemorySearch::ClearAllFrozen();

    if (m_next_program_index != -1) {
        ChangeProgram(m_next_program_index);
        m_next_program_index = -1;
    }

    m_is_running = false;
    m_is_paused = false;

    // Unload user input.
    m_system.HIDCore().UnloadInputDevices();

    // Enable all controllers
    m_system.HIDCore().SetSupportedStyleTag({Core::HID::NpadStyleSet::All});

    // Shutdown the main emulated process
    if (m_load_result == Core::SystemResultStatus::Success) {
        m_system.DetachDebugger();
        m_system.ShutdownMainProcess();
        m_detached_tasks.WaitForAllTasks();
        m_load_result = Core::SystemResultStatus::ErrorNotInitialized;
        m_window.reset();
        OnEmulationStopped(Core::SystemResultStatus::Success);
        return;
    }

    // Tear down the render window.
    m_window.reset();
}

void EmulationSession::PauseEmulation() {
    std::scoped_lock lock(m_mutex);
    // While the game still loads (the app pauses on any onPause, e.g. a second tap on the game)
    // the kernel may not exist yet: only remember it, RunEmulation starts paused.
    if (m_is_running) {
        m_system.Pause();
    }
    m_is_paused = true;
}

void EmulationSession::UnPauseEmulation() {
    std::scoped_lock lock(m_mutex);
    if (m_is_running) {
        m_system.Run();
    }
    m_is_paused = false;
}

namespace {
std::string GetQuickSavePath() {
    const auto dir = Common::FS::GetYuzuPath(Common::FS::YuzuPath::SaveStateDir);
    void(Common::FS::CreateDirs(dir));
    return (dir / "quicksave.bin").string();
}
} // namespace

bool EmulationSession::WaitForSafeMoment(bool can_run, const std::vector<u32>* target,
                                         std::chrono::steady_clock::time_point deadline) {
    static constexpr int MaxAttempts = 100;
    static constexpr auto Step = std::chrono::milliseconds(30);
    std::vector<u32> previous;
    int equal_in_a_row = 0;
    // For the log: how often each thread's wait changed from one look to the next, and the last
    // change, plus the looks that found an arbitration wait (see LogNoSafeMoment).
    int looks = 0;
    int arbiter_looks = 0;
    std::vector<ThreadChurn> churn;
    std::minstd_rand jitter{
        static_cast<u32>(std::chrono::steady_clock::now().time_since_epoch().count())};
    // For a save: a load has to find every thread in the state it had at the save, and
    // a save caught a busy thread in a passing state (the main thread mid-work, another in the
    // middle of a kernel call) could not be loaded again for a long time.
    // So first watch the threads for a while and count each one's states; then save at a look where
    // each thread is in the state it was seen in most, the one a load is most likely to find again.
    static constexpr int ObserveLooks = 30;
    static constexpr int StrictLooks = 50; // then one thread may be off its usual state
    std::vector<std::unordered_map<u32, int>> seen;
    std::vector<u32> usual;
    int observed = 0;
    int picking = 0;
    for (int attempt = 0; attempt < MaxAttempts && std::chrono::steady_clock::now() < deadline;
         attempt++) {
        looks++;
        // Some threads wait on the GPU (one on a request the GPU completes, another on a GPU
        // event). Whether they are still waiting depends on whether the GPU has caught up, and the
        // save and the restore wait for it, which woke them after they were looked at (a load
        // refused itself 133 times on one of them). So let the GPU catch up before every look, at a
        // save and at a load alike, and compare the threads as they are once it has.
        (void)m_system.GPU().SettleFor(std::chrono::milliseconds(2000));
        const std::vector<u32> now = Core::SaveState::WaitSignature(m_system);
        const bool arbiter = Core::SaveState::HasRiskyPendingWaits(m_system);
        if (arbiter) {
            arbiter_looks++;
        }
        if (churn.size() != now.size()) {
            churn.assign(now.size(), {});
        }
        if (!previous.empty() && previous.size() == now.size()) {
            for (size_t i = 0; i < now.size(); i++) {
                if (previous[i] != now[i]) {
                    churn[i].changes++;
                    churn[i].from = previous[i];
                    churn[i].to = now[i];
                }
            }
        }
        equal_in_a_row = (now == previous) ? equal_in_a_row + 1 : 0;
        previous = now;

        bool good = false;
        if (target == nullptr && can_run) {
            if (seen.size() != now.size()) {
                // The thread count changed: start counting again.
                seen.assign(now.size(), {});
                usual.clear();
                observed = 0;
                picking = 0;
            }
            if (observed < ObserveLooks) {
                for (size_t i = 0; i < now.size(); i++) {
                    seen[i][now[i]]++;
                }
                if (++observed == ObserveLooks) {
                    usual.resize(now.size());
                    for (size_t i = 0; i < now.size(); i++) {
                        usual[i] = std::max_element(seen[i].begin(), seen[i].end(),
                                                    [](const auto& a, const auto& b) {
                                                        return a.second < b.second;
                                                    })
                                       ->first;
                    }
                }
            } else if (!arbiter) {
                std::vector<size_t> off;
                for (size_t i = 0; i < now.size(); i++) {
                    if (now[i] != usual[i]) {
                        off.push_back(i);
                    }
                }
                // No thread off its usual state waiting for a service's answer: the service
                // answers within milliseconds, also while the game is paused, so a load finds it
                // there only now and then and it has moved on by the time the restore looks again
                // (a load refused itself 8 times on one such thread).
                const bool passing_ipc = std::ranges::any_of(off, [&](size_t i) {
                    return (now[i] & 0x7F) ==
                           static_cast<u32>(Kernel::ThreadWaitReasonForDebugging::IPC);
                });
                const size_t allowed = ++picking <= StrictLooks ? 0 : 1;
                if (!passing_ipc && off.size() <= allowed) {
                    // The save waits for the GPU and copies what it drew first; the GPU work that
                    // finishes then can wake a thread (one waiting on an event was saved as
                    // running), so do that now and take this look only if nothing changed.
                    Core::SaveState::CopyGpuDrawnImages(m_system);
                    (void)m_system.GPU().SettleFor(std::chrono::milliseconds(2000));
                    const std::vector<u32> settled = Core::SaveState::WaitSignature(m_system);
                    if (settled != now) {
                        LOG_INFO(Frontend,
                                 "Quick Save: threads changed while the GPU caught up, looking "
                                 "again: {}",
                                 Core::SaveState::DescribeWaitDifferences(now, settled));
                        m_system.Run();
                        std::this_thread::sleep_for(Step);
                        m_system.Pause();
                        continue;
                    }
                    LOG_INFO(Frontend,
                             "Quick Save: typical moment after {} look(s), {} thread(s) off its "
                             "usual state",
                             looks, off.size());
                    return true;
                }
            }
        } else if (!arbiter) {
            good = target != nullptr ? Core::SaveState::SignaturesMatch(*target, now)
                                     : equal_in_a_row >= 2;
        }
        if (good) {
            return true;
        }
        if (!can_run) {
            // Held paused by the user: nothing will change. A save has no way to check for
            // stability, so it only needs the lock handoff to be over; a load needs the match.
            const bool ok = target == nullptr && !arbiter;
            if (!ok) {
                LogNoSafeMoment(target, now, looks, arbiter_looks, churn, "paused by the user");
            }
            return ok;
        }
        m_system.Run();
        // A little longer or shorter each time: with a fixed step the looks could keep falling on
        // the same point of the game's frame and never see the states that come at another.
        std::this_thread::sleep_for(Step / 2 + std::chrono::milliseconds(jitter() % Step.count()));
        m_system.Pause();
    }
    LogNoSafeMoment(target, previous, looks, arbiter_looks, churn, "out of attempts or time");
    return false;
}

void EmulationSession::LogNoSafeMoment(const std::vector<u32>* target, const std::vector<u32>& last,
                                       int looks, int arbiter_looks,
                                       const std::vector<ThreadChurn>& churn, const char* why) {
    // What kept WaitForSafeMoment from finding a moment: for a load, the threads not as at the
    // save; for a save, the threads whose wait kept changing.
    std::vector<size_t> order;
    for (size_t i = 0; i < churn.size(); i++) {
        if (churn[i].changes > 0) {
            order.push_back(i);
        }
    }
    std::sort(order.begin(), order.end(),
              [&](size_t a, size_t b) { return churn[a].changes > churn[b].changes; });
    std::string changing;
    for (size_t n = 0; n < order.size() && n < 8; n++) {
        const auto& c = churn[order[n]];
        changing += fmt::format("{}thread {}: {} change(s), last {} -> {}", n > 0 ? "; " : "",
                                order[n], c.changes, Core::SaveState::DescribeWaitWord(c.from),
                                Core::SaveState::DescribeWaitWord(c.to));
    }
    LOG_WARNING(Frontend,
                "No safe moment ({}, {} look(s)): threads not matching the save: {}; threads whose "
                "wait kept changing: {}; looks with an arbitration wait: {} (now: {})",
                why, looks,
                target != nullptr ? Core::SaveState::DescribeWaitDifferences(*target, last)
                                  : std::string{"n/a (saving)"},
                changing.empty() ? std::string{"none"} : changing, arbiter_looks,
                Core::SaveState::DescribeRiskyPendingWaits(m_system));
}

void EmulationSession::WaitForGpuIdle(const char* what) {
    static constexpr auto Timeout = std::chrono::milliseconds(2000);
    const auto start = std::chrono::steady_clock::now();
    const bool idle = m_system.GPU().WaitForIdleFor(Timeout);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - start)
                        .count();
    if (!idle) {
        LOG_WARNING(Frontend, "{}: GPU still busy after {} ms, going ahead anyway", what, ms);
    }
}

void EmulationSession::RewindSyncpoints() {
    if (!m_saved_syncpoints) {
        LOG_WARNING(Frontend, "Quick Load: no syncpoint values from a save, left as they are");
        return;
    }
    auto& host1x = m_system.GPU().Host1x().GetSyncpointManager();
    auto nvdrv =
        m_system.ServiceManager().GetService<Service::Nvidia::NVDRV>("nvdrv:s", true)->GetModule();
    auto& nvdrv_syncpoints = nvdrv->GetContainer().GetSyncpointManager();
    for (u32 id = 0; id < Tegra::Host1x::SyncpointManager::NumSyncpoints; id++) {
        const u32 now = host1x.GetGuestSyncpointValue(id);
        const u32 saved = (*m_saved_syncpoints)[id];
        const u32 delta = now - saved;
        if (delta == 0) {
            continue;
        }
        if (delta > 0x7FFFFFFF) {
            LOG_WARNING(Frontend, "Quick Load: syncpoint {} is at {}, behind the save's {}, left",
                        id, now, saved);
            continue;
        }
        const size_t waiting = host1x.RewindForRestore(id, delta);
        nvdrv_syncpoints.RewindForRestore(id, delta);
        const size_t fences = Service::android::BufferQueueCore::RewindFencesForRestore(id, delta);
        LOG_INFO(Frontend,
                 "Quick Load: syncpoint {} moved back {} to {} (host now {}), {} action(s) "
                 "waiting on it, {} display fence(s) moved",
                 id, delta, saved, host1x.GetHostSyncpointValue(id), waiting, fences);
    }
}

EmulationSession::QuickSaveResult EmulationSession::QuickSaveState() {
    std::scoped_lock lock(m_mutex);
    const bool was_paused = m_is_paused;
    if (!was_paused) {
        m_system.Pause();
    }

    // A save made while a thread is in the middle of a lock handoff can never be loaded back
    // without freezing the game (see HasRiskyPendingWaits), so wait for a moment without one.
    QuickSaveResult result = QuickSaveResult::NoSafeMoment;
    if (WaitForSafeMoment(!was_paused, nullptr)) {
        WaitForGpuIdle("Quick Save");
        result = Core::SaveState::Capture(m_system, GetQuickSavePath()) ? QuickSaveResult::Saved
                                                                        : QuickSaveResult::Failed;
        if (result == QuickSaveResult::Saved) {
            m_saved_audio = AudioCore::Renderer::System::SnapshotAll();
            m_saved_display_slots = Service::android::BufferQueueCore::SnapshotProducerSlots();
            auto& host1x = m_system.GPU().Host1x().GetSyncpointManager();
            m_saved_syncpoints.emplace();
            for (u32 id = 0; id < m_saved_syncpoints->size(); id++) {
                (*m_saved_syncpoints)[id] = host1x.GetGuestSyncpointValue(id);
            }
        }
    } else {
        LOG_WARNING(Frontend, "Quick Save: no safe moment found, nothing was saved");
    }

    if (!was_paused) {
        m_system.Run();
    }
    return result;
}

EmulationSession::QuickLoadResult EmulationSession::QuickLoadState() {
    std::scoped_lock lock(m_mutex);
    const bool was_paused = m_is_paused;

    // The game is paused, but other parts of the emulator can still wake its threads in the
    // milliseconds between looking at them and restoring (the restore itself checks again and
    // refuses, see RestoreFailure::NotSettled): so wait, try, and wait again if it was refused
    // for that reason.
    static constexpr int MaxTries = 20;
    // However many tries that takes, the player is not kept waiting much longer than this: up to
    // a minute could go by before (20 tries of up to 3 s each).
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
    Core::SaveState::RestoreFailure failure = Core::SaveState::RestoreFailure::Other;
    bool restored = false;
    bool no_safe_moment = false;
    for (int attempt = 0; attempt < MaxTries; attempt++) {
        m_system.Pause();

        // A restore done while threads are waking or going to sleep leaves the game frozen or
        // aborted (see WaitSignature): wait until they wait the way they did at the save.
        std::vector<u32> saved_signature;
        if (!Core::SaveState::ReadSavedSignature(GetQuickSavePath(), saved_signature)) {
            if (!was_paused) {
                m_system.Run();
            }
            return QuickLoadResult::Failed; // no usable save
        }
        if (!WaitForSafeMoment(!was_paused, &saved_signature, deadline)) {
            no_safe_moment = true;
            break;
        }

        WaitForGpuIdle("Quick Load");
        restored = Core::SaveState::Restore(m_system, GetQuickSavePath(), &failure);
        if (restored) {
            RewindSyncpoints();
            AudioCore::Renderer::System::RestoreAll(m_saved_audio);
            Service::android::BufferQueueCore::RestoreProducerSlots(m_saved_display_slots);
        }
        if (restored || failure != Core::SaveState::RestoreFailure::NotSettled) {
            break;
        }
        if (attempt + 1 == MaxTries || std::chrono::steady_clock::now() >= deadline) {
            no_safe_moment = true;
        }
    }

    if (!was_paused) {
        m_system.Run();
    }
    if (no_safe_moment) {
        LOG_WARNING(Frontend, "Quick Load: no safe moment found, nothing was changed");
        return QuickLoadResult::NoSafeMoment;
    }
    if (restored) {
        return QuickLoadResult::Loaded;
    }
    return failure == Core::SaveState::RestoreFailure::MemoryLayoutChanged
               ? QuickLoadResult::Incompatible
               : QuickLoadResult::Failed;
}

std::vector<Core::MemorySearch::Match> EmulationSession::CheatSearch(
    s32 needle_value, Core::MemorySearch::ValueType type, float tolerance) {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::Search(m_system, needle_value, type, tolerance);
}

void EmulationSession::CheatTakeSnapshot() {
    std::scoped_lock lock(m_mutex);
    Core::MemorySearch::TakeSnapshot(m_system);
}

std::vector<Core::MemorySearch::Match> EmulationSession::CheatCompareSnapshot(
    Core::MemorySearch::Comparison comparison, Core::MemorySearch::ValueType type) {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::CompareSnapshot(m_system, comparison, type);
}

std::vector<Core::MemorySearch::Match> EmulationSession::CheatRefine(
    std::span<const Core::MemorySearch::Match> candidates,
    std::optional<Core::MemorySearch::Comparison> comparison, s32 needle_value,
    Core::MemorySearch::ValueType type, float tolerance) {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::Refine(m_system, candidates, comparison, needle_value, type,
                                      tolerance);
}

bool EmulationSession::CheatRead(u64 address, std::span<u8> out) {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::Read(m_system, address, out);
}

bool EmulationSession::CheatWrite(u64 address, std::span<const u8> value) {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::Write(m_system, address, value);
}

void EmulationSession::CheatSetFrozen(u64 address, s32 value) {
    std::scoped_lock lock(m_mutex);
    Core::MemorySearch::SetFrozen(m_system, address, value);
}

void EmulationSession::CheatClearFrozen(u64 address) {
    std::scoped_lock lock(m_mutex);
    Core::MemorySearch::ClearFrozen(address);
}

std::vector<Core::MemorySearch::Match> EmulationSession::CheatGetFrozen() {
    std::scoped_lock lock(m_mutex);
    return Core::MemorySearch::GetFrozen();
}

void EmulationSession::HaltEmulation() {
    std::scoped_lock lock(m_mutex);
    m_is_running = false;
    m_cv.notify_one();
}

void EmulationSession::RunEmulation() {
    {
        std::scoped_lock lock(m_mutex);
        m_is_running = true;
    }

    // Load the disk shader cache.
    if (Settings::values.use_disk_shader_cache.GetValue()) {
        LoadDiskCacheProgress(VideoCore::LoadCallbackStage::Prepare, 0, 0);
        m_system.Renderer().ReadRasterizer()->LoadDiskResources(
            m_system.GetApplicationProcessProgramID(), std::stop_token{}, LoadDiskCacheProgress);
        LoadDiskCacheProgress(VideoCore::LoadCallbackStage::Complete, 0, 0);
    }

    {
        std::scoped_lock lock(m_mutex);
        if (!m_is_paused) {
            void(m_system.Run());
        }
    }

    if (m_system.DebuggerEnabled()) {
        m_system.InitializeDebugger();
    }

    while (true) {
        {
            [[maybe_unused]] std::unique_lock lock(m_mutex);
            if (m_cv.wait_for(lock, std::chrono::milliseconds(800),
                              [&]() { return !m_is_running; })) {
                // Emulation halted.
                break;
            }
        }
    }

    // Reset current applet ID.
    m_applet_id = static_cast<int>(Service::AM::AppletId::Application);
}

Common::Android::SoftwareKeyboard::AndroidKeyboard* EmulationSession::SoftwareKeyboard() {
    return m_software_keyboard;
}

void EmulationSession::LoadDiskCacheProgress(VideoCore::LoadCallbackStage stage, int progress,
                                             int max) {
    JNIEnv* env = Common::Android::GetEnvForThread();
    env->CallStaticVoidMethod(Common::Android::GetDiskCacheProgressClass(),
                              Common::Android::GetDiskCacheLoadProgress(), static_cast<jint>(stage),
                              static_cast<jint>(progress), static_cast<jint>(max));
}

void EmulationSession::OnEmulationStarted() {
    JNIEnv* env = Common::Android::GetEnvForThread();
    env->CallStaticVoidMethod(Common::Android::GetNativeLibraryClass(),
                              Common::Android::GetOnEmulationStarted());
}

void EmulationSession::OnEmulationStopped(Core::SystemResultStatus result) {
    JNIEnv* env = Common::Android::GetEnvForThread();
    env->CallStaticVoidMethod(Common::Android::GetNativeLibraryClass(),
                              Common::Android::GetOnEmulationStopped(), static_cast<jint>(result));
}

void EmulationSession::ChangeProgram(std::size_t program_index) {
    JNIEnv* env = Common::Android::GetEnvForThread();
    env->CallStaticVoidMethod(Common::Android::GetNativeLibraryClass(),
                              Common::Android::GetOnProgramChanged(),
                              static_cast<jint>(program_index));
}

u64 EmulationSession::GetProgramId(JNIEnv* env, jstring jprogramId) {
    auto program_id_string = Common::Android::GetJString(env, jprogramId);
    try {
        return std::stoull(program_id_string);
    } catch (...) {
        return 0;
    }
}

static Core::SystemResultStatus RunEmulation(const std::string& filepath,
                                             const size_t program_index,
                                             const bool frontend_initiated) {
    MicroProfileOnThreadCreate("EmuThread");
    SCOPE_EXIT {
        MicroProfileShutdown();
    };

    LOG_INFO(Frontend, "starting");

    if (filepath.empty()) {
        LOG_CRITICAL(Frontend, "failed to load: filepath empty!");
        return Core::SystemResultStatus::ErrorLoader;
    }

    SCOPE_EXIT {
        EmulationSession::GetInstance().ShutdownEmulation();
    };

    jconst result = EmulationSession::GetInstance().InitializeEmulation(filepath, program_index,
                                                                        frontend_initiated);
    if (result != Core::SystemResultStatus::Success) {
        return result;
    }

    EmulationSession::GetInstance().RunEmulation();

    return Core::SystemResultStatus::Success;
}

extern "C" {

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_surfaceChanged(JNIEnv* env, jobject instance,
                                                          [[maybe_unused]] jobject surf) {
    EmulationSession::GetInstance().SetNativeWindow(ANativeWindow_fromSurface(env, surf));
    EmulationSession::GetInstance().SurfaceChanged();
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_surfaceDestroyed(JNIEnv* env, jobject instance) {
    ANativeWindow_release(EmulationSession::GetInstance().NativeWindow());
    EmulationSession::GetInstance().SetNativeWindow(nullptr);
    EmulationSession::GetInstance().SurfaceChanged();
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_setAppDirectory(JNIEnv* env, jobject instance,
                                                           [[maybe_unused]] jstring j_directory) {
    Common::FS::SetAppDirectory(Common::Android::GetJString(env, j_directory));
}

int Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_installFileToNand(JNIEnv* env, jobject instance,
                                                            jstring j_file, jobject jcallback) {
    auto jlambdaClass = env->GetObjectClass(jcallback);
    auto jlambdaInvokeMethod = env->GetMethodID(
        jlambdaClass, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    const auto callback = [env, jcallback, jlambdaInvokeMethod](size_t max, size_t progress) {
        auto jwasCancelled = env->CallObjectMethod(jcallback, jlambdaInvokeMethod,
                                                   Common::Android::ToJDouble(env, max),
                                                   Common::Android::ToJDouble(env, progress));
        return Common::Android::GetJBoolean(env, jwasCancelled);
    };

    return static_cast<int>(
        ContentManager::InstallNSP(EmulationSession::GetInstance().System(),
                                   *EmulationSession::GetInstance().System().GetFilesystem(),
                                   Common::Android::GetJString(env, j_file), callback));
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_doesUpdateMatchProgram(JNIEnv* env, jobject jobj,
                                                                      jstring jprogramId,
                                                                      jstring jupdatePath) {
    u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    std::string updatePath = Common::Android::GetJString(env, jupdatePath);
    std::shared_ptr<FileSys::NSP> nsp = std::make_shared<FileSys::NSP>(
        EmulationSession::GetInstance().System().GetFilesystem()->OpenFile(
            updatePath, FileSys::OpenMode::Read));
    for (const auto& item : nsp->GetNCAs()) {
        for (const auto& nca_details : item.second) {
            if (nca_details.second->GetName().ends_with(".cnmt.nca")) {
                auto update_id = nca_details.second->GetTitleId() & ~0xFFFULL;
                if (update_id == program_id) {
                    return true;
                }
            }
        }
    }
    return false;
}

void JNICALL Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_initializeGpuDriver(JNIEnv* env, jclass clazz,
                                                                       jstring hook_lib_dir,
                                                                       jstring custom_driver_dir,
                                                                       jstring custom_driver_name,
                                                                       jstring file_redirect_dir) {
    EmulationSession::GetInstance().InitializeGpuDriver(
        Common::Android::GetJString(env, hook_lib_dir),
        Common::Android::GetJString(env, custom_driver_dir),
        Common::Android::GetJString(env, custom_driver_name),
        Common::Android::GetJString(env, file_redirect_dir));
}

[[maybe_unused]] static bool CheckKgslPresent() {
    constexpr auto KgslPath{"/dev/kgsl-3d0"};

    return access(KgslPath, F_OK) == 0;
}

[[maybe_unused]] bool SupportsCustomDriver() {
    return android_get_device_api_level() >= 28 && CheckKgslPresent();
}

jboolean JNICALL Java_dev_ironlemon_ironlemon_1emulator_utils_GpuDriverHelper_supportsCustomDriverLoading(
    JNIEnv* env, jobject instance) {
#ifdef ARCHITECTURE_arm64
    // If the KGSL device exists custom drivers can be loaded using adrenotools
    return SupportsCustomDriver();
#else
    return false;
#endif
}

jobjectArray Java_dev_ironlemon_ironlemon_1emulator_utils_GpuDriverHelper_getSystemDriverInfo(
    JNIEnv* env, jobject j_obj, jobject j_surf, jstring j_hook_lib_dir) {
    const char* file_redirect_dir_{};
    int featureFlags{};
    std::string hook_lib_dir = Common::Android::GetJString(env, j_hook_lib_dir);
    auto handle = adrenotools_open_libvulkan(RTLD_NOW, featureFlags, nullptr, hook_lib_dir.c_str(),
                                             nullptr, nullptr, file_redirect_dir_, nullptr);
    auto driver_library = std::make_shared<Common::DynamicLibrary>(handle);
    InputCommon::InputSubsystem input_subsystem;
    auto window =
        std::make_unique<EmuWindow_Android>(ANativeWindow_fromSurface(env, j_surf), driver_library);

    // Only to show the driver's version and name: if the driver can't be opened, say nothing
    // instead of taking the app down.
    try {
        Vulkan::vk::InstanceDispatch dld;
        Vulkan::vk::Instance vk_instance = Vulkan::CreateInstance(
            *driver_library, dld, VK_API_VERSION_1_1, Core::Frontend::WindowSystemType::Android);

        auto surface = Vulkan::CreateSurface(vk_instance, window->GetWindowInfo());

        auto device = Vulkan::CreateDevice(vk_instance, dld, *surface);

        auto driver_version = device.GetDriverVersion();
        auto version_string = fmt::format("{}.{}.{}", VK_API_VERSION_MAJOR(driver_version),
                                          VK_API_VERSION_MINOR(driver_version),
                                          VK_API_VERSION_PATCH(driver_version));

        jobjectArray j_driver_info = env->NewObjectArray(
            2, Common::Android::GetStringClass(), Common::Android::ToJString(env, version_string));
        env->SetObjectArrayElement(j_driver_info, 1,
                                   Common::Android::ToJString(env, device.GetDriverName()));
        return j_driver_info;
    } catch (const std::exception& e) {
        LOG_ERROR(Frontend, "Couldn't read the system GPU driver's info: {}", e.what());
        return nullptr;
    }
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_reloadKeys(JNIEnv* env, jclass clazz) {
    Core::Crypto::KeyManager::Instance().ReloadKeys();
    return static_cast<jboolean>(Core::Crypto::KeyManager::Instance().AreKeysLoaded());
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_unpauseEmulation(JNIEnv* env, jclass clazz) {
    EmulationSession::GetInstance().UnPauseEmulation();
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_pauseEmulation(JNIEnv* env, jclass clazz) {
    EmulationSession::GetInstance().PauseEmulation();
}

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_quickSaveState(JNIEnv* env, jclass clazz) {
    return static_cast<jint>(EmulationSession::GetInstance().QuickSaveState());
}

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_quickLoadState(JNIEnv* env, jclass clazz) {
    return static_cast<jint>(EmulationSession::GetInstance().QuickLoadState());
}

} // extern "C"

namespace {
// These have real C++ linkage (std::vector return types), so they can't live inside the
// surrounding extern "C" block - it's closed above and reopened below just for this group.
std::vector<u8> JByteArrayToBytes(JNIEnv* env, jbyteArray array) {
    std::vector<u8> bytes;
    if (array == nullptr) {
        return bytes;
    }
    const jsize length = env->GetArrayLength(array);
    bytes.resize(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    return bytes;
}

// Matches are marshalled to/from Kotlin as a single interleaved long[]: [addr0, value0,
// addr1, value1, ...]. A custom object type would be cleaner, but this keeps the JNI surface
// to plain arrays like the rest of this file, at the cost of the caller having to decode
// pairs - see CheatOverlay.kt's decodeMatches()/encodeMatches().
jlongArray MatchVectorToJLongArray(JNIEnv* env, const std::vector<Core::MemorySearch::Match>& matches) {
    std::vector<jlong> interleaved(matches.size() * 2);
    for (size_t i = 0; i < matches.size(); i++) {
        interleaved[i * 2] = static_cast<jlong>(matches[i].address);
        interleaved[i * 2 + 1] = static_cast<jlong>(matches[i].value);
    }

    jlongArray array = env->NewLongArray(static_cast<jsize>(interleaved.size()));
    if (array != nullptr && !interleaved.empty()) {
        env->SetLongArrayRegion(array, 0, static_cast<jsize>(interleaved.size()),
                                interleaved.data());
    }
    return array;
}

std::vector<Core::MemorySearch::Match> JLongArrayToMatchVector(JNIEnv* env, jlongArray array) {
    std::vector<Core::MemorySearch::Match> matches;
    if (array == nullptr) {
        return matches;
    }

    const jsize length = env->GetArrayLength(array);
    if (length <= 0 || length % 2 != 0) {
        return matches;
    }

    std::vector<jlong> interleaved(static_cast<std::size_t>(length));
    env->GetLongArrayRegion(array, 0, length, interleaved.data());

    matches.reserve(static_cast<std::size_t>(length) / 2);
    for (jsize i = 0; i < length; i += 2) {
        matches.push_back({.address = static_cast<u64>(interleaved[i]),
                           .value = static_cast<s32>(interleaved[i + 1])});
    }
    return matches;
}
} // namespace

extern "C" {

// jvalueType: 0 = 32-bit integer, 1 = 32-bit float (jneedleValue then carries the float's bits
// and jtolerance is how far a stored float may be from it and still match).
jlongArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatSearch(JNIEnv* env, jclass clazz,
                                                              jint jneedleValue, jint jvalueType,
                                                              jfloat jtolerance) {
    const auto results = EmulationSession::GetInstance().CheatSearch(
        static_cast<s32>(jneedleValue), static_cast<Core::MemorySearch::ValueType>(jvalueType),
        static_cast<float>(jtolerance));
    return MatchVectorToJLongArray(env, results);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatTakeSnapshot(JNIEnv* env, jclass clazz) {
    EmulationSession::GetInstance().CheatTakeSnapshot();
}

jlongArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatCompareSnapshot(JNIEnv* env, jclass clazz,
                                                                       jint jcomparison,
                                                                       jint jvalueType) {
    const auto comparison = static_cast<Core::MemorySearch::Comparison>(jcomparison);
    const auto results = EmulationSession::GetInstance().CheatCompareSnapshot(
        comparison, static_cast<Core::MemorySearch::ValueType>(jvalueType));
    return MatchVectorToJLongArray(env, results);
}

// jcomparison: -1 means an exact-value refine using jneedleValue, 0/1 map to
// Core::MemorySearch::Comparison (Increased/Decreased) and ignore jneedleValue.
jlongArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatRefine(JNIEnv* env, jclass clazz,
                                                              jlongArray jcandidates,
                                                              jint jcomparison,
                                                              jint jneedleValue, jint jvalueType,
                                                              jfloat jtolerance) {
    const auto candidates = JLongArrayToMatchVector(env, jcandidates);
    const std::optional<Core::MemorySearch::Comparison> comparison =
        jcomparison < 0 ? std::nullopt
                        : std::make_optional(static_cast<Core::MemorySearch::Comparison>(jcomparison));
    const auto results = EmulationSession::GetInstance().CheatRefine(
        candidates, comparison, static_cast<s32>(jneedleValue),
        static_cast<Core::MemorySearch::ValueType>(jvalueType), static_cast<float>(jtolerance));
    return MatchVectorToJLongArray(env, results);
}

jbyteArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatRead(JNIEnv* env, jclass clazz,
                                                            jlong jaddress, jint jlength) {
    if (jlength <= 0) {
        return env->NewByteArray(0);
    }

    std::vector<u8> buffer(static_cast<std::size_t>(jlength));
    if (!EmulationSession::GetInstance().CheatRead(static_cast<u64>(jaddress), buffer)) {
        return env->NewByteArray(0);
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(buffer.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(buffer.size()),
                                reinterpret_cast<const jbyte*>(buffer.data()));
    }
    return result;
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatWrite(JNIEnv* env, jclass clazz,
                                                            jlong jaddress, jbyteArray jvalue) {
    const auto value = JByteArrayToBytes(env, jvalue);
    return static_cast<jboolean>(
        EmulationSession::GetInstance().CheatWrite(static_cast<u64>(jaddress), value));
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatSetFrozen(JNIEnv* env, jclass clazz,
                                                            jlong jaddress, jint jvalue) {
    EmulationSession::GetInstance().CheatSetFrozen(static_cast<u64>(jaddress),
                                                   static_cast<s32>(jvalue));
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatClearFrozen(JNIEnv* env, jclass clazz,
                                                              jlong jaddress) {
    EmulationSession::GetInstance().CheatClearFrozen(static_cast<u64>(jaddress));
}

jlongArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_cheatGetFrozen(JNIEnv* env, jclass clazz) {
    const auto results = EmulationSession::GetInstance().CheatGetFrozen();
    return MatchVectorToJLongArray(env, results);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_stopEmulation(JNIEnv* env, jclass clazz) {
    EmulationSession::GetInstance().HaltEmulation();
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_isRunning(JNIEnv* env, jclass clazz) {
    return static_cast<jboolean>(EmulationSession::GetInstance().IsRunning());
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_isPaused(JNIEnv* env, jclass clazz) {
    return static_cast<jboolean>(EmulationSession::GetInstance().IsPaused());
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_initializeSystem(JNIEnv* env, jclass clazz,
                                                            jboolean reload) {
    // Initialize the emulated system.
    if (!reload) {
        EmulationSession::GetInstance().System().Initialize();
    }
    EmulationSession::GetInstance().InitializeSystem(reload);
}

jdoubleArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getPerfStats(JNIEnv* env, jclass clazz) {
    jdoubleArray j_stats = env->NewDoubleArray(4);

    if (EmulationSession::GetInstance().IsRunning()) {
        jconst results = EmulationSession::GetInstance().PerfStats();

        // Converting the structure into an array makes it easier to pass it to the frontend
        double stats[4] = {results.system_fps, results.average_game_fps, results.frametime,
                           results.emulation_speed};

        env->SetDoubleArrayRegion(j_stats, 0, 4, stats);
    }

    return j_stats;
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getCpuBackend(JNIEnv* env, jclass clazz) {
    if (Settings::IsNceEnabled()) {
        return Common::Android::ToJString(env, "NCE");
    }

    return Common::Android::ToJString(env, "JIT");
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getGpuDriver(JNIEnv* env, jobject jobj) {
    return Common::Android::ToJString(
        env, EmulationSession::GetInstance().System().GPU().Renderer().GetDeviceVendor());
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_applySettings(JNIEnv* env, jobject jobj) {
    EmulationSession::GetInstance().System().ApplySettings();
    EmulationSession::GetInstance().System().HIDCore().ReloadInputDevices();
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_logSettings(JNIEnv* env, jobject jobj) {
    Settings::LogSettings();
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_run(JNIEnv* env, jobject jobj, jstring j_path,
                                               jint j_program_index,
                                               jboolean j_frontend_initiated) {
    const std::string path = Common::Android::GetJString(env, j_path);

    const Core::SystemResultStatus result{
        RunEmulation(path, j_program_index, j_frontend_initiated)};
    if (result != Core::SystemResultStatus::Success) {
        env->CallStaticVoidMethod(Common::Android::GetNativeLibraryClass(),
                                  Common::Android::GetExitEmulationActivity(),
                                  static_cast<int>(result));
    }
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_logDeviceInfo(JNIEnv* env, jclass clazz) {
    LOG_INFO(Frontend, "yuzu Version: {}-{}", Common::g_scm_branch, Common::g_scm_desc);
    LOG_INFO(Frontend, "Host OS: Android API level {}", android_get_device_api_level());
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_submitInlineKeyboardText(JNIEnv* env, jclass clazz,
                                                                    jstring j_text) {
    const std::u16string input = Common::UTF8ToUTF16(Common::Android::GetJString(env, j_text));
    EmulationSession::GetInstance().SoftwareKeyboard()->SubmitInlineKeyboardText(input);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_submitInlineKeyboardInput(JNIEnv* env, jclass clazz,
                                                                     jint j_key_code) {
    EmulationSession::GetInstance().SoftwareKeyboard()->SubmitInlineKeyboardInput(j_key_code);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_initializeEmptyUserDirectory(JNIEnv* env,
                                                                        jobject instance) {
    const auto nand_dir = Common::FS::GetYuzuPath(Common::FS::YuzuPath::NANDDir);
    auto vfs_nand_dir = EmulationSession::GetInstance().System().GetFilesystem()->OpenDirectory(
        Common::FS::PathToUTF8String(nand_dir), FileSys::OpenMode::Read);

    const auto user_id = EmulationSession::GetInstance().System().GetProfileManager().GetUser(
        static_cast<std::size_t>(0));
    ASSERT(user_id);

    const auto user_save_data_path = FileSys::SaveDataFactory::GetFullPath(
        {}, vfs_nand_dir, FileSys::SaveDataSpaceId::User, FileSys::SaveDataType::Account, 1,
        user_id->AsU128(), 0);

    const auto full_path = Common::FS::ConcatPathSafe(nand_dir, user_save_data_path);
    if (!Common::FS::CreateParentDirs(full_path)) {
        LOG_WARNING(Frontend, "Failed to create full path of the default user's save directory");
    }
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getAppletLaunchPath(JNIEnv* env, jclass clazz,
                                                                  jlong jid) {
    auto bis_system =
        EmulationSession::GetInstance().System().GetFileSystemController().GetSystemNANDContents();
    if (!bis_system) {
        return Common::Android::ToJString(env, "");
    }

    auto applet_nca =
        bis_system->GetEntry(static_cast<u64>(jid), FileSys::ContentRecordType::Program);
    if (!applet_nca) {
        return Common::Android::ToJString(env, "");
    }

    return Common::Android::ToJString(env, applet_nca->GetFullPath());
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_setCurrentAppletId(JNIEnv* env, jclass clazz,
                                                              jint jappletId) {
    EmulationSession::GetInstance().SetAppletId(jappletId);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_setCabinetMode(JNIEnv* env, jclass clazz,
                                                          jint jcabinetMode) {
    EmulationSession::GetInstance().System().GetFrontendAppletHolder().SetCabinetMode(
        static_cast<Service::NFP::CabinetMode>(jcabinetMode));
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_isFirmwareAvailable(JNIEnv* env, jclass clazz) {
    auto bis_system =
        EmulationSession::GetInstance().System().GetFileSystemController().GetSystemNANDContents();
    if (!bis_system) {
        return false;
    }

    // Query an applet to see if it's available
    auto applet_nca =
        bis_system->GetEntry(0x010000000000100Dull, FileSys::ContentRecordType::Program);
    if (!applet_nca) {
        return false;
    }
    return true;
}

jobjectArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getPatchesForFile(JNIEnv* env, jobject jobj,
                                                                     jstring jpath,
                                                                     jstring jprogramId) {
    const auto path = Common::Android::GetJString(env, jpath);
    const auto vFile =
        Core::GetGameFileFromPath(EmulationSession::GetInstance().System().GetFilesystem(), path);
    if (vFile == nullptr) {
        return nullptr;
    }

    auto& system = EmulationSession::GetInstance().System();
    auto program_id = EmulationSession::GetProgramId(env, jprogramId);
    const FileSys::PatchManager pm{program_id, system.GetFileSystemController(),
                                   system.GetContentProvider()};
    const auto loader = Loader::GetLoader(system, vFile);

    FileSys::VirtualFile update_raw;
    loader->ReadUpdateRaw(update_raw);

    auto patches = pm.GetPatches(update_raw);
    jobjectArray jpatchArray =
        env->NewObjectArray(patches.size(), Common::Android::GetPatchClass(), nullptr);
    int i = 0;
    for (const auto& patch : patches) {
        jobject jpatch = env->NewObject(
            Common::Android::GetPatchClass(), Common::Android::GetPatchConstructor(), patch.enabled,
            Common::Android::ToJString(env, patch.name),
            Common::Android::ToJString(env, patch.version), static_cast<jint>(patch.type),
            Common::Android::ToJString(env, std::to_string(patch.program_id)),
            Common::Android::ToJString(env, std::to_string(patch.title_id)));
        env->SetObjectArrayElement(jpatchArray, i, jpatch);
        ++i;
    }
    return jpatchArray;
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_removeUpdate(JNIEnv* env, jobject jobj,
                                                        jstring jprogramId) {
    auto program_id = EmulationSession::GetProgramId(env, jprogramId);
    ContentManager::RemoveUpdate(EmulationSession::GetInstance().System().GetFileSystemController(),
                                 program_id);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_removeDLC(JNIEnv* env, jobject jobj,
                                                     jstring jprogramId) {
    auto program_id = EmulationSession::GetProgramId(env, jprogramId);
    ContentManager::RemoveAllDLC(EmulationSession::GetInstance().System(), program_id);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_removeMod(JNIEnv* env, jobject jobj, jstring jprogramId,
                                                     jstring jname) {
    auto program_id = EmulationSession::GetProgramId(env, jprogramId);
    ContentManager::RemoveMod(EmulationSession::GetInstance().System().GetFileSystemController(),
                              program_id, Common::Android::GetJString(env, jname));
}

jobjectArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_verifyInstalledContents(JNIEnv* env,
                                                                           jobject jobj,
                                                                           jobject jcallback) {
    auto jlambdaClass = env->GetObjectClass(jcallback);
    auto jlambdaInvokeMethod = env->GetMethodID(
        jlambdaClass, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    const auto callback = [env, jcallback, jlambdaInvokeMethod](size_t max, size_t progress) {
        auto jwasCancelled = env->CallObjectMethod(jcallback, jlambdaInvokeMethod,
                                                   Common::Android::ToJDouble(env, max),
                                                   Common::Android::ToJDouble(env, progress));
        return Common::Android::GetJBoolean(env, jwasCancelled);
    };

    auto& session = EmulationSession::GetInstance();
    std::vector<std::string> result = ContentManager::VerifyInstalledContents(
        session.System(), *session.GetContentProvider(), callback);
    jobjectArray jresult = env->NewObjectArray(result.size(), Common::Android::GetStringClass(),
                                               Common::Android::ToJString(env, ""));
    for (size_t i = 0; i < result.size(); ++i) {
        env->SetObjectArrayElement(jresult, i, Common::Android::ToJString(env, result[i]));
    }
    return jresult;
}

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_verifyGameContents(JNIEnv* env, jobject jobj,
                                                              jstring jpath, jobject jcallback) {
    auto jlambdaClass = env->GetObjectClass(jcallback);
    auto jlambdaInvokeMethod = env->GetMethodID(
        jlambdaClass, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    const auto callback = [env, jcallback, jlambdaInvokeMethod](size_t max, size_t progress) {
        auto jwasCancelled = env->CallObjectMethod(jcallback, jlambdaInvokeMethod,
                                                   Common::Android::ToJDouble(env, max),
                                                   Common::Android::ToJDouble(env, progress));
        return Common::Android::GetJBoolean(env, jwasCancelled);
    };
    auto& session = EmulationSession::GetInstance();
    return static_cast<jint>(ContentManager::VerifyGameContents(
        session.System(), Common::Android::GetJString(env, jpath), callback));
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getSavePath(JNIEnv* env, jobject jobj,
                                                          jstring jprogramId) {
    auto program_id = EmulationSession::GetProgramId(env, jprogramId);
    if (program_id == 0) {
        return Common::Android::ToJString(env, "");
    }

    auto& system = EmulationSession::GetInstance().System();

    Service::Account::ProfileManager manager;
    // TODO: Pass in a selected user once we get the relevant UI working
    const auto user_id = manager.GetUser(static_cast<std::size_t>(0));
    ASSERT(user_id);

    const auto nandDir = Common::FS::GetYuzuPath(Common::FS::YuzuPath::NANDDir);
    auto vfsNandDir = system.GetFilesystem()->OpenDirectory(Common::FS::PathToUTF8String(nandDir),
                                                            FileSys::OpenMode::Read);

    const auto user_save_data_path = FileSys::SaveDataFactory::GetFullPath(
        {}, vfsNandDir, FileSys::SaveDataSpaceId::User, FileSys::SaveDataType::Account, program_id,
        user_id->AsU128(), 0);
    return Common::Android::ToJString(env, user_save_data_path);
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getDefaultProfileSaveDataRoot(JNIEnv* env,
                                                                            jobject jobj,
                                                                            jboolean jfuture) {
    Service::Account::ProfileManager manager;
    // TODO: Pass in a selected user once we get the relevant UI working
    const auto user_id = manager.GetUser(static_cast<std::size_t>(0));
    ASSERT(user_id);

    const auto user_save_data_root =
        FileSys::SaveDataFactory::GetUserGameSaveDataRoot(user_id->AsU128(), jfuture);
    return Common::Android::ToJString(env, user_save_data_root);
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_addFileToFilesystemProvider(JNIEnv* env, jobject jobj,
                                                                       jstring jpath) {
    EmulationSession::GetInstance().ConfigureFilesystemProvider(
        Common::Android::GetJString(env, jpath));
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_clearFilesystemProvider(JNIEnv* env, jobject jobj) {
    EmulationSession::GetInstance().GetContentProvider()->ClearAllEntries();
}

jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_areKeysPresent(JNIEnv* env, jobject jobj) {
    auto& system = EmulationSession::GetInstance().System();
    system.GetFileSystemController().CreateFactories(*system.GetFilesystem());
    return ContentManager::AreKeysPresent();
}

} // extern "C"
