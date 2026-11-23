package com.dawoer.npullm.speech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * vc166: TTS 端点（:8084，跑在 :tts 进程）。
 *
 * 路由：
 *   GET  /                    首页（proto + curl 用法 + LAN 警示，门外）
 *   GET  /health              {status,proto,port,ready,model,speakers,sid,engine}（门外）
 *   GET  /v1/models           已导入模型列表 + 当前 active
 *   POST /v1/audio/speech     {input|text, voice|sid, speed, response_format:"wav"[, meta:true]}
 *   GET  /v1/audio/speech?text=..&voice=..&speed=..&meta=1
 *        默认返回 audio/wav 二进制（对外客户端要能直接播）；meta=1 或
 *        Accept: application/json 时返回 JSON（UI 侧试听走这条，拿文件路径本地播）
 *   POST /admin/reload        重扫/释放引擎（仅 127.0.0.1）
 *   POST /v1/audio/transcriptions → 501（ASR 在 vc167 的 :8085）
 *
 * key 门：Bearer / X-Api-Key / ?key=；/ 与 /health 门外，其余门内。
 */
public class SpeechServer extends NanoHTTPD {

    public static final String PROTO_VERSION = "vc166-tts1";
    private static final int MAX_TEXT = 1000;

    private final android.content.Context ctx;
    private volatile String apiKey = "";

    public SpeechServer(android.content.Context c, int port) {
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
                Response r = nano(Response.Status.UNAUTHORIZED, "application/json",
                        "{\"error\":{\"message\":\"invalid key\",\"type\":\"auth\"}}");
                r.addHeader("WWW-Authenticate", "Bearer realm=\"po-tts\"");
                return r;
            }
            if ("/v1/models".equals(uri)) return serveModels();
            if ("/admin/reload".equals(uri)) return serveReload(session);
            if ("/v1/audio/speech".equals(uri)) return serveSpeech(session);
            if ("/v1/audio/transcriptions".equals(uri)) {
                return err(Response.Status.NOT_IMPLEMENTED,
                        "ASR 在 vc167 的 :8085 提供（本端口只做 TTS）", "not_implemented");
            }
            return err(Response.Status.NOT_FOUND, "not found", "not_found");
        } catch (Throwable t) {
            return err(Response.Status.INTERNAL_ERROR, SpeechTts.msg(t), "engine");
        }
    }

    // ===================== /health =====================

    private Response serveHealth() {
        JSONObject h = new JSONObject();
        try {
            h.put("status", "ok");
            h.put("ok", true);
            h.put("proto", PROTO_VERSION);
            h.put("port", SpeechConfig.port(SpeechConfig.KIND_TTS));
            h.put("ready", SpeechTts.ready(ctx));
            h.put("model", SpeechTts.modelName(ctx));
            h.put("engine", SpeechTts.loaded());
            int sp = SpeechTts.loaded() ? SpeechTts.speakers() : SpeechConfig.ttsSpeakers();
            h.put("speakers", sp > 0 ? sp : 1);
            h.put("sid", SpeechConfig.ttsSid());
            h.put("sample_rate", SpeechTts.sampleRate());
        } catch (Throwable t) {
            return nano(Response.Status.OK, "application/json",
                    "{\"status\":\"ok\",\"ok\":false,\"proto\":\"" + PROTO_VERSION + "\"}");
        }
        return nano(Response.Status.OK, "application/json", h.toString());
    }

    // ===================== /v1/models =====================

    private Response serveModels() {
        try {
            JSONObject o = new JSONObject();
            o.put("active", SpeechConfig.activeName(ctx, SpeechConfig.KIND_TTS));
            o.put("proto", PROTO_VERSION);
            o.put("models", ModelPackInstaller.listModels(ctx, SpeechConfig.KIND_TTS));
            return nano(Response.Status.OK, "application/json", o.toString());
        } catch (Throwable t) {
            return err(Response.Status.INTERNAL_ERROR, SpeechTts.msg(t), "internal");
        }
    }

    // ===================== /admin/reload =====================

    private Response serveReload(IHTTPSession session) {
        String remote = session.getHeaders() == null ? null
                : session.getHeaders().get("remote-addr");
        boolean local = remote == null || "127.0.0.1".equals(remote) || "::1".equals(remote);
        if (!local) {
            return err(Response.Status.FORBIDDEN, "admin 仅本机可调", "forbidden");
        }
        SpeechTts.shutdown();
        return nano(Response.Status.OK, "application/json",
                "{\"ok\":true,\"reloaded\":true,\"active\":\""
                        + esc(SpeechConfig.activeName(ctx, SpeechConfig.KIND_TTS)) + "\"}");
    }

    // ===================== /v1/audio/speech =====================

    private Response serveSpeech(IHTTPSession session) throws Exception {
        String text = "";
        float speed = 1.0f;
        int sid = -1;
        boolean meta = false;
        boolean badVoice = false;

        String accept = session.getHeaders() == null ? null
                : session.getHeaders().get("accept");
        if (accept != null && accept.indexOf("application/json") >= 0) meta = true;

        if (Method.POST.equals(session.getMethod())) {
            JSONObject b = body(session);
            text = b.optString("input", b.optString("text", ""));
            speed = (float) b.optDouble("speed", 1.0d);
            meta = meta || b.optBoolean("meta", false);
            String v = b.optString("voice", "");
            if (v.length() == 0) v = b.optString("sid", "");
            int[] out = parseVoice(v);
            if (out[0] == -2) badVoice = true;
            else sid = out[0];
        } else {
            Map<String, String> p = session.getParms();
            text = p.get("text");
            if (text == null) text = p.get("input");
            if (text == null) text = "";
            String sp = p.get("speed");
            if (sp != null) {
                try { speed = Float.parseFloat(sp.trim()); } catch (Throwable ignore) {}
            }
            String m = p.get("meta");
            meta = meta || "1".equals(m) || "true".equals(m);
            String v = p.get("voice");
            if (v == null) v = p.get("sid");
            int[] out = parseVoice(v);
            if (out[0] == -2) badVoice = true;
            else sid = out[0];
        }

        if (badVoice) return err(Response.Status.BAD_REQUEST,
                "voice 只接受 \"default\" 或 0..N-1 的数字", "invalid_voice");
        if (text.trim().length() == 0) {
            return err(Response.Status.BAD_REQUEST, "input/text 必填", "invalid_request");
        }
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);

        String wav = SpeechTts.synthesize(ctx, text, sid, speed);
        File f = new File(wav);

        if (meta) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("file", wav);
            o.put("input", text);   // vc173: 回显原文，客户端据此做合成结果归属校验
            o.put("bytes", f.length());
            o.put("sample_rate", SpeechTts.sampleRate());
            o.put("speakers", SpeechTts.speakers());
            o.put("sid", SpeechConfig.ttsSid());
            o.put("model", SpeechTts.modelName(ctx));
            o.put("proto", PROTO_VERSION);
            return nano(Response.Status.OK, "application/json", o.toString());
        }
        InputStream in = new FileInputStream(f);
        Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "audio/wav", in, f.length());
        r.addHeader("Content-Disposition", "inline; filename=\"tts.wav\"");
        return r;
    }

    /** -1=当前选中；-2=非法；>=0=指定 sid（HTTP voice/sid 字段契约）。 */
    public static int[] parseVoice(String v) {
        if (v == null) return new int[]{-1};
        String s = v.trim();
        if (s.length() == 0 || "default".equalsIgnoreCase(s)) return new int[]{-1};
        try {
            int n = Integer.parseInt(s);
            if (n < 0) return new int[]{-2};
            return new int[]{n};
        } catch (Throwable t) {
            return new int[]{-2};
        }
    }

    // ===================== 首页 =====================

    private Response serveHome(IHTTPSession session) {
        String host = session.getHeaders() == null ? "localhost"
                : String.valueOf(session.getHeaders().get("host"));
        String base = "http://" + host;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>PocketOrca TTS</title></head>")
          .append("<body style=\"font-family:sans-serif;max-width:680px;margin:24px auto;padding:0 12px;color:#222\">")
          .append("<h2>PocketOrca TTS</h2>")
          .append("<p>OpenAI-compatible speech endpoint (sherpa-onnx VITS). proto: ")
          .append(PROTO_VERSION).append("</p><ul>")
          .append("<li><code>POST ").append(base).append("/v1/audio/speech</code> ")
          .append("— {\"input\":\"你好\",\"voice\":\"default\",\"speed\":1.0} → audio/wav</li>")
          .append("<li><code>GET ").append(base).append("/v1/audio/speech?text=你好&amp;voice=0</code> ")
          .append("— 浏览器可直接试听</li>")
          .append("<li><code>GET ").append(base).append("/health</code> — health probe</li>")
          .append("</ul><p>鉴权：<code>Authorization: Bearer &lt;key&gt;</code> ")
          .append("（Key 在 App 语音页查看/复制）。</p>")
          .append("<p>LAN-only service. Never expose to the public internet.</p>")
          .append("</body></html>");
        return nano(Response.Status.OK, "text/html; charset=utf-8", sb.toString());
    }

    // ===================== 工具 =====================

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

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private Response err(Response.Status st, String msg, String type) {
        return nano(st, "application/json",
                "{\"error\":{\"message\":\"" + esc(msg) + "\",\"type\":\"" + esc(type) + "\"}}");
    }

    private Response nano(Response.Status st, String mime, String body) {
        return NanoHTTPD.newFixedLengthResponse(st, mime, body);
    }
}
