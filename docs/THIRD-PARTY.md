# Third-Party Components & Legal Notices

This document lists all third-party components shipped in or used to build
PocketOrca-LLM, with their licenses and provenance.

## 1. llama.cpp runtime (Apache-2.0 ports of GGML / MIT llama.cpp)

- **Upstream**: https://github.com/ggml-org/llama.cpp
- **License**: MIT (see upstream LICENSE). GGML library is MIT.
- **Shipped**: prebuilt shared libraries (`libllama-server.so`, `libggml*.so`,
  `libmtmd.so`) compiled from the llama.cpp source tree, plus the Hexagon DSP
  skeletons described in §2.
- **Attribution**: llama.cpp by Georgi Gerganov and the ggml-org contributors.

## 2. Hexagon DSP skeletons (`prebuilt/htp-libs-16k/libggml-htp-v*.so`)

- **Provenance**: compiled from llama.cpp's own source tree
  (`ggml/src/ggml-hexagon/htp/*.c`), which is MIT-licensed open source.
  Copyright of the object code therefore belongs to the llama.cpp
  contributors under MIT — the Qualcomm Hexagon SDK compiler used to produce
  the ELF does not attach any copyright or license to its output.
- **Toolchain**: Qualcomm Hexagon SDK Community Edition 6.6.0.0 (Hexagon Tools
  19.0.07), obtained via Qualcomm's own public distribution at
  https://github.com/snapdragon-toolchain/hexagon-sdk — the same distribution
  channel llama.cpp's official Snapdragon build tooling
  (`scripts/snapdragon/`) documents and requires.
- **No proprietary material**: the skeletons contain no Qualcomm proprietary
  code, paths, or markers (verified by string-scan; they link against the
  QuRT/dspqueue runtime already present on the device's firmware).
- **Note**: the Hexagon SDK itself is licensed under Qualcomm's own terms;
  this project does not redistribute the SDK. Users who wish to rebuild the
  skeletons must obtain the SDK from Qualcomm and accept its license.
  Prebuilt skeletons are provided for convenience; a from-source build path
  is documented in the README.

## 3. Android vendor closure libraries (`prebuilt/vendor/`)

Minimal Android platform libraries packaged so the exec runtime can resolve
its dependency closure on some devices (see README, "native libraries"):

| Library | Source | License |
|---|---|---|
| libbase.so, libcutils.so, libhidlbase.so, libutils.so, libhardware.so, libdmabufheap.so, android.hardware.common-V2-ndk.so | AOSP | Apache-2.0 |
| libc++.so | LLVM libc++ (extracted from AOSP) | Apache-2.0 WITH LLVM exception |

Apache-2.0 requires that recipients be told these files are modified from
their AOSP/LLVM originals where applicable; packaging/relocation for Android
linker compatibility does not alter the licensed code. AOSP and LLVM sources
are publicly available; no NOTICE file from those projects was removed.

**Removed**: the Qualcomm-proprietary Adreno OpenCL ICD loader
(`libOCL-vendor.so`, compiled from `vendor/qcom/proprietary` sources) is
intentionally NOT distributed. At runtime the OpenCL loader is resolved from
the device's own vendor partition, and the Hexagon fastRPC transport
(`libcdsprpc.so`) likewise resolves from device vendor libraries — the same
path any GPU/NPU-accelerated app takes.

## 4. Test-time dependencies (not shipped in the APK)

| Component | License | Used for |
|---|---|---|
| org.json (stleary/JSON-java) | Public domain (dedicated 2020) | JVM logic tests only |

## 5. This project

PocketOrca-LLM app code (Java, assets, build scripts) is licensed under the MIT
license in the repository root [LICENSE](../LICENSE).
