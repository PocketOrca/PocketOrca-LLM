package com.dawoer.npullm;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.4.0 (vc74): bmoe engine integration into the manager state machine.
 *
 * LlamaServerManager stays the single state authority (ST_READY/STARTING/RUNNING/ERROR,
 * FGS notifier, log ring). The bmoe engine differs from llama-server:
 *   - process = libbmoecli.so --session (line protocol), no HTTP port
 *   - state pushes come from the BMOE_READY / process-exit events, not /health probes
 *   - generation = stdin JSON request, streamed back as BMOE_PROGRESS deltas
 * This adapter owns the BmoeSession lifecycle and translates its callbacks into
 * manager state pushes; chat flow calls generate() directly (bmoe profile has no
 * OpenAI endpoint).
 *
 * d8 constraints: no enums, no anonymous classes; named static nested only.
 */
final class BmoeAdapter implements BmoeSession.Callbacks {

    private final LlamaServerManager m;
    private BmoeSession session;
    /** chat-mode turn bookkeeping: clear_kv on the first turn after (re)load, false after. */
    private volatile boolean nextTurnFresh = true;
    private volatile int genNPredict = 8192;
    private volatile boolean genThink = false;

    BmoeAdapter(LlamaServerManager manager) { m = manager; }

    boolean hasSession() { return session != null; }
    boolean isGenerating() { return session != null && session.isGenerating(); }

    /** Launch the bmoe engine. exePath = nativeLibraryDir/libbmoecli.so; argv already
     *  built by BmoeSession.buildCommand (profile args, model substituted, threads). */
    synchronized void start(String[] cmd) {
        if (session != null) return;
        nextTurnFresh = true;
        BmoeSession s = new BmoeSession(m.log());
        s.setCallbacks(this);
        session = s;
        s.launch(cmd);
    }

    synchronized void stop() {
        BmoeSession s = session;
        session = null;
        if (s != null) s.stop();
    }

    /** One chat turn. clearKv: first turn after load = true (new conversation),
     *  continuation turns = false (engine keeps chat_history + KV prefix). */
    boolean generate(String prompt, int nPredict, boolean think, boolean clearKv) {
        BmoeSession s = session;
        if (s == null) return false;
        genNPredict = nPredict;
        genThink = think;
        return s.generate(prompt, nPredict, think, clearKv);
    }

    boolean cancel() {
        BmoeSession s = session;
        if (s == null) return false;
        s.cancel();
        return true;
    }

    /** true on the first turn after (re)load — the caller must send clear_kv=true. */
    boolean needsFreshTurn() { return nextTurnFresh; }
    void markTurnSent() { nextTurnFresh = false; }

    /** Live telemetry for the FGS notification (bmoe has no /metrics endpoint). */
    double tokPerSec() { BmoeSession s = session; return s == null ? -1 : s.lastTokPerSec(); }
    double cacheHitPct() { BmoeSession s = session; return s == null ? -1 : s.lastCacheHitPct(); }
    double loadSeconds() { BmoeSession s = session; return s == null ? -1 : s.loadSeconds(); }

    /**
     * Static chunk/done hook for the WebView chat UI. The Activity (and its
     * ChatUiBridge) is recreated independently of the manager singleton, so the
     * bridge registers itself here on every onCreate; with the app dead (reborn
     * path) the delegate is absent and chunks are dropped silently.
     */
    static final class UiHook {
        interface Delegate { void onChunk(String t); void onDone(String status, String statsJson); }
        private static volatile Delegate d;
        static void setDelegate(Delegate x) { d = x; }
        static void chunk(String t) { Delegate x = d; if (x != null) x.onChunk(t); }
        static void done(String s, String j) { Delegate x = d; if (x != null) x.onDone(s, j); }
        private UiHook() {}
    }

    /* ---------- BmoeSession.Callbacks → manager state / chat UI ---------- */

    @Override public void onEngineState(int state, String msg) {
        // ST_RUNNING arrives with BMOE_READY; manager pushes to UI + FGS notifier
        m.pushBmoeState(state, msg);
    }

    @Override public void onEngineDied(int exitCode) {
        m.pushBmoeDied(exitCode);
    }

    @Override public void onChunk(String deltaText) {
        m.pushBmoeChunk(deltaText);
    }

    @Override public void onReasoning(String deltaReasoning) {
        m.pushBmoeReasoning(deltaReasoning);
    }

    @Override public void onGenDone(String status, String statsJson) {
        m.pushBmoeGenDone(status, statsJson);
    }

    /* ---------- pure helpers (JVM-testable) ---------- */

    /**
     * Chat request JSON (from the WebView, OpenAI shape) → bmoe generate prompt.
     * bmoe --chatml renders the model template over its own chat_history, so the
     * caller sends only the NEW user message; the system message (when present)
     * is prepended as plain text on the first turn of a conversation.
     * Returns null when the request shape is unusable (no user message).
     */
    static String promptFromMessages(String bodyJson) {
        try {
            JSONObject j = new JSONObject(bodyJson);
            JSONArray msgs = j.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return null;
            String system = null;
            String lastUser = null;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject o = msgs.optJSONObject(i);
                if (o == null) continue;
                String role = o.optString("role", "");
                String content = o.optString("content", "");
                if ("system".equals(role)) system = content;
                else if ("user".equals(role)) lastUser = content;
            }
            if (lastUser == null || lastUser.isEmpty()) return null;
            if (system != null && !system.isEmpty()) {
                return "[System]\n" + system + "\n\n[User]\n" + lastUser;
            }
            return lastUser;
        } catch (Exception e) {
            return null;
        }
    }

    /** max_tokens from the chat request body (fallback 8192). */
    static int maxTokensFrom(String bodyJson) {
        try {
            return new JSONObject(bodyJson).optInt("max_tokens", 8192);
        } catch (Exception e) {
            return 1024;
        }
    }
}
