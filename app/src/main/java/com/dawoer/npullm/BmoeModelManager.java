package com.dawoer.npullm;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.4.0 (vc74): bmoe model storage — multi-shard downloader + sideload import.
 *
 * Storage MUST live under getFilesDir()/models (app-private /data ext4/f2fs):
 * the bmoe engine's expert streaming uses O_DIRECT, which the /sdcard FUSE view
 * does not support (engine falls back to buffered I/O and loses its advantage —
 * T4 measured 8% slower, and mmap-level variance on big models). App-private
 * storage also needs no permission and is wiped on uninstall.
 *
 * Shards: HuggingFace large models ship as model-00001-of-00003.gguf …; the
 * engine takes the FIRST shard path and reads the whole set from its directory.
 * Downloader derives every shard URL from the first-shard URL pattern.
 *
 * d8 constraints: no enums, no anonymous classes; named static nested only.
 */
final class BmoeModelManager {

    interface Notifier { void onDlStatus(String statusJson); }

    private static volatile BmoeModelManager sInstance;
    static synchronized BmoeModelManager get(Context ctx) {
        if (sInstance == null) sInstance = new BmoeModelManager(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private volatile Notifier notifier;
    void setNotifier(Notifier n) { notifier = n; }

    private volatile boolean busy = false;
    private volatile String phase = "idle";   // idle|download|import|done|error
    private volatile int idx = 0, count = 0;
    private volatile String name = "";
    private volatile long bytes = 0, total = 0;
    private volatile String error = "";

    private BmoeModelManager(Context c) { ctx = c; }

    static File modelsDir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** v1.4.0(vc84): resolve a chat-UI model reference to an absolute gguf path.
     *  The bmoe picker stores entries as "__bmoe__<name>" (JS bmoePick), and
     *  bmoeList shows plain file names — both must resolve to modelsDir
     *  (app-private /data ext4, O_DIRECT-capable). Absolute paths pass through.
     *  Returns null when unresolvable/unreadable so the caller fails fast with
     *  a clear message instead of spawning libbmoecli into ENOENT (S25 vc82
     *  incident: the raw "__bmoe__…" tag reached -m verbatim). JVM-testable. */
    static String resolveModelPath(File dir, String ref) {
        if (ref == null) return null;
        String name = ref;
        if (name.startsWith("__bmoe__")) name = name.substring("__bmoe__".length());
        File f = new File(name);
        if (!f.isAbsolute()) {
            if (!GGUF.matcher(name).find()) return null; // only plain .gguf names resolve in modelsDir
            f = new File(dir, name);
        }
        return f.canRead() ? f.getPath() : null;
    }

    static String resolveModelPath(Context c, String ref) {
        return resolveModelPath(modelsDir(c), ref);
    }

    /* ---------- pure helpers (JVM-testable) ---------- */

    private static final Pattern SHARD =
            Pattern.compile("-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GGUF = Pattern.compile("\\.gguf$", Pattern.CASE_INSENSITIVE);

    /** filename without query/path */
    static String urlFileName(String url) {
        String p = url == null ? "" : url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int s = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return s >= 0 ? p.substring(s + 1) : p;
    }

    /** total shard count parsed from a main-shard filename (1 when not sharded) */
    static int totalShards(String fileName) {
        Matcher m = SHARD.matcher(fileName == null ? "" : fileName);
        return m.find() ? Integer.parseInt(m.group(2)) : 1;
    }

    /** shard i URL: replace the -NNNNN- ordinal in the main-shard URL (1-based). */
    static String shardUrl(String mainUrl, int i) {
        String f = urlFileName(mainUrl);
        Matcher m = SHARD.matcher(f);
        if (!m.find()) return mainUrl;
        String replacement = "-" + String.format(Locale.US, "%05d", i) + "-of-" + m.group(2) + ".gguf";
        String newF = f.substring(0, m.start()) + replacement;
        return mainUrl.substring(0, mainUrl.length() - f.length()) + newF;
    }

    static boolean validModelUrl(String url) {
        return url != null && url.startsWith("http") && GGUF.matcher(urlFileName(url)).find();
    }

    static String humanBytes(long b) {
        if (b >= 1_073_741_824L) return String.format(Locale.US, "%.2f GB", b / 1.073741824e9);
        if (b >= 1_048_576L) return String.format(Locale.US, "%.1f MB", b / 1.048576e6);
        return String.format(Locale.US, "%.0f KB", b / 1024.0);
    }

    /* ---------- listing ---------- */

    static String listJson(Context c) {
        File dir = modelsDir(c);
        StringBuilder sb = new StringBuilder("{\"models\":[");
        File[] fs = dir.listFiles();
        long free = new android.os.StatFs(dir.getPath()).getAvailableBytes();
        boolean first = true;
        if (fs != null) {
            for (File f : fs) {
                if (!GGUF.matcher(f.getName()).find() || !f.isFile()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(jsonEsc(f.getName()))
                  .append("\",\"sizeBytes\":").append(f.length())
                  .append(",\"path\":\"").append(jsonEsc(f.getPath())).append("\"}");
            }
        }
        sb.append("],\"freeBytes\":").append(free).append('}');
        return sb.toString();
    }

    static String delete(Context c, String fileName) {
        File f = new File(modelsDir(c), fileName);
        // path traversal guard: only plain names inside modelsDir
        if (!f.getPath().startsWith(modelsDir(c).getPath()) || f.getName().contains("/")) {
            return "{\"ok\":false,\"error\":\"bad name\"}";
        }
        boolean ok = f.delete();
        return "{\"ok\":" + ok + "}";
    }

    /* ---------- download ---------- */

    /** Start a download; one at a time. Returns error string or null on started. */
    String startDownload(String urlStr) {
        if (busy) return "已有任务进行中";
        if (!validModelUrl(urlStr)) return "URL 必须以 .gguf 结尾";
        busy = true;
        phase = "download";
        error = "";
        bytes = 0;
        total = 0;
        name = urlFileName(urlStr);
        count = totalShards(name);
        final String url = urlStr;
        Thread t = new Thread(() -> runDownload(url), "bmoe-dl");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private void runDownload(String mainUrl) {
        try {
            File dir = modelsDir(ctx);
            long grand = 0;
            for (int i = 1; i <= count; i++) {
                idx = i;
                String u = shardUrl(mainUrl, i);
                String f = urlFileName(u);
                name = f;
                File dest = new File(dir, f);
                File part = new File(dir, f + ".part");
                long done = downloadOne(u, dest, part, i == 1);
                grand += done;
                bytes = grand;
                notifyStatus();
            }
            phase = "done";
            notifyStatus();
        } catch (Throwable e) {
            phase = "error";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            notifyStatus();
        } finally {
            busy = false;
        }
    }

    /** Download one shard with resume; returns bytes written (total file size). */
    private long downloadOne(String urlStr, File dest, File part, boolean verifyMagic) throws IOException {
        long offset = part.exists() ? part.length() : 0;
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                long size = downloadAttempt(urlStr, part, verifyMagic && attempt == 1);
                // completed: promote .part -> final
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new IOException("rename .part 失败");
                return size;
            } catch (IOException e) {
                last = e;
                offset = part.exists() ? part.length() : 0;
                if (offset > 0) log("[bmoe-dl] 分片重试 " + attempt + " 断点=" + humanBytes(offset) + " err=" + e);
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { break; }
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private long downloadAttempt(String urlStr, File part, boolean verifyMagic) throws IOException {
        HttpURLConnection conn = null;
        long offset = part.exists() ? part.length() : 0;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = conn.getResponseCode();
            InputStream in;
            if (code == 206) {
                in = conn.getInputStream();
            } else if (code == 200) {
                offset = 0; // server ignored Range — restart shard
                in = conn.getInputStream();
            } else if (code == 416) {
                // already complete? verify against Content-Range if present
                throw new IOException("HTTP 416（分片已完整或区间错）");
            } else {
                throw new IOException("HTTP " + code);
            }
            long contentLen = conn.getContentLength();
            if (contentLen > 0) total = offset + contentLen;
            long written = offset;
            OutputStream out = new FileOutputStream(part, offset > 0);
            byte[] buf = new byte[256 * 1024];
            long lastNotify = 0;
            boolean headerChecked = !verifyMagic || offset > 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (!headerChecked) {
                        // GGUF magic check on first shard, first bytes
                        String m4 = new String(buf, 0, Math.min(4, n), StandardCharsets.US_ASCII);
                        if (!"GGUF".equals(m4)) {
                            throw new IOException("不是 GGUF 文件（魔数不符）");
                        }
                        headerChecked = true;
                    }
                    out.write(buf, 0, n);
                    written += n;
                    bytes = written;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 600) { lastNotify = now; notifyStatus(); }
                }
            } finally {
                try { out.close(); } catch (IOException ignored) {}
                try { in.close(); } catch (IOException ignored) {}
            }
            return written;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ---------- sideload import ---------- */

    /** Import a picked file (real path anywhere MANAGE_EXTERNAL_STORAGE reaches)
     *  into modelsDir. rename first (same filesystem), else streamed copy. */
    String startImport(String srcPath) {
        if (busy) return "已有任务进行中";
        File src = new File(srcPath);
        if (!src.isFile() || !GGUF.matcher(src.getName()).find()) return "请选择 .gguf 文件";
        File dir = modelsDir(ctx);
        long free = new android.os.StatFs(dir.getPath()).getAvailableBytes();
        if (free < src.length() + 64L * 1024 * 1024) {
            return "空间不足：需 " + humanBytes(src.length()) + "，可用 " + humanBytes(free)
                    + "（导入完成后请删除原文件释放空间）";
        }
        busy = true;
        phase = "import";
        error = "";
        name = src.getName();
        count = 1;
        idx = 1;
        final File source = src;
        Thread t = new Thread(() -> runImport(source), "bmoe-import");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private void runImport(File src) {
        try {
            File dest = new File(modelsDir(ctx), src.getName());
            if (dest.exists() && dest.length() == src.length()) {
                phase = "done";
                name = dest.getName();
                total = dest.length();
                bytes = total;
                notifyStatus();
                return;
            }
            boolean renamed = false;
            try {
                renamed = src.renameTo(dest);
            } catch (Throwable ignored) {}
            if (!renamed) {
                // cross-filesystem copy with progress (FUSE /sdcard -> /data)
                total = src.length();
                bytes = 0;
                InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[512 * 1024];
                long lastNotify = 0;
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        bytes += n;
                        long now = System.currentTimeMillis();
                        if (now - lastNotify > 600) { lastNotify = now; notifyStatus(); }
                    }
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                    try { out.close(); } catch (IOException ignored) {}
                }
            }
            phase = "done";
            total = dest.length();
            bytes = total;
            name = dest.getName();
            notifyStatus();
            log("[bmoe-import] 完成 " + name + (renamed ? "（rename）" : "（复制完成；原文件未删除，可手动清理）"));
        } catch (Throwable e) {
            phase = "error";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            notifyStatus();
        } finally {
            busy = false;
        }
    }

    /* ---------- status ---------- */

    String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"busy\":").append(busy)
          .append(",\"phase\":\"").append(phase).append('"')
          .append(",\"idx\":").append(idx)
          .append(",\"count\":").append(count)
          .append(",\"name\":\"").append(jsonEsc(name)).append('"')
          .append(",\"bytes\":").append(bytes)
          .append(",\"total\":").append(total);
        if (error != null && !error.isEmpty()) {
            sb.append(",\"error\":\"").append(jsonEsc(error)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private void notifyStatus() {
        Notifier n = notifier;
        if (n != null) n.onDlStatus(statusJson());
    }

    private void log(String s) {
        android.util.Log.i("npullm", s);
    }

    static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
