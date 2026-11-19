# Mali-G57 support notes for Astra

## Confirmed from the current source tree

- The Vulkan texture path contains Mali-specific BCn handling, including ASTC/EAC recompression for formats Mali cannot sample directly and GPU-side BC6H decoding.
- Android native code checks for the Mali kernel device node `/dev/mali0` before reporting custom-driver loading support.
- The driver fetcher currently lists experimental PanVK packages only for Mali-G615, Mali-G720, and Mali-G52. It filters Mali packages by detected GPU model so a driver built for another Mali model is not offered.
- The Infinix Hot 50 4G target in this project uses a Mali-G57 MC2. The current PanVK list contains no matching Mali-G57 build.

## Changes made in this branch

The driver fetcher now distinguishes an identified Mali GPU from an unknown/non-Adreno GPU. Unknown GPUs no longer get treated as Mali by default, and therefore are not shown Mali PanVK driver packages just because they are not detected as Adreno.

The README now documents partial Mali support and explicitly notes that Mali-G57 has no matching PanVK package in the current list.

## Safe operating rule for Mali-G57 MC2

Use the system-provided Vulkan driver unless a PanVK package is explicitly built and verified for Mali-G57 MC2 and the Android version on the device. Do not install the G615, G720, or G52 packages on the G57. Driver download availability is not evidence of compatibility.

## Not yet verified

- Actual Vulkan physical-device and driver strings reported by the Infinix Hot 50 4G.
- Presence and permissions of `/dev/mali0` on the target firmware.
- Game boot and rendering on Mali-G57 MC2.
- Long-session stability, shader-cache behavior, GPU memory use, and thermal behavior.
- Successful APK build and installation of the Astra flavor.

These checks require a completed CI build and runtime testing on the target device. No FPS or compatibility guarantee is made.
