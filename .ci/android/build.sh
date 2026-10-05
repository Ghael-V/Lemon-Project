#!/bin/sh -e

# SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

NUM_JOBS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)
export CMAKE_BUILD_PARALLEL_LEVEL="${NUM_JOBS}"
ARTIFACTS_DIR="$PWD/artifacts"

: "${CCACHE:=false}"
RETURN=0

usage() {
    cat <<EOF
Usage: $0 [-t|--target FLAVOR] [-b|--build-type BUILD_TYPE]
       [-h|--help] [-r|--release] [-n|--nightly] [-e|--experimental] [extra options]

Build script for Android.
Associated variables can be set outside the script,
and will apply both to this script and the packaging script.
bool values are "true" or "false"

Options:
    -r, --release        	Enable update checker. If set, sets the DEVEL bool variable to false.
                         	By default, DEVEL is true.
    -t, --target <FLAVOR> 	Build flavor (variable: TARGET)
                          	Valid values are: standard, lite, lite-spoofed, legacy, optimized
                          	Default: standard
    -b, --build-type <TYPE>	Build type (variable: TYPE)
                          	Valid values are: Release, RelWithDebInfo, Debug
                          	Default: Debug
    -n, --nightly           Create a nightly build.
    -e, --experimental      Create an experimental build (own applicationId, coexists with
                            nightly/mainline on the same device).
    -f, --fast              Development build: keeps the native build folder between commits so
                            only changed files are recompiled. The version the app reports may
                            lag behind the commit. Never for a release.

Extra arguments are passed to CMake (e.g. -DCMAKE_OPTION_NAME=VALUE)
Set the CCACHE variable to "true" to enable build caching.
The APK and AAB will be output into "artifacts".

EOF

    exit "$RETURN"
}

die() {
	echo "-- ! $*" >&2
	RETURN=1 usage
}

target() {
    [ -z "$1" ] && die "You must specify a valid target."

    TARGET="$1"
}

type() {
    [ -z "$1" ] && die "You must specify a valid type."

    TYPE="$1"
}

while true; do
	case "$1" in
		-r|--release) DEVEL=false ;;
		-t|--target) target "$2"; shift ;;
		-b|--build-type) type "$2"; shift ;;
        -n|--nightly) NIGHTLY=true ;;
        -e|--experimental) EXPERIMENTAL=true ;;
        -f|--fast) FAST=true ;;
		-h|--help) usage ;;
		*) break ;;
	esac

	shift
done

: "${TARGET:=standard}"
: "${TYPE:=Release}"
: "${DEVEL:=true}"

# GetSCMRev.cmake only uses the clean git tag (e.g. "v0.2.3") as the build version when a
# GIT-RELEASE marker file exists at the repo root when CMake configures - otherwise it falls
# back to a "<commit-hash>-<branch>" string, which would never match a GitHub release tag and
# would make the update checker think every launch has a new version available. CI presumably
# creates this itself; our manual -r builds need to create (and clean up) it ourselves.
if [ "$DEVEL" != "true" ] && [ ! -e GIT-RELEASE ]; then
    echo "release" > GIT-RELEASE
    # Absolute path: the trap fires wherever CWD happens to be by then, and the script cd's
    # into src/android further down - a relative path here would silently clean up nothing.
    trap "rm -f '$PWD/GIT-RELEASE'" EXIT
fi

TARGET_LOWER=$(echo "$TARGET" | tr '[:upper:]' '[:lower:]')

case "$TARGET_LOWER" in
	lite) FLAVOR=Lite ;;
	lite-spoofed) FLAVOR=LiteSpoofed ;;
	legacy) FLAVOR=Legacy ;;
	optimized) FLAVOR=GenshinSpoof ;;
	standard) FLAVOR=Mainline ;;
	*) die "Invalid build flavor $TARGET."
esac

case "$TYPE" in
	RelWithDebInfo|Release|Debug) ;;
	*) die "Invalid build type $TYPE."
esac

LOWER_FLAVOR=$(echo "$FLAVOR" | sed 's/./\L&/')
LOWER_TYPE=$(echo "$TYPE" | sed 's/./\L&/')

if [ -n "${ANDROID_KEYSTORE_B64}" ]; then
    export ANDROID_KEYSTORE_FILE="${GITHUB_WORKSPACE}/ks.jks"
    echo "${ANDROID_KEYSTORE_B64}" | base64 --decode > "${ANDROID_KEYSTORE_FILE}"
	SHA1SUM=$(keytool -list -v -storepass "${ANDROID_KEYSTORE_PASS}" -keystore "${ANDROID_KEYSTORE_FILE}" | grep SHA1 | cut -d " " -f3)
	echo "-- Keystore SHA1 is ${SHA1SUM}"
fi

cd src/android
chmod +x ./gradlew

set -- "$@" -DUSE_CCACHE="${CCACHE}"

nightly() {
    [ "$NIGHTLY" = "true" ]
}

experimental() {
    [ "$EXPERIMENTAL" = "true" ]
}

if nightly || [ "$DEVEL" != "true" ]; then
    set -- "$@" -DENABLE_UPDATE_CHECKER=ON
fi

if nightly; then
    NIGHTLY=true
else
    NIGHTLY=false
fi

if experimental; then
    EXPERIMENTAL=true
else
    EXPERIMENTAL=false
fi

echo "-- building..."

./gradlew "copy${FLAVOR}${TYPE}Outputs" \
    -Dorg.gradle.caching="${CCACHE}" \
    -Dorg.gradle.parallel="${CCACHE}" \
    -Dorg.gradle.workers.max="${NUM_JOBS}" \
    -PYUZU_ANDROID_ARGS="$*" \
    -Pnightly="$NIGHTLY" \
    -Pexperimental="$EXPERIMENTAL" \
    -PfastDev="${FAST:-false}" \
    --info

if [ -n "${ANDROID_KEYSTORE_B64}" ]; then
    rm "${ANDROID_KEYSTORE_FILE}"
fi

echo "-- Done! APK and AAB artifacts are in ${ARTIFACTS_DIR}"

# The file a release is published as: "Lemon-<tag>-<variant>.apk" instead of Gradle's
# "app-<flavor>-<type>.apk". The in-app updater finds a variant's APK by the END of the name
# (src/common/net/net.cpp), so the suffix here must match the one that variant looks for, and the
# name carries no spaces or "&" (GitHub rewrites them). Only a commit that carries a tag gets that
# name: "git describe --abbrev=0" handed an untagged commit the previous release's tag.
case "$TARGET_LOWER" in
    standard) RELEASE_PREFIX=Lemon; RELEASE_SUFFIX=standard ;;
    lite) RELEASE_PREFIX=Lemon-Lite; RELEASE_SUFFIX=lite ;;
    lite-spoofed) RELEASE_PREFIX=Lemon-Lite-Spoofed; RELEASE_SUFFIX=lite-spoofed ;;
    *) RELEASE_PREFIX= ;;
esac
if [ "$DEVEL" != "true" ] && [ -n "$RELEASE_PREFIX" ]; then
    RELEASE_TAG=$(git describe --tags --exact-match 2>/dev/null || true)
    RELEASE_FILE="${RELEASE_PREFIX}-${RELEASE_TAG}-${RELEASE_SUFFIX}.apk"
    if [ -z "$RELEASE_TAG" ]; then
        echo "-- This commit has no tag: no ${RELEASE_PREFIX}-<tag>-${RELEASE_SUFFIX}.apk was made"
    else
        cp -f "${ARTIFACTS_DIR}/app-${LOWER_FLAVOR}-${LOWER_TYPE}.apk" "${ARTIFACTS_DIR}/${RELEASE_FILE}"
        echo "-- Release file: ${RELEASE_FILE}"
    fi
fi

ls -l "${ARTIFACTS_DIR}/"
