// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Lemon Online: the link between the app's room screens and the core's RoomMember.
// The service (HTTP) hands the app a relay address and a one-use ticket; this file only joins and
// leaves that relay and reports back what the RoomMember sees. Lemon keeps one global RoomMember,
// created by Network::Init() when the app starts; every game's LDN and socket services use it.
// Nothing here calls Network::Shutdown(): the RoomMember lives as long as the app.
// Tickets are credentials: they are never logged.

#include <mutex>
#include <string>
#include <vector>

#include <jni.h>

#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/logging.h"
#include "core/memory/cheat_engine.h"
#include "network/network.h"
#include "network/room_member.h"

namespace {

// Global refs and method ids of the Kotlin OnlineRoom object, cached on the first call from the UI
// thread: FindClass on ENet's thread would only see the system class loader.
jclass s_class = nullptr;
jmethodID s_on_state = nullptr;
jmethodID s_on_error = nullptr;
jmethodID s_on_members = nullptr;
std::once_flag s_bind_once;

Network::RoomMember::CallbackHandle<Network::RoomMember::State> s_state_handle;
Network::RoomMember::CallbackHandle<Network::RoomMember::Error> s_error_handle;
Network::RoomMember::CallbackHandle<Network::RoomInformation> s_info_handle;

std::shared_ptr<Network::RoomMember> Member() {
    return Network::GetRoomMember().lock();
}

// Members as "nickname<US>game name<US>username" so Kotlin gets one flat String[]. The
// relay's username is "u" + the member's friend code (empty for anonymous members).
void SendMembers(const Network::RoomMember& member) {
    JNIEnv* env = Common::Android::GetEnvForThread();
    const auto& list = member.GetMemberInformation();
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(list.size()), string_class, nullptr);
    for (size_t i = 0; i < list.size(); ++i) {
        const std::string entry = list[i].nickname + '\x1f' + list[i].game_info.name + '\x1f' +
                                  list[i].username;
        jstring item = Common::Android::ToJString(env, entry);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    env->CallStaticVoidMethod(s_class, s_on_members, array);
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(string_class);
}

void BindCallbacks(JNIEnv* env, jclass clazz) {
    s_class = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    s_on_state = env->GetStaticMethodID(s_class, "onNativeState", "(I)V");
    s_on_error = env->GetStaticMethodID(s_class, "onNativeError", "(I)V");
    s_on_members = env->GetStaticMethodID(s_class, "onNativeMembers", "([Ljava/lang/String;)V");

    if (!Member()) {
        // The app normally creates it at start-up; make sure it exists before binding to it.
        Network::Init();
    }
    auto member = Member();
    if (!member) {
        LOG_ERROR(Network, "Online: no room member to bind to");
        return;
    }
    // The callbacks run on the RoomMember's network thread. The handles stay alive for the
    // app's lifetime: the RoomMember outlives every room.
    s_state_handle = member->BindOnStateChanged([](const Network::RoomMember::State& state) {
        JNIEnv* jni = Common::Android::GetEnvForThread();
        jni->CallStaticVoidMethod(s_class, s_on_state, static_cast<jint>(state));
    });
    s_error_handle = member->BindOnError([](const Network::RoomMember::Error& error) {
        LOG_INFO(Network, "Online: room error {}", static_cast<int>(error));
        JNIEnv* jni = Common::Android::GetEnvForThread();
        jni->CallStaticVoidMethod(s_class, s_on_error, static_cast<jint>(error));
    });
    s_info_handle = member->BindOnRoomInformationChanged([](const Network::RoomInformation&) {
        // Called right after the member list was rewritten, on the same thread: safe to read.
        if (auto room_member = Member()) {
            SendMembers(*room_member);
        }
    });
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL Java_dev_lemon_lemon_1emu_online_OnlineRoom_nativeBind(
    JNIEnv* env, jclass clazz) {
    std::call_once(s_bind_once, [&] { BindCallbacks(env, clazz); });
}

// Blocks while ENet connects (up to a few seconds): call it off the UI thread.
JNIEXPORT void JNICALL Java_dev_lemon_lemon_1emu_online_OnlineRoom_nativeJoin(
    JNIEnv* env, jclass, jstring j_host, jint port, jstring j_nickname, jstring j_ticket) {
    auto member = Member();
    if (!member) {
        return;
    }
    if (member->IsConnected()) {
        member->Leave();
    }
    const std::string host = Common::Android::GetJString(env, j_host);
    const std::string nickname = Common::Android::GetJString(env, j_nickname);
    const std::string ticket = Common::Android::GetJString(env, j_ticket);
    LOG_INFO(Network, "Online: joining relay {}:{}", host, port);
    // The room password stays empty: the service already checked it, the ticket is the pass.
    member->Join(nickname, host.c_str(), static_cast<u16>(port), 0, Network::NoPreferredIP, "",
                 ticket);
}

JNIEXPORT void JNICALL Java_dev_lemon_lemon_1emu_online_OnlineRoom_nativeLeave(
    JNIEnv*, jclass) {
    if (auto member = Member(); member && member->IsConnected()) {
        LOG_INFO(Network, "Online: leaving the room");
        member->Leave();
    }
}

JNIEXPORT jint JNICALL Java_dev_lemon_lemon_1emu_online_OnlineRoom_nativeState(
    JNIEnv*, jclass) {
    auto member = Member();
    return member ? static_cast<jint>(member->GetState()) : 0;
}

// A room that doesn't allow cheats: stop the cheat engine and the memory cheater while in it.
JNIEXPORT void JNICALL Java_dev_lemon_lemon_1emu_online_OnlineRoom_nativeSetCheatsBlocked(JNIEnv* env, jclass clazz,
                                                                  jboolean blocked) {
    Core::Memory::SetCheatsBlocked(blocked == JNI_TRUE);
}

} // extern "C"
