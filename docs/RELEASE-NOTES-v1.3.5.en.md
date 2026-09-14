# PocketOrca-LLM v1.3.5 — First public release

**English** | [中文](RELEASE-NOTES-v1.3.5.md)

PocketOrca-LLM turns your Snapdragon phone into an **offline-capable** LLM server for your local network: Hexagon NPU / Adreno GPU / CPU three-engine inference, an OpenAI-compatible endpoint, direct connection from any standard client. **Free forever, no ads, no in-app purchases — data never leaves the device.**

> This app ships no model files — download GGUF models yourself (see the [User Guide](USER-GUIDE.md)).

## 📦 Install

- Download `PocketOrca-LLM-v1.3.5-vc61-release.apk` (md5 `6421457e`)
- Requires Android 8.0+, arm64 device
- On first launch, grant the three permissions in order: battery exemption → all-files access → notifications.

## ✨ Highlights

- **Three engines**: Hexagon NPU (htp) / Adreno GPU (ocl) / CPU — pick per device and model quantization
- **OpenAI-compatible endpoint**: `http://<phone-ip>:8080/v1/chat/completions` — OpenWebUI, ChatBox, SillyTavern and any standard client connect directly
- **Survives backgrounding**: stays reachable through screen-off, task-swipe and unplug; auto-revives within 1.5 s if the process is killed
- **Live notification monitor**: CPU / GPU / RAM / temperature / tok-s / connections
- **Sampling playground**: temperature / top_k / top_p / min_p / repeat_penalty / system prompt, applied live
- **Dual-mode Chat**: local streaming; or connect to any remote OpenAI-compatible API
- **API keys encrypted** (Android Keystore, AES-GCM)
- **Trilingual UI** (Simplified Chinese / Traditional Chinese / English) + dark/light theme
- **Privacy**: loading and inference run entirely on-device; nothing is uploaded

## 📱 Compatibility

### CPU engine — theoretically any Android phone released after 2023

Qualcomm / MediaTek / other ARMv8+ chips are all worth a try. Older chips (A53/A55 little cores) run but slowly; small quants (Q4_0 / Q4_K_M) with 4 GB+ RAM recommended. Please test and report back.

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
| Snapdragon 8 Gen 2 | v73 | ⚠️ Incomplete upstream support — ≤4B models work, 7B waits on upstream |

> Upstream llama.cpp Hexagon support is early days: native NPU kernels exist only for plain 4-bit formats like Q4_0; popular formats like Q4_K_M fall back to CPU. Only Q4_0 (INT4) has been tested so far — largest verified model: Qwen2.5-7B-Q4_0.
> All measured speeds were taken on a Galaxy S25 (OneUI 8.0).

Older 7-series chips (Gen 1 / Gen 2, v69) have no NPU engine — use CPU / GPU.

## ⚠️ Known limitations

- **Qwen3 models hang on the NPU engine** (upstream llama.cpp bug); the app auto-switches to GPU
- **Snapdragon 8 Gen 2 GPU driver defect**: Q4_K quants produce garbled output — use CPU instead (device driver issue; may self-heal after an OS update)
- MediaTek devices: CPU engine only
- Sustained inference runs hot — keep the phone plugged in and ventilated

## 📄 License

MIT for this app's own code. Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT); the Qualcomm Hexagon components in the prebuilt directories are for building and running on your own Qualcomm hardware only. See [LICENSE](../LICENSE).
