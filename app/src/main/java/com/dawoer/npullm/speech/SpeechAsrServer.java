package com.dawoer.npullm.speech;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * vc167: ASR 端点（:8085，跑在 :asr 进程）。
 *
 * 路由：
 *   GET  /                          首页（proto + curl 用法 + LAN 警示，门外）
 *   GET  /health                    {ok,proto,port,ready,model,engine}（门外）
 *   POST /v1/audio/transcriptions   multipart 字段 file（wav/mp3/m4a/webm/ogg）
 *                                   + 可选 language / model → {text,duration_ms,...}
 *                                   也接受 JSON {"audio_base64":"...","language":"zh"}
 *   POST /admin/reload              仅 127.0.0.1
 *
 * key 门 sk-asr-：Bearer / X-Api-Key / ?key=。
 */
public class SpeechAsrServer extends NanoHTTPD {

    public static final String PROTO_VERSION = "vc176-asr1";

    private final android.content.Context ctx;
    private volatile String apiKey = "";

    public SpeechAsrServer(android.content.Context c, int port) {
        super(port);
        this.ctx = c;
    }

    public void setApiKey(String k) {
        this.apiKey = k == null ? "" : k;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) uri = "";
        try {
            if ("/".equals(uri) || "/index.html".equals(uri)) return serveHome(session);
            if ("/health".equals(uri)) return serveHealth();
            if (!gateOk(session)) {
                Response r = err(Response.Status.UNAUTHORIZED, "invalid key", "auth");
                r.addHeader("WWW-Authenticate", "Bearer realm=\"po-asr\"");
                return r;
            }
            if ("/admin/reload".equals(uri)) return serveReload(session);
            if ("/v1/audio/transcriptions".equals(uri)) return serveTranscribe(session);
            return err(Response.Status.NOT_FOUND, "not found", "not_found");
        } catch (Throwable t) {
            return err(Response.Status.INTERNAL_ERROR, SpeechTts.msg(t), "engine");
        }
    }

    private Response serveHealth() {
        JSONObject h = new JSONObject();
        try {
            h.put("status", "ok");
            h.put("ok", true);
            h.put("proto", PROTO_VERSION);
            h.put("port", SpeechConfig.port(SpeechConfig.KIND_ASR));
            h.put("ready", SpeechAsr.ready(ctx));
            h.put("model", SpeechAsr.modelName(ctx));
            h.put("engine", SpeechAsr.loaded());
        } catch (Throwable t) {
            return nano(Response.Status.OK, "application/json",
                    "{\"status\":\"ok\",\"ok\":false,\"proto\":\"" + PROTO_VERSION + "\"}");
        }
        return nano(Response.Status.OK, "application/json", h.toString());
    }

    private Response serveReload(IHTTPSession session) {
        String remote = session.getHeaders() == null ? null
                : session.getHeaders().get("remote-addr");
        boolean local = remote == null || "127.0.0.1".equals(remote) || "::1".equals(remote);
        if (!local) return err(Response.Status.FORBIDDEN, "admin 仅本机可调", "forbidden");
        SpeechAsr.shutdown();
        return nano(Response.Status.OK, "application/json", "{\"ok\":true,\"reloaded\":true}");
    }

    private Response serveTranscribe(IHTTPSession session) throws Exception {
        Map<String, String> headers = session.getHeaders();
        String ctype = headers == null ? null : headers.get("content-type");
        String language = "";
        File audio = null;
        File tmpCopy = null;
        try {
            if (ctype != null && ctype.indexOf("multipart/form-data") >= 0) {
                Map<String, String> files = new HashMap<String, String>();
                session.parseBody(files);
                String path = null;
                for (Map.Entry<String, String> e : files.entrySet()) {
                    String v = e.getValue();
                    if (v != null && v.length() > 0 && new File(v).isFile()) {
                        path = v;
                        if ("file".equals(e.getKey()) || "audio".equals(e.getKey())) break;
                    }
                    if ("file".equals(e.getKey()) || "audio".equals(e.getKey())) path = v;
                }
                Map<String, String> parms = session.getParms();
                if (parms != null) {
                    String lg = parms.get("language");
                    if (lg == null) lg = parms.get("lang");
                    if (lg != null) language = lg.trim();
                }
                if (path == null || !new File(path).isFile()) {
                    return err(Response.Status.BAD_REQUEST,
                            "multipart 里没有 file 字段（或字段为空）", "invalid_request");
                }
                audio = new File(path);
            } else {
                // JSON + base64（避免二进制 POST 被当字符串处理）
                JSONObject b = body(session);
                String b64 = b.optString("audio_base64", b.optString("audio", ""));
                language = b.optString("language", "").trim();
                if (b64.length() == 0) {
                    return err(Response.Status.BAD_REQUEST,
                            "请求体为空：用 multipart 字段 file，或 JSON audio_base64", "invalid_request");
                }
                byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                tmpCopy = new File(ctx.getCacheDir(),
                        "asr_up-" + System.currentTimeMillis() + ".bin");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(tmpCopy);
                try { fo.write(raw); fo.flush(); } finally { fo.close(); }
                audio = tmpCopy;
            }

            // vc175: 入口校验音频魔数，坏文件干净 400，不让解码器裸抛（Failed to instantiate extractor）
            if (!looksLikeAudio(audio)) {
                return err(Response.Status.BAD_REQUEST,
                        "不是有效音频文件（支持 wav/mp3/m4a/webm/ogg）", "invalid_audio");
            }
            float[] pcm = SpeechPcm.decode(ctx, audio);
            if (pcm == null || pcm.length < 1600) {
                return err(Response.Status.BAD_REQUEST,
                        "音频太短或无法解码（支持 wav/mp3/m4a/webm/ogg）", "invalid_audio");
            }
            SpeechAsr.Result r = SpeechAsr.recognize(ctx, pcm, SpeechPcm.TARGET_RATE, language);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", r.text);
            o.put("duration_ms", r.durationMs);
            o.put("language", r.lang);
            o.put("emotion", r.emotion);
            o.put("event", r.event);
            o.put("model", SpeechAsr.modelName(ctx));
            o.put("proto", PROTO_VERSION);
            return nano(Response.Status.OK, "application/json", o.toString());
        } finally {
            if (tmpCopy != null) {
                try { tmpCopy.delete(); } catch (Throwable ignore) {}
            }
        }
    }

    private Response serveHome(IHTTPSession session) {
        String host = session.getHeaders() == null ? "localhost"
                : String.valueOf(session.getHeaders().get("host"));
        String base = "http://" + host;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>PocketOrca ASR</title></head>")
          .append("<body style=\"font-family:sans-serif;max-width:680px;margin:24px auto;padding:0 12px;color:#222\">")
          .append("<h2>PocketOrca ASR</h2>")
          .append("<p>Speech-to-text endpoint (sherpa-onnx SenseVoice). proto: ")
          .append(PROTO_VERSION).append("</p><ul>")
          .append("<li><code>curl -F file=@a.m4a ").append(base)
          .append("/v1/audio/transcriptions -H \"Authorization: Bearer &lt;key&gt;\"</code></li>")
          .append("<li>支持 wav / mp3 / m4a / webm / ogg；可选 language=auto|zh|en|ja|ko|yue</li>")
          .append("<li><code>GET ").append(base).append("/health</code> — health probe</li>")
          .append("</ul><p>LAN-only service. Never expose to the public internet.</p>")
          .append("</body></html>");
        return nano(Response.Status.OK, "text/html; charset=utf-8", sb.toString());
    }

    boolean gateOk(IHTTPSession session) {
        String key = apiKey;
        if (key.length() == 0) return true;
        Map<String, String> h = session.getHeaders();
        String auth = h == null ? null : h.get("authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            if (key.equals(auth.substring(7).trim())) return true;
        }
        String xk = h == null ? null : h.get("x-api-key");
        if (xk != null && key.equals(xk.trim())) return true;
        Map<String, String> p = session.getParms();
        String qk = p == null ? null : p.get("key");
        return qk != null && key.equals(qk);
    }

    /** vc175: 按魔数粗判音频容器（RIFF wav / ID3+mp3 / ftyp m4a / OggS / WebM / fLaC / ADTS aac）。 */
    static boolean looksLikeAudio(File f) {
        if (f == null || !f.isFile() || f.length() < 12) return false;
        java.io.InputStream in = null;
        try {
            byte[] b = new byte[16];
            in = new java.io.FileInputStream(f);
            int n = 0, r;
            while (n < 16 && (r = in.read(b, n, 16 - n)) > 0) n += r;
            if (n < 12) return false;
            if (b[0] == (byte)'R' && b[1] == (byte)'I' && b[2] == (byte)'F' && b[3] == (byte)'F') return true;          // WAV
            if (b[0] == (byte)'I' && b[1] == (byte)'D' && b[2] == (byte)'3') return true;                                  // MP3 ID3
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return true;                                                                      // MP3/AAC 裸帧
            if (b[4] == (byte)'f' && b[5] == (byte)'t' && b[6] == (byte)'y' && b[7] == (byte)'p') return true;      // M4A/MP4
            if (b[0] == (byte)'O' && b[1] == (byte)'g' && b[2] == (byte)'g' && b[3] == (byte)'S') return true;      // OGG
            if (b[0] == (byte)0x1A && b[1] == (byte)0x45 && b[2] == (byte)0xDF && b[3] == (byte)0xA3) return true;                                // WebM/MKV
            if (b[0] == (byte)'f' && b[1] == (byte)'L' && b[2] == (byte)'a' && b[3] == (byte)'C') return true;      // FLAC
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignore) {} }
        }
    }

    JSONObject body(IHTTPSession session) {
        try {
            // vc175: NanoHTTPD Content-Type 无 charset 时按 US-ASCII 解 body，中文全坏
            // （vc170 已在客户端补 charset=UTF-8，这里是服务端兜底：外部直连不带 charset 也能正确解析）
            Map<String, String> hh = session.getHeaders();
            String ct = hh == null ? null : hh.get("content-type");
            if (ct != null && ct.toLowerCase().indexOf("charset") < 0) {
                hh.put("content-type", ct + "; charset=UTF-8");
            }
            Map<String, String> files = new HashMap<String, String>();
            session.parseBody(files);
            String raw = files.get("postData");
            if (raw == null || raw.trim().length() == 0) return new JSONObject();
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private Response err(Response.Status st, String msg, String type) {
        return nano(st, "application/json",
                "{\"error\":{\"message\":\"" + SpeechTts.esc(msg) + "\",\"type\":\""
                        + SpeechTts.esc(type) + "\"}}");
    }

    private Response nano(Response.Status st, String mime, String body) {
        return NanoHTTPD.newFixedLengthResponse(st, mime, body);
    }
}
