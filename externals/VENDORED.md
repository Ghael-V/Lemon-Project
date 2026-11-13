# Vendored externals

yuzu pulled these as git submodules, but their repositories no longer exist. Iron Lemon keeps
copies of the original sources here instead.

| Directory | Source | Changes |
|---|---|---|
| `dynarmic/` | Debian `dynarmic_6.7.0+ds.orig.tar.xz` (sha256 `3e8e3127fb09a846a099db42410090000f0c3b467ae5279b9d5b114fb0a44da8`), the version Debian builds yuzu 1734 with | `externals/robin-map` added (Tessil/robin-map v1.2.1), Debian had stripped it |
| `sirit/` | The copy bundled in Debian's `yuzu_0-1734+ds.orig.tar.gz` (sha256 `42e689dae12a180da8aadfcb72945576280f35cb2a63aea8343db10dd4d180ee`) | `externals/SPIRV-Headers` added (KhronosGroup, `vulkan-sdk-1.3.268.0`); the `SPIRV-Headers::SPIRV-Headers` alias is only created when that version doesn't define it; `tests/` removed |
| `mbedtls/` | Mbed-TLS/mbedtls `3.6.7` (LTS; release tarball, sha256 `a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6`), replacing yuzu's fork | `tests/`, `programs/`, `docs/`, `doxygen/`, `visualc/`, `ChangeLog.d/` removed (`framework/` stays: its CMake needs it) |
