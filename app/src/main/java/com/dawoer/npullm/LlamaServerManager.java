package com.dawoer.npullm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    /** vc88: watchdog 感知 worker 模式所需的状态读取口（包可见，无 setter）。 */
    int stateCode() { return state; }
    boolean isIdleOrError() { return state == ST_READY || state == ST_ERROR; }
    /** v1.3.7: STARTING is a legitimate phase for the FGS — model load takes
     *  seconds, and ServerService.onStartCommand used to self-stop in that
     *  window (the "notification never shows" bug). */
    boolean isStarting() { return state == ST_STARTING; }
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
    private volatile BmoeAdapter bmoe;          // v1.4.0: bmoe --session path
    private volatile BmoeServiceManager bmoeSvc; // vc105: bmoe 对外服务（bridge→CLI 两进程）
    private volatile ParcelFileDescriptor[] jniPipe;
    private volatile Thread jniThread;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile int state = ST_READY;
    private int port = 8080;
    // last-applied sampling params (spec §4: request-level, live-updatable
    // via setSampling; statusJson echoes them so the CLI page can restore UI)
    private volatile String lastProfile = "htp";
    // vc132: remember the 对外开放 choice of the current/last exec so statusJson
    // can tell the UI which host form to display (0.0.0.0 run → LAN IP, else
    // 127.0.0.1). Pure display metadata; the bind host itself stays L535.
    private volatile boolean lastPublicOn = false;
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

    /* ---------- v1.4.0: bmoe adapter callbacks (state + chat UI + FGS) ---------- */

    void pushBmoeState(int s, String msg) {
        if (stopping.get()) return;
        pushState(s, msg == null ? "" : msg);
    }

    void pushBmoeDied(int exitCode) {
        if (stopping.get()) return;
        logLine("[bmoe] 会话结束 exitCode=" + exitCode);
        bmoe = null;
        if (state == ST_STARTING || state == ST_RUNNING) {
            pushDied(exitCode);
            state = ST_READY;
            Notifier n = notifier;
            if (n != null) n.onState(ST_READY, "");
        }
    }

    void pushBmoeChunk(String t) { BmoeAdapter.UiHook.chunk(t); }

    void pushBmoeReasoning(String t) {
        // vc158: reasoning 走独立通道 → window.onChatReasoning（原先塞进 chunk 流，
        // 思考文本因此进了聊天气泡、chat.db 和 RAG 索引）。
        if (t == null || t.isEmpty()) return;
        BmoeAdapter.UiHook.reasoning(t);
    }

    void pushBmoeGenDone(String status, String statsJson) {
        BmoeAdapter.UiHook.done(status, statsJson);
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
            return "--load-mode none -m {MODEL} --ctx-size 4096 --threads 4";
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

    /** v1.4.0: profiles.json "engine" field — "bmoe" routes to the BmoeAdapter
     *  (ProcessBuilder --session line protocol), default "llama" (HTTP server). */
    private String profileEngine(String profileId) {
        loadProfiles();
        try {
            return profiles.getJSONObject(profileId).optString("engine", "llama");
        } catch (JSONException e) {
            return "llama";
        }
    }

    /** Package-private: chat flow checks whether the bmoe engine owns the session. */
    synchronized boolean isBmoeRunning() {
        return bmoe != null && (state == ST_RUNNING || state == ST_STARTING);
    }

    synchronized BmoeAdapter bmoeAdapter() { return bmoe; }

    /* ---------- public API ---------- */

    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload) {
        start(modelPath, profileId, newPort, ctxSize, reasoning, threads, noKvOffload, 0, 0);
    }

    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload, final int ubatchSize) {
        start(modelPath, profileId, newPort, ctxSize, reasoning, threads, noKvOffload,
                ubatchSize, 0);
    }

    /* ---------- vc99 C2: slot save/restore ---------- */

    /**
     * The directory handed to llama-server as --slot-save-path. Created
     * EAGERLY: arg.cpp rejects the flag when the directory does not exist
     * (fs_is_directory check at startup) — getExternalFilesDir(null) with
     * mkdirs guarantees it on every profile, every launch.
     */
    static File slotsDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "slots");
        if (!d.exists()) d.mkdirs();
        return d;
    }


    /**
     * vc99 C1: scan a model directory for an mmproj GGUF (name contains
     * "mmproj"). Pure file logic — JVM-testable. Returns the absolute path of
     * the largest match (f16 preferred over quants), or null.
     */
    static String findMmproj(File dir) {
        if (dir == null || !dir.canRead()) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        String best = null;
        long bestLen = -1;
        for (File k : kids) {
            String n = k.getName().toLowerCase(java.util.Locale.US);
            if (n.endsWith(".gguf") && n.contains("mmproj") && k.length() > bestLen) {
                best = k.getAbsolutePath();
                bestLen = k.length();
            }
        }
        return best;
    }

    /** Chat-page gate: last server launch carried --slot-save-path. */
    synchronized boolean slotSaveActive() { return lastSlotSave; }
    private volatile boolean lastSlotSave;

    /** vc104: last server launch carried --api-key (null = auth off). */
    synchronized boolean apiKeyActive() { return lastApiKey != null && !lastApiKey.isEmpty(); }
    /** Plain key for Java-side local calls only (Bearer header). Never leaves the app. */
    synchronized String apiKeyPlain() { return lastApiKey; }
    private volatile String lastApiKey;

    /** vc119: UI KV quant choice for -ctk/-ctv ("f16"|"q8_0"|"q4_0"). */
    private volatile String lastKvSel = "q8_0";
    /** vc119: UI power mode for NPU/MTP ("" = engine default burst, no env). */
    private volatile String lastPowerMode = "";

    /** vc119: legal GGML_HEXAGON_POWER_MODE values (llama.cpp #30423, v150). */
    static final String[] POWER_MODES = {
            "burst", "sustained_high_performance", "high_performance", "balanced",
            "low_balanced", "high_power_saver", "power_saver", "low_power_saver"};

    /** vc119: only these values are accepted for the power-mode selector. */
    static boolean isPowerMode(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String m : POWER_MODES) if (m.equals(s)) return true;
        return false;
    }

    /** vc119: legal -ctk/-ctv values exposed by the UI (anything else -> q8_0). */
    static boolean isKvSel(String s) {
        return "f16".equals(s) || "q8_0".equals(s) || "q4_0".equals(s);
    }

    /**
     * POST /slots/{id}?action=save|restore|erase (verified against the v1.4.2
     * baseline: server.cpp:296-297 routes, handle_slots_save/restore read
     * {"filename":...} from the body, erase takes no body; endpoint_slots
     * defaults ON in common.h:677 — only --slot-save-path had to be added).
     * Returns the raw server JSON (ok or error) so the WebView can toast it.
     */
    String slotAction(Context c, String action, String filename) {
        String act = "save".equals(action) || "restore".equals(action) || "erase".equals(action)
                ? action : null;
        if (act == null) return "{\"error\":\"bad action\"}";
        if (!slotSaveActive()) return "{\"error\":\"server not started with slots\"}";
        if (state != ST_RUNNING) return "{\"error\":\"server not running\"}";
        try {
            StringBuilder u = new StringBuilder("http://127.0.0.1:").append(port)
                    .append("/slots/0?action=").append(act);
            HttpURLConnection conn = (HttpURLConnection) new URL(u.toString()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(15000); // save of a 32K-context slot can take a moment
            // vc104: local server may require Bearer auth (--api-key).
            if (apiKeyActive()) conn.setRequestProperty("Authorization", "Bearer " + apiKeyPlain());
            if (!"erase".equals(act)) {
                if (filename == null || filename.isEmpty()) return "{\"error\":\"filename required\"}";
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject();
                body.put("filename", filename);
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            if (is != null) while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            try { is.close(); } catch (Exception ignored) {}
            String out = bo.toString("UTF-8");
            return out == null || out.isEmpty()
                    ? "{\"error\":\"HTTP " + code + "\"}"
                    : out;
        } catch (Exception e) {
            return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /**
     * vc91: sleepIdleSeconds > 0 → `--sleep-idle-seconds N` (server destroys
     * model+KV after the idle timeout; the next request triggers a reload).
     * vc99: slotsOn → --slot-save-path &lt;filesDir&gt;/slots (dir pre-created;
     * slot endpoints default on — common.h:677 endpoint_slots=true).
     */
    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload, final int ubatchSize,
                            final int sleepIdleSeconds) {
        start(modelPath, profileId, newPort, ctxSize, reasoning, threads,
                noKvOffload, ubatchSize, sleepIdleSeconds, "q8_0", "", false, false, null, null);
    }

    synchronized void start(final String modelPath, final String profileId, final int newPort,
                            final int ctxSize, final String reasoning,
                            final int threads, final boolean noKvOffload, final int ubatchSize,
                            final int sleepIdleSeconds, final String kvSel,
                            final String powerMode, final boolean slotsOn,
                            final boolean publicOn, final String apiKey,
                            final String extraArgs) {
        if (state == ST_STARTING || state == ST_RUNNING) return;
        port = newPort;
        lastProfile = profileId;
        // vc132: display-source for the endpoint card (publicOn ⇒ LAN IP form).
        lastPublicOn = publicOn;
        // vc119: remember both new selectors (bmoe returns early below, so the
        // llama-server paths can rely on these being set before buildCommand).
        lastKvSel = isKvSel(kvSel) ? kvSel : "q8_0";
        lastPowerMode = isPowerMode(powerMode) ? powerMode : "";
        // vc104: remember the key for local Java->server calls (Bearer auth)
        // and the CLI page. Never logged, never echoed into the WebView.
        lastApiKey = (apiKey == null || apiKey.isEmpty()) ? null : apiKey;
        log.clear();
        stopping.set(false);

        // v1.4.0: bmoe engine — vc105 起改走对外服务形态：APK 拉起
        // bmoe-http-bridge（OpenAI /v1 + /health，Bearer 鉴权对齐 vc104），
        // 由 bridge spawn libbmoecli.so --session。服务页选 MoE 模型点
        // 「启动服务」即此路；共享参数区（端口/上下文/线程/对外开放/Key/思考）
        // 全部透传，无 bmoe 专属参数。端口占用检查保留。
        if ("bmoe".equals(profileEngine(profileId))) {
            // v1.4.0(vc84): UI may hand us a picker tag ("__bmoe__<name>") or a
            // bare models-dir name — resolve to the real /data path BEFORE argv
            // (vc82 S25: raw tag reached libbmoecli -m → ENOENT, exitCode=1).
            final String mpath = BmoeModelManager.resolveModelPath(ctx, modelPath);
            if (mpath == null) {
                logLine("[启动前检查] bmoe 模型无法解析或不存在: " + modelPath);
                pushState(ST_ERROR, "模型文件不存在，请在 BigMoE 卡重新选用或导入");
                return;
            }
            // vc113/A3: MoE 直启走 bmoe 引擎（纯 CPU），无 HTP 量化问题
            // —— 预检只挂在下面 NPU/MTP 分支；这里保持原语义
            if (portInUse(newPort)) {
                logLine("[启动前检查] 端口 " + newPort + " 已被占用（非本 APP 进程），请换一个端口");
                pushState(ST_ERROR, "端口 " + newPort + " 已被占用");
                return;
            }
            pushState(ST_STARTING, "正在启动…");
            lastSlotSave = false; // bmoe 无 slots 端点，清上轮残留防 Chat 页 slot 误显
            BmoeServiceManager.Cfg cfg = new BmoeServiceManager.Cfg(
                    mpath, newPort, ctxSize, threads, !"off".equals(reasoning),
                    publicOn, lastApiKey);
            bmoeSvc = new BmoeServiceManager();
            bmoeSvc.start(cfg, new BmoeSvcHost(this)); // static 嵌套：本机 d8 对含 this$0 的类崩 NPE（vc105 复现矩阵定案）
            return;
        }

        if (portInUse(newPort)) {
            logLine("[启动前检查] 端口 " + newPort + " 已被占用（非本 APP 进程），请换一个端口");
            pushState(ST_ERROR, "端口 " + newPort + " 已被占用");
            return;
        }

        // vc113/A3: NPU/MTP preflight — deep GGUF probe (KV + tensor dir, never
        // tensor bytes) before any process spawn. A non-whitelisted quant only
        // produces a silent CPU fallback at load; a non-MTP model under MTP
        // profile dies later with a raw engine error. Both now fail fast in
        // plain language (Ace3P 1013: user burned a session discovering this).
        final boolean npuProfile = "htp".equals(profileId) || "mtp".equals(profileId);
        if (npuProfile && "llama".equals(profileEngine(profileId))) {
            final String mpath2 = resolveModelPath2(modelPath);
            if (mpath2 != null) {
                StringBuilder quantErr = new StringBuilder();
                GgufProbe.Result pr = GgufProbe.probe(new java.io.File(mpath2), quantErr);
                if (pr.parsed) {
                    if ("mtp".equals(profileId) && !pr.hasMtp) {
                        logLine("[预检] MTP 档但模型无 blk.*.nextn 张量（非 MTP 版本）");
                        pushState(ST_ERROR, "此模型不是 MTP 版本——请选用文件名带 MTP 的 GGUF，或改用 NPU 档");
                        return;
                    }
                    if (!GgufProbe.npuQuantSupported(pr.ftype)
                            || (pr.ftype <= 0 && quantErr.length() > 0 && !GgufProbe.npuQuantSupported(-1))) {
                        String qn = pr.ftype > 0 ? ftypeName((int) pr.ftype)
                                : (quantErr.length() > 0 ? quantErr.toString() : "未知量化");
                        logLine("[预检] 量化 " + qn + " 不在 NPU 白名单（"
                                + GgufProbe.NPU_QUANT_NAMES + "）");
                        pushState(ST_ERROR, "量化格式 " + qn
                                + " 不支持 NPU——请换 Q4_0 或 Q4_K_M 量化，或改用 CPU/GPU 档");
                        return;
                    }
                    logLine("[预检] NPU 档量化 OK (" + (pr.ftype > 0 ? ftypeName((int) pr.ftype) : "按张量核验通过") + ")");
                } else {
                    logLine("[预检] GGUF 头无法解析，跳过 NPU 预检（交引擎自行处理）");
                }
            }
        }
        final String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        // v0.4.1 fix: argv[0] MUST be an absolute path — profileBinary() only
        // returns the bare jniLibs filename.
        final String exePath = isJniProfile(profileId)
                ? nativeDir + "/libllmoclsrv.so"
                : nativeDir + "/" + profileBinary(profileId);
        lastSlotSave = slotsOn;
        String[] cmd = buildCommand(exePath,
                profileArgs(profileId), modelPath,
                publicOn ? "0.0.0.0" : "127.0.0.1", newPort, ctxSize, reasoning,
                threads, noKvOffload, ubatchSize, sleepIdleSeconds,
                slotsOn ? slotsDir(ctx).getAbsolutePath() : null, extraArgs,
                lastKvSel, lastPowerMode);
        // vc104: opt-in local API key — llama.cpp `--api-key KEY` (arg.cpp
        // "KEY", repeatable). Non-empty (after trim) only when the UI switch
        // is on. bmoe never reaches buildCommand (own argv builder).
        String apiKeyArg = lastApiKey == null ? "" : lastApiKey.trim();
        if (!apiKeyArg.isEmpty()) {
            String[] cmd2 = new String[cmd.length + 2];
            System.arraycopy(cmd, 0, cmd2, 0, cmd.length);
            cmd2[cmd.length] = "--api-key";
            cmd2[cmd.length + 1] = apiKeyArg;
            cmd = cmd2;
            logLine("[auth] 已启用本地服务 Key（请求需携带 Authorization: Bearer）");
        }
        // vc99 C1: auto-detect an mmproj next to the model (same dir, name
        // contains "mmproj", .gguf). Without it llama-server rejects all
        // image/audio/video input ("provide the mmproj" hint) — the C1 chat
        // attachments would never work end-to-end. Largest file wins when
        // several variants sit together (f16 > quantized = safest default).
        String mmproj = findMmproj(new File(modelPath).getParentFile());
        if (mmproj != null) {
            String[] cmd2 = new String[cmd.length + 2];
            System.arraycopy(cmd, 0, cmd2, 0, cmd.length);
            cmd2[cmd.length] = "--mmproj";
            cmd2[cmd.length + 1] = mmproj;
            cmd = cmd2;
            logLine("[mmproj] 检测到多模态投影: " + mmproj);
        }
        pushState(ST_STARTING, "正在启动…");
        logLine("[cmd] " + join(cmd));
        logLine("[mode] " + (isJniProfile(profileId) ? "JNI in-process (OpenCL)" : "exec 子进程 (Hexagon NPU / CPU / MTP)"));

        if (isJniProfile(profileId)) {
            startJni(cmd);
        } else {
            // v1.2.4: pass engine kind down — non-htp exec children must
            // never touch the Hexagon driver (S23U CPU death, see launchExec).
            // vc89: mtp 档同为 HTP 通路（--device HTP0 + draft-mtp），exec env 必须放行 NPU 枚举
            startExec(cmd, "htp".equals(profileId) || "mtp".equals(profileId), lastPowerMode);
        }
    }

    synchronized void stop() {
        if (state == ST_READY || state == ST_ERROR) return;
        // adopted orphan has no Process handle — kill the port owner by name
        if (proc == null && jniServer == null) killOrphanByPort();
        stopping.set(true);
        pushState(ST_READY, "已停止");
        BmoeAdapter b = bmoe;
        if (b != null) { b.stop(); bmoe = null; }
        BmoeServiceManager bs = bmoeSvc;
        if (bs != null) { bs.stop(); bmoeSvc = null; }
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
            // vc132: endpoint display form — true ⇒ 0.0.0.0 bind (LAN IP shown)
            j.put("publicOn", lastPublicOn);
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
        startExec(cmd, htpEngine, "");
    }

    private void startExec(final String[] cmd, final boolean htpEngine, final String powerMode) {
        final String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        // v0.4.1: File.canExecute() can return false on some ROMs for the
        // app's own nativeLibraryDir even though exec succeeds (verified:
        // llama-npu.apk v1.1 ran NPU inference). Warn, never block — the
        // real verdict is ProcessBuilder's IOException.
        if (!new File(cmd[0]).canExecute()) {
            logLine("[警告] canExecute=false（ROM 视图差异，不拦截）: " + cmd[0]);
        }
        new Thread(() -> {
            Process p = launchExec(cmd, nativeDir, htpEngine, powerMode);
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
     * CPU included. GGML_HEXAGON_DEVICES=none + GGML_HEXAGON_ARCH=79 = zero
     * devices. Universal recipe, zero per-SoC branches: upstream 58367713a
     * unconditionally forces ndev=1 on Android for opt_arch<75 (covers every
     * pre-v75 Snapdragon and autodetect-failure), so "none" alone is not
     * enough; ARCH=79 skips that force. Legacy GGML_HEXAGON_NDEV is dead
     * (deprecated; "0" clamped back to 1) — never use it again.
     * Verified 0925 smoke: 0.6B/4B CPU-only clean output.
     * htp children stay untouched so NPU enumeration works as before.
     *
     * vc69 htp experiment hook: htp children get an optional env overlay
     * read from Download/npullm-htp-env.json — flat
     * {"GGML_HEXAGON_OPBATCH":"8192",...}. Purpose: tune Hexagon
     * dispatch/thermal knobs (OPBATCH/OPQUEUE/OPPOLL/PROFILE/VERBOSE…)
     * WITHOUT rebuilding the APK: edit file, restart server. Missing file,
     * empty object or malformed JSON = silent no-op (run defaults).
     * Non-htp children must never see the overlay (S23U poison, see above).
     * vc98 DMA64 pin: htp children get GGML_HEXAGON_DMA64=0. Upstream 0c3626ec06
     * (in since the v1.4.0 baseline) enables 64-bit DMA extended mapping for
     * opt_arch>79; devices shipping an older vendor libcdsprpc.so then fail
     * fastrpc_mmap with 0x0000000e (EFAULT) on first decode = hard crash
     * (issue #1, 8E5/SM8850). Pinning the env leaves <=79 unaffected (the
     * engine ignores the flag there) and restores the v1.3.8 mapping path on
     * >79 with no measured perf loss. The htp overlay JSON still wins
     * (escape hatch for future SoCs whose cdsp is new enough, until upstream
     * adds probing/fallback).
     */
    static void applyExecEnvPolicy(final java.util.Map<String, String> env,
                                   final boolean htpEngine) {
        applyExecEnvPolicy(env, htpEngine, null);
    }

    static void applyExecEnvPolicy(final java.util.Map<String, String> env,
                                   final boolean htpEngine,
                                   final String htpEnvJson) {
        if (!htpEngine) {
            env.put("GGML_HEXAGON_DEVICES", "none");
            env.put("GGML_HEXAGON_ARCH", "79");
            return;
        }
        // vc98 DMA64 pin (issue #1): see javadoc. Overlay entries below may
        // still override (a GGML_HEXAGON_DMA64 in the JSON wins).
        env.put("GGML_HEXAGON_DMA64", "0");
        if (htpEnvJson == null) return;
        try {
            JSONObject o = new JSONObject(htpEnvJson);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k == null || k.isEmpty()) continue;
                Object v = o.opt(k);
                if (v == null || v == JSONObject.NULL) continue;
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty()) env.put(k, s);
                } else if (v instanceof Number || v instanceof Boolean) {
                    env.put(k, String.valueOf(v));
                }
            }
        } catch (Exception ignored) {
            // malformed overlay → run with defaults; never block startup
        }
    }

    /** vc69: htp-only env overlay file (see applyExecEnvPolicy overload). */
    static final String HTP_ENV_OVERLAY = "/storage/emulated/0/Download/npullm-htp-env.json";

    private String readHtpEnvOverlay() {
        try {
            File f = new File(HTP_ENV_OVERLAY);
            if (!f.isFile()) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            String s = bo.toString("UTF-8").trim();
            if (s.isEmpty()) return null;
            logLine("[htp-env] overlay 已加载: " + s);
            return s;
        } catch (Throwable t) {
            logLine("[htp-env] overlay 读取失败（忽略，按默认跑）: " + t);
            return null;
        }
    }

    private Process launchExec(String[] cmd, String nativeDir, boolean htpEngine) {
        return launchExec(cmd, nativeDir, htpEngine, "");
    }

    private Process launchExec(String[] cmd, String nativeDir, boolean htpEngine,
                               final String powerMode) {
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
            // vc118 批1（A17 热修）：捆绑 libc++.so（旧 NDK）按 soname 遮蔽系统库，
            // A17 的 liblog 需要 __hash_memory → CANNOT LINK。stub 目录追加
            // libc++.so -> /system/lib64/libc++.so，系统新库按 soname 胜出。
            // 独立 try/catch：失败只记日志不阻断（旧系统本就有可用的捆绑 libc++）。
            try {
                java.util.List<String> sl = new java.util.ArrayList<>();
                int n = SysLibStubs.ensure(stubDir, new String[]{"libc++.so"}, "/system/lib64", sl);
                for (String line : sl) logLine(line);
                if (n > 0) logLine("[syslib-stub] htp 路径 libc++.so 已指向系统库（A17 遮蔽修复）");
            } catch (Throwable t) {
                logLine("[syslib-stub] libc++.so symlink 创建失败（维持捆绑库解析，旧系统不受影响）: " + t);
            }
            pb.environment().put("LD_LIBRARY_PATH", stubDir.getPath() + ":" + nativeDir);
            // vc97: some OEM package managers silently skip non-"lib*" APK
            // entries (Samsung Android14, Ace3P 8Gen3):
            // vendor.qti.hardware.dsp-V1-ndk.so never lands in nativeDir ->
            // libcdsprpc DT_NEED breaks -> HTP enumerates empty ("invalid
            // device"). Self-extract it into filesDir/vqti and prepend to the
            // search path. No-op on devices whose PM already extracted it.
            try {
                File vqInNative = new File(nativeDir, "vendor.qti.hardware.dsp-V1-ndk.so");
                if (!vqInNative.canRead()) {
                    File vqApk = new File(ctx.getApplicationInfo().sourceDir);
                    StringBuilder vqErr = new StringBuilder(); // vc113/A2: surface the cause
                    File vqDir = extractVqti(vqApk,
                            new File(ctx.getFilesDir().getPath(), "vqti"),
                            "lib/arm64-v8a/vendor.qti.hardware.dsp-V1-ndk.so",
                            vqApk.lastModified(), vqErr);
                    if (vqDir != null) {
                        pb.environment().put("LD_LIBRARY_PATH",
                                stubDir.getPath() + ":" + vqDir.getPath() + ":" + nativeDir);
                        logLine("[vqti] vendor.qti DSP 库已自解: " + vqDir.getPath());
                    } else {
                        logLine("[vqti] vendor.qti DSP 库自解不可用（"
                                + (vqErr.length() == 0 ? "unknown" : vqErr.toString())
                                + "）——此类机型 NPU 档可能 invalid device");
                    }
                }
            } catch (Throwable t) {
                logLine("[vqti] 自解异常: " + t);
            }
            // vc69: htp children get the optional Download/npullm-htp-env.json
            // overlay (thermal/dispatch tuning without APK rebuild); non-htp
            // must never see it (Hexagon poison on session-less devices).
            applyExecEnvPolicy(pb.environment(), htpEngine,
                    htpEngine ? readHtpEnvOverlay() : null);
            // vc119: UI power-mode selector (NPU/MTP only, the htpEngine flag
            // covers exactly those two profiles). #30423: legal values apply
            // silently, illegal ones WARN + fall back to burst; empty string
            // = engine default (burst), no env set.
            if (htpEngine && isPowerMode(powerMode)) {
                pb.environment().put("GGML_HEXAGON_POWER_MODE", powerMode);
                logLine("[power] GGML_HEXAGON_POWER_MODE=" + powerMode);
            }
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

    /**
     * vc97: extract one non-"lib*" entry from the APK that some OEM package
     * managers silently skip (Samsung Android14, Ace3P 8Gen3). libcdsprpc
     * DT_NEEDs this lib; without it HTP device enumeration comes up empty
     * ("invalid device"). Idempotent; re-extracts when apkStamp (sourceDir
     * mtime) advances past the extracted copy. Returns outDir on success,
     * null when the entry is missing or extraction fails.
     */
    static File extractVqti(File apkFile, File outDir, String entryName, long apkStamp) {
        return extractVqti(apkFile, outDir, entryName, apkStamp, null);
    }

    /**
     * vc113/A2: fail-cause variant — errRef (when non-null) receives a short
     * reason: "mkdirs-failed:<path>" | "no-entry:<name>" | "rename-failed"
     * | "io:<throwable>". vc97 swallowed every failure into null and the
     * caller could only print 自解不可用 with zero cause (Ace3P 1013 blind
     * spot: a plain missing-directory error was indistinguishable from a
     * broken APK).
     */
    static File extractVqti(File apkFile, File outDir, String entryName, long apkStamp, StringBuilder errRef) {
        final File target = new File(outDir, entryName.substring(entryName.lastIndexOf('/') + 1));
        if (target.canRead() && target.lastModified() >= apkStamp) return outDir;
        java.util.zip.ZipFile apk = null;
        try {
            // vc113/A2 root fix: outDir was never created — a first-ever run
            // hit FileNotFoundException (FileOutputStream on a missing dir) and
            // the catch turned it into null = 自解永远不可用. S25 never saw
            // this path (its PM extracts everything); Ace3P-class devices did.
            if (!outDir.isDirectory() && !outDir.mkdirs() && !outDir.isDirectory()) {
                if (errRef != null) errRef.append("mkdirs-failed:").append(outDir.getPath());
                return null;
            }
            apk = new java.util.zip.ZipFile(apkFile);
            java.util.zip.ZipEntry e = apk.getEntry(entryName);
            if (e == null) {
                if (errRef != null) errRef.append("no-entry:").append(entryName);
                return null;
            }
            final File tmp = new File(outDir, target.getName() + ".tmp");
            java.io.InputStream is = apk.getInputStream(e);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            is.close();
            tmp.setExecutable(true, false);
            if (!tmp.renameTo(target)) {
                tmp.delete();
                if (errRef != null) errRef.append("rename-failed");
                return null;
            }
            return outDir;
        } catch (Throwable t) {
            if (errRef != null) errRef.append("io:").append(String.valueOf(t));
            return null;
        } finally {
            if (apk != null) try { apk.close(); } catch (Throwable ignored) {}
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
     * - vc91 sleep-idle: sleepIdleSeconds > 0 appends `--sleep-idle-seconds N`
     *   (idle timeout → server destroys model+KV, RAM freed; next request
     *   auto-reloads). 0 = flag absent (server default disabled).
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload) {
        return buildCommand(exe, profileArgs, modelPath, host, port, ctxSize, reasoning,
                threads, noKvOffload, 0, 0, null);
    }

    /**
     * v1.2.1: --ubatch-size UI override, mirrors --ctx-size exactly.
     * v1.2.5: ubatchSize <= 0 = strip the flag entirely (llama.cpp built-in
     * default), even when the profile carries one.
     * 动机：S23U(8G2) fastRPC 驱动对大缓冲映射有上限，profile 固定 1024 会
     * fastrpc_mmap 失败；用户需要按机型调低 ubatch。
     * vc91: sleepIdleSeconds > 0 appends `--sleep-idle-seconds N` (server
     * destroys model+KV after the idle timeout; next request reloads).
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload, int ubatchSize,
                                 int sleepIdleSeconds) {
        return buildCommand(exe, profileArgs, modelPath, host, port, ctxSize, reasoning,
                threads, noKvOffload, ubatchSize, sleepIdleSeconds, null);
    }

    /**
     * vc99: slotSavePath != null appends `--slot-save-path &lt;dir&gt;` (dir must
     * already exist — arg.cpp fs_is_directory gate). Applies to every exec
     * profile; bmoe never reaches buildCommand (own argv builder).
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload, int ubatchSize,
                                 int sleepIdleSeconds, String slotSavePath) {
        return buildCommand(exe, profileArgs, modelPath, host, port, ctxSize, reasoning,
                threads, noKvOffload, ubatchSize, sleepIdleSeconds, slotSavePath, null,
                "q8_0", "");
    }

    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload, int ubatchSize,
                                 int sleepIdleSeconds, String slotSavePath, String extraArgs) {
        return buildCommand(exe, profileArgs, modelPath, host, port, ctxSize, reasoning,
                threads, noKvOffload, ubatchSize, sleepIdleSeconds, slotSavePath, extraArgs,
                "q8_0", "");
    }

    /**
     * vc121: kvSel ("f16"|"q8_0"|"q4_0") replaces the profile's -ctk/-ctv
     * pair; powerMode ("" = engine default burst) becomes
     * GGML_HEXAGON_POWER_MODE on NPU/MTP exec children only.
     * Flags always emitted upstream-valid: llama.cpp arg.cpp defines
     * -ctk/--cache-type-k (no "--ctk"); vc120's --ctk spelling died with
     * "error: invalid argument: --ctk" before model load.
     */
    static String[] buildCommand(String exe, String profileArgs, String modelPath,
                                 String host, int port, int ctxSize, String reasoning,
                                 int threads, boolean noKvOffload, int ubatchSize,
                                 int sleepIdleSeconds, String slotSavePath, String extraArgs,
                                 String kvSel, String powerMode) {
        if (!isKvSel(kvSel)) kvSel = "q8_0";
        String[] base = splitArgs(profileArgs);
        java.util.List<String> cmd = new java.util.ArrayList<>(base.length + 12);
        cmd.add(exe);

        boolean hasCtx = false, hasReasoning = false, hasThreads = false, hasKvOff = false,
                hasUbatch = false, hasSleepIdle = false, hasCtk = false;
        for (int i = 0; i < base.length; i++) {
            String t = base[i];
            if ("{MODEL}".equals(t)) { cmd.add(modelPath); continue; }
            if (("--ctk".equals(t) || "-ctk".equals(t)) && i + 1 < base.length) {
                hasCtk = true;
                cmd.add("-ctk"); cmd.add(kvSel); i++; continue; // vc121: upstream-valid short flag
            }
            if (("--ctv".equals(t) || "-ctv".equals(t)) && i + 1 < base.length) {
                cmd.add("-ctv"); cmd.add(kvSel); i++; continue; // vc121
            }
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
            // vc91: profile-carried --sleep-idle-seconds gets the UI value
            // replaced in place (>0) or stripped (0 = user disabled).
            if ("--sleep-idle-seconds".equals(t) && sleepIdleSeconds <= 0) { i++; continue; }
            if ("--sleep-idle-seconds".equals(t)) {
                hasSleepIdle = true;
                cmd.add(t);
                if (i + 1 < base.length) cmd.add(String.valueOf(sleepIdleSeconds));
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
        if (sleepIdleSeconds > 0 && !hasSleepIdle) {
            cmd.add("--sleep-idle-seconds"); cmd.add(String.valueOf(sleepIdleSeconds));
        }
        if (noKvOffload && !hasKvOff) cmd.add("--no-kv-offload");
        // vc121: profile without -ctk (e.g. cpu) gets the UI value appended (short form)
        if (!hasCtk) { cmd.add("-ctk"); cmd.add(kvSel); cmd.add("-ctv"); cmd.add(kvSel); }
        if (hasReasoning) {
            // profile carried its own --reasoning; value already replaced above
        } else {
            cmd.add("--reasoning");
            cmd.add("on".equals(reasoning) ? "on" : "off");
        }
        if (slotSavePath != null && slotSavePath.length() > 0) {
            cmd.add("--slot-save-path");
            cmd.add(slotSavePath);
        }
        // vc118 batch2: user-provided extra CLI args (advanced settings box).
        // Sanitized here (last line of defense — the UI also strips newlines):
        // \r/\n/\0 collapse to spaces so one textarea can never smuggle a second
        // argv element, a "--host/--port" override is dropped (they carry the
        // Java-side state contract; the notification card and endpoint UI both
        // echo them), and everything is appended BEFORE --host/--port so the
        // fixed tail always wins. splitArgs() handles the quoting.
        String extra = sanitizeExtraArgs(extraArgs);
        if (!extra.isEmpty()) {
            String[] more = splitArgs(extra);
            for (int i = 0; i < more.length; i++) {
                if ("--host".equals(more[i]) || "--port".equals(more[i])) { i++; continue; }
                cmd.add(more[i]);
            }
        }

        cmd.add("--host");
        cmd.add(host);
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        return cmd.toArray(new String[0]);
    }



    /**
     * vc118 batch2: normalize user-typed extra CLI args from the advanced
     * settings box. CR/\n/NUL collapse to spaces (a textarea must never be
     * able to smuggle a second argv element), the result is trimmed; empty
     * input yields "". Pure function, JVM-testable, no android.* deps.
     */
    static String sanitizeExtraArgs(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c == '\n' || c == '\r' || c == '\0') ? ' ' : c);
        }
        return sb.toString().trim();
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

    /**
     * vc105: bmoe 对外服务（bridge→CLI）的 Host 实现。必须是 STATIC 嵌套类——
     * 本机 d8 8.2.2-dev 对任何含 this$0 的类（非静态内部类/匿名类）崩 NPE
     * （vc105 最小复现矩阵定案：非静态内部类/匿名类崩，static 嵌套/lambda 过；
     * vc104 全库实现类均为 static 嵌套故从未触发）。
     */
    private static final class BmoeSvcHost implements BmoeServiceManager.Host {
        private final LlamaServerManager m;
        BmoeSvcHost(LlamaServerManager m) { this.m = m; }
        @Override public void bmoeLog(String line) { m.logLine(line); }
        @Override public void bmoeReady(String base) {
            m.logLine("[mode] bmoe 服务 (bridge → cli --session, OpenAI /v1, auth="
                    + (m.lastApiKey == null || m.lastApiKey.isEmpty() ? "off" : "on") + ")");
            m.logLine("[服务] OpenAI 端点 " + base + "/v1 · /health 探活 · " + m.port);
            m.pushState(ST_RUNNING, "");
        }
        @Override public void bmoeFailed(String why) {
            m.logLine("[bmoe] " + why);
            if (m.stopping.get()) return;
            m.pushState(ST_ERROR, why);
        }
    }

    /** vc113/A3: llama_ftype -> short name (values from include/llama.h L113-150, 1013). */
    static String ftypeName(int ftype) {
        switch (ftype) {
            case 0: return "F32";
            case 1: return "F16";
            case 2: return "Q4_0";
            case 3: return "Q4_1";
            case 7: return "Q8_0";
            case 8: return "Q5_0";
            case 9: return "Q5_1";
            case 10: return "Q2_K";
            case 11: return "Q3_K_S";
            case 12: return "Q3_K_M";
            case 13: return "Q3_K_L";
            case 14: return "Q4_K_S";
            case 15: return "Q4_K_M";
            case 16: return "Q5_K_S";
            case 17: return "Q5_K_M";
            case 18: return "Q6_K";
            case 19: return "IQ2_XXS";
            case 20: return "IQ2_XS";
            case 21: return "Q2_K_S";
            case 22: return "IQ3_XS";
            case 23: return "IQ3_XXS";
            case 24: return "IQ1_S";
            case 25: return "IQ4_NL";
            case 26: return "IQ3_S";
            case 27: return "IQ3_M";
            case 28: return "IQ2_S";
            case 29: return "IQ2_M";
            case 30: return "IQ4_XS";
            case 31: return "IQ1_M";
            case 32: return "BF16";
            case 36: return "TQ1_0";
            case 37: return "TQ2_0";
            case 38: return "MXFP4";
            case 39: return "NVFP4";
            case 1024: return "GUESSED";
            default: return "FTYPE" + ftype;
        }
    }

    /**
     * vc113/A3: resolve the user-facing model path to a readable file without
     * duplicating the picker-tag logic (the bmoe manager owns that shape).
     * Absolute + existing -> as-is; else try BmoeModelManager.resolveModelPath
     * (handles __bmoe__ tags and models-dir bare names); null when unresolvable
     * — preflight then just skips (engine still reports its own error).
     */
    private static String resolveModelPath2(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) return null;
        java.io.File f = new java.io.File(modelPath);
        if (f.isFile()) return modelPath;
        try {
            return BmoeModelManager.resolveModelPath(AppCtx.get(), modelPath);
        } catch (Throwable t) {
            return null;
        }
    }
}