# PocketOrca-LLM v1.5.2 Release Notes

This update gives the phone "ears and a mouth": speech recognition and speech synthesis ship as two independent services, and the Chat page can now listen and speak. With this release the on-device suite is complete — inference (8080), search (8081), knowledge base (8082), text-to-speech (8084), speech-to-text (8085).

## 1. Speech Services

Engine: sherpa-onnx. The two services run as fully independent processes with separate foreground notifications and separate access keys — starting/stopping one does not affect the other.

| Service | Port | Standard endpoint | Recommended model | Key prefix |
|---|---|---|---|---|
| TTS (speech synthesis) | 8084 | `POST /v1/audio/speech` (returns wav) | melo-tts-zh_en (Chinese + English, fp32) | `sk-tts-` |
| ASR (speech recognition) | 8085 | `POST /v1/audio/transcriptions` | SenseVoice-small | `sk-asr-` |

- The endpoints follow the OpenAI audio API shape, so third-party clients / scripts / agents can call them directly. Only `/health` is public; everything else sits behind the key gate and returns 401 on auth failure.
- Chinese encoding fallback: the server now forces UTF-8, so external direct connections without a charset no longer mangle Chinese (previously this caused "empty synthesis result").
- 48k / 24k external audio resampling is now anti-aliased — no more dropped syllables from external audio.
- Voices are generated from the model's real speaker count: single-voice models (e.g. melo) hide the dropdown automatically; multi-voice models show it and are selectable.

### Model pack import (not bundled — bring your own)

Service page speech card → sub-page "Import model pack", pick a zip: unpack → validate → atomic replace. On failure the old model is left untouched (supported formats, layout and file checklist below).

Model packs are self-described by `po-model.json` (kind / family / name / files / display). Without a manifest, the family is detected from directory signatures. **The criterion is a file-structure contract, not a model-name whitelist** — anything meeting the contract installs, so the "supported range" is exactly the conditions below (verified line by line against the `ModelManifest` / `TtsConfigFactory` / `SpeechAsr` source).

### TTS (VITS family): the pack must contain all of

| Required | Notes |
|---|---|
| `model*.onnx` | acoustic + vocoder graphs, int8 or fp32. fp32 recommended; the app assembles min(6, cores − 1) threads |
| `tokens.txt` | phoneme table |
| `lexicon.txt` | lexicon file (may be empty) |
| `dict/` | jieba dictionary directory, required for Chinese tokenization |

Optional (auto-enabled when present): `espeak-ng-data/` (needed by piper-style English VITS), `date.fst` / `number.fst` / `phone.fst` / `new_heteronym.fst` (date, number, phone, heteronym text regexes).

- ✅ **Verified on device**: official sherpa `vits-melo-tts-zh_en` (fp32; a ready-made zip is provided with this release)
- ✅ **Should install if the structure matches** (not individually verified on device): `vits-melo-tts-zh`, other Chinese/English VITS with a jieba dict, multi-voice VITS (voice dropdown auto-generated from speaker count, sid 0…N-1)
- ⚠️ **Will be rejected**: "missing files" — official piper English packs ship without `lexicon.txt` / `dict/` by default and are blocked by validation; non-VITS models are rejected too
- ⛔ **Family not supported**: Matcha-TTS, Kokoro, Kitten (the engine has config slots and the assembly layer has branches, but selecting one reports "not supported in this version"); ZipVoice does not exist as a family in sherpa-onnx 1.12.15 at all

### ASR (SenseVoice family): the pack must contain all of

| Required | Notes |
|---|---|
| `model*.onnx` | SenseVoice weights, int8 or fp32 |
| `tokens.txt` | vocabulary |

- ✅ **Verified on device**: `sensevoice-small` (zh / yue / en / ja / ko, with language, emotion and event tags)
- ✅ **Usable**: other SenseVoice weights of the same architecture; recognition language auto / zh / en / ja / ko / yue
- ⛔ **Not supported**: Transducer (Zipformer / Conformer), Paraformer, Whisper, FireRedAsr, Moonshine, Nemo-CTC, WeNet-CTC, Zipformer-CTC, Canary, Dolphin. These families have the same file shape as SenseVoice (model + tokens) and will be misdetected as SenseVoice, but assembly always loads as SenseVoice and will fail at runtime — treat them all as "not supported"; do not expect them to work by swapping files in.

### Packaging & storage constraints

- Only **zip** (preferred, per-entry CRC checked) and **tar.gz** are accepted; **tar.bz2 is not supported** — convert to zip first
- Layout inside the pack is free: flat or one nested directory, the model root is located by recursively finding `model*.onnx` (depth ≤ 6, shallowest wins)
- Including `po-model.json` is recommended; packs install without it (family detected from signatures), but multi-model coexistence and language/voice info depend on it
- **Same-name models cannot be overwritten**: changing a model pack requires changing the manifest `name`, otherwise "cannot replace model directory with the same name"
- Unpacking validates inside `_tmp`; invalid packs leave the old model untouched, and the page lists what is missing
- Multiple models coexist with list switching; ASR packs should stay ≤ 240 MB (shares memory with the main model)
- **Speech models are not shipped with the APK**: TTS ≈ 182 MB (fp32) / ASR ≈ 240 MB — import the zip yourself
- Running the speech services alongside the main model uses considerable memory; on low-RAM devices start them on demand

## 2. Chat Page Speech Recognition & Playback

- 🎤 **Push to talk**: recording is transcribed automatically and the text is filled into the input box for editing before sending.
- 🔊 **Auto-read toggle**: when on, every reply is read aloud — not a one-shot read; tap again while playing to stop.
- **Streaming playback**: speech starts as soon as the model starts producing text, no waiting for the full reply; split by sentence, first segment 20 chars then 50, segments pre-synthesized ahead of playback, no gaps at seams.
- Stop / replay / rapid consecutive messages reset the whole queue — no more cross-segment bleed-through or double playback.

## 3. Fixes

1. **NPU / MTP profile crashing with KV quant Q4_0 (exit 134)**: now silently clamped on three layers — the dropdown no longer offers Q4_0, stale values revert to Q8_0 on profile switch, and the Java side re-clamps when `--device HTP*`; no toast, silent fallback to safe values. Thanks @halagua, @Soleil27k for the reports.
2. **Top status pill always showed off**: status now polls the true value every 3 s (previously push-only, so a cold start or taking over an already-running service left stale UI), plus a fix for the visual misalignment caused by a missing slider class; tapping the pill checks the real state before deciding start/stop.
3. **No back navigation on sub-pages**: adapted to Android predictive back (`onBackPressed` is no longer called on targetSdk 36), now using `OnBackInvokedDispatcher`; back gesture returns to the services page and restores bottom tab highlight, back at root still exits the app.
4. **Services page key rows**: stopped services no longer show a key row; it appears once the service starts, with key values and copy buttons right-aligned across all four cards.
5. **TTS / ASR sub-pages**: full-width buttons that turn dark red "Stop service" while running; Traditional Chinese / English strings completed.

## Install

Download `PocketOrca-LLM-v1.5.2-release.apk`

- Requires Android 12 or later, arm64; signed with the same certificate as v1.5.0 / v1.5.1, installs over the top
- Size 122,283,871 bytes; MD5 `fb7e46ceb992d6c6626785f83160ead9`
- After installing, confirm version 1.5.2 on the settings page; speech features require importing a model pack before starting the services

GitHub: https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee: https://gitee.com/PocketOrca/pocket-orca-llm/releases
