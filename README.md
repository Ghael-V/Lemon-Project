<!--
# SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: 2018 yuzu Emulator Project
# SPDX-License-Identifier: GPL-2.0-or-later
-->
<!-- lang: en-GB -->

<h1 align="center">
  <br>
  <img src="./src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Lemon" width="200">
  <br>
  <b>Lemon</b>
  <br>
</h1>

<h4 align="center">A private, Android-only Nintendo Switch emulator built for Adreno GPUs.</h4>

<p align="center">
  <a href="#about">About</a> |
  <a href="#scope">Scope</a> |
  <a href="#building">Building</a> |
  <a href="#license">License</a>
</p>

## About

Lemon is a fork of [Eden](https://git.eden-emu.dev/eden-emu/eden), an open-source Switch emulator, trimmed down to
just the Android app and stripped of everything that isn't needed to build and run it on a single Adreno-equipped
Android device: no Qt/desktop/CLI targets, no multi-platform CI, no Mali/PowerVR-specific code paths.

This is a **private** project, not a public distribution — there's no download page, no community Discord, and no
open call for contributions. It exists to run on the maintainer's own device(s).

Emulation behavior itself is unchanged from upstream Eden; this fork only changes what gets built, how, and the
Android app's branding/UX around it.

## Scope

- **Android only.** Every desktop/CLI build target from upstream has been removed.
- **Adreno only.** Mali and PowerVR GPUs are explicitly out of scope and untested.
- **No keys, no firmware.** `prod.keys` and Switch firmware are not part of this repository and never will be —
  sourcing them from your own legally owned console is entirely on you. This fork does not touch key derivation,
  decryption, or DRM in any way; it only builds the emulator app.

## Building

Every push to `main` builds automatically via [GitHub Actions](.github/workflows/android-build.yml), so a local
Android Studio setup usually isn't necessary just to get an APK. To build locally:

**Dependencies:** [Android Studio](https://developer.android.com/studio), NDK 27+ and CMake 3.22.1 (installable
from Android Studio's SDK Manager), and Git.

```sh
git clone --recursive https://github.com/Ghael-V/Lemon-Project.git
```

Then either open `Lemon-Project/src/android` in Android Studio and use `Run > Run 'app'`, or from a terminal:

```sh
export ANDROID_SDK_ROOT=path/to/sdk
export ANDROID_NDK_ROOT=path/to/ndk
cd Lemon-Project/src/android
./gradlew assembleMainlineRelWithDebInfo
```

`.ci/android/build.sh` (used by CI) wraps the same Gradle build with a friendlier flavor/build-type flag interface —
run it with `--help` for details.

## License

Lemon, like Eden, is licensed under the GPLv3 (or any later version). Refer to [LICENSE.txt](./LICENSE.txt).

## Special thanks

This project stands on the work of Eden and, before it, yuzu — and the broader community of Switch emulator forks
that came out of yuzu's shutdown:

- Yuzu
- Ryujinx
- Sudachi
- Citron
- Torzu
- Suyu
- Ryubing

And everyone who continues or has contributed to any of them. <3
