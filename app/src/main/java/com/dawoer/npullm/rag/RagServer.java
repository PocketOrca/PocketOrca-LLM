package com.dawoer.npullm.rag;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * RAG 独立服务端点（默认 :8082，与 :8081 搜索服务同款形态）。
 *
 * 路由：
 *   GET  /health                 → {status,proto,port}
 *   GET  /v1/rag/status          → 库/块数、嵌入进程、索引进度
 *   POST /v1/rag/index           {"dir":"<saf tree uri>","rebuild":false}
 *   POST /v1/rag/search          {"query":"..","top_k":5,"min_score":0.30,
 *                                 "include_chat":false,"library_id":-1,"file_cap":-1}
 *   GET  /v1/rag/search?q=..&k=5&min_score=0.4&include_chat=1&library_id=2&file_cap=2
 *        （vc159：GET/POST 参数完全对齐，响应回显实际生效值）
 *   POST /v1/rag/drop            {"library_id":N}
 *   POST /v1/embeddings          → OpenAI 兼容（转发到嵌入进程，外部脚本可直调）
 *
 * key 门：Authorization: Bearer K / X-Api-Key: K / ?key=K，空 = 不启用。
 * 除 /health 与 /v1/rag/status 外全部要过门（status 只报计数，不含正文）。
 */
public class RagServer extends NanoHTTPD {

    /**
     * vc159：版本串必须随 build.sh 的 VC 一起手工前移。
     * 之前停在 "vc141-rag1" 而 key 门禁是 vc157 的，运维按此字段判版本必然误判。
     */
    public static final String PROTO_VERSION = "vc159-rag1";

    private volatile RagService svc;
    private volatile String apiKey = "";

    public RagServer(RagService s) {
        this(s, RagConfig.port());
    }

    public RagServer(RagService s, int port) {
        super(port);
        this.svc = s;
    }

    public void setApiKey(String k) {
        this.apiKey = k == null ? "" : k;
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) uri = "";
        try {
            if ("/health".equals(uri)) {
                JSONObject h = new JSONObject();
                h.put("status", "ok");
                h.put("proto", PROTO_VERSION);
                h.put("port", RagConfig.port());
                return plain(Response.Status.OK, h.toString());
            }
            if (!gateOk(session)) {
                Response r = plain(Response.Status.UNAUTHORIZED, "{\"error\":\"invalid key\"}");
                r.addHeader("WWW-Authenticate", "Bearer realm=\"po-rag\"");
                return r;
            }
            // vc157: status 从门内移到门外 → 门内。它含库名与 dir_uri（路径），
            // 不该给未鉴权调用方看；对外只留 /health。
            if ("/v1/rag/status".equals(uri)) {
                return plain(Response.Status.OK, svc.status());
            }
            if ("/v1/rag/index".equals(uri) && Method.POST.equals(session.getMethod())) {
                JSONObject b = body(session);
                String dir = b.optString("dir", RagConfig.dirUri());
                if (dir == null || dir.length() == 0) {
                    return plain(Response.Status.BAD_REQUEST,
                            "{\"ok\":false,\"error\":\"未指定目录（先选目录或传 dir）\"}");
                }
                return plain(Response.Status.OK, svc.index(dir, b.optBoolean("rebuild", false)));
            }
            if ("/v1/rag/search".equals(uri)) {
                // vc159: GET 与 POST 支持同一组参数。旧实现 GET 分支不读 min_score，
                // 于是 ?min_score=0.6 被静默忽略、永远落回默认 0.30 —— 参数"存在但不生效"，
                // 比没有参数更坏。现在两条路径都解析 query/top_k/k/min_score/include_chat/
                // library_id/file_cap，并由响应回显实际生效值。
                String q = "";
                int k = RagConfig.topK();
                float min = -1f;
                boolean incChat = false;
                long libId = -1L;
                int fileCap = -1;
                if (Method.POST.equals(session.getMethod())) {
                    JSONObject b = body(session);
                    q = b.optString("query", b.optString("q", ""));
                    if (b.has("top_k")) k = b.optInt("top_k", k);
                    else if (b.has("k")) k = b.optInt("k", k);
                    if (b.has("min_score")) min = (float) b.optDouble("min_score", -1d);
                    incChat = b.optBoolean("include_chat", false);
                    libId = b.optLong("library_id", -1L);
                    if (b.has("file_cap")) fileCap = b.optInt("file_cap", -1);
                } else {
                    Map<String, String> p = session.getParms();
                    String qq = p.get("q");
                    if (qq == null) qq = p.get("query");
                    q = qq == null ? "" : qq;
                    incChat = "1".equals(p.get("include_chat"));
                    k = intParam(p, "k", k);
                    k = intParam(p, "top_k", k);
                    min = floatParam(p, "min_score", min);
                    libId = longParam(p, "library_id", libId);
                    fileCap = intParam(p, "file_cap", fileCap);
                }
                return plain(Response.Status.OK, svc.search(q, k, min, libId, incChat, fileCap));
            }
            if ("/v1/rag/drop".equals(uri) && Method.POST.equals(session.getMethod())) {
                JSONObject b = body(session);
                long id = b.optLong("library_id", -1);
                if (id <= 0) {
                    return plain(Response.Status.BAD_REQUEST,
                            "{\"ok\":false,\"error\":\"library_id 必填\"}");
                }
                return plain(Response.Status.OK, svc.drop(id));
            }
            if ("/v1/embeddings".equals(uri) && Method.POST.equals(session.getMethod())) {
                return proxyEmbeddings(session);
            }
            if ("/v1/rag/libs".equals(uri)) {
                return plain(Response.Status.OK, svc.status());
            }
            return plain(Response.Status.NOT_FOUND, "{\"error\":\"not found\"}");
        } catch (Throwable t) {
            return plain(Response.Status.INTERNAL_ERROR,
                    "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}");
        }
    }

    // -------------------------------------------------------------- gate

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

    // ------------------------------------------------------------ helpers

    static Map<String, String> emptyMap() {
        return new HashMap<String, String>();
    }

    // vc159: GET 查询参数解析（非法值静默回落默认，不抛 400——curl 手敲容错优先）
    static int intParam(Map<String, String> p, String k, int dft) {
        if (p == null) return dft;
        String v = p.get(k);
        if (v == null) return dft;
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return dft; }
    }

    static long longParam(Map<String, String> p, String k, long dft) {
        if (p == null) return dft;
        String v = p.get(k);
        if (v == null) return dft;
        try { return Long.parseLong(v.trim()); } catch (Throwable t) { return dft; }
    }

    static float floatParam(Map<String, String> p, String k, float dft) {
        if (p == null) return dft;
        String v = p.get(k);
        if (v == null) return dft;
        try { return Float.parseFloat(v.trim()); } catch (Throwable t) { return dft; }
    }

    JSONObject body(IHTTPSession session) {
        try {
            Map<String, String> files = emptyMap();
            session.parseBody(files);
            String raw = files.get("postData");
            if (raw == null || raw.trim().length() == 0) return new JSONObject();
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /**
     * /v1/embeddings 透传：保证嵌入进程在线，再把请求原样转给 127.0.0.1:8083，
     * 响应原样回。这样外部脚本调 :8082/v1/embeddings 得到的是标准 OAI 形状，
     * 而不用知道内部的 :8083。
     */
    Response proxyEmbeddings(IHTTPSession session) {
        try {
            Map<String, String> files = emptyMap();
            session.parseBody(files);
            String raw = files.get("postData");
            if (raw == null || raw.trim().length() == 0) {
                return plain(Response.Status.BAD_REQUEST, "{\"error\":\"empty body\"}");
            }
            JSONObject b = new JSONObject(raw);
            String input = b.optString("input", "");
            if (input.length() == 0) {
                return plain(Response.Status.BAD_REQUEST,
                        "{\"error\":\"\\\"input\\\" must be a non-empty string\"}");
            }
            RagService s = svc;
            if (s == null || !s.embedClient().ensureUp()) {
                return plain(Response.Status.SERVICE_UNAVAILABLE,
                        "{\"error\":\"embedding backend unavailable\"}");
            }
            HttpURLConnection c = null;
            try {
                URL u = new URL("http://127.0.0.1:" + RagConfig.EMBED_PORT + "/v1/embeddings");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(120000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(raw.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
                int code = c.getResponseCode();
                InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
                String resp = in == null ? "{\"error\":\"no body\"}" : readAll(in);
                return newFixedLengthResponse(code == 200
                        ? Response.Status.OK : Response.Status.lookup(code),
                        "application/json", resp);
            } finally {
                if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            return plain(Response.Status.INTERNAL_ERROR,
                    "{\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}");
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static Response plain(Response.Status st, String body) {
        return newFixedLengthResponse(st, "application/json", body);
    }
}
