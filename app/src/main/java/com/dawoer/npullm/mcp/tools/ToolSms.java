package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.dawoer.npullm.mcp.McpFormat;
import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpRedact;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.access.Permissions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * sms.list（L2，READ_SMS）：读手机短信（收发都在内）。
 *
 * 走 ContentResolver 而不是 Shizuku `content query`：结构化、列名稳定、不受 shell 输出格式漂移影响。
 * 正文一律过 McpRedact（验证码脱敏是资损防线），要原文必须 include_sensitive=true 且留 trace。
 */
public final class ToolSms implements McpToolDef.Executor {

    private static final String PERM = Permissions.READ_SMS;

    private final Context ctx;
    private final McpTrace trace;

    public ToolSms(Context ctx, McpTrace trace) {
        this.ctx = ctx.getApplicationContext();
        this.trace = trace;
    }

    public static McpToolDef def(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("limit", McpParamCheck.schemaInteger("返回条数", 1, 50, 20));
            props.put("since_minutes", McpParamCheck.schemaInteger("只看最近多少分钟，0=不限时间（默认，按条数取最近的）", 0, 525600, 0));
            props.put("address", McpParamCheck.schemaString("只看某个号码/联系人的会话，可省略"));
            props.put("include_sensitive", McpParamCheck.schemaBool("返回验证码原文（默认 false，脱敏为 ****）", false));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "sms.list",
                "读取手机短信",
                "返回手机最近收到的短信（号码、时间、类型、正文）。仅在用户明确要求查看短信时调用。"
                        + "验证码类内容默认脱敏为 ****；号码本身不脱敏（需要识别是谁发的）。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolSms(ctx, trace),
                true,
                new PermGate(ctx, PERM));
    }

    /** 命名静态嵌套（d8 零匿名类铁律）。 */
    static final class PermGate implements McpToolDef.Gate {
        private final Context ctx;
        private final String perm;

        PermGate(Context ctx, String perm) {
            this.ctx = ctx;
            this.perm = perm;
        }

        @Override
        public boolean open() {
            return Permissions.granted(ctx, perm);
        }
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
        String address = McpFormat.normalizeNumber(args.optString("address", ""));
        boolean sensitive = args.optBoolean("include_sensitive", false);

        // since_minutes=0（默认）= 不按时间过滤，只按条数取最近 N 条。
        // 早期版本默认 30 天，但手机短信常常几个月不新增，用户问「最近短信」时被时间窗整条滤空。
        StringBuilder sel = new StringBuilder();
        java.util.ArrayList<String> selArgs = new java.util.ArrayList<>();
        if (sinceMin > 0) {
            sel.append("date>=?");
            selArgs.add(String.valueOf(System.currentTimeMillis() - sinceMin * 60000L));
        }
        if (address.length() > 0) {
            // 库里的 address 格式不统一（有 +86/无 +86/短号），用子串匹配而不是等值
            if (sel.length() > 0) sel.append(" AND ");
            sel.append("address LIKE ?");
            selArgs.add("%" + address + "%");
        }

        Cursor c = null;
        JSONArray arr = new JSONArray();
        int scanned = 0;
        try {
            Uri uri = Telephony.Sms.CONTENT_URI;
            c = ctx.getContentResolver().query(uri, new String[]{
                    "_id", "address", "date", "type", "body", "read", "thread_id"
            }, sel.length() == 0 ? null : sel.toString(), selArgs.toArray(new String[0]), "date DESC");
            if (c == null) {
                return McpResult.error("INTERNAL", "短信查询返回空 cursor（provider 拒绝）");
            }
            while (c.moveToNext() && arr.length() < limit) {
                scanned++;
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.getLong(0));
                    o.put("address", c.getString(1) == null ? "" : c.getString(1));
                    o.put("date", c.getLong(2));
                    o.put("type", McpFormat.smsTypeLabel(c.getInt(3)));
                    o.put("body", McpRedact.maybeMask(McpFormat.clip(c.getString(4), 500), sensitive));
                    o.put("read", c.getInt(5) == 1);
                    o.put("thread_id", c.getLong(6));
                } catch (Exception e) {
                    // 单行字段失败不许打掉整条工具（S1 device.state 的教训）
                    try { o.put("err", String.valueOf(e.getClass().getSimpleName())); } catch (Exception ignored) { }
                }
                arr.put(o);
            }
        } catch (SecurityException e) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        } catch (Throwable t) {
            return McpResult.error("INTERNAL", "短信读取失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", arr.length());
            out.put("redacted", !sensitive);
            out.put("since_minutes", sinceMin);
            out.put("items", arr);
        } catch (Exception ignored) { }

        if (sensitive && trace != null) {
            trace.proto("sensitive_read sms.list count=" + arr.length() + " address=" + address);
        }
        return McpResult.json(out);
    }
}
