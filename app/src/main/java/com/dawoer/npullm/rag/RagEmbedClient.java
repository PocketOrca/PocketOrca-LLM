package com.dawoer.npullm.rag;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 嵌入进程客户端（bge-small-zh-v1.5 Q8，llama-server --embedding 独立进程）。
 *
 * 形态决策（BRIEF 批2）：嵌入模型不进聊天进程、也不进 :8082 进程，而是自己
 * 一个 llama-server 子进程（127.0.0.1:8083），由本类按需拉起、空闲回收。
 * 好处是内存账目干净：不检索时不占模型内存；拉起/回收都由这个类的生命周期
 * 管，异常可 kill 重来。
 *
 * llama-server 自带 /v1/embeddings（server.cpp:194），所以这里只做三件事：
 * 保证模型文件在、保证进程活着、把请求转过去。
 */
final class RagEmbedClient {

    static final String MODEL_ASSET = "rag/bge-small-zh-v1.5-q8_0.gguf";
    static final String MODEL_NAME = "bge-small-zh-v1.5-q8_0.gguf";
    /** llama.cpp 要求 input 是 string（数组会 400），所以逐条发。 */
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 120000;
    private static final long BOOT_WAIT_MS = 90000L;
    private static final long IDLE_RECYCLE_MS = 5 * 60 * 1000L;

    private final Context ctx;
    private Process proc;
    private volatile long lastUsed;
    private volatile boolean starting;
    private final java.util.List<Thread> drains = new java.util.ArrayList<Thread>();

    RagEmbedClient(Context c) {
        this.ctx = c.getApplicationContext();
    }

    // ---------------------------------------------------------------- model

    /** 模型文件（首次从 assets 展开；长度不符则重拷）。 */
    synchronized File modelFile() {
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, MODEL_NAME);
        long want = assetSize();
        if (want > 0 && f.length() == want) return f;
        File tmp = new File(dir, MODEL_NAME + ".part");
        InputStream in = null;
        OutputStream out = null;
        try {
            AssetManager am = ctx.getAssets();
            in = am.open(MODEL_ASSET);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            if (want > 0 && tmp.length() != want) {
                android.util.Log.w("RagEmbed", "modelFile: size mismatch want=" + want
                        + " got=" + tmp.length());
                tmp.delete();
                return null;
            }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) {
                android.util.Log.w("RagEmbed", "modelFile: rename failed");
                tmp.delete();
                return null;
            }
            return f;
        } catch (Throwable t) {
            android.util.Log.w("RagEmbed", "modelFile: copy failed: " + t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
        }
    }

    /** assets 里模型长度（拿不到 → -1，表示不做长度校验）。 */
    private long assetSize() {
        try {
            AssetManager am = ctx.getAssets();
            return am.openFd(MODEL_ASSET).getLength();
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean modelReady() {
        File f = modelFile();
        return f != null && f.length() > 0;
    }

    // ------------------------------------------------------------- process

    boolean isUp() {
        try {
            return health(600) == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 健康检查：200 = ok；其它/异常 → -1。 */
    int health(int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + RagConfig.EMBED_PORT + "/health");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 确保嵌入进程可用（幂等；并发调用只有一个真去拉）。
     * 失败原因由调用方通过 health()/日志判读，这里只回 boolean。
     */
    synchronized boolean ensureUp() {
        if (isUp()) { lastUsed = System.currentTimeMillis(); return true; }
        if (starting) return waitUp(BOOT_WAIT_MS);
        File model = modelFile();
        if (model == null || model.length() == 0) {
            android.util.Log.w("RagEmbed", "ensureUp: model missing");
            return false;
        }
        String exe = ctx.getApplicationInfo().nativeLibraryDir + "/libllamaserver.so";
        String libDir = ctx.getApplicationInfo().nativeLibraryDir;
        starting = true;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe,
                    "-m", model.getAbsolutePath(),
                    "--embedding",
                    "--pooling", "cls",
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(RagConfig.EMBED_PORT),
                    "--ctx-size", "512",
                    "--batch-size", "512",
                    "--ubatch-size", "512",
                    "--threads", "4",
                    "--no-webui",
                    "--no-warmup",
                    // 嵌入只吃 CPU：不加这句，模型加载会被 ggml-hex（HTP）抢去
                    // "failed to open session" 直接失败（vc145 实撞）
                    "--device", "none");
            pb.directory(ctx.getFilesDir());
            // exec 子进程按**文件名**找 libOpenCL.so，而包内只有 libOCLstub.so（SONAME 已
            // 改写为 libOpenCL.so，主进程靠 System.loadLibrary 能配对，exec 不行）。
            // → 拷一份文件名正确的 stub 到私有目录，塞进 LD_LIBRARY_PATH 第一位。
            // 注意：不能把 libOpenCL.so 打进 APK 本体——build-verify 明确禁止
            // （会抢 ocl 的 sphal channel，真机 GPU 直接崩）。
            String stubDir = prepareOpenClStub(libDir);
            pb.environment().put("LD_LIBRARY_PATH",
                    stubDir != null ? stubDir + ":" + libDir : libDir);
            pb.redirectErrorStream(true);
            proc = pb.start();
            spawnDrain(proc, "rag-embed-out");
            lastUsed = System.currentTimeMillis();
            return waitUp(BOOT_WAIT_MS);
        } catch (Throwable t) {
            android.util.Log.w("RagEmbed", "spawn failed: " + t);
            killProc();
            return false;
        } finally {
            starting = false;
        }
    }

    private boolean waitUp(long budgetMs) {
        long end = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < end) {
            if (health(800) == 200) { lastUsed = System.currentTimeMillis(); return true; }
            try { Thread.sleep(250); } catch (InterruptedException e) { return false; }
            Process p = proc;
            if (p != null && !p.isAlive()) {
                android.util.Log.w("RagEmbed", "embed proc died, exit=" + exitOf(p));
                return false; // 进程已死，别再等满预算
            }
        }
        return false;
    }

    /** exec 用的 libOpenCL.so（= 包内 libOCLstub.so 改个名的副本）；失败回 null。 */
    private String prepareOpenClStub(String libDir) {
        try {
            File src = new File(libDir, "libOCLstub.so");
            if (!src.isFile() || src.length() == 0) {
                android.util.Log.w("RagEmbed", "stub missing: " + src);
                return null;
            }
            File dir = new File(ctx.getFilesDir(), "embedlibs");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dst = new File(dir, "libOpenCL.so");
            if (dst.length() != src.length()) {
                java.io.InputStream in = null;
                java.io.OutputStream out = null;
                try {
                    in = new java.io.FileInputStream(src);
                    out = new java.io.FileOutputStream(dst);
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.flush();
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ig) {}
                    if (out != null) try { out.close(); } catch (Throwable ig) {}
                }
            }
            return dir.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("RagEmbed", "stub copy failed: " + t);
            return null;
        }
    }

    /** 子进程退出码（拿不到给 -999）。 */
    private static int exitOf(Process p) {
        try { return p.exitValue(); } catch (Throwable t) { return -999; }
    }

    private void spawnDrain(final Process p, String name) {
        Thread t = new Thread(new DrainTask(p), name);
        t.setDaemon(true);
        t.start();
        drains.add(t);
    }

    /** 进程 stdout/stderr 必须有人读，否则管道写满会把子进程卡死。 */
    private static final class DrainTask implements Runnable {
        private final Process p;
        DrainTask(Process p) { this.p = p; }
        @Override public void run() {
            InputStream in = null;
            try {
                in = p.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                int logged = 0;
                while ((n = in.read(buf)) > 0) {
                    // 前 20 行转进 logcat：子进程秒死时这是唯一线索（否则只有黑盒 false）
                    if (logged < 20) {
                        String chunk = new String(buf, 0, n, "UTF-8");
                        for (String ln : chunk.split("\n")) {
                            if (ln.trim().length() > 0 && logged < 20) {
                                android.util.Log.i("RagEmbed", "> " + ln);
                                logged++;
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ig) {}
            }
        }
    }

    synchronized void stop() {
        killProc();
    }

    private void killProc() {
        Process p = proc;
        proc = null;
        if (p == null) return;
        try { p.destroy(); } catch (Throwable ignore) {}
        try {
            if (!p.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (Throwable ignore) {
            try { p.destroyForcibly(); } catch (Throwable ig) {}
        }
    }

    /** 空闲回收（RagService 定时调）：非常驻且超时无请求 → 释放模型内存。 */
    synchronized void recycleIfIdle() {
        if (RagConfig.autoEmbed()) return;
        long used = lastUsed;
        if (used > 0 && System.currentTimeMillis() - used > IDLE_RECYCLE_MS) killProc();
    }

    // --------------------------------------------------------------- embed

    /**
     * 单条文本 → 向量。进程不在会先拉起。失败返回 null（调用方跳过该块，
     * 不让一块失败掀翻整次索引）。
     */
    float[] embed(String text) {
        if (text == null || text.length() == 0) return null;
        if (!ensureUp()) return null;
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + RagConfig.EMBED_PORT + "/v1/embeddings");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("model", "bge");
            byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(raw);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) return null;
            String resp = readAll(c.getInputStream());
            JSONObject o = new JSONObject(resp);
            JSONArray data = o.optJSONArray("data");
            if (data == null || data.length() == 0) return null;
            JSONArray vec = data.getJSONObject(0).optJSONArray("embedding");
            if (vec == null || vec.length() == 0) return null;
            float[] out = new float[vec.length()];
            for (int i = 0; i < out.length; i++) out[i] = (float) vec.getDouble(i);
            if (out.length < 32) return null; // 明显不是嵌入向量
            lastUsed = System.currentTimeMillis();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
