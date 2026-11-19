# Astra Emulator — Android Build

This branch adds an Android product flavor named `astra` to the existing Lemon-derived codebase. It provides a distinct application ID and launcher name while retaining the existing emulation engine and upstream flavors.

## Identity
- Display name: Astra Emulator
- Application ID: `id.suksesjt12.astraemu`
- ABI: `arm64-v8a`
- Minimum Android API: 31 (Android 12)

## Build variant
From `src/android`, build the debug-adjacent variant:

```sh
./gradlew assembleAstraRelWithDebInfo
```

The APK output is expected under `app/build/outputs/apk/astra/relWithDebInfo/`.

## Important status
This is an independent Android app identity/build flavor, not a new emulation engine written from scratch. The underlying native emulator code remains derived from the existing project and its licenses must be preserved. No APK has been built or tested in this workflow yet.
