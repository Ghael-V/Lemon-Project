# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: 2018 yuzu Emulator Project
# SPDX-License-Identifier: GPL-2.0-or-later

# Enable modules to include each other's files
include_directories(.)

# Dynarmic
if ((ARCHITECTURE_x86_64 OR ARCHITECTURE_arm64 OR ARCHITECTURE_riscv64 OR ARCHITECTURE_loongarch64) AND NOT YUZU_STATIC_ROOM)
    add_subdirectory(dynarmic)
    add_library(dynarmic::dynarmic ALIAS dynarmic)
endif()

# CMake seems to only define _DEBUG on Windows
set_property(DIRECTORY APPEND PROPERTY
    COMPILE_DEFINITIONS $<$<CONFIG:Debug>:_DEBUG> $<$<NOT:$<CONFIG:Debug>>:NDEBUG>)

if (YUZU_STATIC_BUILD)
    add_compile_definitions(QT_STATICPLUGIN)
endif()

# Build identifiers
if (NIGHTLY_BUILD)
    add_compile_definitions(NIGHTLY_BUILD)
endif()

# Legacy (android only)
if (YUZU_LEGACY)
    message(WARNING "Making legacy build. Performance may suffer.")
    add_compile_definitions(YUZU_LEGACY)
endif()

# Genshin Spoof (android only)
if (GENSHIN_SPOOF)
    message(WARNING "Making Genshin spoof build")
    add_compile_definitions(GENSHIN_SPOOF)
endif()

# Build ID (mingw only right now)
# Pretty much just refers to the CI "target" for this build
if (NOT BUILD_ID)
    if (ARCHITECTURE_x86_64)
        set(BUILD_ID amd64)
    elseif(ARCHITECTURE_arm64)
        if (WIN32)
            set(BUILD_ID arm64)
        else()
            set(BUILD_ID aarch64)
        endif()
    else()
        set(BUILD_ID "${ARCHITECTURE}")
    endif()
endif()

add_compile_definitions(BUILD_ID="${BUILD_ID}")

# Set compilation flags (Android always builds with Clang via the NDK)
add_compile_options(
    $<$<COMPILE_LANGUAGE:C,CXX>:-fwrapv>
    $<$<COMPILE_LANGUAGE:C,CXX>:-pipe>
    # Disable RTTI (C++ only)
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)

if (ENABLE_WERROR)
    add_compile_options(
        $<$<COMPILE_LANGUAGE:C,CXX>:-Werror=all>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Werror=extra>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Werror=missing-declarations>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Werror=shadow>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Werror=unused>)
else()
    add_compile_options(
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wall>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wextra>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wmissing-declarations>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wshadow>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wunused>
        # Some compilers could particularly misbehave :)
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-error-all>)
endif()
add_compile_options(
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-attributes>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-invalid-offsetof>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-unused-parameter>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-missing-field-initializers>)

add_compile_options(
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wshadow-uncaptured-local>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wimplicit-fallthrough>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wtype-limits>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-braced-scalar-init>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-unused-private-field>
    $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-nullability-completeness>)

if (ARCHITECTURE_x86_64)
    add_compile_options($<$<COMPILE_LANGUAGE:C,CXX>:-mcx16>)
endif()

add_subdirectory(common)
add_subdirectory(network)

add_subdirectory(core)
add_subdirectory(audio_core)
add_subdirectory(video_core)
add_subdirectory(hid_core)
add_subdirectory(input_common)
add_subdirectory(frontend_common)
add_subdirectory(shader_recompiler)

if (YUZU_TESTS)
    add_subdirectory(tests)
endif()

if (ENABLE_WEB_SERVICE)
    add_subdirectory(web_service)
endif()

add_subdirectory(android/app/src/main/jni)
target_include_directories(yuzu-android PRIVATE android/app/src/main)

include(GenerateDepHashes)
