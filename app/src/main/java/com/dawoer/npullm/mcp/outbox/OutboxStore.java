package com.dawoer.npullm.mcp.outbox;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * outbox 事件表（vc185）：SQLite，落 files 目录，:mcp 进程独占写。
 *
 * 为什么用表而不是内存队列：断网/Doze/进程被杀之后必须能补投，且 events.poll 是
 * 「推是触发、拉是事实源」里的事实源——内存队列一死事实就没了。
 */
public final class OutboxStore {

    private static final String DB = "mcp_outbox.db";
    private static final int VERSION = 1;

    public static final String ST_PENDING = "pending";
    public static final String ST_INFLIGHT = "inflight";
    public static final String ST_DELIVERED = "delivered";
    public static final String ST_FAILED = "failed";
    public static final String ST_DROPPED = "dropped";

    private static Helper helper;
    private static SQLiteDatabase db;

    private OutboxStore() {}

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) {
            super(c.getApplicationContext(), DB, null, VERSION);
            // vc185: UI 在主进程读统计、投递在 :mcp 进程写——WAL 让读不被写锁卡住
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE event(" +
                    "event_id TEXT PRIMARY KEY," +
                    "seq INTEGER NOT NULL," +
                    "ts INTEGER NOT NULL," +
                    "source TEXT, kind TEXT, priority INTEGER," +
                    "title TEXT, body_excerpt TEXT, sensitive INTEGER, payload TEXT," +
                    "state TEXT NOT NULL, attempts INTEGER DEFAULT 0," +
                    "next_retry INTEGER DEFAULT 0, run_id TEXT," +
                    "dedupe_key TEXT, delivered_ts INTEGER DEFAULT 0)");
            d.execSQL("CREATE INDEX idx_state ON event(state, next_retry)");
            d.execSQL("CREATE UNIQUE INDEX idx_seq ON event(seq)");
            d.execSQL("CREATE INDEX idx_dedupe ON event(dedupe_key, ts)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
            // v1 首版：以后加列写 ALTER TABLE，不 drop（丢了就等于丢事件）
        }
    }

    private static synchronized SQLiteDatabase db(Context ctx) {
        if (db != null && db.isOpen()) return db;
        if (helper == null) helper = new Helper(ctx);
        db = helper.getWritableDatabase();
        return db;
    }

    /** 入队：event_id 已存在即忽略（幂等）。返回分配的 seq，重复返回 -1。 */
    public static synchronized long insert(Context ctx, JSONObject e) {
        if (ctx == null || e == null) return -1;
        SQLiteDatabase d = db(ctx);
        String id = e.optString("event_id", "");
        if (id.length() == 0) return -1;
        try {
            if (exists(d, id)) return -1;
            long seq = e.optLong("seq", 0);
            if (seq <= 0) seq = maxSeq(d) + 1;
            d.execSQL("INSERT INTO event(event_id,seq,ts,source,kind,priority,title,body_excerpt,sensitive,payload,state,attempts,next_retry,run_id,dedupe_key,delivered_ts)" +
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    new Object[]{id, seq, e.optLong("ts", System.currentTimeMillis()),
                            e.optString("source", ""), e.optString("kind", ""), e.optInt("priority", 2),
                            e.optString("title", ""), e.optString("body_excerpt", ""),
                            e.optBoolean("sensitive", false) ? 1 : 0, e.optString("payload", ""),
                            ST_PENDING, 0, 0L, null, e.optString("dedupe_key", "")});
            // 单表上限：超 2000 删最旧的 pending（记 dropped 计数靠调用方日志）
            int over = McpOutboxLogic.dropCount(countPendingDb(d), McpOutboxLogic.MAX_PENDING);
            if (over > 0) {
                d.execSQL("DELETE FROM event WHERE state=? AND seq IN (SELECT seq FROM event WHERE state=? ORDER BY seq LIMIT ?)",
                        new Object[]{ST_PENDING, ST_PENDING, over});
            }
            return seq;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 轮询游标（events.poll）：按 seq 升序返回 since_seq 之后的所有状态事件。 */
    public static synchronized JSONArray poll(Context ctx, long sinceSeq, int limit) {
        JSONArray arr = new JSONArray();
        if (ctx == null) return arr;
        Cursor c = null;
        try {
            c = db(ctx).rawQuery("SELECT * FROM event WHERE seq>? ORDER BY seq ASC LIMIT ?",
                    new String[]{String.valueOf(sinceSeq), String.valueOf(limit)});
            while (c.moveToNext()) arr.put(row(c));
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
        return arr;
    }

    /** 到期待投递。 */
    public static synchronized List<JSONObject> due(Context ctx, long now, int limit) {
        List<JSONObject> out = new ArrayList<>();
        if (ctx == null) return out;
        Cursor c = null;
        try {
            c = db(ctx).rawQuery("SELECT * FROM event WHERE state=? AND next_retry<=? ORDER BY seq ASC LIMIT ?",
                    new String[]{ST_PENDING, String.valueOf(now), String.valueOf(limit)});
            while (c.moveToNext()) out.add(row(c));
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static synchronized void markDelivered(Context ctx, String eventId, String runId, long now) {
        exec(ctx, "UPDATE event SET state=?, run_id=?, delivered_ts=?, next_retry=0 WHERE event_id=?",
                new Object[]{ST_DELIVERED, runId == null ? "" : runId, now, eventId});
    }

    public static synchronized void markRetry(Context ctx, String eventId, int attempts, long nextRetry) {
        exec(ctx, "UPDATE event SET state=?, attempts=?, next_retry=? WHERE event_id=?",
                new Object[]{ST_PENDING, attempts, nextRetry, eventId});
    }

    public static synchronized void markFailed(Context ctx, String eventId, int attempts) {
        exec(ctx, "UPDATE event SET state=?, attempts=? WHERE event_id=?",
                new Object[]{ST_FAILED, attempts, eventId});
    }

    /** 30s 内同 dedupe_key 合并（不新增事件）。 */
    public static synchronized boolean recentDuplicate(Context ctx, String dedupeKey, long now, long windowMs) {
        if (ctx == null || dedupeKey == null || dedupeKey.length() == 0) return false;
        Cursor c = null;
        try {
            c = db(ctx).rawQuery("SELECT 1 FROM event WHERE dedupe_key=? AND ts>=? LIMIT 1",
                    new String[]{dedupeKey, String.valueOf(now - windowMs)});
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    /** 最近 1 小时内投递成功的时刻（唤醒预算）。 */
    public static synchronized long[] deliveredStamps(Context ctx, long now) {
        List<Long> l = new ArrayList<>();
        Cursor c = null;
        try {
            c = db(ctx).rawQuery("SELECT delivered_ts FROM event WHERE state=? AND delivered_ts>?",
                    new String[]{ST_DELIVERED, String.valueOf(now - 3600000L)});
            while (c.moveToNext()) l.add(c.getLong(0));
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
        long[] out = new long[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    // ===== vc189：每小时唤醒预算按「唤醒」计数，不按事件计数 =====
    // 旧实现拿 deliveredStamps（每个成功投递的**事件**）当预算依据：一批 3 条事件
    // 只算 1 次唤醒，却被记成 3 次；反过来限额被高估，聚合窗口一关就会打爆通知风暴。
    private static final String WAKE_FILE = "mcp_outbox.wakes";

    /** 记一次成功唤醒（同一批多个事件只记一条）。 */
    public static synchronized void addWake(Context ctx, long ts) {
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), WAKE_FILE);
            java.util.List<String> keep = new java.util.ArrayList<String>();
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0) continue;
                    try {
                        if (Long.parseLong(line) > ts - 3600000L) keep.add(line);
                    } catch (Throwable ignored) { }
                }
                r.close();
            }
            keep.add(String.valueOf(ts));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep.size(); i++) sb.append(keep.get(i)).append('\n');
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, false);
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
        } catch (Throwable ignored) { }
    }

    /** 最近 1 小时内的唤醒时刻（预算与 UI 的唯一判据）。 */
    public static synchronized long[] wakeStamps(Context ctx, long now) {
        java.util.List<Long> l = new java.util.ArrayList<Long>();
        if (ctx != null) {
            try {
                java.io.File f = new java.io.File(ctx.getFilesDir(), WAKE_FILE);
                if (f.exists()) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() == 0) continue;
                        try {
                            long t = Long.parseLong(line);
                            if (t > now - 3600000L) l.add(t);
                        } catch (Throwable ignored) { }
                    }
                    r.close();
                }
            } catch (Throwable ignored) { }
        }
        long[] out = new long[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    public static synchronized int countPending(Context ctx) {
        return count(ctx, "state=?", new String[]{ST_PENDING});
    }

    public static synchronized int count(Context ctx, String where, String[] args) {
        if (ctx == null) return 0;
        Cursor c = null;
        try {
            c = db(ctx).rawQuery("SELECT COUNT(*) FROM event WHERE " + where, args);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    /** delivered 保留 7 天。 */
    public static synchronized void pruneDelivered(Context ctx, long now) {
        long cut = now - McpOutboxLogic.KEEP_DELIVERED_DAYS * 86400000L;
        exec(ctx, "DELETE FROM event WHERE state=? AND delivered_ts>0 AND delivered_ts<?",
                new Object[]{ST_DELIVERED, cut});
    }

    /** UI/工具用的汇总。 */
    public static synchronized JSONObject stats(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("pending", count(ctx, "state=?", new String[]{ST_PENDING}));
            o.put("delivered", count(ctx, "state=?", new String[]{ST_DELIVERED}));
            o.put("failed", count(ctx, "state=?", new String[]{ST_FAILED}));
            o.put("total", count(ctx, "1=1", new String[0]));
            o.put("last_error", lastError(ctx));
        } catch (Throwable ignored) { }
        return o;
    }

    private static String lastError = "";

    public static void noteError(String msg) {
        lastError = msg == null ? "" : msg;
    }

    private static String lastError(Context ctx) {
        return lastError;
    }

    private static void exec(Context ctx, String sql, Object[] args) {
        if (ctx == null) return;
        try {
            db(ctx).execSQL(sql, args);
        } catch (Throwable ignored) { }
    }

    private static boolean exists(SQLiteDatabase d, String id) {
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT 1 FROM event WHERE event_id=? LIMIT 1", new String[]{id});
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    /** 入队时用：直接拿 db 句柄统计 pending（不加锁，调用点已在 synchronized 内）。 */
    private static int countPendingDb(SQLiteDatabase d) {
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT COUNT(*) FROM event WHERE state=?", new String[]{ST_PENDING});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    private static long maxSeq(SQLiteDatabase d) {
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT IFNULL(MAX(seq),0) FROM event", new String[0]);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    private static JSONObject row(Cursor c) {
        JSONObject o = new JSONObject();
        try {
            o.put("event_id", c.getString(c.getColumnIndexOrThrow("event_id")));
            o.put("seq", c.getLong(c.getColumnIndexOrThrow("seq")));
            o.put("ts", c.getLong(c.getColumnIndexOrThrow("ts")));
            o.put("source", str(c, "source"));
            o.put("kind", str(c, "kind"));
            o.put("priority", c.getInt(c.getColumnIndexOrThrow("priority")));
            o.put("title", str(c, "title"));
            o.put("body_excerpt", str(c, "body_excerpt"));
            o.put("sensitive", c.getInt(c.getColumnIndexOrThrow("sensitive")) == 1);
            o.put("payload", str(c, "payload"));
            o.put("state", str(c, "state"));
            o.put("attempts", c.getInt(c.getColumnIndexOrThrow("attempts")));
            o.put("next_retry", c.getLong(c.getColumnIndexOrThrow("next_retry")));
            o.put("run_id", str(c, "run_id"));
            o.put("delivered_ts", c.getLong(c.getColumnIndexOrThrow("delivered_ts")));
        } catch (Throwable ignored) { }
        return o;
    }

    private static String str(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }
}
