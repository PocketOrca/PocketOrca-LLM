package com.dawoer.npullm.mcp.access;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.dawoer.npullm.mcp.outbox.OutboxIngest;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知采集器：跑在 :mcp 进程（manifest 里 android:process=":mcp"），零 IPC。
 *
 * 只保留"服务连接之后"的通知（系统限制，历史通知拿不到）；环形缓存 200 条，纯内存不落盘。
 * 脱敏不在采集时做，而在读取时按 include_sensitive 决定——这样"默认脱敏、显式索取"才有意义。
 */
public final class NotificationCollector extends NotificationListenerService {

    private static final String TAG = "PoMcpNotif";
    public static final int RING_MAX = 200;

    private static final ArrayDeque<JSONObject> RING = new ArrayDeque<>();
    private static volatile boolean connected = false;

    @Override
    public void onListenerConnected() {
        connected = true;
        Log.i(TAG, "onListenerConnected");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        Log.i(TAG, "onListenerDisconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg != null && pkg.equals(getPackageName())) return;   // 自己的通知不采

        try {
            JSONObject o = new JSONObject();
            o.put("key", sbn.getKey());
            o.put("app", pkg);
            o.put("app_name", appLabel(pkg));
            o.put("time", sbn.getPostTime());
            o.put("ongoing", sbn.isOngoing());
            String cat = sbn.getNotification() != null ? sbn.getNotification().category : null;
            o.put("category", cat == null ? JSONObject.NULL : cat);

            Notification n = sbn.getNotification();
            Bundle ex = n != null ? n.extras : null;
            if (ex != null) {
                o.put("title", str(ex.getCharSequence(Notification.EXTRA_TITLE)));
                o.put("text", str(ex.getCharSequence(Notification.EXTRA_TEXT)));
                CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (big != null) o.put("big_text", str(big));
            }
            // 分组摘要/空壳通知（title+text+big_text 全空）对 agent 零信息，灌进缓存只会
            // 挤掉真通知：S25 实测 shell 聚合摘要就是 5 条里占 1 条。
            if (o.optString("title", "").length() == 0
                    && o.optString("text", "").length() == 0
                    && o.optString("big_text", "").length() == 0) return;
            push(o);

            // vc185：同一条通知顺手进 outbox（推是触发，拉是事实源）。
            // 回调路径不做网络、不做语义判断，所有规则闸都在 OutboxIngest 里，异常一律吞。
            try {
                JSONObject pl = new JSONObject();
                pl.put("app", pkg);
                pl.put("key", sbn.getKey());
                OutboxIngest.offer(this, "notification", "notif", pkg,
                        o.optString("app_name", pkg),
                        o.optString("title", ""), o.optString("text", ""), pl);
            } catch (Throwable ignored) { }
        } catch (Exception e) {
            Log.w(TAG, "collect failed: " + e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 保留历史：移除不删缓存（agent 需要"刚才来过什么"）
    }

    private static void push(JSONObject o) {
        synchronized (RING) {
            RING.addFirst(o);
            while (RING.size() > RING_MAX) RING.removeLast();
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(Object cs) {
        return cs == null ? "" : String.valueOf(cs);
    }

    // ---------------- 对外只读接口（供 tools 层调用）----------------

    public static boolean isConnected() {
        return connected;
    }

    public static int size() {
        synchronized (RING) { return RING.size(); }
    }

    /** 按时间倒序返回快照（未脱敏，脱敏由调用方按 include_sensitive 决定）。 */
    public static List<JSONObject> snapshot(int limit, long sinceMs, String pkgFilter) {
        List<JSONObject> out = new ArrayList<>();
        synchronized (RING) {
            for (JSONObject o : RING) {
                if (out.size() >= limit) break;
                long t = o.optLong("time", 0L);
                if (sinceMs > 0 && t < sinceMs) continue;
                if (pkgFilter != null && pkgFilter.length() > 0
                        && !pkgFilter.equals(o.optString("app", ""))) continue;
                out.add(o);
            }
        }
        return out;
    }
}
