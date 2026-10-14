# PocketOrca-LLM v1.4.3 Release Notes

## English

## New Features

- Major: a MoE expert-streaming engine (Apache-2.0) is now integrated as a hidden engine profile. 35B-class MoE models (strongly recommended: Qwen3.6-35B-A3B-Q4_K_M.gguf) run on 12 GB phones — measured steady ~5-6 token/s with ~3 s first token (Snapdragon 8 Elite, Galaxy S25), byte-identical to a full load. Recommended on Snapdragon 8 Elite and above; older devices may try it but it is not fully tested there. The MoE profile is hidden by design: the model browser runs a model self-check and shows the MoE profile only when an MoE model is detected — this adds a short self-check wait when browsing models, thanks for your patience
- One-tap MoE model import: pick a GGUF file and import it straight into the app directory (enabling O_DIRECT direct I/O is recommended — it improves expert-streaming read performance)
- LAN exposure switch for the local server (thanks Coolapk user @阳家)
- API key authentication (thanks bilibili user @点点圈圈No1)

## Fixes & Improvements

- Major: model pre-checks for the NPU engine
- Fixed UI overlap between the three-button navigation bar and the app interface on some devices

## Install

- Download `PocketOrca-LLM-v1.4.3-release.apk`
- Requires Android 12 or above, arm64
- Signed with the same certificate as v1.4.2, installs directly over it
