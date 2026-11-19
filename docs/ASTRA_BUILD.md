# Astra Emulator — Android Build

This branch adds an Android product flavor named `astra` to the existing Lemon-derived codebase. It gives the app a distinct display name and application ID while retaining the existing emulator engine and upstream flavors.

## App identity
- Display name: Astra Emulator
- Release application ID: `id.suksesjt12.astraemu`
- ABI: `arm64-v8a`
- Minimum Android API: 31 (Android 12)

The `debug` and `relWithDebInfo` build types append their own application ID suffixes. The `release` build uses the base application ID.

## Build

From the repository root:

```sh
CCACHE=true .ci/android/build.sh -t astra -b Release
```

The build helper creates the APK and AAB in the repository's `artifacts/` directory. The corresponding Gradle task is `copyAstraReleaseOutputs`.

Without release signing secrets, the existing Gradle configuration signs the Release APK with the repository's default debug keystore. It can be sideloaded for testing, but it is not a production Play Store signing setup.

GitHub Actions builds this Astra Release variant on pushes to `mali-g57-optimization` that touch the Android source/build files, and can also be started manually. It uploads the APK and AAB as a workflow artifact.

## Important compatibility boundary

This change establishes a separate Android application identity and build variant. It does **not** replace the underlying emulator engine or prove Mali-G57 compatibility. The engine remains derived from the existing Lemon codebase. Mali-G57 graphics behavior, Vulkan capability checks, game boot, and sustained stability still require source-level review and testing on real hardware.

Preserve all upstream notices and comply with the repository's GPL-3.0-or-later license and the licenses of bundled dependencies.
