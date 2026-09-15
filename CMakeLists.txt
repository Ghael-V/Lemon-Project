# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.31)

project(yuzu)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMakeModules")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMakeModules/find")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/externals/cmake-modules")

set(CPM_SOURCE_CACHE ${CMAKE_SOURCE_DIR}/.cache/cpm)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_CXX_SCAN_FOR_MODULES 0)

include(DetectPlatform)
include(DetectArchitecture)
include(DefaultConfig)
include(UseLTO)
include(FasterLinker)
include(UseCcache)
include(CMakeDependentOption)
include(CTest)
include(CPMUtil)

if (NOT DEFINED ARCHITECTURE)
    message(FATAL_ERROR "Architecture didn't make it out of scope, did you delete DetectArchitecture.cmake?")
endif()

option(ENABLE_UPDATE_CHECKER "Enable update checker (Android)" OFF)

option(ENABLE_WERROR "Enable -Werror diagnostics" ON)

# Lossless Scaling frame generation. Only Android.
cmake_dependent_option(ENABLE_LSFG "Enable Lossless Scaling frame generation" ON "ANDROID" OFF)

option(ENABLE_RESHADE "Enable ReShade FX post-processing effects" ON)

# TODO(crueter): Cleanup, each dep that has a bundled option should allow to choose between bundled, external, system
option(ENABLE_CUBEB "Enables the cubeb audio backend" ON)

set(EXT_DEFAULT OFF)
if (ANDROID)
    set(EXT_DEFAULT ON)
endif()

option(YUZU_USE_BUNDLED_SDL3 "Download bundled SDL3 build" ${EXT_DEFAULT})

# ffmpeg
option(YUZU_USE_BUNDLED_FFMPEG "Download bundled FFmpeg" ${EXT_DEFAULT})
cmake_dependent_option(YUZU_USE_EXTERNAL_FFMPEG "Build FFmpeg from external source" OFF "NOT ANDROID" OFF)

# sirit
set(BUNDLED_SIRIT_DEFAULT OFF)
if (ANDROID)
    set(BUNDLED_SIRIT_DEFAULT ON)
endif()

option(YUZU_USE_BUNDLED_SIRIT "Download bundled sirit" ${BUNDLED_SIRIT_DEFAULT})

cmake_dependent_option(ENABLE_LIBUSB "Enable the use of LibUSB" OFF "0" OFF)

cmake_dependent_option(ENABLE_OPENGL "Enable OpenGL" OFF "0" OFF)
mark_as_advanced(FORCE ENABLE_OPENGL)

option(ENABLE_WEB_SERVICE "Enable web services (telemetry, etc.)" ON)
option(ENABLE_WIFI_SCAN "Enable WiFi scanning" OFF)

option(YUZU_TESTS "Compile tests" "${BUILD_TESTING}")

option(YUZU_DOWNLOAD_ANDROID_VVL "Download validation layer binary for android" ON)

option(YUZU_LEGACY "Apply patches that improve compatibility with older GPUs (e.g. Snapdragon 865) at the cost of performance" OFF)

option(NIGHTLY_BUILD "Use Nightly qualifiers in the update checker and build metadata" OFF)

option(YUZU_DOWNLOAD_TIME_ZONE_DATA "Always download time zone binaries" ON)
set(YUZU_TZDB_PATH "" CACHE STRING "Path to a pre-downloaded timezone database")

option(YUZU_DISABLE_LLVM "Disable LLVM (useful for CI)" OFF)

set(DEFAULT_YUZU_USE_BUNDLED_OPENSSL OFF)
if (EXT_DEFAULT)
    set(DEFAULT_YUZU_USE_BUNDLED_OPENSSL ON)
endif()

option(YUZU_USE_BUNDLED_OPENSSL "Download bundled OpenSSL build" ${DEFAULT_YUZU_USE_BUNDLED_OPENSSL})

if (ANDROID AND YUZU_DOWNLOAD_ANDROID_VVL)
    AddJsonPackage(vulkan-validation-layers)

    set(abi ${CMAKE_ANDROID_ARCH_ABI})

    set(vvl_lib_path "${CMAKE_CURRENT_SOURCE_DIR}/src/android/app/src/main/jniLibs/${abi}/")
    file(COPY "${vulkan-validation-layers_SOURCE_DIR}/${abi}/libVkLayer_khronos_validation.so"
        DESTINATION "${vvl_lib_path}")
endif()

if(EXISTS ${PROJECT_SOURCE_DIR}/hooks/pre-commit AND NOT EXISTS ${PROJECT_SOURCE_DIR}/.git/hooks/pre-commit)
    if (EXISTS ${PROJECT_SOURCE_DIR}/.git/)
        message(STATUS "Copying pre-commit hook")
        file(COPY hooks/pre-commit DESTINATION ${PROJECT_SOURCE_DIR}/.git/hooks)
    endif()
endif()

if (ARCHITECTURE_arm64)
    set(HAS_NCE 1)
    add_compile_definitions(HAS_NCE=1)
endif()

# Android's NDK libc++ (Clang) ships std::stop_token/std::jthread as
# experimental and gates them behind this flag.
add_compile_options(
    $<$<COMPILE_LANGUAGE:C,CXX>:-fexperimental-library>)

# Build/optimization presets
if (CXX_GCC OR CXX_CLANG)
    if (ARCHITECTURE_x86_64)
        # See https://gcc.gnu.org/onlinedocs/gcc/x86-Options.html
        # Generic supports Pentium Pro instruction set and above
        # TODO: if a value is unknown, pass that as march and mtune
        set(YUZU_BUILD_PRESET "custom" CACHE STRING "Build preset to use. One of: custom, generic, v3, zen2, zen4, native")

        if (${YUZU_BUILD_PRESET} STREQUAL "generic")
            set(march x86-64)
            set(mtune generic)
        elseif (${YUZU_BUILD_PRESET} STREQUAL "v3")
            set(march x86-64-v3)
            set(mtune generic)
        elseif (${YUZU_BUILD_PRESET} STREQUAL "zen2")
            set(march znver2)
            set(mtune znver2)
        elseif (${YUZU_BUILD_PRESET} STREQUAL "zen4")
            set(march znver4)
            set(mtune znver4)
        endif()
    elseif(ARCHITECTURE_arm64)
        # See https://gcc.gnu.org/onlinedocs/gcc/AArch64-Options.html
        set(YUZU_BUILD_PRESET "custom" CACHE STRING "Build preset to use. One of: custom, generic, optimized, armv9, native")
        set(mtune generic)
        if (${YUZU_BUILD_PRESET} STREQUAL "generic")
            set(march armv8-a)
        elseif (${YUZU_BUILD_PRESET} STREQUAL "optimized")
            set(march armv8.2-a+lse+rcpc)
        elseif (${YUZU_BUILD_PRESET} STREQUAL "armv9")
            set(march armv9-a)
        endif()
    endif()

    if ("${YUZU_BUILD_PRESET}" STREQUAL "native")
        set(march native)
        set(mtune native)
    endif()

    if (DEFINED march AND DEFINED mtune)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=${march} -mtune=${mtune}")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=${march} -mtune=${mtune}")
    endif()
endif()

# Configure C++ standard
# ===========================

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Output binaries to bin/
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${PROJECT_BINARY_DIR}/bin)

# System imported libraries
# =======================================================================

set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)

find_package(RenderDoc MODULE)

# openssl funniness
if (YUZU_USE_BUNDLED_OPENSSL)
    set(BUILD_SHARED_LIBS OFF)
    AddJsonPackage(openssl-ci)
else()
    AddJsonPackage(openssl)
    set(OPENSSL_BUILD_VERBOSE ON)
    set(OPENSSL_CONFIGURE_VERBOSE ON)

    if (OpenSSL_ADDED)
        AddJsonPackage(openssl-cmake)
    endif()
endif()

if (OpenSSL_ADDED)
    add_compile_definitions(YUZU_BUNDLED_OPENSSL)
endif()

message(STATUS "Fetching needed dependencies with CPM")

set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF)
set(ENABLE_TESTING OFF)

# boost
set(BOOST_INCLUDE_LIBRARIES algorithm icl pool container heap asio headers process filesystem crc variant)

AddJsonPackage(boost)

# really annoying thing where boost::headers doesn't work with cpm
# TODO(crueter) investigate
set(BOOST_NO_HEADERS ${Boost_ADDED})

if (Boost_ADDED)
    add_compile_definitions(YUZU_BOOST_v1)
    if (NOT MSVC OR CXX_CLANG)
        target_compile_options(boost_heap INTERFACE $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-shadow>)
        target_compile_options(boost_icl INTERFACE $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-shadow>)
        target_compile_options(boost_asio INTERFACE
            $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-conversion>
            $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-implicit-fallthrough>)
    endif()
endif()

# fmt
AddJsonPackage(fmt)

# lz4
AddJsonPackage(lz4)

if (lz4_ADDED)
    add_library(lz4::lz4 ALIAS lz4_static)
endif()

# zstd
AddJsonPackage(zstd)

if (zstd_ADDED)
    add_library(zstd::zstd ALIAS libzstd_static)
    add_library(zstd::libzstd ALIAS libzstd_static)
endif()

# nlohmann
AddJsonPackage(nlohmann)

# zlib
AddJsonPackage(zlib)

if (ZLIB_ADDED)
    add_library(ZLIB::ZLIB ALIAS zlibstatic)
endif()

# Opus
AddJsonPackage(opus)

if (NOT TARGET Opus::opus)
    add_library(Opus::opus ALIAS opus)
endif()

if(NOT TARGET Boost::headers)
    AddJsonPackage(boost_headers)
endif()

# List of all FFmpeg components required
set(FFmpeg_COMPONENTS
    avcodec
    avfilter
    avutil
    swscale)

# This function should be passed a list of all files in a target. It will automatically generate
# file groups following the directory hierarchy, so that the layout of the files in IDEs matches the
# one in the filesystem.
function(create_target_directory_groups target_name)
    # Place any files that aren't in the source list in a separate group so that they don't get in
    # the way.
    source_group("Other Files" REGULAR_EXPRESSION ".")

    get_target_property(target_sources "${target_name}" SOURCES)

    foreach(file_name IN LISTS target_sources)
        get_filename_component(dir_name "${file_name}" PATH)
        # Group names use '\' as a separator even though the entire rest of CMake uses '/'...
        string(REPLACE "/" "\\" group_name "${dir_name}")
        source_group("${group_name}" FILES "${file_name}")
    endforeach()
endfunction()

add_subdirectory(externals)

# pass targets from externals
# TODO(crueter): CPMUtil Propagate func?
find_package(enet)

if (ARCHITECTURE_x86 OR ARCHITECTURE_x86_64)
    find_package(xbyak)
endif()

find_package(VulkanMemoryAllocator)
find_package(VulkanUtilityLibraries)
find_package(SimpleIni)
find_package(sirit)
find_package(gamemode)
find_package(frozen)

if (ENABLE_WEB_SERVICE OR ENABLE_UPDATE_CHECKER)
    find_package(cpp-jwt)
endif()

if (ARCHITECTURE_arm64 OR DYNARMIC_TESTS)
    find_package(oaknut)
endif()

if (ENABLE_CUBEB)
    find_package(cubeb)
endif()

if (YUZU_TESTS OR DYNARMIC_TESTS)
    find_package(Catch2)
endif()

if (NOT (YUZU_USE_BUNDLED_FFMPEG OR YUZU_USE_EXTERNAL_FFMPEG))
    # Use system installed FFmpeg
    find_package(FFmpeg REQUIRED QUIET COMPONENTS ${FFmpeg_COMPONENTS})

    # TODO(crueter): Version
    set_property(GLOBAL APPEND PROPERTY CPM_PACKAGE_NAMES FFmpeg)
    set_property(GLOBAL APPEND PROPERTY CPM_PACKAGE_SHAS "unknown (system)")
    set_property(GLOBAL APPEND PROPERTY CPM_PACKAGE_URLS "https://github.com/FFmpeg/FFmpeg")
endif()

# Include source code
# ===================

add_subdirectory(src)
