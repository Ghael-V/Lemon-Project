# Lemon Project Mali-G57 Optimization Plan

## Target device
- Android: Infinix Hot 50 4G
- SoC: MediaTek Helio G100
- GPU: Mali-G57 MC2
- RAM: 8 GB

## Scope
This branch is a device-focused investigation and optimization baseline. It does not claim that performance gains have been measured.

## Existing upstream capabilities to preserve
- Mali texture handling and BCn texture recompression paths.
- Existing Vulkan renderer/driver selection and per-game graphics settings.
- Existing shader cache, asynchronous shader options, and game-specific overrides.
- Save data, firmware, keys, and library migration behavior.

## Proposed work, in order
1. Inspect current GPU backend and Mali-specific paths before editing.
2. Add reproducible diagnostics for GPU vendor/renderer, Vulkan capabilities, memory budget, and selected graphics options.
3. Review texture conversion and staging-buffer memory behavior for Mali-G57; avoid duplicating existing upstream features.
4. Review Android memory-pressure handling and release behavior; do not introduce aggressive cache purges that cause shader recompilation stutter.
5. Add a conservative device profile only if the code has a supported profile mechanism. Keep overrides user-configurable and reversible.
6. Build debug APK and run static checks/CI.
7. Test boot, library scan, game launch/exit, shader-cache persistence, save/load, and sustained sessions on a real Mali-G57 device. Record results and regressions.

## Acceptance criteria
- No unsupported claims about FPS gains.
- No changes to firmware, keys, game content, or user save files.
- Existing settings remain compatible.
- Device-specific changes are opt-in or have a safe fallback.
- Build and test evidence is recorded before calling the work complete.

## Current status
Repository access is available, but source-tree/file retrieval through the current GitHub integration has not yet succeeded. This document establishes the scope; source-code changes and APK builds are pending source access and CI verification.
