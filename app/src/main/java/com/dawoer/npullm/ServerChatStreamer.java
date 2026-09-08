package com.dawoer.npullm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * SSE streaming chat for the Chat page (spec §3.5). Runs one request on a
 * worker thread, forwards each data: chunk to the WebView via onChatChunk,
 * and finishes with onChatDone(status, stats).
 *
 * d8-safe: static nested classes only, no anonymous classes, no enums.
 */
final class ServerChatStreamer {

    interface Callbacks {
        void onChunk(String text);          // delta content (content or reasoning)
        void onDone(String status, String statsJson);
    }

    /** d8-safe: named static Runnable + WeakReference back to the streamer. */
    static final class Job implements Runnable {
        private final ServerChatStreamer owner;
        private final String url;
        private final String bodyJson;
        private final String bearer;
        Job(ServerChatStreamer owner, String url, String bodyJson, String bearer) {
            this.owner = owner; this.url = url; this.bodyJson = bodyJson; this.bearer = bearer;
        }
        @Override public void run() { owner.execute(url, bodyJson, bearer); }
    }

    private volatile Callbacks callbacks;
    private volatile HttpURLConnection conn;

    synchronized void setCallbacks(Callbacks c) { callbacks = c; }

    void start(String url, String bodyJson) { start(url, bodyJson, null); }

    void start(String url, String bodyJson, String bearer) {
        new Thread(new Job(this, url, bodyJson, bearer), "chat-sse").start();
    }

    /** Best-effort abort of the in-flight request. */
    synchronized void abort() {
        HttpURLConnection c = conn;
        if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
    }

    private void emit(String js) {
        Callbacks c = callbacks;
        if (c != null) c.onChunk(js);
    }

    private void execute(String urlStr, String body, String bearer) {
        int status = 0;
        int tok = 0;
        long t0 = System.currentTimeMillis();
        long firstTokMs = -1;
        StringBuilder stats = new StringBuilder("{}");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            synchronized (this) { conn = c; }
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(300000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            if (bearer != null && !bearer.isEmpty())
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            status = c.getResponseCode();
            if (status != 200) {
                done(status, "{\"error\":\"HTTP " + status + "\"}");
                return;
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (conn != null && line.equals("")) continue;
                if (line.startsWith("data:")) {
                    dataBuf.setLength(0);
                    dataBuf.append(line.substring(5).trim());
                    String payload = dataBuf.toString();
                    if (payload.equals("[DONE]")) break;
                    // extract delta content via org.json
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(payload);
                        org.json.JSONArray choices = j.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            org.json.JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            String piece = deltaPiece(delta);
                            if (!piece.isEmpty()) {
                                if (firstTokMs < 0) firstTokMs = System.currentTimeMillis() - t0;
                                tok++;
                                emit(piece);
                            }
                        }
                        // usage (OpenAI) preferred, timings (llama.cpp) fallback;
                        // "" when the payload carries neither.
                        String s = statsFrom(j, System.currentTimeMillis() - t0, firstTokMs);
                        if (!s.isEmpty()) {
                            stats.setLength(0);
                            stats.append(s);
                        }
                    } catch (Throwable ignoredPiece) { /* skip malformed chunk */ }
                }
            }
            r.close();
            android.util.Log.i("chatSSE", "done ok tok=" + tok);
            // No usage/timings seen (common on remote endpoints): fall back to
            // the actual emitted token count so the UI never shows 0 tok.
            if (stats.length() <= 2 && tok > 0) {
                stats.append("{\"completion_tokens\":").append(tok)
                     .append(",\"total_ms\":").append(System.currentTimeMillis() - t0)
                     .append(",\"first_token_ms\":").append(Math.max(0, firstTokMs)).append('}');
            }
            done(200, stats.toString());
        } catch (Throwable e) {
            android.util.Log.w("chatSSE", "chat stream fail url=" + urlStr, e);
            if (tok == 0 && status == 0)
                done(-1, "{\"error\":\"" + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + "\"}");
            else
                done(status == 0 ? 200 : status, stats.length() > 2 ? stats.toString() : "{\"aborted\":true}");
        }
    }

    /** Extract the streamed text piece from a choices[0].delta object.
     *  Only real JSON strings count: org.json's optString(key, null) returns
     *  the literal string "null" for a missing or JSON-null key (v1.1.8
     *  "null"-prefix bug), so membership is tested with opt()+instanceof. */
    static String deltaPiece(org.json.JSONObject delta) {
        if (delta == null) return "";
        Object oCt = delta.opt("content");
        Object oRc = delta.opt("reasoning_content");
        String ct = (oCt instanceof String) ? (String) oCt : null;
        String rc = (oRc instanceof String) ? (String) oRc : null;
        return (ct != null) ? ct : (rc != null ? rc : "");
    }

    /** Stats JSON for onChatDone: usage (OpenAI) preferred, timings
     *  (llama.cpp final chunk: predicted_n/predicted_ms) fallback,
     *  "" when the payload carries neither. */
    static String statsFrom(org.json.JSONObject chunk, long elapsedMs, long firstTokMs) {
        int n = -1;
        org.json.JSONObject u = chunk.optJSONObject("usage");
        if (u != null) n = u.optInt("completion_tokens", -1);
        if (n < 0) {
            org.json.JSONObject tm = chunk.optJSONObject("timings");
            if (tm != null) n = tm.optInt("predicted_n", -1);
        }
        if (n < 0) return "";
        return "{\"completion_tokens\":" + n
             + ",\"total_ms\":" + Math.max(0, elapsedMs)
             + ",\"first_token_ms\":" + Math.max(0, firstTokMs) + '}';
    }

    private void done(int status, String statsJson) {
        Callbacks c = callbacks;
        if (c != null) c.onDone(String.valueOf(status), statsJson);
    }
}
