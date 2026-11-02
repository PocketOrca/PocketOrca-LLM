# PocketOrca-LLM v1.5.0 Release Notes

The headline of this release: the app graduates from a **local inference server** to a **service aggregator on your phone**.

A new **built-in search service** speaks the standard SearXNG schema — your phone becomes a local SearXNG-compatible instance that third-party agents and clients can plug into directly, no intermediate server required.

## 1. Search Service

- **Standard API**: `/search?format=json`, aligned with the official SearXNG schema — agents can use it out of the box.
- **6 built-in search engines**:

  | Engine | Network | Notes |
  |---|---|---|
  | Bing | CN / Global | Dual-host fallback with automatic retry on bad results |
  | Baidu | CN | Mobile-page parsing; may occasionally fail due to anti-bot measures — fails silently without affecting other engines |
  | Sogou | CN | — |
  | DuckDuckGo | Global | lite endpoint |
  | Wikipedia | Global | — |
  | GitHub | Global | Repository search with star counts |

  On mainland-China networks, **Bing / Baidu / Sogou** are recommended.
- **Structured direct answers**: searching "×× stock price" returns a market row (price / change / timestamp); "×× weather" returns a weather row. Unrecognized queries fall back to plain web search.
- **Per-engine circuit breaker**: engines that fail repeatedly are suspended for 30 seconds; the reason (timeout / dns / http) is reported in `unresponsive_engines` for easy debugging.
- **Configurable port**: default 8081, range 1024–65535, must not conflict with 8080. The notification and the address row follow the new port; restart the search service after changing it.
- **Redesigned search page**: status / address (copyable) / port rows plus a start button with four states (starting / running / stopped / retry); engines collapse into a drawer — one toggle per engine, effective immediately; a speed-test button checks connectivity to each engine.

## 2. Chat & Inference

- **Web-connected chat**: when both the inference server and the search service are running, toggle web search in the Chat page to search while answering.
- **Max output 1024 → 8192 tokens**: long replies are no longer truncated.
- **Free-scrolling streaming replies**: swiping up mid-generation stops auto-follow; a ↓ button appears at the bottom right — tap it or scroll back down to resume following.
- **"Enable MoE service" toggle** (Settings, off by default): when off, GGUF headers are not probed for MoE detection, improving app startup speed.

## 3. Other Improvements

- The server endpoint now shows the `/v1` suffix; with LAN exposure off it displays `127.0.0.1` instead of the LAN IP.
- **In-app update check is enabled starting with this version.**
- The engine-log card moved from the search page to the bottom of the CLI page.

## Install

Download `PocketOrca-LLM-v1.5.0-release.apk`

Requires Android 12 or above, arm64. Signed with the same certificate as v1.4.4 — installs directly over the existing version.

- GitHub: https://github.com/PocketOrca/PocketOrca-LLM/releases
- Gitee: https://gitee.com/PocketOrca/pocket-orca-llm/releases
