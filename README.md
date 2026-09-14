<div align="center">

# PocketOrca-LLM

**A local LLM server built for Qualcomm chips**

Hexagon NPU · Adreno GPU · CPU — three engines · OpenAI-compatible endpoint · Data never leaves the device

Free forever · No ads · No in-app purchases

[Download latest](../../releases/latest) · [User Guide](docs/USER-GUIDE.md) · [Release notes](docs/RELEASE-NOTES-v1.3.5.md) · [Report an issue](../../issues)

[中文](README.zh-CN.md)

</div>

---

> [!IMPORTANT]
> Plug your phone in while running and keep it cool.

> This app ships **no model files** — download a GGUF model yourself after installing (see [User Guide §2](docs/USER-GUIDE.md#2-download-models)).

> Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT). Many thanks to the open-source community.

## What is it

PocketOrca-LLM turns your Snapdragon phone into an LLM server for your local network:

- **Three engines, on demand**: Hexagon NPU / Adreno GPU (OpenCL) / CPU — pick per device and model quantization
- **OpenAI-compatible endpoint**: `http://<phone-ip>:8080/v1/chat/completions` — OpenWebUI, ChatBox, SillyTavern, or any standard client connects directly
- **Stays online in the background**: survives screen-off, being swiped away, and unplugging; auto-revives within 1.5 s if the system kills the process 
- **Tuning playground**: temperature / top_k / top_p / min_p / repeat_penalty / system prompt, applied instantly
- **Two chat modes**: stream locally, or connect to any remote OpenAI-compatible API
- **Privacy**: model loading and inference run entirely on the phone — nothing is ever uploaded; the API key is stored encrypted (Android Keystore, AES-GCM)

## Quick start

1. Install the APK from [Releases](../../releases/latest), then grant the three permissions in order: **battery exemption / all-files access / notifications** (allow all — the battery exemption is what keeps the server alive with the screen off)
2. Download a GGUF model from [hf-mirror.com](https://hf-mirror.com) or [modelscope.cn](https://modelscope.cn) and drop it anywhere on the phone (rule of thumb: 4B-and-under models for 8 GB devices, up to 9B for 12 GB, experiment freely on 16 GB+; author-tested ceiling: Qwen3.5-9B-Q4_K_M (GPU/CPU), Qwen2.5-7B-Q4_0 (NPU))
3. On the **Server** tab: pick the model file → pick an engine → start
4. Point any OpenAI-compatible client on your network to `http://<phone-ip>:8080/v1`

Step-by-step details in the [User Guide](docs/USER-GUIDE.md).

## Build from source

```bash
# 1. Bring your own build environment: JDK 21, Android SDK (aapt2/d8/apksigner), Android NDK r27
# 2. Grab the NPU/GPU inference runtime (ocl-libs, ~210 MB) from the Release attachments and extract it at the repo root
# 3. Edit the SDK/NDK paths and version numbers at the top of scripts/build.sh, then run:
bash scripts/build.sh          # output: app-release.apk
bash scripts/build-verify.sh   # integrity check (lib closure / manifest / assets)
```

Prebuilt Hexagon skels (`prebuilt/htp-libs-16k`) and the vendor closure (`prebuilt/vendor`) are included in the repo.

## Performance

- **Recommended**: Snapdragon 8 Gen 2 or newer, 12 GB+ RAM
- **NPU engine**: Snapdragon 8 Elite and later (HTP v75+). Use Q4_0 GGUF files for NPU — see the NPU table below
- **Measured speeds** (Galaxy S25, OneUI 8.0): ~30 t/s for 1B Q4_0; a steady 10 t/s for Qwen2.5-7B-Q4_0 (NPU)
- **Context**: ≤32K recommended on 12 GB devices
- MediaTek and other ARMv8 devices: the CPU engine is worth a try

## Compatibility (measured, as of 2026-09)

> Only the author's own devices have been tested. Other devices may hit incompatibilities from chip, OS, or driver differences — if yours misbehaves, please [open an issue](../../issues) (device + engine + quantization format).

### CPU engine — theoretically any Android phone released after 2023

Qualcomm / MediaTek / other ARMv8+ chips are all worth a try. Older chips (A53/A55 little cores) will run but slowly; small quants (Q4_0 / Q4_K_M) with 4 GB+ RAM recommended. Please test and report back.

### GPU engine — Adreno (OpenCL), Snapdragon 8 series

| SoC | Adreno | Status |
|---|---|---|
| Snapdragon 8 Elite Gen 5 | 840 | ✅ Verified |
| Snapdragon 8 Elite | 830 | ✅ Main test device |
| Snapdragon 8 Gen 5 | 830 | ⚠️ Untested |
| Snapdragon 8 Gen 3 | 750 | ✅ Verified |
| Snapdragon 8 Gen 2 | 740 | ⚠️ Adreno 740 driver bug — garbled output on Q4_K quants |

MediaTek (Mali) GPUs are not supported for the GPU engine — use CPU.

### NPU engine — Hexagon

| SoC | HTP arch | Status |
|---|---|---|
| Snapdragon 8 Elite (SM8750) | v75 | ✅ Verified (~23 t/s at 1B; ~10 t/s at 7B; Qwen3 excluded) |
| Snapdragon 8 Elite Gen 5 (SM8850) | v79 | ⚠️ Untested |
| Snapdragon 8 Gen 5 | Newer Hexagon | ⚠️ Untested |
| Snapdragon 7+ Gen 3 (SM7675) | v73 | ⚠️ Awaiting a test device |
| Snapdragon 8 Gen 2 | v73 | ⚠️ Incomplete upstream support — small models (≤4B) work, 7B waits on upstream |

> Upstream llama.cpp support for Hexagon is still early days: the NPU path has native kernels only for plain 4-bit formats like Q4_0, while popular formats like Q4_K_M fall back to CPU. Only Q4_0 (INT4) has been tested so far — largest verified model: Qwen2.5-7B-Q4_0.
> All measured speeds were taken on a Galaxy S25 (OneUI 8.0).

Older 7-series chips (Gen 1 / Gen 2, v69) have no NPU engine — use CPU / GPU.

> [!NOTE]
> Real-world compatibility and performance vary by device and OS version — measure for yourself. Issue reports with your results are very welcome; they help fill out this matrix.

## Known limitations

- **Qwen3-family models deadlock the NPU engine** (an upstream llama.cpp bug) — the app will guide you to switch to the GPU engine
- **Snapdragon 8 Gen 2 GPU driver bug**: Q4_K quants produce garbled output — use CPU instead (a device driver issue; may clear up on Android 15)
- MediaTek devices: CPU engine only
- Sustained inference runs hot — plug in and watch the temperatures

## Tech stack

llama.cpp (MIT) · Hexagon SDK 6.6 · Android NDK r27 · hand-rolled build chain (aapt2 + d8 + apksigner) · WebView + JS bridge · 16 KB page-size compliant · targetSdk 36

## License

MIT for this app's own code — see [LICENSE](LICENSE). llama.cpp and its dependencies keep their own licenses; the Qualcomm Hexagon components in the prebuilt directories are for building and running on your own Qualcomm hardware only.
