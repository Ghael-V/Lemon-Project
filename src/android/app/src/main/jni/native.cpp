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
#include <filesystem>
#include <thread>
#include <vector>
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
#include <unistd.h>

#include "android_settings.h"
#include "audio_core/renderer/system_manager.h"
#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/detached_tasks.h"
#include "common/dynamic_library.h"
#include "common/fs/path_util.h"
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/microprofile.h"
#include "common/nextendo_session.h"
#include "common/scm_rev.h"
#include "common/scope_exit.h"
#include "common/settings.h"
#include "common/string_util.h"
#include "core/core.h"
#include "core/cpu_manager.h"
#include "core/crypto/key_manager.h"
#include "core/crypto/ncz_decompressor.h"
#include "core/file_sys/card_image.h"
#include "core/file_sys/common_funcs.h"
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
#include "core/hle/kernel/k_thread.h"
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
#include "core/loader/nso.h"
#include "common/hex_util.h"
#include "core/memory_search.h"
#include "core/savestate.h"
#include "frontend_common/config.h"
#include "hid_core/frontend/emulated_controller.h"
#include "hid_core/hid_core.h"
#include "hid_core/hid_types.h"
#include "jni/native.h"
#include "video_core/frame_gen_stats.h"
#include "video_core/gpu.h"
#include "video_core/host1x/host1x.h"
#include "video_core/capture.h"
#include "video_core/renderer_base.h"
#include "video_core/textures/decoders.h"
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

    // NSP and XCI go by their extension. One holding only an update or DLC has nothing to boot,
    // so it is not identified as an NSP or XCI and has no program id of its own, yet every title
    // in it belongs to a game: all of them are registered.
    const auto container_type = Loader::GuessFromFilename(file->GetName());
    if (container_type == Loader::FileType::NSP || container_type == Loader::FileType::XCI) {
        const auto nsp = container_type == Loader::FileType::NSP
                             ? std::make_shared<FileSys::NSP>(file)
                             : FileSys::XCI{file}.GetSecurePartitionNSP();
        if (!nsp) {
            return;
        }
        for (const auto& title : nsp->GetNCAs()) {
            for (const auto& entry : title.second) {
                m_manual_provider->AddEntry(entry.first.first, entry.first.second, title.first,
                                            entry.second->GetBaseFile());
            }
        }
        return;
    }

    auto loader = Loader::GetLoader(m_system, file);
    if (!loader || loader->GetFileType() != Loader::FileType::NCA) {
        return;
    }
    u64 program_id = 0;
    if (loader->ReadProgramId(program_id) == Loader::ResultStatus::Success) {
        m_manual_provider->AddEntry(FileSys::TitleType::Application,
                                    FileSys::GetCRTypeFromNCAType(FileSys::NCA{file}.GetType()),
                                    program_id, file);
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

namespace {
std::filesystem::path GetQuickSaveDir() {
    const auto dir = Common::FS::GetYuzuPath(Common::FS::YuzuPath::SaveStateDir);
    void(Common::FS::CreateDirs(dir));
    return dir;
}

std::string GetQuickSavePath(u64 id) {
    return (GetQuickSaveDir() / fmt::format("quicksave-{}.bin", id)).string();
}

s64 UnixTimeMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

// A Quick Save only loads in the run of the game it was made in, and each is over a gigabyte: the
// files of an earlier run (saves and their pictures, and those of earlier builds) are deleted when
// a game starts.
void DeleteOldQuickSaves() {
    std::error_code error;
    for (const auto& entry : std::filesystem::directory_iterator(GetQuickSaveDir(), error)) {
        const auto name = entry.path().filename().string();
        if (name.starts_with("quicksave") && entry.is_regular_file(error) &&
            !Common::FS::RemoveFile(entry.path())) {
            LOG_WARNING(Frontend, "Quick Save: could not delete '{}' from an earlier run", name);
        }
    }
}
} // namespace

std::string EmulationSession::QuickSaveThumbnailPath(u64 id) {
    return (GetQuickSaveDir() / fmt::format("quicksave-{}.jpg", id)).string();
}

Core::SystemResultStatus EmulationSession::InitializeEmulation(const std::string& filepath,
                                                               const std::size_t program_index,
                                                               const bool frontend_initiated) {
    m_presented_frames = 0;
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

    DeleteOldQuickSaves();

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
    // What the Quick Saves kept on the host belongs to the game that just ended.
    {
        std::scoped_lock slots_lock{m_slots_mutex};
        m_slots.clear();
        m_thumbnail.clear();
    }

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
// The audio renderer goes on while the game is paused, signalling the game's audio thread every
// few milliseconds: a Quick Save or Load comparing the game's threads found that thread awake
// moments after it had looked, and looked again for seconds (often until it gave up). So it is
// held while the game is paused for one, and goes on while the game runs between looks.
bool audio_held = false;

void HoldAudio() {
    if (!AudioCore::Renderer::SystemManager::Hold()) {
        LOG_WARNING(Frontend, "Quick Save/Load: an audio frame was still being made");
    }
}

class QuickStateAudioHold {
public:
    QuickStateAudioHold() {
        HoldAudio();
        audio_held = true;
    }
    ~QuickStateAudioHold() {
        audio_held = false;
        AudioCore::Renderer::SystemManager::Release();
    }
};

} // namespace

void EmulationSession::RunForAMoment(std::chrono::milliseconds duration) {
    if (audio_held) {
        AudioCore::Renderer::SystemManager::Release();
    }
    m_system.Run();
    std::this_thread::sleep_for(duration);
    m_system.Pause();
    if (audio_held) {
        HoldAudio();
    }
}

bool EmulationSession::WaitForSafeMoment(bool can_run, const std::vector<u32>* target,
                                         std::chrono::steady_clock::time_point deadline,
                                         const std::vector<u64>* target_tls,
                                         std::unique_lock<std::shared_timed_mutex>* gate,
                                         Core::SaveState::LoadCheck* check,
                                         const std::vector<u32>* target_svc) {
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
        // A load looks with no service able to answer (see the gate parameter): a game had a
        // thread waiting for an answer about half the time, and after a look found it waiting as
        // at the save, the answer came before the restore looked again, up to 13 times in a row,
        // until the load gave up.
        const bool gate_closed = gate != nullptr && Core::SaveState::CloseServiceGate(
                                                        *gate, std::chrono::milliseconds(50));
        const std::vector<u32> now = Core::SaveState::WaitSignature(m_system, target_tls);
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
            } else {
                std::vector<size_t> off;
                for (size_t i = 0; i < now.size(); i++) {
                    if (now[i] != usual[i]) {
                        off.push_back(i);
                    }
                }
                // An address-arbiter wait is fine where it is the thread's usual state: a load then
                // finds it in the same wait on the same address (a game's main thread waits on one
                // between frames in some areas and on loading screens, in 88 of 100 looks, so no
                // save could be made there). One caught in a passing arbiter wait is an event or
                // lock changing hands: not then.
                const bool passing_arbiter = std::ranges::any_of(off, [&](size_t i) {
                    return (now[i] & 0x7F) ==
                           static_cast<u32>(Kernel::ThreadWaitReasonForDebugging::Arbitration);
                });
                // Nor a thread off its usual state waiting for a service's answer: the service
                // answers within milliseconds, also while the game is paused, so a load finds it
                // there only now and then and it has moved on by the time the restore looks again
                // (a load refused itself 8 times on one such thread).
                const bool passing_ipc = std::ranges::any_of(off, [&](size_t i) {
                    return (now[i] & 0x7F) ==
                           static_cast<u32>(Kernel::ThreadWaitReasonForDebugging::IPC);
                });
                const size_t allowed = ++picking <= StrictLooks ? 0 : 1;
                if (!passing_arbiter && !passing_ipc && off.size() <= allowed) {
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
                        RunForAMoment(Step);
                        continue;
                    }
                    LOG_INFO(Frontend,
                             "Quick Save: typical moment after {} look(s), {} thread(s) off its "
                             "usual state",
                             looks, off.size());
                    return true;
                }
            }
        } else if (target != nullptr && target_tls != nullptr) {
            // Every thread as at the save first (the match asks for every arbiter wait of the save,
            // on the same address, and no other; the restore checks the full address). After a
            // while, or at once when threads of the save have ended or the game is held paused,
            // the restore may put threads back into their saved state (see Restore's
            // allow_rebuild): after a scene change the scene's threads have ended and long-lived
            // ones wait elsewhere, and no look would ever find them as at the save (a boss fight's
            // save, loaded after dying and waking at a bench, was refused every time).
            static constexpr int ExactLooks = 30;
            static const std::vector<u32> no_svc;
            const auto& svc = target_svc != nullptr ? *target_svc : no_svc;
            auto result = Core::SaveState::CheckLoad(m_system, *target, *target_tls, svc, false);
            if (!result.ok && (!can_run || looks > ExactLooks || result.ended > 0)) {
                result = Core::SaveState::CheckLoad(m_system, *target, *target_tls, svc, true);
            }
            if (check != nullptr) {
                *check = result;
            }
            good = result.ok && (gate == nullptr || gate_closed);
        } else if (target != nullptr) {
            good =
                Core::SaveState::SignaturesMatch(*target, now) && (gate == nullptr || gate_closed);
        } else if (!arbiter) {
            good = equal_in_a_row >= 2;
        }
        if (good) {
            return true;
        }
        if (gate != nullptr && gate->owns_lock()) {
            gate->unlock();
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
        // A little longer or shorter each time: with a fixed step the looks could keep falling on
        // the same point of the game's frame and never see the states that come at another.
        RunForAMoment(Step / 2 + std::chrono::milliseconds(jitter() % Step.count()));
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

void EmulationSession::RewindSyncpoints(
    const std::optional<std::array<u32, Tegra::Host1x::SyncpointManager::NumSyncpoints>>&
        saved_syncpoints) {
    if (!saved_syncpoints) {
        LOG_WARNING(Frontend, "Quick Load: no syncpoint values from a save, left as they are");
        return;
    }
    auto& host1x = m_system.GPU().Host1x().GetSyncpointManager();
    auto nvdrv =
        m_system.ServiceManager().GetService<Service::Nvidia::NVDRV>("nvdrv:s", true)->GetModule();
    auto& nvdrv_syncpoints = nvdrv->GetContainer().GetSyncpointManager();
    for (u32 id = 0; id < Tegra::Host1x::SyncpointManager::NumSyncpoints; id++) {
        const u32 now = host1x.GetGuestSyncpointValue(id);
        const u32 saved = (*saved_syncpoints)[id];
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
    std::optional<QuickStateAudioHold> audio_hold{std::in_place};

    // A save made while a thread is in the middle of a lock handoff can never be loaded back
    // without freezing the game, so wait for a moment a load can find again (see
    // WaitForSafeMoment).
    QuickSaveResult result = QuickSaveResult::NoSafeMoment;
    if (WaitForSafeMoment(!was_paused, nullptr)) {
        WaitForGpuIdle("Quick Save");
        u64 new_id = 0;
        const size_t limit = static_cast<size_t>(
            std::clamp(AndroidSettings::values.quick_save_slots.GetValue(), 1, MaxQuickSaveSlots));
        if (!MakeRoomForQuickSave(limit)) {
            result = QuickSaveResult::NoSpace;
        } else {
            new_id = m_next_slot_id++;
            const std::string path = GetQuickSavePath(new_id);
            result = Core::SaveState::Capture(m_system, path) ? QuickSaveResult::Saved
                                                              : QuickSaveResult::Failed;
            if (result == QuickSaveResult::Failed) {
                Core::SaveState::DiscardSave(path);
            }
        }
        if (result == QuickSaveResult::Saved) {
            QuickSaveSlot slot{new_id, UnixTimeMs(), {}};
            auto& saved = slot.host;
            saved.audio = AudioCore::Renderer::System::SnapshotAll();
            saved.engines = m_system.GPU().SnapshotEngines();
            saved.display_slots = Service::android::BufferQueueCore::SnapshotProducerSlots();
            auto& host1x = m_system.GPU().Host1x().GetSyncpointManager();
            saved.syncpoints.emplace();
            for (u32 id = 0; id < saved.syncpoints->size(); id++) {
                (*saved.syncpoints)[id] = host1x.GetGuestSyncpointValue(id);
            }

            // The picture the load dialog shows: the frame the game last presented, which is the
            // one on screen while it is paused for the save.
            const auto picture_start = std::chrono::steady_clock::now();
            std::vector<u8> picture;
            {
                using namespace VideoCore::Capture;
                const auto tiled = m_system.GPU().GetAppletCaptureBuffer();
                if (tiled.size() >= TiledSize) {
                    picture.resize(LinearWidth * LinearHeight * BytesPerPixel);
                    Tegra::Texture::UnswizzleTexture(picture, tiled, BytesPerPixel, LinearWidth,
                                                     LinearHeight, LinearDepth, BlockHeight,
                                                     BlockDepth);
                }
            }
            LOG_INFO(Frontend, "Quick Save: picture of slot {} taken in {} ms{}", slot.id,
                     std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - picture_start)
                         .count(),
                     picture.empty() ? " (none: the renderer gave no frame)" : "");

            std::scoped_lock slots_lock{m_slots_mutex};
            m_thumbnail = std::move(picture);
            m_slots.push_back(std::move(slot));
            while (m_slots.size() > limit) {
                DiscardQuickSaveSlot(0);
            }
        }
    } else {
        LOG_WARNING(Frontend, "Quick Save: no safe moment found, nothing was saved");
    }

    audio_hold.reset();
    if (!was_paused) {
        m_system.Run();
    }
    return result;
}

void EmulationSession::DiscardQuickSaveSlot(size_t index) {
    const u64 id = m_slots[index].id;
    Core::SaveState::DiscardSave(GetQuickSavePath(id));
    const std::string picture = QuickSaveThumbnailPath(id);
    if (Common::FS::Exists(picture)) {
        void(Common::FS::RemoveFile(picture));
    }
    m_slots.erase(m_slots.begin() + static_cast<std::ptrdiff_t>(index));
}

bool EmulationSession::MakeRoomForQuickSave(size_t limit) {
    // About the size of the last save (the game's memory changes little between saves), or before
    // the first one all the memory the game uses (a save skips what is zero), with some margin.
    u64 needed = 0;
    {
        std::scoped_lock slots_lock{m_slots_mutex};
        if (!m_slots.empty()) {
            std::error_code error;
            needed = std::filesystem::file_size(GetQuickSavePath(m_slots.back().id), error);
            if (error) {
                needed = 0;
            }
        }
    }
    if (needed == 0) {
        if (auto* process = m_system.ApplicationProcess()) {
            needed = process->GetUsedUserPhysicalMemorySize();
        }
    }
    needed += needed / 20 + (64ULL << 20);

    const auto dir = GetQuickSaveDir();
    const auto available = [&dir] {
        std::error_code error;
        const auto space = std::filesystem::space(dir, error);
        return error ? std::numeric_limits<u64>::max() : static_cast<u64>(space.available);
    };
    // Slots that a new save would push out anyway go first, so that a full storage does not stop
    // saving once the slots are filled.
    std::scoped_lock slots_lock{m_slots_mutex};
    while (available() < needed && !m_slots.empty() && m_slots.size() >= limit) {
        LOG_WARNING(Frontend,
                    "Quick Save: {} MiB free, about {} MiB needed: slot {} deleted before saving",
                    available() >> 20, needed >> 20, m_slots.front().id);
        DiscardQuickSaveSlot(0);
    }
    if (available() < needed) {
        LOG_WARNING(Frontend, "Quick Save: {} MiB free, about {} MiB needed: nothing was saved",
                    available() >> 20, needed >> 20);
        return false;
    }
    return true;
}

std::vector<EmulationSession::QuickSaveSlotInfo> EmulationSession::QuickSaveSlots() const {
    std::scoped_lock slots_lock{m_slots_mutex};
    std::vector<QuickSaveSlotInfo> out;
    out.reserve(m_slots.size());
    for (const auto& slot : m_slots) {
        out.push_back(
            {slot.id, slot.saved_at_ms, Core::SaveState::CanStillLoad(GetQuickSavePath(slot.id))});
    }
    return out;
}

std::vector<u8> EmulationSession::TakeQuickSaveThumbnail() {
    std::scoped_lock slots_lock{m_slots_mutex};
    return std::exchange(m_thumbnail, {});
}

EmulationSession::QuickLoadResult EmulationSession::QuickLoadState(u64 id) {
    std::scoped_lock lock(m_mutex);
    const bool was_paused = m_is_paused;

    // A save from before the game started (an earlier run) cannot be loaded: refused at once, not
    // after looks that could never match (its threads all "ended"). Slots exist only in this run.
    const auto slot_it = id == 0 ? (m_slots.empty() ? m_slots.end() : std::prev(m_slots.end()))
                                 : std::ranges::find(m_slots, id, &QuickSaveSlot::id);
    if (slot_it == m_slots.end()) {
        return QuickLoadResult::NotThisSession;
    }
    const size_t slot_index = static_cast<size_t>(slot_it - m_slots.begin());
    const std::string path = GetQuickSavePath(slot_it->id);
    if (!Core::SaveState::HasSaveFromThisSession(path)) {
        return QuickLoadResult::NotThisSession;
    }

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
    bool threads_missing = false;
    bool moved_past = false;
    std::optional<QuickStateAudioHold> audio_hold;
    for (int attempt = 0; attempt < MaxTries; attempt++) {
        m_system.Pause();
        if (!audio_hold) {
            audio_hold.emplace();
        }

        // A restore done while threads are waking or going to sleep leaves the game frozen or
        // aborted (see WaitSignature): wait until they wait the way they did at the save.
        std::vector<u32> saved_signature;
        std::vector<u64> saved_tls;
        std::vector<u32> saved_svc;
        if (!Core::SaveState::ReadSavedSignature(path, saved_signature, &saved_tls, &saved_svc)) {
            audio_hold.reset();
            if (!was_paused) {
                m_system.Run();
            }
            return QuickLoadResult::Failed; // no usable save
        }
        std::unique_lock gate{Core::SaveState::ServiceReplyGate(), std::defer_lock};
        Core::SaveState::LoadCheck check;
        if (!WaitForSafeMoment(!was_paused, &saved_signature, deadline, &saved_tls, &gate, &check,
                               &saved_svc)) {
            no_safe_moment = true;
            // Threads of the save have ended and cannot be created again in the state they were
            // saved in: the game is between scenes (a death screen can end a level's workers
            // until the level loads again).
            threads_missing = check.ended_blocked;
            // Or the save holds a thread that had already ended then, and the game has moved on:
            // waiting will not help (seen when a save made during a game's opening video was loaded
            // after it), only a new save will.
            moved_past = check.moved_past;
            break;
        }

        WaitForGpuIdle("Quick Load");
        restored = Core::SaveState::Restore(m_system, path, &failure, &gate, check.rebuilt > 0);
        if (gate.owns_lock()) {
            gate.unlock();
        }
        if (restored) {
            const auto& saved = m_slots[slot_index].host;
            if (saved.engines) {
                const size_t channels = m_system.GPU().RestoreEngines(*saved.engines);
                LOG_INFO(Frontend, "Quick Load: GPU engine state of {} channel(s) put back",
                         channels);
            }
            RewindSyncpoints(saved.syncpoints);
            AudioCore::Renderer::System::RestoreAll(saved.audio);
            Service::android::BufferQueueCore::RestoreProducerSlots(saved.display_slots);
        }
        if (restored || failure != Core::SaveState::RestoreFailure::NotSettled) {
            break;
        }
        if (attempt + 1 == MaxTries || std::chrono::steady_clock::now() >= deadline) {
            no_safe_moment = true;
        }
    }

    audio_hold.reset();
    if (!was_paused) {
        m_system.Run();
    }
    if (no_safe_moment) {
        LOG_WARNING(Frontend, "Quick Load: no safe moment found{}, nothing was changed",
                    moved_past        ? " (a thread that had ended at the save is in the way: "
                                        "the game has moved past the save)"
                    : threads_missing ? " (threads of the save have ended since)"
                                      : "");
        if (moved_past) {
            return QuickLoadResult::MovedPast;
        }
        return threads_missing ? QuickLoadResult::GameLoading : QuickLoadResult::NoSafeMoment;
    }
    if (restored) {
        // The slots after this one were made from what the load undid: they cannot load any more.
        std::scoped_lock slots_lock{m_slots_mutex};
        while (m_slots.size() > slot_index + 1) {
            DiscardQuickSaveSlot(m_slots.size() - 1);
        }
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
    // A title ID's low 13 bits say which part of an application it is: the program index of a
    // multi-program game, 0x800 for its update, 0x1000 + n for add-on content n. Two IDs are the
    // same game when everything above those bits agrees.
    const auto same_application = [](u64 lhs, u64 rhs) {
        return ((lhs ^ rhs) & FileSys::BASE_TITLE_ID_MASK) == 0;
    };
    const u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    const std::string update_path = Common::Android::GetJString(env, jupdatePath);
    const auto nsp = std::make_shared<FileSys::NSP>(
        EmulationSession::GetInstance().System().GetFilesystem()->OpenFile(
            update_path, FileSys::OpenMode::Read));
    // The package's metadata NCAs carry the title IDs it installs.
    for (const auto& [title_id, ncas] : nsp->GetNCAs()) {
        for (const auto& [record, nca] : ncas) {
            if (nca->GetName().ends_with(".cnmt.nca") &&
                same_application(nca->GetTitleId(), program_id)) {
                return true;
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

[[maybe_unused]] static bool CheckMaliPresent() {
    constexpr auto MaliPath{"/dev/mali0"};

    return access(MaliPath, F_OK) == 0;
}

[[maybe_unused]] bool SupportsCustomDriver() {
    if (android_get_device_api_level() < 28) {
        return false;
    }
    // Mesa PanVK builds that talk to the Mali kernel driver (mali_kbase) load too: adrenotools'
    // custom-driver path only swaps the vulkan.* HAL the system loader opens, so it isn't tied to
    // Adreno. Its KGSL helpers (turbo, GPU mappings) stay Adreno-only.
    if (CheckMaliPresent()) {
        return true;
    }
    return CheckKgslPresent();
}

jboolean JNICALL Java_dev_ironlemon_ironlemon_1emulator_utils_GpuDriverHelper_hasKgslDevice(
    JNIEnv* env, jobject instance) {
#ifdef ARCHITECTURE_arm64
    return CheckKgslPresent();
#else
    return false;
#endif
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

        // [version, driver name, GPU model]: the model ("Mali-G615 MC6") picks the PanVK builds,
        // the driver name is just "Mali" there.
        jobjectArray j_driver_info = env->NewObjectArray(
            3, Common::Android::GetStringClass(), Common::Android::ToJString(env, version_string));
        env->SetObjectArrayElement(j_driver_info, 1,
                                   Common::Android::ToJString(env, device.GetDriverName()));
        env->SetObjectArrayElement(
            j_driver_info, 2,
            Common::Android::ToJString(env, std::string{device.GetModelName()}));
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

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_quickLoadSlot(JNIEnv* env, jclass clazz, jlong id) {
    return static_cast<jint>(EmulationSession::GetInstance().QuickLoadState(static_cast<u64>(id)));
}

jlongArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getQuickSaveSlots(JNIEnv* env, jclass clazz) {
    // Three numbers a slot, oldest slot first: id, when it was made (Unix ms), loadable (1 or 0).
    std::vector<jlong> flat;
    for (const auto& slot : EmulationSession::GetInstance().QuickSaveSlots()) {
        flat.push_back(static_cast<jlong>(slot.id));
        flat.push_back(static_cast<jlong>(slot.saved_at_ms));
        flat.push_back(slot.loadable ? 1 : 0);
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(flat.size()));
    if (out != nullptr) {
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    }
    return out;
}

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_quickSaveThumbnailPath(JNIEnv* env, jclass clazz,
                                                                       jlong id) {
    return Common::Android::ToJString(
        env, EmulationSession::QuickSaveThumbnailPath(static_cast<u64>(id)));
}

jbyteArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_takeQuickSaveThumbnail(JNIEnv* env,
                                                                          jclass clazz) {
    const auto picture = EmulationSession::GetInstance().TakeQuickSaveThumbnail();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(picture.size()));
    if (out != nullptr && !picture.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(picture.size()),
                                reinterpret_cast<const jbyte*>(picture.data()));
    }
    return out;
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

jlong Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getPresentedFrameCount(JNIEnv* env,
                                                                               jclass clazz) {
    return static_cast<jlong>(EmulationSession::GetInstance().PresentedFrames());
}

jlong Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getGeneratedFrameCount(JNIEnv* env,
                                                                               jclass clazz) {
    return static_cast<jlong>(VideoCore::GeneratedFrameCount());
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
    LOG_INFO(Frontend, "Iron Lemon version: {}-{}", Common::g_scm_branch, Common::g_scm_desc);
    LOG_INFO(Frontend, "Host OS: Android API level {}", android_get_device_api_level());
    // 16 KiB pages (newer Android 15+ devices) change what host memory mappings may look like.
    LOG_INFO(Frontend, "Host page size: {} bytes", sysconf(_SC_PAGESIZE));
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

// Firmware updated past the keys: some system title of the new key generation can't be decrypted,
// and games then hang while loading with nothing said. Reads every system title's header.
jboolean Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_firmwareNeedsNewerKeys(JNIEnv* env,
                                                                                   jclass clazz) {
    const auto bis_system =
        EmulationSession::GetInstance().System().GetFileSystemController().GetSystemNANDContents();
    if (!bis_system) {
        return false;
    }
    for (const auto& entry : bis_system->ListEntries()) {
        const auto nca = bis_system->GetEntry(entry.title_id, entry.type);
        if (nca && nca->GetStatus() == Loader::ResultStatus::ErrorMissingKeyAreaKey) {
            LOG_WARNING(Frontend, "Firmware title {:016X} needs newer keys than the installed ones",
                        entry.title_id);
            return true;
        }
    }
    return false;
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

// ---- Lemon's cheat manager (Atmosphere cheats under the title's mod directory) ----

} // extern "C"

namespace {

// The build id the cheats of a game are keyed by: its main executable's, with the installed update
// applied (cheat codes only fit the build they were made for). Read from the game file, so it is
// known before the game has ever run.
std::optional<FileSys::PatchManager::BuildID> ReadMainBuildId(Core::System& system,
                                                              const std::string& path,
                                                              u64 program_id) {
    const auto file = Core::GetGameFileFromPath(system.GetFilesystem(), path);
    if (file == nullptr) {
        return std::nullopt;
    }
    const auto loader = Loader::GetLoader(system, file);
    if (loader == nullptr) {
        return std::nullopt;
    }

    FileSys::VirtualDir exefs;
    switch (loader->GetFileType()) {
    case Loader::FileType::NSP: {
        const FileSys::NSP nsp{file, program_id};
        // A file that bundles the update runs the update's executable.
        if (const auto update = nsp.GetNCA(FileSys::GetUpdateTitleID(program_id),
                                           FileSys::ContentRecordType::Program)) {
            exefs = update->GetExeFS();
        }
        if (exefs == nullptr) {
            exefs = nsp.GetExeFS();
        }
        if (exefs == nullptr) {
            if (const auto nca = nsp.GetNCA(program_id, FileSys::ContentRecordType::Program)) {
                exefs = nca->GetExeFS();
            }
        }
        break;
    }
    case Loader::FileType::XCI: {
        const FileSys::XCI xci{file, program_id};
        // An XCI with its update in the secure partition ("1G+1U") runs the update's executable.
        if (const auto secure = xci.GetSecurePartitionNSP()) {
            if (const auto update = secure->GetNCA(FileSys::GetUpdateTitleID(program_id),
                                                   FileSys::ContentRecordType::Program)) {
                exefs = update->GetExeFS();
            }
        }
        if (exefs == nullptr) {
            if (const auto nca_file = xci.GetProgramNCAFile()) {
                exefs = FileSys::NCA{nca_file}.GetExeFS();
            }
        }
        break;
    }
    case Loader::FileType::NCA:
        exefs = FileSys::NCA{file}.GetExeFS();
        break;
    case Loader::FileType::DeconstructedRomDirectory:
        exefs = file->GetContainingDirectory();
        break;
    default:
        return std::nullopt; // homebrew: no cheats keyed by build id
    }
    if (exefs == nullptr) {
        // As the NCA loader does: a program whose executable only the update holds.
        if (const auto update = system.GetContentProvider().GetEntry(
                FileSys::GetUpdateTitleID(program_id), FileSys::ContentRecordType::Program)) {
            exefs = update->GetExeFS();
        }
    }

    const FileSys::PatchManager pm{program_id, system.GetFileSystemController(),
                                   system.GetContentProvider()};
    exefs = pm.PatchExeFS(exefs);
    const auto main = exefs != nullptr ? exefs->GetFile("main") : nullptr;
    Loader::NSOHeader header{};
    if (main == nullptr || main->ReadObject(&header) != sizeof(header) ||
        header.magic != Common::MakeMagic('N', 'S', 'O', '0')) {
        return std::nullopt;
    }
    return header.build_id;
}

// Whether `program_id` is the game running now.
bool IsRunningGame(u64 program_id) {
    auto& session = EmulationSession::GetInstance();
    return session.IsRunning() &&
           session.System().GetApplicationProcessProgramID() == program_id;
}

// The 16 hex digits cheat files are named after, back into a build id.
FileSys::PatchManager::BuildID ParseBuildId(const std::string& hex) {
    FileSys::PatchManager::BuildID id{};
    const auto bytes = Common::HexStringToVector(hex.substr(0, 16), false);
    std::copy_n(bytes.begin(), (std::min)(bytes.size(), id.size()), id.begin());
    return id;
}

FileSys::PatchManager CheatPatchManager(u64 program_id) {
    auto& system = EmulationSession::GetInstance().System();
    return FileSys::PatchManager{program_id, system.GetFileSystemController(),
                                 system.GetContentProvider()};
}

} // namespace

extern "C" {

jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getCheatBuildId(JNIEnv* env, jobject jobj,
                                                              jstring jpath, jstring jprogramId) {
    auto& system = EmulationSession::GetInstance().System();
    const u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    std::optional<FileSys::PatchManager::BuildID> build_id;
    if (IsRunningGame(program_id)) {
        build_id = system.GetApplicationProcessBuildID();
    } else {
        build_id = ReadMainBuildId(system, Common::Android::GetJString(env, jpath), program_id);
    }
    if (!build_id) {
        return Common::Android::ToJString(env, "");
    }
    return Common::Android::ToJString(env, Common::HexToString(*build_id, true).substr(0, 16));
}

jobjectArray Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getCheats(JNIEnv* env, jobject jobj,
                                                             jstring jprogramId,
                                                             jstring jbuildId) {
    const u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    const auto cheats = CheatPatchManager(program_id).ListCheats(
        ParseBuildId(Common::Android::GetJString(env, jbuildId)));
    // One string per cheat: folder, name, enabled, master, joined by \x1F.
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(cheats.size()),
                                           Common::Android::GetStringClass(), nullptr);
    for (size_t i = 0; i < cheats.size(); i++) {
        const auto& cheat = cheats[i];
        const std::string row = fmt::format("{}\x1F{}\x1F{}\x1F{}", cheat.folder, cheat.name,
                                            cheat.enabled ? 1 : 0, cheat.master ? 1 : 0);
        env->SetObjectArrayElement(out, static_cast<jsize>(i),
                                   Common::Android::ToJString(env, row));
    }
    return out;
}

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_setCheatEnabled(JNIEnv* env, jobject jobj,
                                                           jstring jprogramId, jstring jbuildId,
                                                           jstring jfolder, jstring jname,
                                                           jboolean enabled) {
    const u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    using Result = FileSys::PatchManager::CheatToggleResult;
    const Result result = CheatPatchManager(program_id).SetCheatEnabled(
        EmulationSession::GetInstance().System().GetFilesystem(),
        ParseBuildId(Common::Android::GetJString(env, jbuildId)),
        Common::Android::GetJString(env, jfolder), Common::Android::GetJString(env, jname),
        enabled);
    if (result == Result::Saved && IsRunningGame(program_id)) {
        EmulationSession::GetInstance().System().ReloadCheats();
    }
    // 0 saved, 1 failed, 2 too many on.
    return result == Result::Saved ? 0 : result == Result::TooMany ? 2 : 1;
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_reloadCheats(JNIEnv* env, jobject jobj,
                                                        jstring jprogramId) {
    if (IsRunningGame(EmulationSession::GetProgramId(env, jprogramId))) {
        EmulationSession::GetInstance().System().ReloadCheats();
    }
}

jint Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_addCheats(JNIEnv* env, jobject jobj,
                                                     jstring jprogramId, jstring jbuildId,
                                                     jstring jtext, jstring jfolder) {
    const u64 program_id = EmulationSession::GetProgramId(env, jprogramId);
    const long count = CheatPatchManager(program_id).AddCheats(
        EmulationSession::GetInstance().System().GetFilesystem(),
        ParseBuildId(Common::Android::GetJString(env, jbuildId)),
        Common::Android::GetJString(env, jtext), Common::Android::GetJString(env, jfolder));
    if (count > 0 && IsRunningGame(program_id)) {
        EmulationSession::GetInstance().System().ReloadCheats();
    }
    return static_cast<jint>(count);
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
    // Only create the factories that are missing. Recreating them (the default) destroys the
    // registered caches a library scan on another thread may be reading at that moment, and the
    // UI asks this often (setup refreshes each step as it is shown).
    system.GetFileSystemController().CreateFactories(*system.GetFilesystem(), false);
    return ContentManager::AreKeysPresent();
}

// "name<TAB>bytes done<TAB>bytes total" for the solid .nsz/.xcz content being decompressed right
// now, or null when none is. The game-list scan shows it so a long first-time wait is explained.
jstring Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_getNczDecodeInfo(JNIEnv* env, jobject jobj) {
    const auto progress = Core::Crypto::GetNczDecodeProgress();
    if (!progress) {
        return nullptr;
    }
    return Common::Android::ToJString(env, progress->name + '\t' + std::to_string(progress->done) +
                                               '\t' + std::to_string(progress->total));
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_setNextendoSession(JNIEnv* env, jobject obj,
                                                                           jlong pid, jstring username,
                                                                           jstring nex_token) {
    Common::Nextendo::SetSession({
        .pid = static_cast<u64>(pid),
        .username = Common::Android::GetJString(env, username),
        .nex_token = Common::Android::GetJString(env, nex_token),
    });
}

void Java_dev_ironlemon_ironlemon_1emulator_NativeLibrary_clearNextendoSession(JNIEnv* env,
                                                                             jobject obj) {
    Common::Nextendo::ClearSession();
}

} // extern "C"
