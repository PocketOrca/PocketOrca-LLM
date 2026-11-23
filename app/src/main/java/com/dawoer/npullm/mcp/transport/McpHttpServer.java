package com.dawoer.npullm.mcp.transport;

import com.dawoer.npullm.mcp.McpReply;
import com.dawoer.npullm.mcp.McpServer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * :8086 传输层。
 * 路由：POST /mcp（协议）、GET /health（免鉴权探活）、GET /info（要 key）、GET /mcp → 405（明确不提供 SSE）。
 * 恒 application/json 单对象，不分块、不流式。
 */
public final class McpHttpServer extends NanoHTTPD {

    private final McpServer core;

    public McpHttpServer(int port, McpServer core) {
        super(port);
        this.core = core;
    }

    /** NanoHTTPD 默认单线程处理请求；tools/call 已在核心层串行，这里保持简单。 */
    public boolean startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        // vc186：NanoHTTPD 的 getUri() 只给 path，query 在 getQueryParameterString()。
        // /diagnose?trace=5&deep=1 必须把 query 带进 core.handle，否则参数被静默忽略。
        String qs = session.getQueryParameterString();
        if (qs != null && qs.length() > 0) uri = uri + "?" + qs;
        String method = session.getMethod() != null ? session.getMethod().name() : "GET";

        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> e : session.getHeaders().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey().toLowerCase(), e.getValue());
        }

        String body = "";
        if ("POST".equals(method) || "PUT".equals(method)) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String pd = files.get("postData");
                if (pd != null) body = pd;
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, McpReply.JSON,
                        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"body read failed\"}}");
            }
        }

        McpReply r = core.handle(method, uri, headers, body);
        Response.Status st = Response.Status.lookup(r.status);
        if (st == null) st = Response.Status.OK;
        Response resp = newFixedLengthResponse(st, r.contentType, r.body);
        resp.addHeader("Cache-Control", "no-store");
        return resp;
    }
}
