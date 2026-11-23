package com.dawoer.npullm.mcp.tools;

import android.content.Context;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpRedact;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.access.NotificationCollector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * notifications.list：L1（通知使用权）。
 * 默认脱敏；include_sensitive=true 才给原文，且必须留 trace。
 */
public final class ToolNotifications implements McpToolDef.Executor {

    private final Context ctx;
    private final McpTrace trace;

    public ToolNotifications(Context ctx, McpTrace trace) {
        this.ctx = ctx.getApplicationContext();
        this.trace = trace;
    }

    public static McpToolDef def(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("limit", McpParamCheck.schemaInteger("返回条数", 1, 50, 20));
            props.put("since_minutes", McpParamCheck.schemaInteger("只看最近多少分钟", 1, 1440, 60));
            props.put("app", McpParamCheck.schemaString("应用包名，可选，如 com.tencent.mm"));
            props.put("include_sensitive", McpParamCheck.schemaBool("返回验证码原文（默认 false，脱敏为 ****）", false));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "notifications.list",
                "读取手机通知",
                "返回手机最近收到的通知（应用、标题、正文、时间）。仅在用户明确要求查看通知时调用。验证码类内容默认脱敏为 ****。"
                        + "只包含本服务启用之后的通知，历史通知无法获取。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolNotifications(ctx, trace));
    }

    @Override
    public McpResult exec(JSONObject args) {
        if (!NotificationCollector.isConnected()) {
            return McpResult.error("NOT_AVAILABLE",
                    "通知使用权未开启或未连接：请在设置-通知使用权里勾选本应用，或用 Shizuku 授权");
        }
        int limit = args.optInt("limit", 20);
        int sinceMin = args.optInt("since_minutes", 60);
        String app = args.optString("app", "");
        boolean sensitive = args.optBoolean("include_sensitive", false);

        long since = System.currentTimeMillis() - sinceMin * 60000L;
        List<JSONObject> list = NotificationCollector.snapshot(limit, since, app);

        JSONArray arr = new JSONArray();
        for (JSONObject o : list) {
            JSONObject c = new JSONObject();
            try {
                c.put("app", o.optString("app", ""));
                c.put("app_name", o.optString("app_name", ""));
                c.put("title", McpRedact.maybeMask(o.optString("title", ""), sensitive));
                c.put("text", McpRedact.maybeMask(o.optString("text", ""), sensitive));
                if (o.has("big_text")) {
                    c.put("big_text", McpRedact.maybeMask(o.optString("big_text", ""), sensitive));
                }
                c.put("time", o.optLong("time", 0L));
                c.put("category", o.isNull("category") ? JSONObject.NULL : o.optString("category", ""));
                c.put("key", o.optString("key", ""));
            } catch (Exception ignored) { }
            arr.put(c);
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", arr.length());
            out.put("buffer_size", NotificationCollector.size());
            out.put("redacted", !sensitive);
            out.put("items", arr);
        } catch (Exception ignored) { }

        if (sensitive && trace != null) {
            trace.proto("sensitive_read notifications.list count=" + arr.length() + " app=" + app);
        }
        return McpResult.json(out);
    }
}
