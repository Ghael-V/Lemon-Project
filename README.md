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
  <img src="./dist/lemon-logo.svg" alt="Lemon" width="200">
  <br>
  <b>Lemon</b>
  <br>
</h1>

<h4 align="center">An Android-only Nintendo Switch emulator built for Adreno GPUs.</h4>

<p align="center">
  <a href="https://lemon-emu.org">Website</a> |
  <a href="https://git.lemon-emu.org/lemon/Lemon-Project">Main repository</a> |
  <a href="https://lemon-emu.org/faq.html">FAQ</a> |
  <a href="https://lemon-emu.org/guide.html">Settings guide</a> |
  <a href="https://discord.com/invite/PEE7Q5TVM5">Discord</a>
</p>

> The main repository and the releases live at <https://git.lemon-emu.org/lemon/Lemon-Project>.
> This GitHub repository is a mirror. For help or to report a problem, use the Discord.

<p align="center">
  <a href="https://git.lemon-emu.org/lemon/Lemon-Project/releases/latest">
    <img src="https://img.shields.io/gitea/v/release/lemon/Lemon-Project?gitea_url=https%3A%2F%2Fgit.lemon-emu.org&label=latest%20release&color=success" alt="Latest release">
  </a>
  <a href="./LICENSE.txt">
    <img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="License: GPL-3.0-or-later">
  </a>
  <a href="https://github.com/Ghael-V/Lemon-Project/stargazers">
    <img src="https://img.shields.io/github/stars/Ghael-V/Lemon-Project?style=flat&color=yellow&label=GitHub%20mirror%20stars" alt="GitHub mirror stars">
  </a>
  <a href="https://github.com/Ghael-V">
    <img src="https://img.shields.io/badge/dev-Ghael--V-blueviolet" alt="Developer">
  </a>
</p>

<p align="center">
  <a href="#about">About</a> |
  <a href="#features">Features</a> |
  <a href="#changelog">Changelog</a> |
  <a href="#scope">Scope</a> |
  <a href="#building">Building</a> |
  <a href="#license">License</a>
</p>

## About

Lemon is an Android-only Nintendo Switch emulator built for Adreno GPUs. It runs on the open-source emulation
core developed by the yuzu community and the projects that followed it, trimmed down to just what's needed to build
and run it on a single Adreno-equipped Android device: no Qt/desktop/CLI targets, no multi-platform CI, no
Mali/PowerVR-specific code paths.

Started as a personal build for the maintainer's own device(s); builds are now published on the
[Releases page](https://git.lemon-emu.org/lemon/Lemon-Project/releases) of Lemon's own server and the app checks
for new ones on launch (with the GitHub mirror as a fallback). It's
still a small, personal-scale project rather than a community one — there's a [Discord](https://discord.com/invite/PEE7Q5TVM5)
for feedback and support, but it isn't actively looking for external contributions; the source and releases are
public under the GPL.

Most of Lemon's additions are Android-side features layered on top of unchanged core emulation, plus changes to
what gets built, how, and the app's branding/UX. The one exception is savestate: making Quick Save/Quick Load work
reliably on real games required real changes to the emulated kernel itself (how a thread parked mid-syscall is
captured and restored) — see [Features](#features) below.

## Features

Everything below is specific to Lemon, on top of the Switch emulation it inherits from Eden/yuzu:

- **Redesigned interface** — a launcher-style library (carousel, grid or list), a quick in-game panel with a live
  performance card, redesigned Settings, About and Statistics screens, and full controller navigation, including
  gamepad shortcuts to open the in-game menu.
- **Lemon Cheater** — a live memory search/edit tool built into the in-game menu, Cheat-Engine style: exact-value
  and blind (unknown-value) searches over 32-bit integers and floats (a float matches within the precision you
  typed), refine by increased/decreased/unchanged across passes, direct value editing,
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
  reliable in every game/moment.
- **Controller layout presets** — a "Diseño del mando" entry in the pause menu with one-tap presets (default, big
  buttons, swapped D-pad/stick) plus quick access to the existing drag-and-resize edit mode, which used to be
  buried two menus deep with no indication it existed.
- **Controller layout per game** — move and resize the on-screen controls for one game only, or for all of them.
- **Reset all settings** — a red card at the bottom of Settings that puts every setting back to a fresh install's
  defaults, optionally also deleting every game's custom settings. Game folders, storage locations and each game's
  add-on choices are kept (unless the games' settings are deleted too).
- **Website, settings guide and FAQ** at [lemon-emu.org](https://lemon-emu.org), in English and Spanish, with a plain-language
  explanation of every setting.
- **Game usage stats** — automatic per-game playtime, last-played time and session count, surfaced as a
  "Continue playing" shortcut on the games list and a sortable ranking on a dedicated Statistics screen.
- **Carousel/grid/list browsing** with per-card usage badges, favorites, and search/filtering across your library.
- **In-app updates** — checks Lemon's own server on launch (falling back to the GitHub mirror when it can't be
  reached), on demand from the About screen too, and can download/install the new APK directly, with no path
  (missing release, no connection) that crashes the app. A one-time "What's new" notice follows an update.
- **Adreno GPU driver manager** — install alternate Adreno graphics drivers per game, plus per-game performance
  presets, frame generation and post-processing options.
- Save data import/export, Amiibo loading, and ad-hoc multiplayer, same as upstream Eden.

## Changelog

Full release notes (including Nightly/Experimental prereleases) are on the
[Releases page](https://git.lemon-emu.org/lemon/Lemon-Project/releases). Highlights:

As of v0.3, Nightly and Experimental have been merged into `main` and retired as separate channels — one
consolidated build going forward instead of splitting fixes across three branches.

- **v1.0.0** — Lemon has its own home: the code, the releases and the updates now live at
  [git.lemon-emu.org](https://git.lemon-emu.org/lemon/Lemon-Project) (GitHub is a mirror) and there is a website,
  [lemon-emu.org](https://lemon-emu.org), with a settings guide and an FAQ. A new interface: launcher-style library,
  quick in-game panel with a performance card, redesigned Settings/About/Statistics and full controller navigation;
  the new screens are translated to Spanish. Controller layout per game. Lemon Cheater searches, edits and freezes
  floats as well as integers. **Reset all settings** on the Settings screen. **GPU Mode is Accurate by default**:
  Fast made textures flicker in some games (confirmed on Garfield and Dragon Ball). Updates come from Lemon's own
  server with GitHub as a fallback, a Check for updates button on the About screen and a "What's new" notice after
  an update. Fixes: FIFA's missing player indicators, ball-direction lines, button prompts and text (a texture handle
  that travels with the vertices could not be followed by the shader compiler, so those draws were silently
  dropped; draws are now split by texture); FIFA 23 stuck at the splash screen (the network service lacked an
  `Ioctl` call); updates and DLC bundled in `.nsz` files showing the wrong version and no DLC; motion controls on
  every screen rotation, so they work on foldables and tablets held landscape. Known issue: in FIFA 23 the faces
  of some players can show wrong colors.
- **v0.3.5** — Coming from Eden, Citron or yuzu: Lemon imports your saves (plus keys and firmware if it has none)
  without opening the other emulator, from the first-run setup or the top of Settings. Android 12 support. The
  Lemon-Ade driver ships inside the app and is picked automatically on the Adreno 830, with an automatic fallback to
  the system driver if a game fails to start with it; Lemon-Ade is also first in the driver downloader. Games built
  for Switch firmware 22.0+ (ZBIC-compressed executables) load. New logo by Madeleine. Fixes: Add-ons toggles
  flipping other entries while scrolling, GPU-written textures evicted under memory pressure, the update notice
  never showing, plus several upstream Eden fixes (MK8D/SM3DW local wireless crash, controller-disconnect freeze,
  SGSR black screen on system screens, UE5 buffer sync races, IPS/pchtxt mods).
- **v0.3.4** — Hotfix: the loading screen could stay up forever after a game had started. The check that hides it
  once the first frame renders shared its message queue with the touch-overlay auto-hide, which clears that whole
  queue on every screen touch; the check was cancelled and never restarted. The adaptive-performance watchdog had
  the same problem and is fixed too.
- **v0.3.3** — Compressed dumps: `.nsz` and `.xcz` load directly. Block-compressed files (the `.xcz` default) are
  read in place with nothing written to disk; solid ones are decompressed once on first use and cached for later
  launches (least recently used entries evicted past 24 GiB). Rendering: added a missing barrier before indirect
  compute dispatches, compute dispatches are no longer skipped, and fixed crashes on dead compute descriptor slots
  and when rescaling texel buffer views. A corrupted or truncated pipeline cache is now rejected instead of crashing
  on launch. The loading screen stays up until the first real frame. Older Adreno devices (Snapdragon 888/865): the
  custom GPU driver is no longer loaded and unloaded repeatedly. Quick Load validates the whole savestate before
  touching any memory. Fixed a Lemon Cheater freeze deadlock and an out-of-memory abort when taking a snapshot. The
  game list scans faster (containers are no longer re-parsed on every query) and no longer races between threads,
  which could crash or drop updates/DLC. Also includes everything from v0.3.2-nightly.1 below.
- **v0.3.2-nightly.1** — Prerelease. Quick Save/Quick Load no longer aborts a restore over a memory region that's
  legitimately (not corruptly) become partially unmapped since the save; that region is now skipped instead, like an
  excluded sleeping-thread stack. Fixed a crash on some older Adreno/KGSL devices (confirmed on Snapdragon 888 and
  865) importing a custom GPU driver from your own file instead of the in-app downloader — two driver-manager UI
  labels were each building a full throwaway Vulkan device just to read two strings, and that churn stacked with a
  real driver reload into several rapid GPU-driver open/close cycles. New app icon and branding (launcher icon,
  default profile picture), plus a Ko-fi link on the About screen.
- **v0.3.1** — Fixed the auto-updater: `enable_update_checks` defaulted to `false`, so the update check never ran for
  anyone who hadn't manually found and flipped a Settings toggle they had no reason to know existed (now on by
  default); a separate tag-comparison bug meant Nightly-flavored builds' check silently bailed out even once
  enabled. Renamed remaining "Eden" leftovers to "Lemon" (device/system nickname in Settings > Advanced, plus a
  few lower-visibility internal identifiers).
- **v0.3** — Channel consolidation: Nightly and Experimental are merged into `main` and retired; a single build
  going forward. Quick Save/Quick Load now excludes threads asleep on a kernel wait from the restore instead of
  corrupting their stack/context, making it reliable on real multi-threaded games (previously same-session only,
  MVP-quality); savestate files shrunk ~3x via sparse-encoding zero-filled memory pages. Fixed a missing Vulkan
  synchronization barrier between compute-shader dispatches and the draws/copies that consume their output,
  causing intermittent black rendering artifacts (confirmed on Zelda: Tears of the Kingdom's procedural grass and
  shadows). Retry custom GPU driver loading instead of falling back to the (on some devices, unstable) system
  driver after a single attempt, fixing a repeated crash-on-launch pattern confirmed on Snapdragon 888/Adreno 660.
  Fixed a native out-of-bounds memory-unmap during shutdown that could corrupt state carried over into the next
  game session. Fixed a crash opening Settings after exiting emulation. Fixed Release builds silently sharing a
  stale build configuration with the debug-adjacent RelWithDebInfo build type (wrong reported version, unoptimized
  binary). Controller layout presets (Default/Big/Swapped D-pad↔stick) with one-tap apply from a new in-game menu.
  Discord and Buy Me a Coffee links on the About screen.
- **v0.2.3** — "Continue playing" card and playtime/last-played/session sorting on the Statistics screen; Lemon
  Cheater freeze/unfreeze with a dedicated "Frozen" view; input macro recorder/player; auto-updater pointed at
  this repository instead of upstream Eden's.
- **v0.2.2** — Game usage statistics (playtime, last played, session count), a new Statistics screen, and per-card
  usage badges on the games list.
- **v0.2.1** — Fixed the Lemon Cheater's context menu appearing behind game cards in Carousel view.
- **v0.2** — Added the Lemon Cheater live memory editor, plus assorted bug fixes.
- **v0.1** — Initial release.

## Scope

- **Android only.** Every desktop/CLI build target from upstream has been removed.
- **Adreno only.** Mali and PowerVR GPUs are explicitly out of scope and untested; the app may start on them,
  but they are not supported (see the [FAQ](https://lemon-emu.org/faq.html)).
- **No keys, no firmware.** `prod.keys` and Switch firmware are not part of this repository and never will be —
  sourcing them from your own legally owned console is entirely on you. Lemon does not touch key derivation,
  decryption, or DRM in any way; it only builds the emulator app.

## Building

Releases are built and published manually (see the [Releases page](https://git.lemon-emu.org/lemon/Lemon-Project/releases)
for prebuilt APKs). A [GitHub Actions workflow](.github/workflows/android-build.yml) exists to verify the build
still compiles, but it's manually triggered (`workflow_dispatch`) and only runs on the GitHub mirror. To build
locally:

**Dependencies:** [Android Studio](https://developer.android.com/studio), NDK 27+ and CMake 3.22.1 (installable
from Android Studio's SDK Manager), and Git.

```sh
git clone --recursive https://git.lemon-emu.org/lemon/Lemon-Project.git
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

This project stands on the open-source work of the yuzu community and of the projects that came after it:

- Yuzu
- Eden
- Ryujinx
- Sudachi
- Citron
- Torzu
- Suyu
- Ryubing

And everyone who continues or has contributed to any of them. <3

A very special thank you to Madeleine, who designed Lemon's logo for free and put up with endless
rounds of changes with a lot of patience. Thank you, Madeleine! <3

<a href="https://buymeacoffee.com/ghael">Si te ha gustado mi trabajo puedes invitarme a un café</a>

<a href="https://ko-fi.com/LemonProject">O si lo prefieres, un Ko-Fi... Gracias!</a>

