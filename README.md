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

<h4 align="center">An Android-only Nintendo Switch emulator built for Adreno GPUs.</h4>

<p align="center">
  <a href="#about">About</a> |
  <a href="#features">Features</a> |
  <a href="#scope">Scope</a> |
  <a href="#building">Building</a> |
  <a href="#license">License</a>
</p>

## About

Lemon is a fork of [Eden](https://git.eden-emu.dev/eden-emu/eden), an open-source Switch emulator, trimmed down to
just the Android app and stripped of everything that isn't needed to build and run it on a single Adreno-equipped
Android device: no Qt/desktop/CLI targets, no multi-platform CI, no Mali/PowerVR-specific code paths.

Started as a personal build for the maintainer's own device(s); builds are now published on the
[Releases page](https://github.com/Ghael-V/Lemon-Project/releases) and the app checks for new ones on launch. It's
still a small, personal-scale project rather than a community one — there's no Discord and it isn't actively
looking for external contributions, but the source and releases are public under the GPL.

Most of Lemon's additions are Android-side features layered on top of unchanged core emulation, plus changes to
what gets built, how, and the app's branding/UX. The one exception is savestate: making Quick Save/Quick Load work
reliably on real games required real changes to the emulated kernel itself (how a thread parked mid-syscall is
captured and restored) — see [Features](#features) below.

## Features

Everything below is specific to Lemon, on top of the Switch emulation it inherits from Eden/yuzu:

- **Lemon Cheater** — a live memory search/edit tool built into the in-game menu, Cheat-Engine style: exact-value
  and blind (unknown-value) searches, refine by increased/decreased/unchanged across passes, direct value editing,
  and freezing a found address so it stays fixed (infinite HP/ammo/etc.) via a background rewrite thread, without
  needing a premade cheat code.
- **Input macros** — record a sequence of on-screen controller presses with their timing and play it back, looped
  or once, for repetitive farming/grinding or practicing a sequence.
- **Quick Save / Quick Load (experimental)** — same-session savestate. Required a real fix in the emulated kernel
  itself: a guest thread parked mid-syscall (waiting on a condvar, an IPC reply, a timer...) lives inside a host
  fiber whose true resume point isn't in the register/memory snapshot a savestate captures, so a naive restore left
  it resuming into a world that no longer matched what it expected and the game aborted. Restore now leaves any
  still-waiting thread's own context and stack untouched, and retries around waits that depend on another guest
  thread instead of forcing them. Verified working repeatedly on real, demanding titles, but still not 100%
  reliable in every game/moment — available in the [Nightly and Experimental prerelease
  builds](https://github.com/Ghael-V/Lemon-Project/releases), not yet in mainline.
- **Game usage stats** — automatic per-game playtime, last-played time and session count, surfaced as a
  "Continue playing" shortcut on the games list and a sortable ranking on a dedicated Statistics screen.
- **Carousel/grid/list browsing** with per-card usage badges, favorites, and search/filtering across your library.
- **In-app updates** — checks this repository's GitHub Releases on launch and can download/install the new APK
  directly, with no path (missing release, no connection) that crashes the app.
- **Adreno GPU driver manager** — install alternate Adreno graphics drivers per game, plus per-game performance
  presets, frame generation and post-processing options.
- Save data import/export, Amiibo loading, and ad-hoc multiplayer, same as upstream Eden.

## Scope

- **Android only.** Every desktop/CLI build target from upstream has been removed.
- **Adreno only.** Mali and PowerVR GPUs are explicitly out of scope and untested.
- **No keys, no firmware.** `prod.keys` and Switch firmware are not part of this repository and never will be —
  sourcing them from your own legally owned console is entirely on you. This fork does not touch key derivation,
  decryption, or DRM in any way; it only builds the emulator app.

## Building

Releases are built and published manually (see the [Releases page](https://github.com/Ghael-V/Lemon-Project/releases)
for prebuilt APKs). A [GitHub Actions workflow](.github/workflows/android-build.yml) exists to verify the build
still compiles, but it's manually triggered (`workflow_dispatch`), not run automatically on every push. To build
locally:

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

<a href="https://buymeacoffee.com/ghael">Si te ha gustado mi trabajo puedes invitarme a un café</a>
