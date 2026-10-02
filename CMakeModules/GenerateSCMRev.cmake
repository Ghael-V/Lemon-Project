# SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: 2019 yuzu Emulator Project
# SPDX-License-Identifier: GPL-2.0-or-later

# generate git/build information
include(GetSCMRev)

function(get_timestamp _var)
    string(TIMESTAMP timestamp UTC)
    set(${_var} "${timestamp}" PARENT_SCOPE)
endfunction()

get_timestamp(BUILD_DATE)

if (DEFINED GIT_RELEASE)
    set(BUILD_VERSION "${GIT_TAG}")
    set(GIT_REFSPEC "${GIT_RELEASE}")
    set(IS_DEV_BUILD false)
else()
    string(SUBSTRING ${GIT_COMMIT} 0 10 BUILD_VERSION)
    set(BUILD_VERSION "${BUILD_VERSION}-${GIT_REFSPEC}")
    set(IS_DEV_BUILD true)
endif()

if (NIGHTLY_BUILD)
    set(IS_NIGHTLY_BUILD true)
else()
    set(IS_NIGHTLY_BUILD false)
endif()

set(GIT_DESC ${BUILD_VERSION})

# Generate cpp with Git revision from template

# Updates come from Lemon's own Forgejo server; the GitHub repo is only a mirror, kept as a
# fallback for when the server cannot be reached (or has no release yet). Both speak the same
# release JSON (tag_name, name, body, assets[].browser_download_url), so one parser serves both.
# The owner/repo of the Forgejo repository is set here and nowhere else.
set(LEMON_GIT_HOST "git.lemon-emu.org")
set(LEMON_GIT_REPO "lemon/Lemon-Project")
set(LEMON_GITHUB_HOST "api.github.com")
set(LEMON_GITHUB_REPO "Ghael-V/Lemon-Project")

# All branches are tagged and released identically, so there is no nightly-vs-stable split.
set(BUILD_AUTO_UPDATE_STABLE_REPO "${LEMON_GIT_REPO}")
set(BUILD_AUTO_UPDATE_STABLE_API "${LEMON_GIT_HOST}")
set(BUILD_AUTO_UPDATE_STABLE_API_PATH "api/v1/repos")

set(BUILD_AUTO_UPDATE_API_PATH "/api/v1/repos/${LEMON_GIT_REPO}/releases/latest")
set(BUILD_AUTO_UPDATE_WEBSITE "https://${LEMON_GIT_HOST}")
set(BUILD_AUTO_UPDATE_API "${LEMON_GIT_HOST}")
set(BUILD_AUTO_UPDATE_REPO "${LEMON_GIT_REPO}")

set(BUILD_AUTO_UPDATE_FALLBACK_API "${LEMON_GITHUB_HOST}")
set(BUILD_AUTO_UPDATE_FALLBACK_API_PATH "/repos/${LEMON_GITHUB_REPO}/releases/latest")
set(BUILD_AUTO_UPDATE_FALLBACK_REPO "${LEMON_GITHUB_REPO}")
if (NIGHTLY_BUILD)
    set(REPO_NAME "Lemon Nightly")
else()
    set(REPO_NAME "Lemon")
endif()

set(BUILD_ID ${GIT_REFSPEC})
set(BUILD_FULLNAME "${REPO_NAME} ${BUILD_VERSION} ")
set(CXX_COMPILER "${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")

configure_file(scm_rev.cpp.in scm_rev.cpp @ONLY)
