# PocketOrca-LLM v1.4.2 Release Notes

## English

## New Features

- Major: multimodal support. After the model service starts, test images and voice notes from the attachment menu on the Chat page. For images, use a vision model (e.g. Qwen3-VL / Qwen2.5-VL) — mmproj is loaded automatically when placed in the same folder as the model. JPEG/PNG are supported; convert HEIC/WebP first, and avoid very large resolutions. For voice notes, pair with a recognition model (e.g. Qwen3-ASR-0.6B+mmproj) to record and transcribe — no configuration needed. Available on NPU/GPU/MTP engines
- Session persistence: save / restore / delete sessions. Restoring rehydrates the KV cache directly (no prefill) and replays the chat history for seamless continuation

## Compatibility

- Engine upgrade: llama.cpp baseline update — Q2/Q3_K quantized models now run on the NPU (Q3_K matches Q4_K_M in speed; 9B models save ~1.3 GB RAM by switching to Q3_K_M), and MTP speculative decoding ships with the official baseline

## Fixes & Improvements

- Fixed NPU startup crash on newer platforms such as the Snapdragon 8 Elite Gen 5 (upstream DMA64 mapping is incompatible with older DSP firmware; mitigated by default — thanks @qwerkilo for reporting the issue)

## Install

- Download `PocketOrca-LLM-v1.4.2-release.apk`
- Requires Android 12 or above, arm64
- Signed with the same certificate as v1.4.1, installs directly over it
