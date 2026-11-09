package com.dawoer.npullm.rag;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量库（SQLite 单文件，app 私有目录）。
 *
 * 为什么不是 sqlite-vec：Android 的 SQLiteDatabase 没有公开的
 * loadExtension()（sqlite-vec 需要 .so 扩展 + enable_load_extension，要么
 * 自带一份 NDK 编的 sqlite3，要么改系统库 —— 两者都给这份 APK 增加一条
 * native 依赖链和一类崩溃面）。个人知识库的量级（万级块、512 维）用 Java
 * 暴力余弦是 O(n) 几十毫秒，且纯 Java 可测、零 native 风险。本类因此只在
 * 存储层用 SQLite，检索在 RagVectors 里做。
 *
 * 表：
 *   lib    — 一个被索引的目录（SAF tree uri 唯一）
 *   doc    — 库内一个文件（relpath 唯一）+ 指纹（mtime/size/sha256）
 *   chunk  — 分块文本 + 向量 blob + 维度
 */
final class RagStore extends SQLiteOpenHelper {

    private static final String DB = "rag.db";
    private static final int VERSION = 1;

    /** 内存缓存：一次检索要读全部向量，重建时机=任何写入。 */
    private static final class Cache {
        final long libId;
        final List<Row> rows;
        Cache(long libId, List<Row> rows) { this.libId = libId; this.rows = rows; }
    }

    static final class Row {
        final long chunkId;
        final String relpath;
        final String title;
        final String text;
        final float[] vec;
        /** 块的来源时间戳（doc.mtime）；聊天记忆用它做时间衰减。 */
        final long ts;
        Row(long chunkId, String relpath, String title, String text, float[] vec) {
            this(chunkId, relpath, title, text, vec, 0L);
        }
        Row(long chunkId, String relpath, String title, String text, float[] vec, long ts) {
            this.chunkId = chunkId;
            this.relpath = relpath;
            this.title = title;
            this.text = text;
            this.vec = vec;
            this.ts = ts;
        }
    }

    private volatile Cache cache;

    RagStore(Context c) {
        super(c.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE lib("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "dir_uri TEXT UNIQUE NOT NULL,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "files INTEGER NOT NULL DEFAULT 0,"
                + "chunks INTEGER NOT NULL DEFAULT 0,"
                + "indexed_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE doc("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "lib_id INTEGER NOT NULL,"
                + "relpath TEXT NOT NULL,"
                + "mtime INTEGER NOT NULL DEFAULT 0,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "hash TEXT NOT NULL DEFAULT '',"
                + "chunks INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(lib_id, relpath))");
        db.execSQL("CREATE TABLE chunk("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "lib_id INTEGER NOT NULL,"
                + "doc_id INTEGER NOT NULL,"
                + "seq INTEGER NOT NULL DEFAULT 0,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "body TEXT NOT NULL DEFAULT '',"
                + "vec BLOB,"
                + "dim INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_doc_lib ON doc(lib_id)");
        db.execSQL("CREATE INDEX idx_chunk_lib ON chunk(lib_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 是首版；将来升版走「删库重建」的一致性语义（索引可重建）
        db.execSQL("DROP TABLE IF EXISTS chunk");
        db.execSQL("DROP TABLE IF EXISTS doc");
        db.execSQL("DROP TABLE IF EXISTS lib");
        onCreate(db);
    }

    // ------------------------------------------------------------------ lib

    synchronized long ensureLib(String dirUri, String name) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM lib WHERE dir_uri=?",
                new String[]{dirUri});
        long id = -1;
        try {
            if (c.moveToFirst()) id = c.getLong(0);
        } finally { c.close(); }
        if (id > 0) return id;
        ContentValues cv = new ContentValues();
        cv.put("dir_uri", dirUri);
        cv.put("name", name == null ? "" : name);
        return db.insert("lib", null, cv);
    }

    synchronized void touchLib(long libId, int files, int chunks) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("files", files);
        cv.put("chunks", chunks);
        cv.put("indexed_at", System.currentTimeMillis());
        db.update("lib", cv, "id=?", new String[]{String.valueOf(libId)});
        cache = null;
    }

    /** [{id,name,dir,uri,files,chunks,indexed_at}] */
    synchronized String libsJson() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id,name,dir_uri,files,chunks,indexed_at FROM lib ORDER BY id", null);
        StringBuilder sb = new StringBuilder("[");
        try {
            boolean first = true;
            while (c.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(c.getLong(0))
                        .append(",\"name\":\"").append(esc(c.getString(1))).append('"')
                        .append(",\"dir\":\"").append(esc(c.getString(2))).append('"')
                        .append(",\"files\":").append(c.getInt(3))
                        .append(",\"chunks\":").append(c.getInt(4))
                        .append(",\"indexed_at\":").append(c.getLong(5)).append('}');
            }
        } finally { c.close(); }
        return sb.append(']').toString();
    }

    synchronized int[] totals() {
        SQLiteDatabase db = getReadableDatabase();
        int[] out = new int[3]; // libs, files, chunks
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM lib", null);
            if (c.moveToFirst()) out[0] = c.getInt(0);
            c.close(); c = null;
            c = db.rawQuery("SELECT COUNT(*) FROM doc", null);
            if (c.moveToFirst()) out[1] = c.getInt(0);
            c.close(); c = null;
            c = db.rawQuery("SELECT COUNT(*) FROM chunk", null);
            if (c.moveToFirst()) out[2] = c.getInt(0);
        } finally { if (c != null) c.close(); }
        return out;
    }

    /** vc159: 库是否存在（drop / 按库检索的前置判定）。 */
    synchronized boolean libExists(long libId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT 1 FROM lib WHERE id=? LIMIT 1",
                new String[]{String.valueOf(libId)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    synchronized void dropLib(long libId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("chunk", "lib_id=?", new String[]{String.valueOf(libId)});
        db.delete("doc", "lib_id=?", new String[]{String.valueOf(libId)});
        db.delete("lib", "id=?", new String[]{String.valueOf(libId)});
        cache = null;
    }

    // ------------------------------------------------------------------ doc

    /** {mtime,size,hash,chunks} 或 null（无记录）。 */
    synchronized String[] docState(long libId, String relpath) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT mtime,size,hash,chunks FROM doc WHERE lib_id=? AND relpath=?",
                new String[]{String.valueOf(libId), relpath});
        try {
            if (!c.moveToFirst()) return null;
            return new String[]{String.valueOf(c.getLong(0)), String.valueOf(c.getLong(1)),
                    c.getString(2) == null ? "" : c.getString(2),
                    String.valueOf(c.getInt(3))};
        } finally { c.close(); }
    }

    /** 写入/更新 doc 行并替换其 chunk（先删旧块）。返回 doc id。 */
    synchronized long putDoc(long libId, String relpath, long mtime, long size,
                             String hash, List<RagChunker.Chunk> chunks, List<float[]> vecs) {
        SQLiteDatabase db = getWritableDatabase();
        long docId = -1;
        Cursor c = db.rawQuery("SELECT id FROM doc WHERE lib_id=? AND relpath=?",
                new String[]{String.valueOf(libId), relpath});
        try {
            if (c.moveToFirst()) docId = c.getLong(0);
        } finally { c.close(); }
        ContentValues cv = new ContentValues();
        cv.put("lib_id", libId);
        cv.put("relpath", relpath);
        cv.put("mtime", mtime);
        cv.put("size", size);
        cv.put("hash", hash == null ? "" : hash);
        cv.put("chunks", chunks == null ? 0 : chunks.size());
        if (docId > 0) {
            db.update("doc", cv, "id=?", new String[]{String.valueOf(docId)});
            db.delete("chunk", "doc_id=?", new String[]{String.valueOf(docId)});
        } else {
            docId = db.insert("doc", null, cv);
        }
        if (chunks != null) {
            for (int i = 0; i < chunks.size(); i++) {
                RagChunker.Chunk ch = chunks.get(i);
                float[] v = (vecs != null && i < vecs.size()) ? vecs.get(i) : null;
                byte[] blob = v == null ? new byte[0] : RagVectors.toBlob(v);
                ContentValues cc = new ContentValues();
                cc.put("lib_id", libId);
                cc.put("doc_id", docId);
                cc.put("seq", ch.seq);
                cc.put("title", ch.title);
                cc.put("body", ch.text);
                cc.put("vec", blob);
                cc.put("dim", v == null ? 0 : v.length);
                db.insert("chunk", null, cc);
            }
        }
        cache = null;
        return docId;
    }

    synchronized void deleteDoc(long libId, String relpath) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM doc WHERE lib_id=? AND relpath=?",
                new String[]{String.valueOf(libId), relpath});
        long docId = -1;
        try {
            if (c.moveToFirst()) docId = c.getLong(0);
        } finally { c.close(); }
        if (docId <= 0) return;
        db.delete("chunk", "doc_id=?", new String[]{String.valueOf(docId)});
        db.delete("doc", "id=?", new String[]{String.valueOf(docId)});
        cache = null;
    }

    /** 已索引文件 relpath 全集（用于「磁盘上已删除」的清理）。 */
    synchronized List<String> docPaths(long libId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT relpath FROM doc WHERE lib_id=?",
                new String[]{String.valueOf(libId)});
        List<String> out = new ArrayList<String>();
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally { c.close(); }
        return out;
    }

    // ---------------------------------------------------------------- chunk

    /** 检索用：该库全部块（带向量）。缓存到内存，写入即失效。 */
    synchronized List<Row> rows(long libId) {
        Cache cc = cache;
        if (cc != null && cc.libId == libId) return cc.rows;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT c.id,d.relpath,c.title,c.body,c.vec,d.mtime FROM chunk c"
                        + " JOIN doc d ON d.id=c.doc_id WHERE c.lib_id=? ORDER BY c.id",
                new String[]{String.valueOf(libId)});
        List<Row> out = new ArrayList<Row>();
        try {
            while (c.moveToNext()) {
                float[] v = RagVectors.fromBlob(c.getBlob(4));
                if (v.length == 0) continue; // 半途索引的块跳过，不参与检索
                out.add(new Row(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), v, c.getLong(5)));
            }
        } finally { c.close(); }
        cache = new Cache(libId, out);
        return out;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
