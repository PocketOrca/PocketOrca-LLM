package com.dawoer.npullm.rag;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目录索引器：SAF 树遍历 → 分块 → 嵌入 → 入库，带指纹增量。
 *
 * 只用 framework 的 DocumentsContract（项目无 androidx——手工构建链只带
 * jsoup/nanohttpd 两个 jar，引 androidx.documentfile 会直接编译不过）。
 *
 * 指纹增量口径（BRIEF 批2）：mtime+size+sha256 幂等；mtime/size 未变直接跳过
 * （不读文件），变了再算 hash 判断是「真改」还是「只是 touch」。指纹不符的
 * 「提示重建」不在索引器里做——它只更新库，status 返回 changed 计数，UI 决定话术。
 *
 * 拒收：二进制（NUL 字节）、超大文件（>2MB）、扩展名不在白名单。
 * 编码：UTF-8 严格解码失败 → GBK 回退（中文库的常见情形）。
 */
public final class RagIndexer {

    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    static final int MAX_FILES = 5000;

    private static final String[] EXT = {
            ".md", ".markdown", ".mdx", ".txt", ".rst", ".adoc",
            ".java", ".kt", ".py", ".js", ".ts", ".tsx", ".jsx",
            ".c", ".h", ".cpp", ".cc", ".hpp", ".rs", ".go",
            ".sh", ".bash", ".sql", ".gradle", ".toml", ".yaml", ".yml",
            ".json", ".xml", ".css", ".scss"};

    /** 索引进度（volatile，Bridge/UI 轮询读）。 */
    static volatile String phase = "idle";
    static volatile int done;
    static volatile int total;
    static volatile String currentFile = "";

    static final class Result {
        int files;
        int chunks;
        int added;
        int changed;
        int skipped;
        int removed;
        int failed;
        String error;
        String toJson() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"ok\":").append(error == null).append(',');
            sb.append("\"files\":").append(files).append(',');
            sb.append("\"chunks\":").append(chunks).append(',');
            sb.append("\"added\":").append(added).append(',');
            sb.append("\"changed\":").append(changed).append(',');
            sb.append("\"skipped\":").append(skipped).append(',');
            sb.append("\"removed\":").append(removed).append(',');
            sb.append("\"failed\":").append(failed);
            if (error != null) sb.append(",\"error\":\"").append(RagStore.esc(error)).append('"');
            return sb.append('}').toString();
        }
    }

    private final Context ctx;
    private final RagStore store;
    private final RagEmbedClient embed;

    RagIndexer(Context c, RagStore s, RagEmbedClient e) {
        this.ctx = c.getApplicationContext();
        this.store = s;
        this.embed = e;
    }

    Result index(String dirUri, boolean rebuild) {
        Result r = new Result();
        phase = "scan";
        done = 0;
        total = 0;
        currentFile = "";
        try {
            if (dirUri == null || dirUri.length() == 0) {
                r.error = "尚未选择文档目录";
                phase = "idle";
                return r;
            }
            Uri tree = Uri.parse(dirUri);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            List<DocFile> files = new ArrayList<DocFile>();
            walk(tree, rootId, "", files, 0);
            if (files.size() > MAX_FILES) files = files.subList(0, MAX_FILES);
            total = files.size();
            String name = queryName(tree, rootId);
            long libId = store.ensureLib(dirUri, name);
            Set<String> seen = new HashSet<String>();
            phase = "index";
            for (int i = 0; i < files.size(); i++) {
                DocFile f = files.get(i);
                done = i + 1;
                currentFile = f.rel;
                indexOne(libId, f, rebuild, r);
                seen.add(f.rel);
            }
            // 清理：库里记着、磁盘上没了的
            phase = "cleanup";
            List<String> known = store.docPaths(libId);
            for (int i = 0; i < known.size(); i++) {
                if (!seen.contains(known.get(i))) {
                    store.deleteDoc(libId, known.get(i));
                    r.removed++;
                }
            }
            r.files = files.size() - r.failed;
            store.touchLib(libId, r.files, r.chunks);
            phase = "idle";
            currentFile = "";
            return r;
        } catch (Throwable t) {
            r.error = String.valueOf(t);
            phase = "idle";
            return r;
        }
    }

    private void indexOne(long libId, DocFile f, boolean rebuild, Result r) {
        String[] old = rebuild ? null : store.docState(libId, f.rel);
        long oldMtime = 0, oldSize = 0;
        String oldHash = "";
        if (old != null) {
            try { oldMtime = Long.parseLong(old[0]); } catch (Throwable ignore) {}
            try { oldSize = Long.parseLong(old[1]); } catch (Throwable ignore) {}
            oldHash = old[2];
        }
        if (old != null && oldMtime == f.mtime && oldSize == f.size) {
            // 快速路径：mtime+size 一致 → 不读文件（重扫的主路径）
            r.skipped++;
            try { r.chunks += Integer.parseInt(old[3]); } catch (Throwable ignore) {}
            return;
        }
        byte[] raw;
        try {
            raw = readBytes(f.uri);
        } catch (Throwable t) {
            r.failed++;
            return;
        }
        if (raw == null || raw.length == 0) { r.failed++; return; }
        String text = decode(raw);
        if (text == null || RagChunker.isBinary(text)) { r.skipped++; return; }
        String hash = RagFingerprint.sha256Hex(raw);
        int state = RagFingerprint.state(oldMtime, oldSize, oldHash, f.mtime, f.size, hash);
        if (state == RagFingerprint.SAME && !rebuild) {
            r.skipped++;
            try { r.chunks += Integer.parseInt(old[3]); } catch (Throwable ignore) {}
            return;
        }
        List<RagChunker.Chunk> chunks = RagChunker.chunk(f.rel, text);
        if (chunks.isEmpty()) { r.skipped++; return; }
        List<float[]> vecs = new ArrayList<float[]>(chunks.size());
        int ok = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] v = embed.embed(chunks.get(i).text);
            vecs.add(v);
            if (v != null) ok++;
        }
        if (ok == 0) { r.failed++; return; }
        store.putDoc(libId, f.rel, f.mtime, f.size, hash, chunks, vecs);
        r.chunks += ok;
        if (state == RagFingerprint.NEW) r.added++;
        else r.changed++;
    }

    // ------------------------------------------------------- chat memory (vc147)

    /**
     * 聊天记忆索引：窗口由 MemoryWindows 生成，这里只做增量比对 + 嵌入 + 入库。
     *
     * 与 SAF 路径共用同一套 lib/doc/chunk 表，lib.dir_uri = internal:chat。
     * 增量口径：块 rel 锚首条消息 id（追加不改身份），mtime=块内最后一条时间、
     * size=块字符数，先比 mtime+size 走快速路径，不等再比 sha256。
     */
    public Result indexChat(List<MemoryWindows.Win> wins, boolean rebuild) {
        Result r = new Result();
        phase = "scan";
        done = 0;
        total = wins == null ? 0 : wins.size();
        currentFile = "";
        try {
            if (wins == null || wins.isEmpty()) {
                r.error = "还没有聊天记录";
                phase = "idle";
                return r;
            }
            long libId = store.ensureLib(RagConfig.CHAT_LIB_URI, RagConfig.CHAT_LIB_NAME);
            Set<String> seen = new HashSet<String>();
            phase = "index";
            for (int i = 0; i < wins.size(); i++) {
                MemoryWindows.Win w = wins.get(i);
                done = i + 1;
                currentFile = w.rel;
                indexText(libId, w.rel, w.ts, w.text, rebuild, r);
                seen.add(w.rel);
            }
            phase = "cleanup";
            List<String> known = store.docPaths(libId);
            for (int i = 0; i < known.size(); i++) {
                if (!seen.contains(known.get(i))) {
                    store.deleteDoc(libId, known.get(i));
                    r.removed++;
                }
            }
            r.files = wins.size() - r.failed;
            store.touchLib(libId, r.files, r.chunks);
            phase = "idle";
            currentFile = "";
            return r;
        } catch (Throwable t) {
            r.error = String.valueOf(t);
            phase = "idle";
            return r;
        }
    }

    /** 纯文本入库（聊天记忆专用；SAF 路径仍走 indexOne，保持已验证代码不动）。 */
    private void indexText(long libId, String rel, long ts, String text,
                           boolean rebuild, Result r) {
        long size = text.length();
        String[] old = rebuild ? null : store.docState(libId, rel);
        long oldMtime = 0, oldSize = 0;
        String oldHash = "";
        if (old != null) {
            try { oldMtime = Long.parseLong(old[0]); } catch (Throwable ignore) {}
            try { oldSize = Long.parseLong(old[1]); } catch (Throwable ignore) {}
            oldHash = old[2];
        }
        if (old != null && oldMtime == ts && oldSize == size) {
            r.skipped++;
            try { r.chunks += Integer.parseInt(old[3]); } catch (Throwable ignore) {}
            return;
        }
        String hash = RagFingerprint.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        int state = RagFingerprint.state(oldMtime, oldSize, oldHash, ts, size, hash);
        if (state == RagFingerprint.SAME && !rebuild) {
            r.skipped++;
            try { r.chunks += Integer.parseInt(old[3]); } catch (Throwable ignore) {}
            return;
        }
        List<RagChunker.Chunk> chunks = RagChunker.chunk(rel, text);
        if (chunks.isEmpty()) { r.skipped++; return; }
        List<float[]> vecs = new ArrayList<float[]>(chunks.size());
        int ok = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] v = embed.embed(chunks.get(i).text);
            vecs.add(v);
            if (v != null) ok++;
        }
        if (ok == 0) { r.failed++; return; }
        store.putDoc(libId, rel, ts, size, hash, chunks, vecs);
        r.chunks += ok;
        if (state == RagFingerprint.NEW) r.added++;
        else r.changed++;
    }

    // ------------------------------------------------------------ walking

    private static final class DocFile {
        final Uri uri;
        final String rel;
        final long mtime;
        final long size;
        DocFile(Uri uri, String rel, long mtime, long size) {
            this.uri = uri;
            this.rel = rel;
            this.mtime = mtime;
            this.size = size;
        }
    }

    private static final String[] PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE};

    private void walk(Uri tree, String parentDocId, String prefix, List<DocFile> out, int depth) {
        if (depth > 8 || out.size() >= MAX_FILES) return;
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        Cursor c = null;
        try {
            c = cr.query(children, PROJ, null, null, null);
            if (c == null) return;
            while (c.moveToNext() && out.size() < MAX_FILES) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long mtime = c.isNull(3) ? 0 : c.getLong(3);
                long size = c.isNull(4) ? 0 : c.getLong(4);
                if (docId == null || name == null || name.startsWith(".")) continue;
                String rel = prefix.length() == 0 ? name : prefix + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, docId, rel, out, depth + 1);
                } else if (accept(name, size)) {
                    out.add(new DocFile(DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            rel, mtime, size));
                }
            }
        } catch (Throwable ignore) {
            // 单目录异常不掀翻整次遍历（不同 provider 脾气不同）
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ig) {}
        }
    }

    private String queryName(Uri tree, String docId) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignore) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ig) {}
        }
        return "docs";
    }

    public static boolean accept(String name, long size) {
        if (size <= 0 || size > MAX_FILE_BYTES) return false;
        String lower = name.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < EXT.length; i++) {
            if (lower.endsWith(EXT[i])) return true;
        }
        return false;
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FILE_BYTES) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    /** UTF-8 严格解码；失败回退 GBK（中文老文档常见）。 */
    public static String decode(byte[] raw) {
        if (raw == null) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("GBK").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
            } catch (Throwable t2) {
                return new String(raw, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
