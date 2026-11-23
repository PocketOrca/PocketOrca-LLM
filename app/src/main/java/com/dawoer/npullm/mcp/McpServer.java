package com.dawoer.npullm.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MCP 协议分发核心。零 Android 依赖，JVM 可全测。
 *
 * 两代并存：
 *  - HANDSHAKE（2024-11-05 … 2025-11-25）：initialize 握手，方法式调用。
 *  - MODERN（2026-07-28）：无 initialize、无 session，每请求带 _meta 信封；
 *    header 与 body 的 protocolVersion 必须一致，不认识的方法回 404 + -32601。
 *
 * 不实现：resources/prompts、SSE、progress 通知、多客户端。
 */
public final class McpServer {

    private final String expectedKey;
    private final String[] allowOrigins;
    private final McpToolRegistry registry;
    private final McpTrace trace;
    private final McpRateLimit rate;
    private final String serverName;
    private final String serverVersion;
    /** MODERN 头校验开关：真机联调时可临时关掉，正式恒 true。 */
    private final boolean strictModernHeaders;
    /** Shizuku 状态，由 transport 层更新（/health 与 /info 回显）。 */
    private volatile String shizukuState = "unknown";
    /** 运行时权限摘要（由 Android 层提供实时值；/health 回显，便于真机验收与 UI 诊断）。 */
    public interface PermSource { String json(); }
    private volatile PermSource permSource;
    /**
     * vc186：环境诊断（由 Android 层提供）。HTTP GET /diagnose 与 MCP 工具 device.diagnose
     * 共用同一实现，保证「agent 看到的」和「人 curl 看到的」永远一致。
     */
    public interface DiagSource { String json(int traceLines, boolean deep); }
    private volatile DiagSource diagSource;

    public McpServer(String expectedKey, String[] allowOrigins, McpToolRegistry registry,
                     McpTrace trace, McpRateLimit rate, String serverName, String serverVersion) {
        this(expectedKey, allowOrigins, registry, trace, rate, serverName, serverVersion, true);
    }

    public McpServer(String expectedKey, String[] allowOrigins, McpToolRegistry registry,
                     McpTrace trace, McpRateLimit rate, String serverName, String serverVersion,
                     boolean strictModernHeaders) {
        this.expectedKey = expectedKey;
        this.allowOrigins = allowOrigins;
        this.registry = registry;
        this.trace = trace;
        this.rate = rate;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.strictModernHeaders = strictModernHeaders;
    }

    public void setShizukuState(String state) {
        this.shizukuState = state == null ? "unknown" : state;
    }

    public void setPermSource(PermSource src) {
        this.permSource = src;
    }

    public void setDiagSource(DiagSource src) {
        this.diagSource = src;
    }

    /**
     * 传输层唯一入口。headers 的 key 必须是**小写**（HTTP 头大小写不敏感，这里统一小写）。
     * 返回的 status：200 正常 / 202 通知 / 400 协议错 / 401 鉴权 / 403 Origin / 404 未知方法 / 405 GET /mcp / 429 限流。
     */
    public McpReply handle(String method, String path, java.util.Map<String, String> headers, String body) {
        String p = path == null ? "/" : path;
        String h = method == null ? "GET" : method.toUpperCase();
        String query = "";
        int qi = p.indexOf('?');
        if (qi >= 0) {
            query = p.substring(qi + 1);
            p = p.substring(0, qi);
        }

        if ("/health".equals(p)) {
            JSONObject hb = new JSONObject();
            try {
                hb.put("ok", true);
                hb.put("proto", PROTO_TAG);
                hb.put("tools", registry.visibleCount());
                hb.put("server", serverName);
                hb.put("shizuku", shizukuState);
                // 实时取：权限可能在 UI 里刚被授予/撤销，快照会骗人
                try {
                    PermSource ps = permSource;
                    hb.put("perms", new JSONObject(ps == null ? "{}" : ps.json()));
                } catch (Exception ignored) { }
            } catch (Exception ignored) { }
            return McpReply.json(hb.toString());
        }

        if ("/diagnose".equals(p)) {
            if (!"GET".equals(h) && !"POST".equals(h)) return McpReply.empty(405);
            String givenD = McpAuth.extractKey(
                    headers == null ? null : headers.get(McpProtocol.HDR_AUTH),
                    headers == null ? null : headers.get(McpProtocol.HDR_API_KEY));
            if (!McpAuth.keyEquals(expectedKey, givenD)) {
                return error(401, null, McpProtocol.ERR_INVALID_REQUEST, "invalid key", null);
            }
            DiagSource d = diagSource;
            if (d == null) {
                return McpReply.json("{\"verdict\":\"blocked\",\"checks\":[],\"next_steps\":[\"diagnose: 诊断源未接入\"],\"trace\":[]}");
            }
            int lines = queryInt(query, "trace", 10);
            boolean deep = queryBool(query, "deep", false);
            try {
                return McpReply.json(d.json(lines, deep));
            } catch (Throwable t) {
                JSONObject err = new JSONObject();
                try {
                    err.put("verdict", "blocked");
                    err.put("error", "诊断自身异常: " + t.getClass().getSimpleName());
                } catch (Exception ignored) { }
                if (trace != null) trace.protoError(500, McpProtocol.ERR_INTERNAL, "diagnose failed");
                return McpReply.json(err.toString());
            }
        }

        if ("/info".equals(p) && "GET".equals(h)) {
            String given0 = McpAuth.extractKey(
                    headers == null ? null : headers.get(McpProtocol.HDR_AUTH),
                    headers == null ? null : headers.get(McpProtocol.HDR_API_KEY));
            if (!McpAuth.keyEquals(expectedKey, given0)) {
                return error(401, null, McpProtocol.ERR_INVALID_REQUEST, "invalid key", null);
            }
            JSONObject info = new JSONObject();
            try {
                info.put("proto", PROTO_TAG);
                info.put("supportedVersions", new JSONArray(McpProtocol.allVersions()));
                info.put("capabilities", capabilities());
                info.put("serverInfo", serverInfo());
                JSONArray tl = new JSONArray();
                for (String n : registry.names()) {
                    McpToolDef d = registry.get(n);
                    if (d.visible()) tl.put(n);
                }
                info.put("tools", tl);
                // 未开通的工具（含不可逆 scope 与未授权权限）单列，便于客户端/UI 诊断
                JSONArray hidden = new JSONArray();
                for (String n : registry.names()) {
                    McpToolDef d = registry.get(n);
                    if (!d.visible()) hidden.put(n);
                }
                info.put("hiddenTools", hidden);
                info.put("shizuku", shizukuState);
                info.put("toolConfigErrors", new JSONArray());
            } catch (Exception ignored) { }
            return McpReply.json(info.toString());
        }

        if ("/mcp".equals(p) && !"POST".equals(h)) {
            // 明确表示不提供 SSE
            return McpReply.empty(405);
        }
        if (!"/mcp".equals(p)) return McpReply.empty(404);

        if (!McpAuth.originAllowed(headers == null ? null : headers.get(McpProtocol.HDR_ORIGIN), allowOrigins)) {
            return error(403, null, McpProtocol.ERR_INVALID_REQUEST, "Origin not allowed", null);
        }
        String given = McpAuth.extractKey(
                headers == null ? null : headers.get(McpProtocol.HDR_AUTH),
                headers == null ? null : headers.get(McpProtocol.HDR_API_KEY));
        if (!McpAuth.keyEquals(expectedKey, given)) {
            return error(401, null, McpProtocol.ERR_INVALID_REQUEST, "invalid key", null);
        }
        if (!rate.tryAcquire(System.currentTimeMillis())) {
            return error(429, null, McpProtocol.ERR_INTERNAL, "rate limited (60/min)", null);
        }

        JSONObject req;
        try {
            req = new JSONObject(body == null ? "" : body);
        } catch (JSONException e) {
            return error(400, null, McpProtocol.ERR_PARSE, "JSON parse error", null);
        }

        String rpcMethod = req.optString("method", "");
        Object id = req.has("id") ? req.opt("id") : null;

        // 头与 body._meta 的版本必须一致（两代通用，规范 MUST）——先于代际判定，避免"头说旧代、body 说新代"被放行
        String hdrV = headers == null ? null : headers.get(McpProtocol.HDR_PROTOCOL_VERSION);
        String metaV = metaVersion(req);
        if (hdrV != null && metaV != null && !hdrV.equals(metaV)) {
            JSONObject data = new JSONObject();
            try {
                data.put("header", hdrV);
                data.put("meta", metaV);
            } catch (Exception ignored) { }
            return error(400, id, McpProtocol.ERR_HEADER_MISMATCH,
                    "MCP-Protocol-Version 与 params._meta.protocolVersion 不一致", data);
        }

        String gen = pickGeneration(headers, req, rpcMethod);
        if ("ERR_VERSION".equals(gen)) {
            JSONObject data = new JSONObject();
            try { data.put("supportedVersions", new JSONArray(McpProtocol.allVersions())); } catch (Exception ignored) { }
            return error(400, id, McpProtocol.ERR_UNSUPPORTED_VERSION, "Unsupported protocol version", data);
        }
        boolean modern = "MODERN".equals(gen);

        if (modern) {
            McpReply bad = validateModern(headers, req, rpcMethod);
            if (bad != null) return bad;
        }

        if (McpProtocol.M_INITIALIZED.equals(rpcMethod)) {
            return McpReply.empty(202);   // 通知类：无 body
        }

        if (McpProtocol.M_INITIALIZE.equals(rpcMethod)) {
            if (modern) return notFound(id);
            String want = params(req).optString("protocolVersion", McpProtocol.HANDSHAKE_FALLBACK);
            String negotiated = McpProtocol.isHandshake(want) ? want : McpProtocol.HANDSHAKE_FALLBACK;
            JSONObject result = new JSONObject();
            try {
                result.put("protocolVersion", negotiated);
                result.put("capabilities", capabilities());
                result.put("serverInfo", serverInfo());
            } catch (Exception ignored) { }
            return result(id, result, false);
        }

        if (McpProtocol.M_DISCOVER.equals(rpcMethod)) {
            if (!modern) return notFound(id);
            JSONObject result = new JSONObject();
            try {
                result.put("supportedVersions", new JSONArray(McpProtocol.allVersions()));
                result.put("capabilities", capabilities());
                result.put("serverInfo", serverInfo());
                // 规范/官方 Java SDK DiscoverResult 要求：ttlMs 与 cacheScope 非空
                result.put("ttlMs", McpProtocol.TOOL_LIST_TTL_MS);
                result.put("cacheScope", McpProtocol.CACHE_SCOPE_PRIVATE);
            } catch (Exception ignored) { }
            return result(id, result, true);
        }

        if (McpProtocol.M_TOOLS_LIST.equals(rpcMethod)) {
            JSONObject result = new JSONObject();
            try {
                result.put("tools", registry.listJson());
                if (modern) {
                    result.put("ttlMs", McpProtocol.TOOL_LIST_TTL_MS);
                    result.put("cacheScope", McpProtocol.CACHE_SCOPE_PRIVATE);
                }
            } catch (Exception ignored) { }
            return result(id, result, modern);
        }

        if (McpProtocol.M_TOOLS_CALL.equals(rpcMethod)) {
            JSONObject pr = params(req);
            String name = pr.optString("name", "");
            if (name.length() == 0) {
                return error(400, id, McpProtocol.ERR_INVALID_PARAMS, "params.name required", null);
            }
            JSONObject args = pr.optJSONObject("arguments");
            McpResult r = registry.call(name, args == null ? new JSONObject() : args);
            JSONObject result = new JSONObject();
            try {
                result.put("content", r.content);
                result.put("isError", r.isError);
            } catch (Exception ignored) { }
            return result(id, result, modern);
        }

        if (McpProtocol.M_PING.equals(rpcMethod)) {
            if (modern) return notFound(id);
            return result(id, new JSONObject(), false);
        }

        return notFound(id);
    }

    public static final String PROTO_TAG = "vc186-mcp3";

    // ---------------- 内部 ----------------

    /** "HANDSHAKE" / "MODERN" / "ERR_VERSION"。 */
    private String pickGeneration(java.util.Map<String, String> headers, JSONObject req, String rpcMethod) {
        String hdr = headers == null ? null : headers.get(McpProtocol.HDR_PROTOCOL_VERSION);
        if (hdr != null && hdr.length() > 0) {
            if (McpProtocol.isHandshake(hdr)) return "HANDSHAKE";
            if (McpProtocol.isModern(hdr)) return "MODERN";
            return "ERR_VERSION";
        }
        if (McpProtocol.M_INITIALIZE.equals(rpcMethod)) return "HANDSHAKE";
        String meta = metaVersion(req);
        if (meta != null && meta.length() > 0) {
            if (McpProtocol.isModern(meta)) return "MODERN";
            if (McpProtocol.isHandshake(meta)) return "HANDSHAKE";
            return "ERR_VERSION";
        }
        return "HANDSHAKE";   // 缺省回落
    }

    private String metaVersion(JSONObject req) {
        JSONObject p = req.optJSONObject("params");
        if (p == null) return null;
        JSONObject meta = p.optJSONObject("_meta");
        if (meta == null) return null;
        return meta.optString(McpProtocol.META_PROTOCOL_VERSION, null);
    }

    /** MODERN 强制校验：header==body._meta、clientCapabilities 必带、Mcp-Method/Mcp-Name 必填。 */
    private McpReply validateModern(java.util.Map<String, String> headers, JSONObject req, String rpcMethod) {
        Object id = req.has("id") ? req.opt("id") : null;
        String hdr = headers == null ? null : headers.get(McpProtocol.HDR_PROTOCOL_VERSION);
        String meta = metaVersion(req);

        if (hdr == null || meta == null || !hdr.equals(meta)) {
            JSONObject data = new JSONObject();
            try {
                data.put("header", hdr);
                data.put("meta", meta);
            } catch (Exception ignored) { }
            return error(400, id, McpProtocol.ERR_HEADER_MISMATCH,
                    "MCP-Protocol-Version 必须与 params._meta.protocolVersion 一致", data);
        }
        JSONObject pr = req.optJSONObject("params");
        JSONObject metaObj = pr == null ? null : pr.optJSONObject("_meta");
        if (metaObj == null || !metaObj.has(McpProtocol.META_CLIENT_CAPABILITIES)) {
            return error(400, id, McpProtocol.ERR_INVALID_PARAMS,
                    "params._meta.clientCapabilities required", null);
        }
        if (strictModernHeaders) {
            String hm = headers.get(McpProtocol.HDR_METHOD);
            if (hm == null || !hm.equals(rpcMethod)) {
                return error(400, id, McpProtocol.ERR_HEADER_MISMATCH,
                        "Mcp-Method 缺失或与 body.method 不一致", null);
            }
            if (McpProtocol.M_TOOLS_CALL.equals(rpcMethod)) {
                String hn = headers.get(McpProtocol.HDR_NAME);
                String bn = pr == null ? "" : pr.optString("name", "");
                if (hn == null || !hn.equals(bn)) {
                    return error(400, id, McpProtocol.ERR_HEADER_MISMATCH,
                            "Mcp-Name 缺失或与 params.name 不一致", null);
                }
            }
        }
        return null;
    }

    private McpReply notFound(Object id) {
        return error(404, id, McpProtocol.ERR_METHOD_NOT_FOUND, "Method not found", null);
    }

    private McpReply result(Object id, JSONObject result, boolean modern) {
        try {
            if (modern) result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
            JSONObject env = new JSONObject();
            env.put("jsonrpc", "2.0");
            env.put("id", id == null ? JSONObject.NULL : id);
            env.put("result", result);
            return McpReply.json(env.toString());
        } catch (Exception e) {
            return error(500, id, McpProtocol.ERR_INTERNAL, "build result failed", null);
        }
    }

    private McpReply error(int httpStatus, Object id, int code, String message, JSONObject data) {
        try {
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message);
            if (data != null) err.put("data", data);
            JSONObject env = new JSONObject();
            env.put("jsonrpc", "2.0");
            env.put("id", id == null ? JSONObject.NULL : id);
            env.put("error", err);
            if (trace != null) trace.protoError(httpStatus, code, message);
            return McpReply.json(httpStatus, env.toString());
        } catch (Exception e) {
            return McpReply.json(500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal\"}}");
        }
    }

    private JSONObject capabilities() {
        JSONObject caps = new JSONObject();
        JSONObject tools = new JSONObject();
        try {
            tools.put("listChanged", false);
            caps.put("tools", tools);
        } catch (Exception ignored) { }
        return caps;
    }

    private JSONObject serverInfo() {
        JSONObject si = new JSONObject();
        try {
            si.put("name", serverName);
            si.put("version", serverVersion);
        } catch (Exception ignored) { }
        return si;
    }

    private JSONObject params(JSONObject req) {
        JSONObject p = req.optJSONObject("params");
        return p == null ? new JSONObject() : p;
    }

    /** query 里取整数（缺省或非法回 def，范围夹到 0..50）。 */
    static int queryInt(String query, String key, int def) {
        String v = queryValue(query, key);
        if (v == null) return def;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) n = 0;
            if (n > 50) n = 50;
            return n;
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean queryBool(String query, String key, boolean def) {
        String v = queryValue(query, key);
        if (v == null) return def;
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.length() == 0 || key == null) return null;
        String[] parts = query.split("&");
        for (String part : parts) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (!key.equals(k)) continue;
            return eq < 0 ? "" : part.substring(eq + 1);
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
