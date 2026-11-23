package com.dawoer.npullm.mcp.outbox;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

/**
 * 采集 → 规则闸 → 入队（vc185）。从 NotificationCollector 的回调里调用。
 *
 * 三条纪律：
 * 1. 回调路径**不做网络、不做语义判断**（<50ms 返回，慢了会拖累系统通知分发）。
 * 2. 一切出设备的文本先脱敏（redactOut），白名单外只留「X 应用有 1 条通知」。
 * 3. 任何异常吞掉并记日志：采集失败绝不能影响通知本身。
 */
public final class OutboxIngest {

    private static final String TAG = "PoMcpOutbox";

    private OutboxIngest() {}

    /**
     * @return 入队的 seq，未入队（关闭/去重/异常）返回 -1
     */
    public static long offer(Context ctx, String source, String kind,
                             String app, String appLabel, String title, String text,
                             JSONObject payload) {
        if (ctx == null) return -1L;
        try {
            JSONObject cfg = OutboxConfig.get(ctx);
            if (!McpOutboxLogic.cfgBool(cfg, "enabled", false)) return -1L;

            long now = System.currentTimeMillis();
            boolean ws = McpOutboxLogic.whitelisted(app, McpOutboxLogic.cfg(cfg, "whitelist", ""));
            int priority = McpOutboxLogic.priorityFor(source, ws);

            // 勿扰时段：只入队不唤醒（P0 除外）→ 落到 P2
            int minute = minuteNow(now);
            if (priority != McpOutboxLogic.P0
                    && McpOutboxLogic.inQuiet(minute, McpOutboxLogic.cfg(cfg, "quiet_hours", "23:00-07:30"))) {
                priority = McpOutboxLogic.P2;
            }

            String dk = McpOutboxLogic.dedupeKey(source, app, title);
            if (OutboxStore.recentDuplicate(ctx, dk, now, McpOutboxLogic.DEDUPE_WINDOW_MS)) {
                return -1L;   // 30s 内同源同标题已入队：合并成一次，不再显式新增
            }

            String body = McpOutboxLogic.bodyFor(ws, appLabel, title, text);
            JSONObject e = new JSONObject();
            e.put("ts", now);
            e.put("source", source);
            e.put("kind", kind);
            e.put("priority", priority);
            e.put("dedupe_key", dk);
            e.put("title", McpOutboxLogic.excerpt(McpOutboxLogic.redactOut(
                    ws ? nz(appLabel) : nz(appLabel)), 60));
            e.put("body_excerpt", McpOutboxLogic.excerpt(body, McpOutboxLogic.EXCERPT_MAX));
            e.put("sensitive", McpOutboxLogic.hasSensitive(nz(title) + " " + nz(text)));
            e.put("payload", payload == null ? "{}" : payload.toString());
            e.put("event_id", McpOutboxLogic.eventId(source, dk, now, McpOutboxLogic.sha16(body)));

            long seq = OutboxStore.insert(ctx, e);
            if (seq > 0 && priority == McpOutboxLogic.P0) OutboxWorker.wakeNow();
            if (seq > 0) Log.i(TAG, "queued seq=" + seq + " p=" + priority + " ws=" + ws);
            return seq;
        } catch (Throwable t) {
            Log.w(TAG, "offer failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return -1L;
        }
    }

    /**
     * 自检推送（UI 上的「发测试推送」）：绕过白名单/勿扰/预算，走同一张表、同一条投递通路。
     * 存在的理由：用户配完端点必须能一键验「到底通没通」，否则只能靠碰运气等真通知。
     */
    public static long insertTest(Context ctx) {
        if (ctx == null) return -1L;
        try {
            long now = System.currentTimeMillis();
            String hhmm = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(now));
            String body = "测试事件：手机 → agent 主动推送链路自检 " + hhmm;
            JSONObject e = new JSONObject();
            e.put("ts", now);
            e.put("source", "test");
            e.put("kind", "test");
            e.put("priority", McpOutboxLogic.P1);
            e.put("dedupe_key", "selftest");
            e.put("title", "主动推送自检");
            e.put("body_excerpt", McpOutboxLogic.excerpt(body, McpOutboxLogic.EXCERPT_MAX));
            e.put("sensitive", false);
            e.put("payload", "{\"selftest\":true}");
            e.put("event_id", McpOutboxLogic.eventId("test", "selftest", now, McpOutboxLogic.sha16(body)));
            long seq = OutboxStore.insert(ctx, e);
            if (seq > 0) OutboxWorker.wakeNow();
            return seq;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static int minuteNow(long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
