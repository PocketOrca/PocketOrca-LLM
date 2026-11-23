package com.dawoer.npullm.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * tools/call 的结果体。业务失败一律 isError=true + 人话 reason（PERMISSION_DENIED 等），
 * 绝不裸抛异常、绝不空 content —— 静默失败率必须为零。
 */
public final class McpResult {

    public final boolean isError;
    public final JSONArray content;

    private McpResult(boolean isError, JSONArray content) {
        this.isError = isError;
        this.content = content;
    }

    public static McpResult text(String text) {
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", text == null ? "" : text);
        } catch (Exception ignored) { }
        arr.put(item);
        return new McpResult(false, arr);
    }

    public static McpResult json(JSONObject obj) {
        return text(obj == null ? "{}" : obj.toString());
    }

    /** 图片内容（screen.observe 用；S1 未接，接口先留）。 */
    public static McpResult image(String mimeType, String base64) {
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "image");
            item.put("data", base64 == null ? "" : base64);
            item.put("mimeType", mimeType == null ? "image/png" : mimeType);
        } catch (Exception ignored) { }
        arr.put(item);
        return new McpResult(false, arr);
    }

    /** reason 用固定词表：PERMISSION_DENIED / NOT_AVAILABLE / NOT_FOUND / LOCKED / INVALID_ARGUMENT / TIMEOUT / INTERNAL。 */
    public static McpResult error(String reason, String human) {
        return new McpResult(true, textBody(reason + ": " + (human == null ? "" : human)));
    }

    private static JSONArray textBody(String s) {
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", s);
        } catch (Exception ignored) { }
        arr.put(item);
        return arr;
    }
}
