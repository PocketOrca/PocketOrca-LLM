# PocketOrca-LLM User Guide

> What it is: turn your Snapdragon device into a **fully-offline-capable** LLM server on your local network. Run models on a phone or tablet, and use them from a computer, another tablet, or any device with a browser or an OpenAI-compatible client.

## Contents

1. [Installation](#1-installation)
2. [Download a model](#2-download-a-model)
3. [Start the server](#3-start-the-server)
4. [Connect over LAN](#4-connect-over-lan)
5. [Choosing an engine](#5-choosing-an-engine)
6. [Tuning guide](#6-tuning-guide)
7. [Chat tab](#7-chat-tab)
8. [Remote API mode](#8-remote-api-mode)
9. [Background keep-alive](#9-background-keep-alive)
10. [FAQ](#10-faq)

---

## 1. Installation

**Requirements**: Android 8.0 or later, arm64 device. NPU/GPU engines are Snapdragon 8-series only (see the compatibility table on the Releases page).

1. Download the latest APK from the GitHub Releases page (named like `PocketOrca-LLM-vX.X.X-vcXX-release.apk` — check the Releases page for the exact version), then install it (allow "unknown sources" when prompted)
2. On first launch the app asks for three permissions in a row — **allow all of them**:
   - **Battery exemption** (run in the background) — keeps the server connected with the screen off; declining may cause disconnects
   - **All files access** — to browse GGUF model files on the phone
   - **Notifications** — for the persistent service notification and status monitoring

> Permissions reset after uninstalling; the app walks you through them again on next launch.

## 2. Download a model

PocketOrca-LLM **ships no model files**. Download a GGUF model yourself (4-bit quantization recommended: Q4_K_M or Q4_0).

**Where to download (all reachable from China without a VPN):**

| Source | URL | Notes |
|---|---|---|
| ModelScope (魔搭) | https://modelscope.cn | By Alibaba, fastest in China, sign-up required |
| hf-mirror | https://hf-mirror.com | HuggingFace mirror, no sign-up |
| HuggingFace | https://huggingface.co | VPN required |

**Good starter models** (search keywords):

| Model | Quant | Size | Good for |
|---|---|---|---|
| Qwen3-1.7B / Llama-3.2-1B | Q4_0 / Q4_K_M | 1–1.5 GB | Getting started, fast |
| Qwen3-4B / Phi-4-mini | Q4_K_M | ~2.5 GB | Balanced |
| Qwen2.5-7B | Q4_0 | 4–5 GB | Best quality; needs a 12 GB RAM phone |

> Measured on a Galaxy S25 (8 Elite): 7B runs most stably on the NPU engine — a steady 10 t/s, low heat, no noticeable drop in output over time.

**Where to put it**: anywhere works — the "Download" folder or wherever; the app browses the whole storage. Note: Qwen3 models have an upstream bug on the NPU engine; the app will walk you through switching to GPU.

> **Tip**: when transferring large models from a computer to the phone, the file occasionally arrives incomplete (a few MB short — the server still loads it, but output goes wrong). If a new model misbehaves, first compare the file size against the source.

## 3. Start the server

1. Open the app, go to the **Server** tab
2. Browse and pick a GGUF model file
3. Pick an engine:
   - **NPU**: Snapdragon 8 Elite and later — fastest and most power-efficient (use 4-bit Q4_0 model files; Q4_K_M falls back to GPU/CPU)
   - **GPU**: works across Snapdragon 8 Gen 3 and later, good compatibility (known issue: on 8 Gen 2 devices the GPU engine starts but returns garbled text — tested on Galaxy S23 Ultra / Tab S9, OneUI 6.1)
   - **CPU**: should work on any Android device from 2023 on — test it yourself
4. Tap **Start** and wait for the model to load
5. Once it's up, a green indicator in the top-right corner shows the server is running

> Model loading and inference all happen on the phone itself; only Remote API mode (§8) touches the network.

**Key settings** (in the parameters drawer on the Server tab):

- **Context (ctx)**: how much of the conversation the model can "remember"; default 4096. Keep ≤32K on 12 GB devices (tested with 4B models); ≤8K for 7B/9B models; try larger on 16 GB+ devices
- **CPU threads**: 0 = auto
- **ubatch**: 0 = off
- **Deep thinking**: off by default; enable for Qwen3 and other hybrid-reasoning models
- **KV offload**: off by default; enable to save memory when RAM is tight
- **Port**: default 8080; changing it requires a server restart

## 4. Connect over LAN

Once the server is running, **any device on the same Wi-Fi can connect**:

- **Endpoint**: `http://<phone-ip>:<port>/v1` (the IP is shown at the top of the Server tab)
- **API key**: the key you set inside the app (stored AES-GCM-encrypted) — enter the same value in your client

**Option 1: browser** — open `http://<phone-ip>:8080` in a phone browser and chat directly

**Option 2: OpenAI-compatible clients** (computer / tablet / another phone) — including another device running PocketOrca-LLM; its CLI tab can test LAN and online model APIs

| Client | Platform | Setup |
|---|---|---|
| OpenWebUI | Docker / PC | API URL: `http://<phone-ip>:8080/v1`, key: the app's key, model name: leave empty or use the file name |
| ChatBox | All platforms | Provider: "OpenAI compatible", same as above |
| SillyTavern | All platforms | API: Text Completion or Chat Completion (OpenAI-compatible), same as above |

**Quick check from the command line** (on a computer):

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ***" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

## 5. Choosing an engine

| Your phone | Recommendation |
|---|---|
| Snapdragon 8 Elite / 8 Elite Gen 5 | NPU (fastest, most efficient); Qwen3 models → GPU |
| Snapdragon 8 Gen 3 | NPU or GPU |
| Snapdragon 8 Gen 2 | GPU or CPU |
| Snapdragon 8 Gen 1 and older / MediaTek | CPU |

> Upstream llama.cpp support for Hexagon is still early days: the NPU engine has native kernels only for plain 4-bit formats like Q4_0, while popular formats like Q4_K_M fall back to CPU speed. Use Q4_0 model files for NPU.

## 6. Tuning guide

Parameters live in the drawer on the **Server** / **CLI** tabs and apply instantly:

| Parameter | What it does | Suggested |
|---|---|---|
| temperature | Randomness — higher = more creative | 0.7 for everyday; 0.2–0.4 for code |
| top_k | Only pick from the top-K tokens each step | around 40 |
| top_p | Cumulative-probability cutoff | 0.9 |
| min_p | Cutoff alternative to top_p | 0.05–0.1 |
| repeat_penalty | Repetition penalty | 1.1; raise it if the model loops |
| system prompt | System prompt, sets the persona | presets available (default / concise / detailed) |

## 7. Chat tab

The built-in chat page has two modes:

- **Local mode**: stream straight from the model running on the phone (speed and token stats shown live)
- **Remote mode**: after enabling "remote connection" on the CLI tab, the Chat tab talks to any remote OpenAI-compatible API (say, a model running on your computer), with streaming

## 8. Remote API mode

The **CLI** tab can connect to a llama.cpp server running on another device (or any OpenAI-compatible endpoint):

1. Enter the Base URL (e.g. `http://192.168.1.100:8080/v1`) and API key
2. Tap **Test connection**, then pick a model
3. The Chat tab switches to remote mode automatically; the CLI tab sends raw inference commands

## 9. Background keep-alive

**The service stays online through screen-off, swipe-away, and unplug**:

- Swipe the app away and it revives within 1.5 seconds, restoring the service
- With the screen off, a system alarm keeps it alive and the LAN endpoint reachable
- Only **Stop** inside the app truly stops it — nothing pulls it back up
- On aggressive ROMs (e.g. some Chinese ROMs' "deep sleep"), add PocketOrca-LLM to the system's **auto-start whitelist** for extra safety

> Note: after a revival, **an in-flight generation continues** (the orphan process is adopted) — but the system may have briefly frozen the service. If a client reports a connection timeout, just resend.

## 10. FAQ

**Q: "Failed to load model" at startup?**
Make sure it's GGUF (not .bin/.safetensors); check the file transferred completely (compare sizes); the less RAM you have, the smaller the model should be — 4B and under on 8 GB devices, 9B and under on 12 GB.

**Q: Slower than advertised?**
Check which engine the server is actually running on (visible on the Server tab); make sure power-saving mode is off; heat throttling during long runs is normal.

**Q: Can't reach the phone from my computer?**
① Phone and computer must be on the **same Wi-Fi**; ② the status pill should read "running"; ③ the phone's IP may have changed (common after a router reboot) — re-check it; ④ if the router has "AP isolation" on, devices can't see each other — turn it off in router settings.

**Q: Garbled output?**
Snapdragon 8 Gen 2's GPU engine with Q4_K quantizations has a known driver bug — switch to the CPU engine or Q4_0 quants. Garbled output on any other device: please open a GitHub issue with device + engine + quantization format.

**Q: NPU engine option is greyed out?**
Your SoC isn't on the NPU support list (Snapdragon 8 Elite minimum), or the quantization isn't supported. Use the GPU/CPU engine.

**Q: Qwen3 model freezes on NPU?**
A known upstream bug — the app walks you through switching to the GPU engine. Just follow the prompt.

**Q: Multiple users / public internet?**
Built for personal use on your LAN, with no auth system beyond a single API key. **Do not expose it to the internet.**

**Q: iOS version coming?**
No. NPU acceleration depends on Qualcomm's proprietary Hexagon stack, which has no iOS path; future plans will use a different underlying technology.

---

*See the GitHub repo README for technical details. The app is free forever — if it helps you, a Star is appreciated.*
