package com.dawoer.npullm.mcp;

/**
 * 传输无关的响应：状态码 + 内容类型 + body。
 * 恒 application/json，永不起 SSE。
 */
public final class McpReply {

    public static final String JSON = "application/json";

    public final int status;
    public final String contentType;
    public final String body;   // 202/405 允许为空串

    public McpReply(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? "" : body;
    }

    public static McpReply json(String body) {
        return new McpReply(200, JSON, body);
    }

    public static McpReply json(int status, String body) {
        return new McpReply(status, JSON, body);
    }

    public static McpReply empty(int status) {
        return new McpReply(status, JSON, "");
    }
}
