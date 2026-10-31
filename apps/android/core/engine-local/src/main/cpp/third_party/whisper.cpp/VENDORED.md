# Vendored: whisper.cpp

Whisper.cpp STT (upstream ggml-org/whisper.cpp), **vendored in-tree** (no longer
a git submodule) and **pruned to the subset the Android build compiles**.

- Upstream: https://github.com/ggml-org/whisper.cpp
- Vendored from upstream commit: `927cfce34f31707e17f2bff35c349632fb9e2c3a`
  (metal: remove leftover ggml-metal.metal kernels file (#4051)), pinned until
  2026-10-31.
- Pruned on 2026-10-31 (security-audit surface reduction, see
  `informe-seguridad-2026-10-31.md` P2-1): everything outside the CMake build
  path was removed — `bindings/`, `examples/`, `models/`, `samples/`,
  `scripts/`, `tests/`, `media/`, `ci/`, `.github/`, `.devops/`, `grammars/`
  and all Python/Ruby/Node build helpers; plus the ggml accelerator backends
  the Android build never compiles (CUDA, Metal, SYCL, Vulkan, OpenCL, HIP,
  CANN, MUSA, RPC, WebGPU, zDNN, ZenDNN, OpenVINO, Hexagon, ET, VirtGPU, BLAS),
  which are OFF for this target (`GGML_NATIVE OFF`, NDK arm64/x86_64).

## What the build consumes

`src/main/cpp/CMakeLists.txt` adds `third_party/whisper.cpp` with
`WHISPER_BUILD_EXAMPLES/TESTS/SERVER OFF`; only these paths participate:

- `CMakeLists.txt`, `cmake/`, `src/`, `include/`
- `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/`, `ggml/src/`
  (core files + `ggml-cpu/`; all other backend directories are compiled out)

## Updating

1. Download the upstream release/commit archive and extract
   `src/`, `include/`, `cmake/`, `CMakeLists.txt`, `LICENSE`, `AUTHORS`,
   `README.md`, `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/` and
   `ggml/src/` (core files + `ggml-cpu/`) over this directory.
2. Do **not** reintroduce submodule metadata, non-build helper scripts, or
   unused backend sources: they enlarge the security-audit surface (the 2026
   audit flagged 25 findings in vendored helper scripts alone) without ever
   being compiled into the app.
3. Rebuild: `make android-build` must pass with the native module compiled
   from scratch (`./gradlew :core:engine-local:clean
   :core:engine-local:assembleDebug`).
