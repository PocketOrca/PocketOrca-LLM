# PocketOrca-LLM v1.5.1 Release Notes

This update pushes the app one step further as a "service aggregator": your phone is now also a local knowledge base.

New built-in RAG knowledge base service (:8082), in the same standalone form as the search service — its own port, its own notification, its own key, ready to serve retrieval and embedding capabilities to third-party agents / clients.

## 1. Knowledge base service

Standard API: `/v1/rag/{status,index,search,drop}`, plus an OpenAI-compatible `/v1/embeddings` endpoint — callable directly from external scripts

Standalone form: default port 8082 (no conflict with 8080/8081), its own foreground service and notification channel; starting/stopping it does not affect the inference or search services

Built-in embedding model: bge-small-zh-v1.5 (Q8, ~26 MB, bundled — nothing to download); runs in a separate small process, spawned on demand and reclaimed after 5 minutes idle

Fingerprint-based incremental indexing: mtime+size quick check → sha256 exact check; unchanged files are skipped in milliseconds (re-index measured at 7 ms), changed files trigger a rebuild notice

### Supported file formats

Collected by extension whitelist, 31 types in total; everything else is skipped:

| Category | Extensions | Chunking |
|---|---|---|
| Documents | .md .markdown .mdx .rst .adoc | Split by heading level, chunks keep their heading path |
| Plain text | .txt | ~700-char sliding window with 70-char overlap |
| Code / config | .java .kt .py .js .ts .tsx .jsx .c .h .cpp .cc .hpp .rs .go .sh .bash .sql .gradle .toml .yaml .yml .json .xml .css .scss | Split by function / top-level declaration |

Limits and automatic skipping:

- Single file ≤ 2 MB; larger or empty files are skipped
- Directory recursion ≤ 8 levels, max 5000 files per library; hidden files (starting with `.`) are skipped
- Binary files containing null bytes are rejected (content is only read for whitelisted extensions)
- Encoding: strict UTF-8 decode with GBK fallback — older Chinese files can be indexed directly
- PDF / Word / Excel / PPT / images / audio are not supported — export to md or txt first

### Key & access control

- An access key (`sk-rag-…`) is generated automatically when the service starts; copy or reset it from the page
- Failed auth returns 401 + WWW-Authenticate; only `/health` is public, every other endpoint (including status) sits behind the gate
- Search queries document libraries only; chat memory requires `include_chat=1` explicitly, and the chat memory library cannot be deleted

### Multi-library & search

- Library list: name + file count · chunk count + delete; selecting a new directory adds a library, search spans all libraries
- Search parameters fully aligned between GET/POST (query, k, min_score, include_chat, library_id, file_cap), k up to 50
- Responses echo `requested_k / k / min_score / file_cap / returned / candidates / libs` — whether a parameter took effect is self-evident from the response

## 2. Chat memory

- Chat history persisted (chat.db): only user / assistant message text is stored; thinking traces and tool results never enter the database
- "Index memory" chunks past conversations into the knowledge base with time-decay recall (30-day half-life); memory panel includes index / search / recall toggle / clear
- New knowledge-base button in Chat: relevant knowledge base passages are injected automatically on send (default Top3, ≤500 chars each, ≤1500 chars total); the bubble shows "KB: N passages" so injection is observable

## 3. Chat & inference

- llama.cpp baseline upgrade: 797de63b2 → 4e910a86e (+319 commits)
- KV prefix reuse enabled: all four profiles default to `--cache-reuse 256`, effective for "same prefix, changed middle" workloads; to disable, append `--cache-reuse 0` in advanced args
- Thinking stream fully separated from the answer: thinking text no longer leaks into chat bubbles, chat history, or the memory store; when there is no answer text yet, "Thinking… N chars" is shown
- MoE session n_ctx overflow changed from "reject" to "clamp", fixing empty replies regardless of the thinking toggle
- Fixed UI freeze: slow calls (speed test, inference end) moved off the JavaBridge thread; polling is no longer blocked
- **New HTP v85 kernel** (for Snapdragon 8 Elite Gen 5 / SM8850 devices)

## 4. UI

- Main screen 5 tabs → 3 tabs (Services / Chat / Settings); new services page with three cards — inference / search / knowledge base — each with its own switch, full address, key display, and secondary entry

## 5. Search service

- New landing page: `GET /` and `/index.html` return a service description page
- Result count limit raised 8 → 20

## 6. Known issues

1. **Manually setting KV quantization to Q4_0 on the NPU / MTP profiles crashes** (process exit 134; HTP kernels only support f16 / f32 / q8_0 KV writes). The default q8_0 is safe.
2. **The v85 kernel has passed static verification only**: no v85 (SM8850 / Snapdragon 8 Elite Gen 5) device was available for on-device testing; non-v85 devices still use the original v73–v81 path.

## Install

Download `PocketOrca-LLM-v1.5.1-release.apk`

Requirements: Android 12 or later, arm64. Signed with the same certificate as v1.5.0 — install directly over the existing app.

Upgrade notes:

- Thinking text already mixed into old chat history is not cleaned automatically — if you want it gone, "Clear chat history" then "Index memory" again
- After upgrading: ① copy the new knowledge base key from the KB page and update external clients; ② to purge old chain-of-thought, clear chat history and rebuild the index; ③ keep KV quantization at the default q8_0 on NPU/MTP profiles
- If things go wrong and you want a clean slate: "Reset all caches, settings & stored keys" in Settings + clear chat history; as a last resort uninstall and reinstall (downloaded models will be lost)

GitHub: https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee (official Chinese mirror): https://gitee.com/PocketOrca/pocket-orca-llm/releases
