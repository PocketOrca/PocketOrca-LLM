package com.dawoer.npullm.mcp;

import com.dawoer.npullm.mcp.diag.McpTraceFile;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 调用轨迹（vc186 起落盘）。
 *
 * 两条出口：
 *   1) Sink → logcat（联调时现场看，随时会丢）；
 *   2) McpTraceFile → files/mcp/trace.jsonl（滚动 1 份，可被 device.diagnose 读回）。
 *
 * 只存 argsHash，不存原始参数 —— 通知/短信正文、号码、验证码都不进日志。
 */
public final class McpTrace {

    public interface Sink {
        void line(String msg);
    }

    private final Sink sink;
    private final McpTraceFile file;

    public McpTrace(Sink sink) {
        this(sink, null);
    }

    public McpTrace(Sink sink, McpTraceFile file) {
        this.sink = sink;
        this.file = file;
    }

    public McpTraceFile file() {
        return file;
    }

    /** 兼容旧调用（无 code/错误文本）。 */
    public void call(String tool, String argsJson, boolean ok, long ms) {
        tool(tool, argsJson, ok, ms, "", "");
    }

    /** 一次工具调用：logcat 一行 + JSONL 一行。err 只在失败时给，已由 McpTraceFile 脱敏。 */
    public void tool(String tool, String argsJson, boolean ok, long ms, String code, String err) {
        String hash = McpAuth.sha16(argsJson);
        if (sink != null) {
            StringBuilder sb = new StringBuilder(96);
            sb.append("tool=").append(tool)
              .append(" args=").append(hash)
              .append(" ms=").append(ms)
              .append(" ok=").append(ok);
            if (code != null && code.length() > 0) sb.append(" code=").append(code);
            sink.line(sb.toString());
        }
        if (file != null) file.tool(tool, ok, ms, code, hash, err);
    }

    /** 协议层事件（非致命）。 */
    public void proto(String msg) {
        if (sink != null) sink.line("proto: " + msg);
        if (file != null) file.proto("notice", msg);
    }

    /** 协议层错误（4xx/5xx）：进 trace，诊断时能看到「客户端根本没连对」。 */
    public void protoError(int httpStatus, int code, String message) {
        String d = "http=" + httpStatus + " code=" + code + " " + (message == null ? "" : message);
        if (sink != null) sink.line("proto: " + d);
        if (file != null) file.proto("error", d);
    }

    /**
     * 从 McpResult 的错误文本里抽固定词表码（"PERMISSION_DENIED: 人话" → PERMISSION_DENIED）。
     * 词表外的内容一律不回（防止把手机号/正文带进 trace）。
     */
    public static String codeOf(McpResult r) {
        if (r == null || !r.isError) return "";
        String text = firstText(r.content);
        int i = text.indexOf(": ");
        String head = i > 0 ? text.substring(0, i) : text;
        if (head.length() == 0 || head.length() > 24) return "";
        for (int k = 0; k < head.length(); k++) {
            char c = head.charAt(k);
            boolean okChar = (c >= 'A' && c <= 'Z') || c == '_';
            if (!okChar) return "";
        }
        return head;
    }

    /** 错误人话（仅用于 trace，已脱敏；成功返回空串）。 */
    public static String errText(McpResult r) {
        if (r == null || !r.isError) return "";
        return firstText(r.content);
    }

    private static String firstText(JSONArray content) {
        if (content == null) return "";
        for (int i = 0; i < content.length(); i++) {
            JSONObject o = content.optJSONObject(i);
            if (o == null) continue;
            if ("text".equals(o.optString("type", ""))) return o.optString("text", "");
        }
        return "";
    }
}
