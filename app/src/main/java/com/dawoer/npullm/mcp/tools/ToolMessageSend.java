package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.dawoer.npullm.mcp.McpFormat;
import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpScope;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.access.Permissions;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * messages.send（L2，SEND_SMS）—— 不可逆。
 *
 * 三重叠加（设计 §5.4 L4）：
 *  1) 默认不进 tools/list（visibleByDefault=false）
 *  2) 用户需在 UI 里显式开通 scope（McpScope.SEND_SMS）
 *  3) 需要 SEND_SMS 运行时权限
 * 发送后**必须回读验证**（事后·回读原语）：查 sent 箱确认落库，读不到就如实报 verified=false。
 */
public final class ToolMessageSend implements McpToolDef.Executor {

    private static final String PERM = Permissions.SEND_SMS;

    private final Context ctx;
    private final McpTrace trace;

    public ToolMessageSend(Context ctx, McpTrace trace) {
        this.ctx = ctx.getApplicationContext();
        this.trace = trace;
    }

    public static McpToolDef def(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            JSONObject to = McpParamCheck.schemaString("收件人号码，如 13800138000");
            try { to.put("maxLength", 32); } catch (Exception ignored) { }
            props.put("to", to);
            JSONObject text = McpParamCheck.schemaString("短信正文（建议 ≤200 字，超长会自动分片）");
            try { text.put("maxLength", 1000); } catch (Exception ignored) { }
            props.put("text", text);
        } catch (Exception ignored) { }

        return new McpToolDef(
                "messages.send",
                "发送短信",
                "给指定号码发送一条短信。**不可逆**：发出后无法撤回，只在用户明确要求并确认收件人与正文后调用；"
                        + "不要自行补全或猜测号码。调用后请把返回值里的 verified 字段如实转述给用户。",
                McpParamCheck.objectSchema(props, new String[]{"to", "text"}),
                McpToolDef.LEVEL_IRREVERSIBLE,
                new ToolMessageSend(ctx, trace),
                // visibleByDefault=true 是**故意的**：不可逆工具的「默认隐藏」由 SendGate 负责
                // （scope 未开通 → gate=false → visible() 为 false）。若这里传 false，
                // visible() 会短路返回 false，scope 打开了也永远不会出现（实测踩过）。
                true,
                new SendGate(ctx));
    }

    /** scope 与权限同时满足才露出来。 */
    static final class SendGate implements McpToolDef.Gate {
        private final Context ctx;

        SendGate(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean open() {
            return McpScope.enabled(ctx, McpScope.SEND_SMS) && Permissions.granted(ctx, PERM);
        }
    }

    @Override
    public McpResult exec(JSONObject args) {
        if (!McpScope.enabled(ctx, McpScope.SEND_SMS)) {
            return McpResult.error("NOT_AVAILABLE", "messages.send 未开通：这是不可逆操作，需用户在「MCP 服务」卡片里手动启用");
        }
        if (!Permissions.granted(ctx, PERM)) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        }
        String to = McpFormat.normalizeNumber(args.optString("to", ""));
        String text = args.optString("text", "");
        if (!McpFormat.looksLikeNumber(to)) {
            return McpResult.error("INVALID_ARGUMENT", "收件人号码不合法: " + args.optString("to", ""));
        }
        if (text.trim().length() == 0) {
            return McpResult.error("INVALID_ARGUMENT", "短信正文为空");
        }

        int parts;
        try {
            SmsManager sm = SmsManager.getDefault();
            if (sm == null) return McpResult.error("NOT_AVAILABLE", "本机没有可用的 SmsManager");
            ArrayList<String> list = sm.divideMessage(text);
            parts = list == null ? 0 : list.size();
            if (parts <= 1) {
                sm.sendTextMessage(to, null, text, null, null);
            } else {
                sm.sendMultipartTextMessage(to, null, list, null, null);
            }
        } catch (SecurityException e) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        } catch (Throwable t) {
            return McpResult.error("INTERNAL", "发送失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }

        // 事后回读：sent 箱里 120s 内是否有发往该号码的记录（发了不等于发成功，必须读回来）
        boolean verified = false;
        long refId = -1L;
        String note = "";
        try {
            Thread.sleep(800L);
        } catch (InterruptedException ignored) { }
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() - 120000L;
            c = ctx.getContentResolver().query(
                    Telephony.Sms.Sent.CONTENT_URI,
                    new String[]{"_id", "address", "date"},
                    "date>=? AND address LIKE ?",
                    new String[]{String.valueOf(since), "%" + to + "%"},
                    "date DESC");
            if (c != null && c.moveToFirst()) {
                verified = true;
                refId = c.getLong(0);
            }
        } catch (SecurityException e) {
            note = "已提交发送，但无 READ_SMS 权限无法回读确认";
        } catch (Throwable t) {
            note = "已提交发送，回读异常: " + t.getClass().getSimpleName();
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("to", to);
            out.put("chars", text.length());
            out.put("parts", parts);
            out.put("verified", verified);
            if (verified) out.put("sent_id", refId);
            out.put("note", verified ? "已写入发件箱" : (note.length() > 0 ? note : "已提交，暂未在发件箱读到记录（可能延迟）"));
        } catch (Exception ignored) { }

        if (trace != null) {
            trace.proto("irreversible messages.send to=" + to + " chars=" + text.length()
                    + " verified=" + verified);
        }
        return McpResult.json(out);
    }
}
