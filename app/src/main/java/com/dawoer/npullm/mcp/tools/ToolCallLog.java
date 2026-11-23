package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;

import com.dawoer.npullm.mcp.McpFormat;
import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.access.Permissions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * calllog.list（L2，READ_CALL_LOG）：读通话记录。
 * 通话记录里没有验证码这类资损信息，号码/姓名都不脱敏（否则工具没用）。
 */
public final class ToolCallLog implements McpToolDef.Executor {

    private static final String PERM = Permissions.READ_CALL_LOG;

    private final Context ctx;

    public ToolCallLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("limit", McpParamCheck.schemaInteger("返回条数", 1, 50, 20));
            props.put("since_minutes", McpParamCheck.schemaInteger("只看最近多少分钟，0=不限时间（默认，按条数取最近的）", 0, 525600, 0));
            props.put("type", McpParamCheck.schemaString("只看某一类：incoming/outgoing/missed，可省略"));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "calllog.list",
                "读取通话记录",
                "返回手机最近的通话记录（号码、备注名、方向、时长、时间）。仅在用户问起通话/未接来电时调用。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolCallLog(ctx),
                true,
                new ToolSms.PermGate(ctx, PERM));
    }

    @Override
    public McpResult exec(JSONObject args) {
        if (!Permissions.granted(ctx, PERM)) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        }
        int limit = args.optInt("limit", 20);
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;
        int sinceMin = args.optInt("since_minutes", 0);
        if (sinceMin < 0) sinceMin = 0;
        String wantType = args.optString("type", "").trim();

        // since_minutes=0（默认）= 不按时间过滤，取最近 N 条（测试机上通话记录可能比 30 天更老）
        String dateSel = sinceMin > 0 ? CallLog.Calls.DATE + ">=?" : null;
        String[] dateArgs = sinceMin > 0
                ? new String[]{String.valueOf(System.currentTimeMillis() - sinceMin * 60000L)} : null;

        Cursor c = null;
        JSONArray arr = new JSONArray();
        try {
            c = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{
                            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE
                    },
                    dateSel, dateArgs, CallLog.Calls.DATE + " DESC");
            if (c == null) return McpResult.error("INTERNAL", "通话记录查询返回空 cursor（provider 拒绝）");
            while (c.moveToNext() && arr.length() < limit) {
                String label = McpFormat.callTypeLabel(c.getInt(5));
                if (wantType.length() > 0 && !wantType.equals(label)) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.getLong(0));
                    o.put("number", c.getString(1) == null ? "" : c.getString(1));
                    o.put("name", c.getString(2) == null ? "" : c.getString(2));
                    o.put("date", c.getLong(3));
                    o.put("duration_s", c.getLong(4));
                    o.put("type", label);
                } catch (Exception e) {
                    try { o.put("err", String.valueOf(e.getClass().getSimpleName())); } catch (Exception ignored) { }
                }
                arr.put(o);
            }
        } catch (SecurityException e) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        } catch (Throwable t) {
            return McpResult.error("INTERNAL", "通话记录读取失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", arr.length());
            out.put("since_minutes", sinceMin);
            out.put("items", arr);
        } catch (Exception ignored) { }
        return McpResult.json(out);
    }
}
