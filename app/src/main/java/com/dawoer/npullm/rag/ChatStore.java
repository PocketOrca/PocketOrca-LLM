package com.dawoer.npullm.rag;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天记录持久化（chat.db，独立于 rag.db）。
 *
 * 只存「能当记忆用的」东西：user / assistant 的正文。thinking 段、工具结果、
 * 系统提示一律不落库——它们是噪音，进索引只会稀释召回质量。
 */
public final class ChatStore extends SQLiteOpenHelper {

    private static final String DB = "chat.db";
    private static final int VERSION = 1;
    /** 本地 chat 页目前是单会话；cid 预留多会话形态。 */
    public static final String MAIN_CID = "main";

    private static volatile ChatStore inst;

    public static synchronized ChatStore get(Context c) {
        if (inst == null) inst = new ChatStore(c.getApplicationContext());
        return inst;
    }

    private ChatStore(Context c) {
        super(c, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE msg("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "cid TEXT NOT NULL,"
                + "role TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_msg_cid ON msg(cid,id)");
        db.execSQL("CREATE INDEX idx_msg_ts ON msg(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS msg");
        onCreate(db);
    }

    /** 追加一条消息；同 (cid,role,content,ts) 已存在返回 -1（幂等，防重放）。 */
    public synchronized long append(String cid, String role, String content, long ts) {
        if (content == null || content.trim().length() == 0) return -1;
        String c = cid == null || cid.length() == 0 ? MAIN_CID : cid;
        String r = role == null ? "user" : role;
        SQLiteDatabase db = getWritableDatabase();
        Cursor cur = db.rawQuery(
                "SELECT id FROM msg WHERE cid=? AND role=? AND content=? AND ts=? LIMIT 1",
                new String[]{c, r, content, String.valueOf(ts)});
        try {
            if (cur.moveToFirst()) return -1;
        } finally {
            cur.close();
        }
        ContentValues cv = new ContentValues();
        cv.put("cid", c);
        cv.put("role", r);
        cv.put("content", content);
        cv.put("ts", ts);
        return db.insert("msg", null, cv);
    }

    /** 全部消息（按 id 升序），供窗口切分。 */
    public synchronized List<MemoryWindows.Turn> all() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = db.rawQuery("SELECT id,cid,role,content,ts FROM msg ORDER BY id", null);
        List<MemoryWindows.Turn> out = new ArrayList<MemoryWindows.Turn>();
        try {
            while (cur.moveToNext()) {
                out.add(new MemoryWindows.Turn(cur.getLong(0), cur.getString(1),
                        cur.getString(2), cur.getString(3), cur.getLong(4)));
            }
        } finally {
            cur.close();
        }
        return out;
    }

    /** 最近 limit 条（升序），供页面回填历史。 */
    public synchronized List<MemoryWindows.Turn> recent(String cid, int limit) {
        if (limit <= 0 || limit > 500) limit = 200;
        String c = cid == null || cid.length() == 0 ? MAIN_CID : cid;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = db.rawQuery(
                "SELECT id,cid,role,content,ts FROM (SELECT id,cid,role,content,ts FROM msg"
                        + " WHERE cid=? ORDER BY id DESC LIMIT ?) ORDER BY id",
                new String[]{c, String.valueOf(limit)});
        List<MemoryWindows.Turn> out = new ArrayList<MemoryWindows.Turn>();
        try {
            while (cur.moveToNext()) {
                out.add(new MemoryWindows.Turn(cur.getLong(0), cur.getString(1),
                        cur.getString(2), cur.getString(3), cur.getLong(4)));
            }
        } finally {
            cur.close();
        }
        return out;
    }

    /** 页面回填用 JSON：[{role,content,ts}] */
    public synchronized String historyJson(String cid, int limit) {
        JSONArray arr = new JSONArray();
        List<MemoryWindows.Turn> l = recent(cid, limit);
        for (int i = 0; i < l.size(); i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("role", l.get(i).role);
                o.put("content", l.get(i).content);
                o.put("ts", l.get(i).ts);
                arr.put(o);
            } catch (Throwable ignore) {}
        }
        return arr.toString();
    }

    public synchronized long count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = db.rawQuery("SELECT COUNT(*) FROM msg", null);
        try {
            return cur.moveToFirst() ? cur.getLong(0) : 0;
        } finally {
            cur.close();
        }
    }

    /** 清空聊天历史（不影响 RAG 库；库里的块由下次索引的 cleanup 收掉）。 */
    public synchronized int clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        int n = (int) count();
        db.delete("msg", null, null);
        return n;
    }
}
