package com.dawoer.npullm.mcp.access;

import android.app.Activity;
import android.content.Context;

import com.dawoer.npullm.mcp.shizuku.ShizukuShell;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 权限的「查 + 授」汇总（vc182）。只给主进程的 UI 桥用。
 *
 * 授权两条路，优先免弹窗：
 *   1) Shizuku 已就绪 → `pm grant`（shell uid 2000 执行，用户看不到任何弹窗）
 *   2) Shizuku 不可用 → 走系统 requestPermissions 弹窗（用户点一次）
 * 两条路都是**异步**的，UI 必须轮询 {@link #json(Context)} 拿结果，不能假设调用返回即可用。
 */
public final class PermSnapshot {

    /** requestPermissions 的 requestCode（与麦克风/通知的错开）。 */
    public static final int REQ_MCP_PERMS = 3110;

    /** NLS 组件名：必须与 manifest 里 android:name 拼法一致，写错 allow_listener 静默无效。 */
    private static final String NOTIF_COMPONENT =
            "com.dawoer.npullm/com.dawoer.npullm.mcp.access.NotificationCollector";

    private PermSnapshot() {}

    /** 四项权限 + Shizuku 状态。JSON 字段名与 /health 的 perms 一致，便于对照。 */
    public static String json(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("shizuku", ShizukuShell.state());
            boolean sms = Permissions.granted(ctx, Permissions.READ_SMS);
            boolean call = Permissions.granted(ctx, Permissions.READ_CALL_LOG);
            boolean contacts = Permissions.granted(ctx, Permissions.READ_CONTACTS);
            boolean send = Permissions.granted(ctx, Permissions.SEND_SMS);
            o.put("sms", sms);
            o.put("calllog", call);
            o.put("contacts", contacts);
            o.put("send_sms", send);
            o.put("all", sms && call && contacts && send);
            o.put("notif_listener", notifListenerOn(ctx));
        } catch (Throwable t) {
            try { o.put("ok", false); } catch (Throwable ignored) { }
        }
        return o.toString();
    }

    /** which = sms|calllog|contacts|send_sms|all。返回 {"ok":true,"via":"shizuku|dialog","n":N}。 */
    public static String grant(Activity act, String which) {
        JSONObject r = new JSONObject();
        if (act == null) {
            try { r.put("ok", false); r.put("err", "no-activity"); } catch (Throwable ignored) { }
            return r.toString();
        }
        // 通知使用权是「特殊权限」，不走 requestPermissions：有 Shizuku 就命令行加白名单（零点击），
        // 没有就跳系统设置页让用户手点。两种都返回 ok，UI 靠轮询 json() 看结果。
        if ("notif".equals(which)) {
            try {
                boolean viaShizuku = ShizukuShell.isReady()
                        && ShizukuShell.allowNotificationListener(NOTIF_COMPONENT);
                if (!viaShizuku) {
                    android.content.Intent it = new android.content.Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(it);
                }
                r.put("ok", true);
                r.put("via", viaShizuku ? "shizuku" : "settings");
                r.put("n", 1);
            } catch (Throwable t) {
                try { r.put("ok", false); r.put("err", t.getClass().getSimpleName()); } catch (Throwable ignored) { }
            }
            return r.toString();
        }
        List<String> perms = pick(which);
        boolean viaShizuku = ShizukuShell.isReady();
        try {
            if (viaShizuku) {
                // pm grant 逐条；每条都会 checkSelfPermission 读回（命令成功不等于生效）
                for (int i = 0; i < perms.size(); i++) {
                    ShizukuShell.grantRuntime(act, act.getPackageName(), perms.get(i));
                }
            } else if (!perms.isEmpty()) {
                act.requestPermissions(perms.toArray(new String[0]), REQ_MCP_PERMS);
            }
            r.put("ok", true);
            r.put("via", viaShizuku ? "shizuku" : "dialog");
            r.put("n", perms.size());
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("err", t.getClass().getSimpleName());
            } catch (Throwable ignored) { }
        }
        return r.toString();
    }

    /**
     * 通知使用权是否已授予：读 Settings.Secure 的 enabled_notification_listeners 扁平串。
     * 先按包名前缀匹配——服务端存的是「pkg/类名」形式，直接 contains(pkg+"/") 就够，
     * 不整串比对（换了 Service 类名不该判定失效）。
     */
    public static boolean notifListenerOn(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<String> pick(String which) {
        List<String> out = new ArrayList<>();
        if (which == null) which = "all";
        if ("all".equals(which) || "sms".equals(which)) out.add(Permissions.READ_SMS);
        if ("all".equals(which) || "calllog".equals(which)) out.add(Permissions.READ_CALL_LOG);
        if ("all".equals(which) || "contacts".equals(which)) out.add(Permissions.READ_CONTACTS);
        if ("all".equals(which) || "send_sms".equals(which)) out.add(Permissions.SEND_SMS);
        return out;
    }
}
