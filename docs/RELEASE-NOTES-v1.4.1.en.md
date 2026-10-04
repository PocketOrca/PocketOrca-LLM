# PocketOrca-LLM v1.4.1 Release Notes

## English


## Fixes & Improvements

- Major: new MTP engine profile: tested on the Qwen3.5-4B/9B MTP family — full NPU offload + draft-model speculative decoding: Qwen3.5-4B (Samsung S25, Snapdragon 8 Elite) measured 18.9 tok/s, roughly +30% output speed, up to +60% at long context
- KV cache quantization defaults to q8_0 on every engine profile: roughly half the KV memory
- Sleep on idle: Off / 3 / 10 / 30 minutes — after the timeout the model and KV cache are unloaded, freeing all memory; the next request reloads automatically
- Memory watchdog: process memory sampled every second, alert when running low
- Thinking control: deep-thinking toggle in Chat
- Anthropic-compatible endpoint: /v1/messages — Claude Code connects straight to the phone (ANTHROPIC_BASE_URL=http://<phoneIP>:8080)
- Fixed corrupted output on the Snapdragon 8 Elite (Adreno 830) GPU engine when the KV offload switch was on; the switch now ships OFF (KV stays with weights - correct output and faster). Turning it on is a RAM-saving escape hatch (passes --no-kv-offload to the engine)
- Fixed NPU invalid device on some devices (e.g. Ace3P, Snapdragon 8 Gen 3): the system skips extracting a non-lib-prefixed vendor DSP library from the APK; the app now extracts it itself at startup

## Install

- Download `PocketOrca-LLM-v1.4.1-release.apk`
- Requires Android 12 or above, arm64
- Signed with the same certificate as v1.4.0, installs directly over it
