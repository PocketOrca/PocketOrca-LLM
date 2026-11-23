package com.dawoer.npullm.mcp.outbox;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 投递 worker（vc185）：跑在 :mcp 进程的 FGS 里，把 outbox 表里的 pending 推给 agent。
 *
 * 通路 = POST {endpoint}/v1/runs（Hermes api_server），body {input, idempotency_key}，
 * agent 收下后反调 :8086 的 MCP 工具做后续动作（tts.speak / messages.send …）。
 * 零新协议：不碰 MCP 的 subscriptions/listen（那要求响应是流 + 客户端长连，我们两条都不做）。
 *
 * 四道闸就在这里落地：聚合窗口 / 每小时唤醒预算 / 勿扰时段 / dedupe（dedupe 在入队侧）。
 * 每次唤醒 = 一次完整 agent run，这里省下的每一条都是一笔真金白银。
 */
public final class OutboxWorker {

    private static final String TAG = "PoMcpOutbox";
    private static final long IDLE_MS = 15000L;      // 无事件时 15s 轮一次（Doze 下会被系统再拉长，可接受）
    private static final long KICK_MS = 800L;        // P0 踢醒后稍等，让同批事件落库

    private static volatile boolean running = false;
    private static volatile boolean kick = false;
    private static Thread thread;
    private static Context appCtx;
    private static final Object LOCK = new Object();

    private static volatile long lastOkTs = 0L;
    private static volatile String lastErr = "";
    private static volatile long lastWakeTs = 0L;
    private static volatile int lastWakeCount = 0;
    /* vc187 诊断：worker 心跳 + 早退原因 + 进程号。据此区分
       「线程没跑」/「跑了但被 deliverable、聚合窗口、静默、预算挡住」。 */
    private static volatile long lastTickTs = 0L;
    private static volatile String lastSkip = "";
    private static volatile int tickPid = 0;

    private OutboxWorker() {}

    public static synchronized void start(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        if (running) return;
        running = true;
        thread = new Thread(new Loop(), "mcp-outbox");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "worker started");
    }

    public static synchronized void stop() {
        running = false;
        kick = true;
        synchronized (LOCK) { LOCK.notifyAll(); }
        thread = null;
        Log.i(TAG, "worker stopped");
    }

    public static boolean isRunning() {
        return running;
    }

    /** P0 事件（来电等）立即踢醒，不等轮询周期。 */
    public static void wakeNow() {
        kick = true;
        synchronized (LOCK) { LOCK.notifyAll(); }
    }

    /** 命名静态嵌套（d8 零匿名类铁律）。 */
    private static final class Loop implements Runnable {
        @Override
        public void run() {
            while (running) {
                long wait = IDLE_MS;
                try {
                    if (kick) {
                        kick = false;
                        wait = KICK_MS;
                    }
                    int delivered = tick();
                    lastTickTs = System.currentTimeMillis();
                    if (tickPid == 0) tickPid = android.os.Process.myPid();
                    persistState();
                    if (delivered > 0 && kick) wait = KICK_MS;
                } catch (Throwable t) {
                    lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Log.w(TAG, "tick failed: " + lastErr);
                }
                synchronized (LOCK) {
                    if (!kick && running) {
                        try { LOCK.wait(wait); } catch (InterruptedException ignored) { }
                    }
                }
            }
        }
    }

    /** 一次投递尝试。返回本次投递成功的批次数（0/1）。 */
    private static int tick() {
        if (appCtx == null) { lastSkip = "no_ctx"; return 0; }
        if (!OutboxConfig.deliverable(appCtx)) { lastSkip = "not_deliverable"; return 0; }
        long now = System.currentTimeMillis();
        List<JSONObject> due = OutboxStore.due(appCtx, now, 30);
        if (due.isEmpty()) {
            lastSkip = "due_empty";
            OutboxStore.pruneDelivered(appCtx, now);
            return 0;
        }

        int maxWake = OutboxConfig.maxWakeups(appCtx);
        long windowMs = OutboxConfig.windowMs(appCtx);
        List<JSONObject> batch = pickBatch(due, windowMs);
        if (batch.isEmpty()) { lastSkip = "batch_empty"; return 0; }

        int prio = 9;
        long firstTs = Long.MAX_VALUE;
        for (JSONObject e : batch) {
            prio = Math.min(prio, e.optInt("priority", 2));
            firstTs = Math.min(firstTs, e.optLong("ts", now));
        }
        boolean quiet = McpOutboxLogic.inQuiet(minuteNow(now), OutboxConfig.quietHours(appCtx));
        if (quiet && prio != McpOutboxLogic.P0) { lastSkip = "quiet"; return 0; }   // 静音时段只入队
        if (!McpOutboxLogic.allowWake(OutboxStore.wakeStamps(appCtx, now), now, maxWake)) {
            lastSkip = "budget";
            return 0;                                        // 每小时预算用尽：留下，靠 events.poll 补
        }

        JSONArray arr = new JSONArray();
        StringBuilder ids = new StringBuilder();
        for (JSONObject e : batch) {
            arr.put(e);
            ids.append(e.optString("event_id", "")).append('|');
        }
        String idem = McpOutboxLogic.sha16(ids.toString());
        String input = McpOutboxLogic.renderInput(arr);

        lastWakeTs = now;
        lastWakeCount = batch.size();
        String[] res = post(input, idem);
        int code = res[0] == null ? 0 : parseInt(res[0], 0);

        if (code >= 200 && code < 300) {
            String runId = res[2];
            for (JSONObject e : batch) {
                OutboxStore.markDelivered(appCtx, e.optString("event_id", ""), runId, now);
            }
            OutboxStore.addWake(appCtx, now);   // vc189: 一批 = 一次唤醒
            lastOkTs = now;
            lastErr = "";
            persistState();
            Log.i(TAG, "delivered " + batch.size() + " events run=" + runId + " seq0=" + batch.get(0).optLong("seq", 0));
            OutboxStore.pruneDelivered(appCtx, now);
            return 1;
        }

        lastErr = "HTTP " + code + (res[1] == null || res[1].length() == 0 ? "" : " " + res[1]);
        OutboxStore.noteError(lastErr);
        persistState();
        for (JSONObject e : batch) {
            int attempts = e.optInt("attempts", 0) + 1;
            if (McpOutboxLogic.exhausted(attempts)) {
                OutboxStore.markFailed(appCtx, e.optString("event_id", ""), attempts);
            } else {
                OutboxStore.markRetry(appCtx, e.optString("event_id", ""), attempts,
                        McpOutboxLogic.nextRetry(now, attempts));
            }
        }
        Log.w(TAG, "deliver failed: " + lastErr);
        return 0;
    }

    /** 一批要投的事件：有 P0 就只投它；否则按聚合窗口收拢 P1。 */
    private static List<JSONObject> pickBatch(List<JSONObject> due, long windowMs) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject p0 = null;
        long p0Ts = Long.MAX_VALUE;
        for (JSONObject e : due) {
            if (e.optInt("priority", 2) == McpOutboxLogic.P0) {
                long ts = e.optLong("ts", 0L);
                if (ts < p0Ts) { p0Ts = ts; p0 = e; }
            }
        }
        if (p0 != null) {
            out.add(p0);
            return out;
        }
        long first = -1L;
        for (JSONObject e : due) {
            if (e.optInt("priority", 2) > McpOutboxLogic.P1) continue;   // P2 只入队
            long ts = e.optLong("ts", 0L);
            if (out.isEmpty()) { first = ts; out.add(e); continue; }
            if (McpOutboxLogic.sameBatch(first, ts, windowMs)) out.add(e);
            else break;
        }
        return out;
    }

    /**
     * POST /v1/runs。返回 {code, errBody, runId}。
     * 宁可失败也不要静默丢：非 2xx 一律进退避，5 次后 failed（status 里能看到）。
     */
    private static String[] post(String input, String idem) {
        String[] out = new String[]{"0", "", ""};
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("input", input);
            body.put("idempotency_key", idem);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            URL u = new URL(McpOutboxLogic.runUrl(OutboxConfig.endpoint(appCtx)));
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            String key = OutboxConfig.key(appCtx);
            if (key != null && key.length() > 0) c.setRequestProperty("Authorization", "Bearer " + key);
            // vc189: 幂等键必须走 HTTP header —— api_server 只认 header，放 body 的
            // idempotency_key 被忽略（重试就会重复唤醒 agent）。body 里保留一份仅为自述。
            if (idem != null && idem.length() > 0) c.setRequestProperty("Idempotency-Key", idem);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            out[0] = String.valueOf(c.getResponseCode());
            InputStream is = out[0].startsWith("2") ? c.getInputStream() : c.getErrorStream();
            String text = read(is);
            if (out[0].startsWith("2")) {
                try {
                    out[2] = new JSONObject(text).optString("run_id", "");
                } catch (Throwable ignored) { }
            } else {
                out[1] = McpOutboxLogic.excerpt(text, 120);
            }
        } catch (Throwable t) {
            out[0] = "0";
            out[1] = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static String read(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    private static int minuteNow(long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
    }

    /** 把最近结果写到文件：UI 在主进程，读不到本进程的静态变量。 */
    private static void persistState() {
        if (appCtx == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("last_ok_ts", lastOkTs);
            o.put("last_wake_ts", lastWakeTs);
            o.put("last_wake_count", lastWakeCount);
            o.put("last_error", lastErr == null ? "" : lastErr);
            o.put("last_tick_ts", lastTickTs);
            o.put("last_skip", lastSkip == null ? "" : lastSkip);
            o.put("tick_pid", tickPid);
            OutboxConfig.writeState(appCtx, o);
        } catch (Throwable ignored) { }
    }

    /** UI 状态（同步、轻量）。 */
    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONObject cfg = OutboxConfig.get(ctx);
            long now = System.currentTimeMillis();
            JSONObject st0 = OutboxConfig.runtimeState(ctx);
            // vc187: "运行中" 必须有 worker 心跳背书，否则标记文件陈旧会让 UI 说谎。
            long tickTs0 = st0.optLong("last_tick_ts", 0L);
            boolean markerOn = OutboxConfig.isRunning(ctx);
            o.put("marker", markerOn);
            o.put("running", markerOn && tickTs0 > 0L && (now - tickTs0) < 90000L);
            o.put("worker_alive", running);
            o.put("enabled", McpOutboxLogic.cfgBool(cfg, "enabled", false));
            o.put("endpoint", McpOutboxLogic.cfg(cfg, "endpoint", ""));
            o.put("whitelist", McpOutboxLogic.cfg(cfg, "whitelist", ""));
            // vc189：唤醒 Key 是否已配置。只回后 4 位做指纹，原文永不出 Java 侧。
            String k0 = OutboxConfig.key(ctx);
            boolean keyOn = k0 != null && k0.length() > 0;
            o.put("key_on", keyOn);
            o.put("key_fp", keyOn ? k0.substring(Math.max(0, k0.length() - 4)) : "");
            o.put("deliverable", OutboxConfig.deliverable(ctx));
            // vc187: 待投递事件里「已到期」的条数。due>0 而已投长期为 0 -> worker 没在干活；
            // due==0 而 pending>0 -> 聚合窗口/静默在排队。两种卡点靠这一个数字区分。
            o.put("due", OutboxStore.due(ctx, now, 999).size());
            o.put("quiet_now", McpOutboxLogic.inQuiet(minuteNow(now), McpOutboxLogic.cfg(cfg, "quiet_hours", "")));
            o.put("wakes_last_hour", McpOutboxLogic.pruneStamps(OutboxStore.wakeStamps(ctx, now), now).length);
            o.put("max_wakeups_per_hour", McpOutboxLogic.cfgInt(cfg, "max_wakeups_per_hour", McpOutboxLogic.MAX_WAKEUPS_PER_HOUR));
            long okTs = st0.optLong("last_ok_ts", lastOkTs);
            long wakeTs = st0.optLong("last_wake_ts", lastWakeTs);
            int wakeCnt = st0.optInt("last_wake_count", lastWakeCount);
            String err = st0.optString("last_error", lastErr);
            o.put("last_ok_ts", okTs);
            o.put("last_wake_ts", wakeTs);
            o.put("last_wake_count", wakeCnt);
            o.put("last_error", err);
            o.put("last_tick_ts", st0.optLong("last_tick_ts", lastTickTs));
            o.put("last_skip", st0.optString("last_skip", lastSkip));
            o.put("tick_pid", st0.optInt("tick_pid", tickPid));
            JSONObject st = OutboxStore.stats(ctx);
            java.util.Iterator<String> it = st.keys();
            while (it.hasNext()) {
                String k = it.next();
                o.put("ev_" + k, st.opt(k));
            }
        } catch (Throwable ignored) { }
        return o;
    }
}
