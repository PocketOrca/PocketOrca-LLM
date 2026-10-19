PocketOrca-LLM v1.4.4 Release Notes

Fixes & Improvements

1. Major: Fixed NPU/MTP/MoE engines failing to start on Android 17 devices (bundled libraries shadowing system libraries; thanks to community feedback)
2. System prompt: new input field; automatically attached to Chat conversations, CLI debug requests, and API requests
3. Custom CLI arguments: new extra-arguments text box in advanced settings to pass llama.cpp parameters to the local server (for advanced users)
4. KV cache quantization: three options (f16 / q8_0 (default) / q4_0). For NPU/MTP modes q8_0 is recommended; if you use Q4_0, please enable KV offload.
5. NPU power mode: new power mode selector for NPU/MTP modes (Full speed / Balanced / Power saver, default Full speed), trading off speed against heat and battery life. Measured generation speed with Qwen3.5-4B (Snapdragon 8 Elite, Samsung S25): Full speed NPU ~17.9, MTP ~21.0 tokens/s; Balanced ~14.2, 17.8 (~85% of Full speed); Power saver ~10.7, 10.9 (~52% of Full speed)
6. Local inference engine upgrade (new llama.cpp baseline)
7. Fixed an issue where the NPU mode silently fell back to CPU in some scenarios
8. Server page UI polish: fixed on-screen keyboard overlapping input fields on small-screen devices

Install:
Download PocketOrca-LLM-v1.4.4-release.apk
Requires Android 12 or above, arm64. Signed with the same certificate as v1.4.3, can be installed directly over the existing version.
GitHub: https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee: https://gitee.com/PocketOrca/pocket-orca-llm/releases
