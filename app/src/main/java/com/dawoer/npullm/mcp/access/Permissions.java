package com.dawoer.npullm.mcp.access;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * S2 运行时权限闸门。工具可见性由它决定：没授权的工具**不出现在 tools/list**（不声明即不存在）。
 *
 * 铁律：每次 tools/list / tools/call 都重新查，不缓存 —— 用 `pm revoke` 撤权后工具必须立刻消失。
 * Android 10+ 的 READ_SMS 等属危险权限，checkSelfPermission 是唯一权威判据（Shizuku 的 pm grant
 * 也是改这个状态，所以两条授权路径共用同一判据）。
 */
public final class Permissions {

    public static final String READ_SMS = Manifest.permission.READ_SMS;
    public static final String READ_CALL_LOG = Manifest.permission.READ_CALL_LOG;
    public static final String READ_CONTACTS = Manifest.permission.READ_CONTACTS;
    public static final String SEND_SMS = Manifest.permission.SEND_SMS;
    public static final String POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS;

    private Permissions() {}

    public static boolean granted(Context ctx, String perm) {
        if (ctx == null || perm == null) return false;
        try {
            return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 权限缺失时给模型的固定提示（模型据此告诉用户去开通，不猜、不重试）。 */
    public static String denyHint(String perm) {
        String label;
        if (READ_SMS.equals(perm)) label = "读取短信";
        else if (READ_CALL_LOG.equals(perm)) label = "读取通话记录";
        else if (READ_CONTACTS.equals(perm)) label = "读取联系人";
        else if (SEND_SMS.equals(perm)) label = "发送短信";
        else if (POST_NOTIFICATIONS.equals(perm)) label = "通知";
        else label = perm;
        return label + "权限未授予：请在 PocketOrca-LLM 的「MCP 服务」卡片里开通（或由 Shizuku 自动授予）";
    }
}
