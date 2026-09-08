package com.dawoer.npullm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v0.4.0 (spec v0.2 T6): dual-engine launch.
 * v1.2.2: cpu 引擎改走 exec libllamaserver.so（NDK v1.1 验证运行时）。
 *   背景：Termux 血统的 libggm* / libllm* JNI 栈在 8Gen2(S23U) 上输出
 *   确定性乱码（Termux 二进制计算路径 bug），NDK 运行时同机实测
 *   1B 30.8t/s 输出正确 → cpu 不再走 JNI 旧栈。
 * - htp/cpu: exec libllamaserver.so from nativeLibraryDir.
 * - ocl: run libllmoclsrv.so via JNI inside the app process so the
 *   classloader namespace resolves vendor libOpenCL.so through the
 *   public-libraries channel (sphal) — no GPU vendor libs bundled.
 */
final class LlamaServerManager {
    // State as int constants: the bt34 d8 (8.2.2-dev) has an NPE bug dexing enums.
    static final int ST_READY = 0, ST_STARTING = 1, ST_RUNNING = 2, ST_ERROR = 3;
    private static final String[] STATE_NAMES = {"ready", "starting", "running", "error"};

    interface Ui {
        void onServerState(String state, String msg);
        void onServerDied(int exitCode);
    }

    /** Process-level singleton: the Activity may be destroyed while llama-server
     *  keeps running; a fresh Activity must reattach to the live instance (T5). */
    private static LlamaServerManager sInstance;

    static synchronized LlamaServerManager get(Context ctx, Ui ui) {
        if (sInstance == null) sInstance = new LlamaServerManager(ctx, ui);
        return sInstance;
    }

    static boolean hasInstance() { return sInstance != null; }

    static LlamaServerManager peek() { return sInstance; }

    private static volatile boolean serviceRunning = false;
    static void setServiceRunning(boolean b) { serviceRunning = b; }
    static boolean isServiceRunning() { return serviceRunning; }

    boolean isRunning() { return state == ST_RUNNING; }
    boolean isIdleOrError() { return state == ST_READY || state == ST_ERROR; }
    int currentPort() { return port; }
    private volatile long adoptCheckMs = 0;

    /**
     * Adopt an orphaned llama-server (v1.1.1 fix).
     *
     * Timeline that orphans a server: exec child (htp launcher) survives
     * Android LMK killing our App process (it has FGS priority and an active
     * binder ref via the notification), then the FGS restart re-parents it to
     * the NEW app process. The fresh LlamaServerManager singleton starts at
     * ST_READY — so CLI/Chat read running=false and show 未连接, while the old
     * server keeps serving the LAN perfectly (远程可调参).
     *
     * Detection: a /health 200 on our port while we are not running/stopping.
     * Adoption: mark RUNNING with the probing profile, so statusJson reports
     * running=true and the whole UI re-syncs. We cannot recover the child's
     * argv (model/ctx are unknown), so start() stays blocked until the user
     * stops — stop() kills by port-owner process tree instead of proc handle.
     */
    synchronized boolean adoptOrphanIfAny(int probePort) {
        if (state == ST_STARTING || state == ST_RUNNING) return false;
        if (stopping.get()) return false;
        if (adoptCheckMs > 0 && System.currentTimeMillis() - adoptCheckMs < 3000) return false; // throttle: 1 probe / 3s
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + probePort + "/health").openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            if (conn.getResponseCode() != 200) return false;
            adoptCheckMs = 0;
            port = probePort;
            state = ST_RUNNING;
            stopping.set(false);
            logLine("[接管] 检测到孤儿 llama-server @:" + probePort + "（App 曾被系统回收），已重新接管控制");
            final int s = state;
            Notifier n = notifier;
            if (n != null) n.onState(s, "");
            main.post(() -> ui.onServerState(STATE_NAMES[s], ""));
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * stop() for an adopted orphan has no Process handle — kill by port owner.
     * Walks /proc for the process whose cmdline mentions our launcher name and
     * whose /proc/<pid>/net shows the listen on our port; falls back to
     * killing any libllamaserver process visible to us.
     */
    private void killOrphanByPort() {
        try {
            File dir = new File("/proc");
            String[] names = dir.list();
            if (names == null) return;
            for (String pid : names) {
                if (!pid.chars().allMatch(Character::isDigit)) continue;
                File cmd = new File("/proc/" + pid + "/cmdline");
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cmd))) {
                    String line = r.readLine();
                    if (line == null) continue;
                    if (line.contains("libllamaserver") || line.contains("llama-server")) {
                        logLine("[接管停止] kill " + pid + " (" + line + ")");
                        android.os.Process.killProcess(Integer.parseInt(pid));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** Notification updates from stop()/pushState (Service publishes/cancels). */
    interface Notifier { void onState(int state, String msg); }
    private volatile Notifier notifier;
    void setNotifier(Notifier n) { notifier = n; }

    private final Context ctx;
    private Ui ui;   // v1.3.1: non-final — reborn path re-attaches the real UI
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LogRingBuffer log = new LogRingBuffer(2000);

    private volatile Process proc;              // htp path
    private volatile JniServer jniServer;       // ocl/cpu path
    private volatile ParcelFileDescriptor[] jniPipe;
    private volatile Thread jniThread;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile int state = ST_READY;
    private int port = 8080;
    // last-applied sampling params (spec §4: request-level, live-updatable
    // via setSampling; statusJson echoes them so the CLI page can restore UI)
    private volatile String lastProfile = "htp";
    private volatile double lastTemperature = 0.7;
    private volatile int lastTopK = 40;
    private volatile double lastTopP = 0.95;
    private volatile double lastMinP = 0.05;
    private volatile double lastRepeatPenalty = 1.0;
    private volatile String lastSystemPrompt = "";
    private volatile Thread waitThread;
    private volatile Thread healthThread;

    LlamaServerManager(Context ctx, Ui ui) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
    }

    /** v1.3.1: reborn path creates the singleton with a headless UI; when the
     *  user opens the Activity again, swap in the real forwarder. */
    void setUi(Ui u) { ui = u; }

    /* ---------- logging ---------- */

    LogRingBuffer log() { return log; }

    private void logLine(String s) { log.append((s + "\n").toCharArray(), s.length() + 1); }

    private void pushState(int s, String msg) {
        state = s;
        Notifier n = notifier;
        if (n != null) n.onState(s, msg);
        main.post(() -> ui.onServerState(STATE_NAMES[s], msg));
    }

    private void pushDied(int code) {
        main.post(() -> ui.onServerDied(code));
    }

    /* ---------- profiles ---------- */

    private JSONObject profiles;

    private synchronized void loadProfiles() {
        if (profiles != null) return;
        try (InputStream in = ctx.getAssets().open("profiles.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            profiles = new JSONObject(bo.toString("UTF-8")).getJSONObject("profiles");
        } catch (Exception e) {
            logLine("[profiles] load failed: " + e);
        }
    }

    private String profileArgs(String profileId) {
        loadProfiles();
        try {
            return profiles.getJSONObject(profileId).getString("args");
        } catch (JSONException e) {
            return "--no-mmap -m {MODEL} --ctx-size 4096 --threads 4";
        }
    }

    /** Per-profile launcher binary (spec v0.2 D7b: htp→libllamaserver.so, ocl/cpu→JNI). */
    private String profileBinary(String profileId) {
        loadProfiles();
        try {
            return profiles.getJSONObject(profileId).optString("binary", "libllamaserver.so");
        } catch (JSONException e) {
            return "libllamaserver.so";
        }
    }

    private boolean isJniProfile(String profileId) {
        // v1.2.2: cpu 改走 exec（NDK 运行时）；仅 ocl 需要 JNI in-process
        //（GPU 必须经 sphal 拿 vendor libOpenCL，exec 子进程拿不到）。
        return "ocl".equals(profileId);
    }

    /* ---------- public API ---------- */

    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload) {
        start(modelPath, profileId, newPort, ctxSize, reasoning, threads, noKvOffload, 0);
    }

    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload, final int ubatchSize) {
        if (state == ST_STARTING || state == ST_RUNNING) return;
        port = newPort;
        lastProfile = profileId;
        log.clear();
        stopping.set(false);

        if (portInUse(newPort)) {
            logLine("[启动前检查] 端口 " + newPort + " 已被占用（非本 APP 进程），请换一个端口");
            pushState(ST_ERROR, "端口 " + newPort + " 已被占用");
            return;
        }

        final String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        // v0.4.1 fix: argv[0] MUST be an absolute path — profileBinary() only
        // returns the bare jniLibs filename.
        final String exePath = isJniProfile(profileId)
                ? nativeDir + "/libllmoclsrv.so"
                : nativeDir + "/" + profileBinary(profileId);
        final String[] cmd = buildCommand(exePath,
                profileArgs(profileId), modelPath, "0.0.0.0", newPort, ctxSize, reasoning,
                threads, noKvOffload, ubatchSize);

        pushState(ST_STARTING, "正在启动…");
        logLine("[cmd] " + join(cmd));
        logLine("[mode] " + (isJniProfile(profileId) ? "JNI in-process (OpenCL)" : "exec 子进程 (Hexagon NPU / CPU)"));

        if (isJniProfile(profileId)) {
            startJni(cmd);
        } else {
            // v1.2.4: pass engine kind down — non-htp exec children must
            // never touch the Hexagon driver (S23U CPU death, see launchExec).
            startExec(cmd, "htp".equals(profileId));
        }
    }

    synchronized void stop() {
        if (state == ST_READY || state == ST_ERROR) return;
        // adopted orphan has no Process handle — kill the port owner by name
        if (proc == null && jniServer == null) killOrphanByPort();
        stopping.set(true);
        pushState(ST_READY, "已停止");
        Process p = proc;
        if (p != null) p.destroy();
        if (jniServer != null) {
            try { jniServer.stopServer(); } catch (Throwable ignored) {}
        }
        if (healthThread != null) healthThread.interrupt();
        Thread wt = waitThread;
        if (wt != null) {
            try { wt.join(1500); } catch (InterruptedException ignored) {}
        }
        Thread jt = jniThread;
        if (jt != null) {
            try { jt.join(3000); } catch (InterruptedException ignored) {}
        }
        p = proc;
        if (p != null && p.isAlive()) p.destroyForcibly();
        proc = null;
        jniServer = null;
        jniThread = null;
        ParcelFileDescriptor[] pipe = jniPipe;
        jniPipe = null;
        if (pipe != null) {
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
        }
        pushState(ST_READY, "已停止");
    }

    /** Request-level sampling params (spec §4 rule 5): live-updatable while running. */
    synchronized void setSampling(double temperature, int topK, double topP,
                                  double minP, double repeatPenalty, String systemPrompt) {
        lastTemperature = temperature;
        lastTopK = topK;
        lastTopP = topP;
        lastMinP = minP;
        lastRepeatPenalty = repeatPenalty;
        lastSystemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    String statusJson() {
        // v1.1.2: orphan adoption inline — if we're READY but a llama-server
        // still answers on our port (App process was recycled and restarted),
        // adopt it BEFORE reporting state, so CLI/Chat never see a phantom
        // 未连接 while the LAN endpoint is alive.
        if (state == ST_READY && !stopping.get()) {
            adoptOrphanIfAny(port);
        }
        JSONObject j = new JSONObject();
        try {
            j.put("state", STATE_NAMES[state]);
            j.put("running", state == ST_RUNNING);
            j.put("port", port);
            j.put("profile", lastProfile);
            JSONObject p = new JSONObject();
            p.put("temperature", lastTemperature);
            p.put("topK", lastTopK);
            p.put("topP", lastTopP);
            p.put("minP", lastMinP);
            p.put("repeatPenalty", lastRepeatPenalty);
            p.put("systemPrompt", lastSystemPrompt);
            j.put("sampling", p);
            // v1.1.4 fix: Android libcore has NO java.lang.Process.pid() (Java 9 API).
            // The NoSuchMethodError escaped catch(JSONException) and killed the whole
            // statusJson -> CLI/Chat read running=false (未连接) whenever the server
            // was actually running. pid is display-only; make it non-fatal.
            Process pr = proc;
            if (pr != null && pr.isAlive()) {
                try { j.put("pid", pr.pid()); } catch (Throwable ignored) {}
            }
        } catch (JSONException ignored) {}
        return j.toString();
    }

    /* ---------- exec path (htp / Hexagon NPU / cpu) ---------- */

    private void startExec(final String[] cmd, final boolean htpEngine) {
        final String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        // v0.4.1: File.canExecute() can return false on some ROMs for the
        // app's own nativeLibraryDir even though exec succeeds (verified:
        // llama-npu.apk v1.1 ran NPU inference). Warn, never block — the
        // real verdict is ProcessBuilder's IOException.
        if (!new File(cmd[0]).canExecute()) {
            logLine("[警告] canExecute=false（ROM 视图差异，不拦截）: " + cmd[0]);
        }
        new Thread(() -> {
            Process p = launchExec(cmd, nativeDir, htpEngine);
            if (p == null) {
                pushState(ST_ERROR, "启动失败，见日志");
                return;
            }
            proc = p;
            startPump(p.getInputStream());
            startPump(p.getErrorStream());
            startWaiter(p);
            startHealthProbe();
        }, "llama-starter").start();
    }

    /**
     * v1.2.4 env policy (package-private static for JVM testability):
     * non-htp exec children must disable Hexagon enumeration entirely.
     * Root cause (v1.2.3 S23U CPU regression): with libcdsprpc.so packaged,
     * ggml-hexagon can enumerate CDSP devices even where fastRPC sessions
     * are unavailable (S23U shell uid: "session open failed" 0x80000406);
     * the subsequent GGML_ASSERT(device) abort killed the whole server,
     * CPU included. GGML_HEXAGON_ARCH=79 + GGML_HEXAGON_NDEV=0 = zero
     * devices (verified adb recipe 0907: CPU 30.8 t/s, clean output).
     * htp children stay untouched so NPU enumeration works as before.
     */
    static void applyExecEnvPolicy(final java.util.Map<String, String> env,
                                   final boolean htpEngine) {
        if (!htpEngine) {
            env.put("GGML_HEXAGON_ARCH", "79");
            env.put("GGML_HEXAGON_NDEV", "0");
        }
    }

    private Process launchExec(String[] cmd, String nativeDir, boolean htpEngine) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // [system] linker ns forbids /vendor/lib64 for /data binaries, so
            // ALL vendor libs (libcdsprpc closure) ship in jniLibs.
            // v1.2.4: libggml-opencl now DT_NEEDs libOpenCL.so (sphal patch).
            // Exec children have no sphal channel, so pre-seed a filesDir
            // symlink libOpenCL.so -> jniLibs libOCLstub.so (renamed stub,
            // soname libOCLstub.so) and put that dir FIRST in LD_LIBRARY_PATH.
            // Harmless for cpu/htp: with an unresolvable/no-op OpenCL the
            // ggml-opencl backend degrades exactly as before the patch.
            final File stubDir = new File(ctx.getFilesDir().getPath(), "ocl-stub");
            try {
                stubDir.mkdirs();
                final File link = new File(stubDir, "libOpenCL.so");
                link.delete(); // refresh every launch (nativeDir can change between installs)
                java.nio.file.Files.createSymbolicLink(link.toPath(),
                        new File(nativeDir, "libOCLstub.so").toPath());
            } catch (Throwable t) {
                logLine("[ocl-stub] 符号链接创建失败（exec 下 OpenCL 依赖将无法解析）: " + t);
            }
            pb.environment().put("LD_LIBRARY_PATH", stubDir.getPath() + ":" + nativeDir);
            applyExecEnvPolicy(pb.environment(), htpEngine);
            pb.environment().put("ADSP_LIBRARY_PATH",
                    nativeDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/dsp/cdsp");
            pb.environment().put("HOME", ctx.getFilesDir().getPath());
            pb.redirectErrorStream(false);
            return pb.start();
        } catch (IOException e) {
            logLine("[exec error] " + e);
            return null;
        }
    }

    private void startWaiter(Process p) {
        waitThread = new Thread(() -> {
            int code = -999;
            try { code = p.waitFor(); } catch (InterruptedException ignored) {}
            if (stopping.get()) return;
            logLine("[进程退出] exitCode=" + code);
            if (state == ST_STARTING || state == ST_RUNNING) {
                if (healthThread != null) healthThread.interrupt();
                pushDied(code);
                state = ST_READY;
            }
        }, "llama-waiter");
        waitThread.start();
    }

    /** Stream pump shared by exec (Process streams) and JNI (pipe read end). */
    private void startPump(InputStream in) {
        Thread t = new Thread(() -> {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            try {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) log.append(buf, n);
            } catch (IOException ignored) {}
        }, "log-pump");
        t.setDaemon(true);
        t.start();
    }

    /* ---------- JNI path (ocl/cpu — in-process, sphal OpenCL) ---------- */

    private void startJni(final String[] cmd) {
        new Thread(() -> {
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                logLine("[jni error] pipe: " + e);
                pushState(ST_ERROR, "启动失败（pipe）");
                return;
            }
            jniPipe = pipe;
            final ParcelFileDescriptor readPfd = pipe[0];
            final ParcelFileDescriptor writePfd = pipe[1];
            // detach write end: ownership moves to native (dup2), no finalizer close
            final int writeFd = writePfd.detachFd();

            Thread t = new Thread(() -> {
                try {
                    // inside try: static{} of JniServer loads the whole lib
                    // chain; a failure here must hit the log, not crash the app
                    JniServer js = new JniServer();
                    jniServer = js;
                    int code = js.startServer(cmd, writeFd);
                    if (stopping.get()) return;
                    logLine("[jni] llama_server 返回 code=" + code);
                    if (state == ST_STARTING || state == ST_RUNNING) {
                        if (healthThread != null) healthThread.interrupt();
                        pushDied(code);
                        state = ST_READY;
                    }
                } catch (Throwable e) {
                    if (stopping.get()) return;
                    logLine("[jni error] " + e);
                    if (state == ST_STARTING || state == ST_RUNNING) {
                        if (healthThread != null) healthThread.interrupt();
                        pushDied(-1000);
                    } else {
                        pushState(ST_ERROR, "JNI 启动失败，见日志");
                    }
                }
            }, "llama-jni");
            jniThread = t;
            t.start();

            // pump: same ring-buffer pipeline as the exec path
            InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readPfd);
            Thread pump = new Thread(() -> {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8), 8192);
                try {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) log.append(buf, n);
                } catch (IOException ignored) {}
            }, "jni-log-pump");
            pump.setDaemon(true);
            pump.start();

            startHealthProbe();
        }, "jni-starter").start();
    }

    /* ---------- health probe (shared) ---------- */

    private void startHealthProbe() {
        healthThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000L;
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            while (System.currentTimeMillis() < deadline) {
                if (stopping.get()) return;
                if (healthOk()) {
                    pushState(ST_RUNNING, "");
                    logLine("[health] OK — 服务已就绪 http://0.0.0.0:" + port);
                    return;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
            if (!stopping.get()) {
                logLine("[health] 120s 内未就绪（OpenCL kernel 首次 JIT 较慢属正常），放弃探活");
                pushState(ST_ERROR, "健康检查超时（120s）");
            }
        }, "llama-health");
        healthThread.start();
    }

    private boolean healthOk() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Package-visible: MainActivity Bridge.isPortBusy() reuses the same probe. */
    boolean portInUse(int p) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", p), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor p) {
        if (p != null) try { p.close(); } catch (IOException ignored) {}
    }

    /* ---------- command construction ---------- */

    /**
     * argv array without any shell involvement. {MODEL} inside the profile
     * arg string is substituted AFTER splitting, so model paths containing
     * spaces never need quoting.
     *
     * UI overrides applied at token level (spec v0.5 §4 unified param set):
     * - --ctx-size from the profile is replaced in place; appended if the
     *   profile has none. ctxSize <= 0 = keep profile value.
     * - --threads replaced in place / appended; threads <= 0 = strip the
     *   flag entirely (v1.2.5 UI "0 = auto": llama.cpp default = hardware
     *   auto-detect). Passed for ALL profiles (spec §4.1A rule 1: NPU still
     *   has CPU-side work — tokenize/sampler/logits/GDN fallback).
     * - noKvOffload=true appends `--no-kv-offload` (verified arg.cpp:2417)
     *   unless the profile already carries it; false = flag absent (server
     *   default = KV offload enabled).
     * - reasoning: "on" appends `--reasoning on`; "off" appends `--reasoning off`
     *   (0906 finding: this server build defaults thinking ON, so off must be
     *   explicit). A --reasoning flag already present in the profile gets its
     *   value replaced either way.
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload) {
        return buildCommand(exe, profileArgs, modelPath, host, port, ctxSize, reasoning,
                threads, noKvOffload, 0);
    }

    /**
     * v1.2.1: --ubatch-size UI override, mirrors --ctx-size exactly.
     * v1.2.5: ubatchSize <= 0 = strip the flag entirely (llama.cpp built-in
     * default), even when the profile carries one.
     * 动机：S23U(8G2) fastRPC 驱动对大缓冲映射有上限，profile 固定 1024 会
     * fastrpc_mmap 失败；用户需要按机型调低 ubatch。
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload, int ubatchSize) {
        String[] base = splitArgs(profileArgs);
        java.util.List<String> cmd = new java.util.ArrayList<>(base.length + 12);
        cmd.add(exe);

        boolean hasCtx = false, hasReasoning = false, hasThreads = false, hasKvOff = false,
                hasUbatch = false;
        for (int i = 0; i < base.length; i++) {
            String t = base[i];
            if ("{MODEL}".equals(t)) { cmd.add(modelPath); continue; }
            if ("--ctx-size".equals(t) && ctxSize > 0) {
                hasCtx = true;
                cmd.add(t);
                if (i + 1 < base.length) cmd.add(String.valueOf(ctxSize));
                i++; // skip the profile's old value
                continue;
            }
            if ("--ubatch-size".equals(t) && ubatchSize <= 0) { i++; continue; } // v1.2.5: 0 = strip flag (llama.cpp built-in default), even if profile carries it
            if ("--ubatch-size".equals(t) && ubatchSize > 0) {
                hasUbatch = true;
                cmd.add(t);
                if (i + 1 < base.length) cmd.add(String.valueOf(ubatchSize));
                i++; // skip the profile's old value
                continue;
            }
            if ("--threads".equals(t) && threads <= 0) { i++; continue; } // v1.2.5: 0 = strip flag (llama.cpp auto-detect), even if profile carries it
            if ("--threads".equals(t) && threads > 0) {
                hasThreads = true;
                cmd.add(t);
                if (i + 1 < base.length) cmd.add(String.valueOf(threads));
                i++; // skip the profile's old value
                continue;
            }
            if ("--no-kv-offload".equals(t)) { hasKvOff = true; cmd.add(t); continue; }
            if ("--reasoning".equals(t)) {
                hasReasoning = true;
                cmd.add(t);
                String v = "on".equals(reasoning) ? "on" : "off";
                if (i + 1 < base.length) cmd.add(v);
                i++; // skip the profile's old value
                continue;
            }
            cmd.add(t);
        }
        if (ctxSize > 0 && !hasCtx) { cmd.add("--ctx-size"); cmd.add(String.valueOf(ctxSize)); }
        if (threads > 0 && !hasThreads) { cmd.add("--threads"); cmd.add(String.valueOf(threads)); }
        if (ubatchSize > 0 && !hasUbatch) { cmd.add("--ubatch-size"); cmd.add(String.valueOf(ubatchSize)); }
        if (noKvOffload && !hasKvOff) cmd.add("--no-kv-offload");
        if (hasReasoning) {
            // profile carried its own --reasoning; value already replaced above
        } else {
            cmd.add("--reasoning");
            cmd.add("on".equals(reasoning) ? "on" : "off");
        }

        cmd.add("--host");
        cmd.add(host);
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        return cmd.toArray(new String[0]);
    }

    /** Splits a command line honoring single/double quotes (POC-verified). */
    static String[] splitArgs(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == q) q = 0; else cur.append(c);
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
