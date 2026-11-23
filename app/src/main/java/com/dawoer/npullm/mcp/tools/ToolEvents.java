package com.dawoer.npullm.mcp.tools;

import android.content.Context;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.outbox.OutboxStore;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * events.poll（vc185）：outbox 事实源。
 *
 * 「推是触发、拉是事实源」：推送（/v1/runs）只负责叫醒 agent，agent 被叫醒后
 * 用 seq 游标把事件补齐——网络抖一下、进程被杀一次，推送丢了也能靠它复原。
 */
public final class ToolEvents implements McpToolDef.Executor {

    private final Context ctx;

    public ToolEvents(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("since_seq", McpParamCheck.schemaInteger("只返回此 seq 之后的事件（0=从头）", 0, 2147483647, 0));
            props.put("limit", McpParamCheck.schemaInteger("返回条数", 1, 50, 20));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "events.poll",
                "拉取手机推送事件",
                "按 seq 游标返回手机侧事件（通知/短信/来电/电量等，已脱敏）。"
                        + "当收到推送唤醒或需要补齐遗漏事件时调用；since_seq 用上次返回的最大 seq。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolEvents(ctx));
    }

    @Override
    public McpResult exec(JSONObject args) {
        long since = args.optLong("since_seq", 0L);
        int limit = args.optInt("limit", 20);
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;

        JSONArray raw = OutboxStore.poll(ctx, since, limit);
        JSONArray items = new JSONArray();
        long next = since;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject e = raw.optJSONObject(i);
            if (e == null) continue;
            JSONObject c = new JSONObject();
            try {
                c.put("seq", e.optLong("seq", 0));
                c.put("event_id", e.optString("event_id", ""));
                c.put("ts", e.optLong("ts", 0));
                c.put("source", e.optString("source", ""));
                c.put("kind", e.optString("kind", ""));
                c.put("title", e.optString("title", ""));
                c.put("body_excerpt", e.optString("body_excerpt", ""));
                c.put("sensitive", e.optBoolean("sensitive", false));
                c.put("state", e.optString("state", ""));
                c.put("attempts", e.optInt("attempts", 0));
                c.put("run_id", e.optString("run_id", ""));
                next = Math.max(next, e.optLong("seq", 0));
            } catch (Exception ignored) { }
            items.put(c);
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", items.length());
            out.put("next_seq", next);
            out.put("items", items);
        } catch (Exception ignored) { }
        return McpResult.json(out);
    }
}
