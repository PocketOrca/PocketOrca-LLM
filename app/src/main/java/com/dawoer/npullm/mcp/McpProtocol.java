package com.dawoer.npullm.mcp;

/**
 * 协议常量：版本集合 / 错误码 / _meta 键名 / resultType。
 * 事实源 = MCP 规范 2026-07-28 与 2025-06-18，代际划分沿用官方 SDK 术语
 * （HANDSHAKE_PROTOCOL_VERSIONS / MODERN_PROTOCOL_VERSIONS）。
 *
 * 本类零 Android 依赖，JVM 可直测。
 */
public final class McpProtocol {

    private McpProtocol() {}

    /** 走 initialize 握手的代际（旧代）。 */
    public static final String[] HANDSHAKE_VERSIONS = {
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"
    };

    /** 无状态、每请求信封的代际（新代）。 */
    public static final String[] MODERN_VERSIONS = {
            "2026-07-28"
    };

    /** 握手代缺省回落版本。 */
    public static final String HANDSHAKE_FALLBACK = "2025-06-18";
    /** 我们对外声明的首选版本（MODERN 唯一成员）。 */
    public static final String PREFERRED_VERSION = "2026-07-28";

    // ---- _meta 键（MODERN 每请求必带）----
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    // ---- HTTP 头 ----
    public static final String HDR_PROTOCOL_VERSION = "mcp-protocol-version";
    public static final String HDR_METHOD = "mcp-method";
    public static final String HDR_NAME = "mcp-name";
    public static final String HDR_ORIGIN = "origin";
    public static final String HDR_AUTH = "authorization";
    public static final String HDR_API_KEY = "x-api-key";

    // ---- JSON-RPC 错误码 ----
    public static final int ERR_PARSE = -32700;
    public static final int ERR_INVALID_REQUEST = -32600;
    public static final int ERR_METHOD_NOT_FOUND = -32601;
    public static final int ERR_INVALID_PARAMS = -32602;
    public static final int ERR_INTERNAL = -32603;
    public static final int ERR_HEADER_MISMATCH = -32020;
    /** 缺必需 client capability（MODERN；SDK ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY） */
    public static final int ERR_MISSING_CAPABILITY = -32021;
    public static final int ERR_UNSUPPORTED_VERSION = -32022;

    // ---- 结果字段 ----
    public static final String RESULT_TYPE_COMPLETE = "complete";
    public static final long TOOL_LIST_TTL_MS = 60000L;
    public static final String CACHE_SCOPE_PRIVATE = "private";

    // ---- 方法名 ----
    public static final String M_INITIALIZE = "initialize";
    public static final String M_INITIALIZED = "notifications/initialized";
    public static final String M_DISCOVER = "server/discover";
    public static final String M_TOOLS_LIST = "tools/list";
    public static final String M_TOOLS_CALL = "tools/call";
    public static final String M_PING = "ping";

    public static boolean isHandshake(String v) {
        return contains(HANDSHAKE_VERSIONS, v);
    }

    public static boolean isModern(String v) {
        return contains(MODERN_VERSIONS, v);
    }

    private static boolean contains(String[] arr, String v) {
        if (v == null) return false;
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    /** 供错误响应 data.supportedVersions 使用。 */
    public static String[] allVersions() {
        String[] out = new String[HANDSHAKE_VERSIONS.length + MODERN_VERSIONS.length];
        System.arraycopy(HANDSHAKE_VERSIONS, 0, out, 0, HANDSHAKE_VERSIONS.length);
        System.arraycopy(MODERN_VERSIONS, 0, out, HANDSHAKE_VERSIONS.length, MODERN_VERSIONS.length);
        return out;
    }
}
