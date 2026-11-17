// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <mutex>
#include <type_traits>
#include <vector>

#include <dlfcn.h>
#include <unistd.h>

#include "common/android/performance_hint.h"
#include "common/logging/log.h"

namespace Common::Android::PerformanceHint {

namespace {

// The NDK's APerformanceHint API (libandroid, API 33; setThreads API 34), looked up at run time:
// the app also runs on Android 11 and 12.
struct Api {
    using GetManager = void* (*)();
    using CreateSession = void* (*)(void* manager, const int32_t* tids, size_t count,
                                    int64_t target_ns);
    using CloseSession = void (*)(void* session);
    using UpdateTarget = int (*)(void* session, int64_t target_ns);
    using ReportActual = int (*)(void* session, int64_t actual_ns);
    using SetThreads = int (*)(void* session, const int32_t* tids, size_t count);

    GetManager get_manager{};
    CreateSession create_session{};
    CloseSession close_session{};
    UpdateTarget update_target{};
    ReportActual report_actual{};
    SetThreads set_threads{};

    bool Usable() const {
        return get_manager && create_session && close_session && update_target && report_actual;
    }
};

const Api& GetApi() {
    static const Api api = [] {
        Api result{};
        void* const library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            return result;
        }
        const auto load = [library](auto& function, const char* name) {
            function = reinterpret_cast<std::remove_reference_t<decltype(function)>>(
                dlsym(library, name));
        };
        load(result.get_manager, "APerformanceHint_getManager");
        load(result.create_session, "APerformanceHint_createSession");
        load(result.close_session, "APerformanceHint_closeSession");
        load(result.update_target, "APerformanceHint_updateTargetWorkDuration");
        load(result.report_actual, "APerformanceHint_reportActualWorkDuration");
        load(result.set_threads, "APerformanceHint_setThreads");
        LOG_INFO(Common, "Android performance hints: {}",
                 result.Usable() ? (result.set_threads ? "yes" : "yes, fixed threads") : "no");
        return result;
    }();
    return api;
}

constexpr int64_t DEFAULT_TARGET_NS = 1'000'000'000 / 30;

struct State {
    std::mutex mutex;
    std::vector<int32_t> tids;
    void* session{};
    bool session_threads_stale{};
    int64_t target_ns{DEFAULT_TARGET_NS};
    std::chrono::steady_clock::time_point last_frame{};

    /// Brings the session in line with the thread list. Call with the mutex held.
    void Sync(const Api& api) {
        if (tids.empty()) {
            CloseLocked(api);
            return;
        }
        if (session && !session_threads_stale) {
            return;
        }
        if (session && api.set_threads &&
            api.set_threads(session, tids.data(), tids.size()) == 0) {
            session_threads_stale = false;
            return;
        }
        CloseLocked(api);
        void* const manager = api.get_manager();
        if (manager) {
            session = api.create_session(manager, tids.data(), tids.size(), target_ns);
        }
        session_threads_stale = false;
    }

    void CloseLocked(const Api& api) {
        if (session) {
            api.close_session(session);
            session = nullptr;
        }
    }
};

State& GetState() {
    static State state;
    return state;
}

} // Anonymous namespace

void AddCurrentThread() {
    const Api& api = GetApi();
    if (!api.Usable()) {
        return;
    }
    State& state = GetState();
    std::scoped_lock lock{state.mutex};
    const int32_t tid = static_cast<int32_t>(gettid());
    if (std::ranges::find(state.tids, tid) == state.tids.end()) {
        state.tids.push_back(tid);
        state.session_threads_stale = true;
    }
}

void RemoveCurrentThread() {
    const Api& api = GetApi();
    if (!api.Usable()) {
        return;
    }
    State& state = GetState();
    std::scoped_lock lock{state.mutex};
    const int32_t tid = static_cast<int32_t>(gettid());
    if (std::erase(state.tids, tid) != 0) {
        state.session_threads_stale = true;
        // A session can't keep a thread that ended: update it now, not at the next frame.
        state.Sync(api);
    }
}

void SetTargetFrameDuration(std::chrono::nanoseconds duration) {
    const Api& api = GetApi();
    if (!api.Usable()) {
        return;
    }
    const int64_t target_ns = std::clamp<int64_t>(duration.count(), 1'000'000, 200'000'000);
    State& state = GetState();
    std::scoped_lock lock{state.mutex};
    if (state.target_ns == target_ns) {
        return;
    }
    state.target_ns = target_ns;
    if (state.session) {
        api.update_target(state.session, target_ns);
    }
}

void ReportFrame() {
    const Api& api = GetApi();
    if (!api.Usable()) {
        return;
    }
    State& state = GetState();
    std::scoped_lock lock{state.mutex};
    const auto now = std::chrono::steady_clock::now();
    const auto previous = std::exchange(state.last_frame, now);
    state.Sync(api);
    if (!state.session || previous == std::chrono::steady_clock::time_point{}) {
        return;
    }
    const int64_t interval_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now - previous).count();
    // A long gap is a pause or a loading screen, not a slow frame.
    if (interval_ns > 0 && interval_ns < 4 * state.target_ns) {
        api.report_actual(state.session, interval_ns);
    }
}

void Shutdown() {
    const Api& api = GetApi();
    if (!api.Usable()) {
        return;
    }
    State& state = GetState();
    std::scoped_lock lock{state.mutex};
    state.CloseLocked(api);
    state.tids.clear();
    state.last_frame = {};
    state.target_ns = DEFAULT_TARGET_NS;
}

} // namespace Common::Android::PerformanceHint
