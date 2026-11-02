package com.dawoer.npullm;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.4.0 (vc74): BigMoeOnEdge engine session — ProcessBuilder + --session line protocol.
 *
 * The bmoe engine is a CLI (nativeLibraryDir/libbmoecli.so + renamed libbm* closure),
 * NOT an HTTP server. Protocol (BigMoeOnEdge docs/session.md + telemetry.md, verified
 * against cli/main.cpp source):
 *   stdin : one flat JSON object per line — {"cmd":"generate","prompt":"...","id":N,
 *           "n_predict":N,"think":bool,"clear_kv":bool} | {"cmd":"cancel"} | {"cmd":"close"}
 *   stdout: BMOE_READY {...} once after load, then per generate:
 *           BMOE_BEGIN {"id":N} → [BMOE_LOAD {...}] → BMOE_PROGRESS {...}* →
 *           BMOE_DONE {...} | BMOE_ERROR {"id":N,"fatal":bool,"msg":"..."}
 *   stdin EOF or process exit ends the session; cancel is applied inline by the
 *   engine's reader thread and interrupts the in-flight generate at a decode boundary.
 *
 * d8 constraints: no enums, no anonymous classes (named static nested only), lambdas fine.
 */
final class BmoeSession {

    /** Routes engine events to the manager (state machine, log ring) and the chat UI. */
    interface Callbacks {
        void onEngineState(int state, String msg);
        void onEngineDied(int exitCode);
        void onChunk(String deltaText);      // BMOE_PROGRESS delta_text
        void onReasoning(String deltaReasoning); // BMOE_PROGRESS delta_reasoning
        void onGenDone(String status, String statsJson); // BMOE_DONE / BMOE_ERROR end of a turn
    }

    private static final long READY_TIMEOUT_MS = 300_000L; // 15G-class model, cold I/O

    private final LogRingBuffer log;
    private volatile Callbacks cb;
    private Process proc;
    private BufferedWriter stdin;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile boolean generating = false;
    private volatile boolean readySeen = false;
    private volatile double loadS = -1;
    private volatile double lastTokS = -1;
    private volatile double lastCacheHitPct = -1;
    private volatile String lastArch = "";
    private volatile long genStartMs = 0;
    private volatile long firstTokMs = -1;
    private volatile int genTokens = 0;
    private int seq = 0;

    BmoeSession(LogRingBuffer log) { this.log = log; }

    void setCallbacks(Callbacks c) { cb = c; }

    boolean isGenerating() { return generating; }
    boolean isReady() { return readySeen; }
    double loadSeconds() { return loadS; }
    double lastTokPerSec() { return lastTokS; }
    double lastCacheHitPct() { return lastCacheHitPct; }
    String arch() { return lastArch; }

    /** Launch libbmoecli.so with the fully-built argv (bmoe profile, model substituted). */
    synchronized void launch(String[] cmd) {
        stopping.set(false);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("HOME", System.getProperty("java.io.tmpdir", "/data/local/tmp"));
            // v1.4.0(vc76): LD_LIBRARY_PATH must NOT point at nativeLibraryDir
            // itself — that dir also holds QTI vendor libs (libbase/libutils/
            // libcutils, same names as /system/lib64). Their presence shadowed
            // the system namespace and libinput.so died resolving fmt v11
            // symbols (CANNOT LINK, S25 vc75 incident). The bmoe closure is all
            // lbm*/libbmoecli names with ZERO /system collisions, so expose
            // exactly those through a private symlink dir. vc75 lesson: bionic
            // does not search the executable's own dir, hence the env is still
            // required (vc74: "library lbmllama-common.so not found").
            String nativeDir = AppCtx.get().getApplicationInfo().nativeLibraryDir;
            String libDir = linkClosureDir(AppCtx.get().getCacheDir(), nativeDir);
            if (libDir == null) libDir = nativeDir; // degraded: try anyway
            pb.environment().put("LD_LIBRARY_PATH", libDir);
            pb.redirectErrorStream(false);
            proc = pb.start();
        } catch (IOException e) {
            logLine("[bmoe exec error] " + e);
            Callbacks c = cb;
            if (c != null) c.onEngineState(LlamaServerManager.ST_ERROR, "bmoe 启动失败，见日志");
            return;
        }
        logLine("[bmoe] 进程已启动 pid=" + pidOf(proc));
        startPump(proc.getInputStream(), false);
        startPump(proc.getErrorStream(), true);
        startWaiter(proc);
        startReadyWatchdog();
    }

    private static String pidOf(Process p) {
        try { return String.valueOf(p.pid()); } catch (Throwable t) { return "?"; }
    }

    /** Send one generate request; results stream back through Callbacks. */
    synchronized boolean generate(String prompt, int nPredict, boolean think, boolean clearKv) {
        if (proc == null || !proc.isAlive() || stopping.get()) return false;
        if (generating) return false;
        JSONObject req = new JSONObject();
        try {
            req.put("cmd", "generate");
            req.put("prompt", prompt);
            req.put("id", ++seq);
            req.put("n_predict", nPredict > 0 ? nPredict : 8192);
            req.put("think", think);
            req.put("clear_kv", clearKv);
        } catch (Exception ignored) { return false; }
        generating = true;
        genStartMs = System.currentTimeMillis();
        firstTokMs = -1;
        genTokens = 0;
        return writeStdin(req.toString());
    }

    /** Interrupt the in-flight generate at the next decode boundary (engine-side). */
    void cancel() {
        writeStdin("{\"cmd\":\"cancel\"}");
        logLine("[bmoe] cancel 已发送");
    }

    /** Kill the session process; stdin is closed first so a healthy engine exits on EOF. */
    synchronized void stop() {
        stopping.set(true);
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
        Process p = proc;
        if (p != null) {
            if (p.isAlive()) p.destroy();
            // 1.5s grace, then force — mirrors LlamaServerManager.stop() policy
            long deadline = System.currentTimeMillis() + 1500;
            while (p.isAlive() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    /* ---------- protocol ---------- */

    /** One protocol line → {"event":"READY|BEGIN|LOAD|PROGRESS|DONE|ERROR|other","data":{...}}.
     *  Static + package-private for JVM tests. Returns null for non-protocol lines. */
    static JSONObject parseLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (!t.startsWith("BMOE_")) return null;
        int sp = t.indexOf(' ');
        if (sp <= 5) return null;
        int brace = t.indexOf('{', sp);
        if (brace < 0) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("event", t.substring(5, sp).trim());
            o.put("data", new JSONObject(t.substring(brace)));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** BMOE_PROGRESS delta_text extraction: reset:1 replaces (caller clears), else append. */
    static String progressDelta(JSONObject data, String key) {
        if (data == null) return "";
        Object v = data.opt(key);
        return (v instanceof String) ? (String) v : "";
    }

    private void handleLine(String line) {
        JSONObject parsed = parseLine(line);
        if (parsed == null) {
            logLine("[bmoe:out] " + line);
            return;
        }
        String event = parsed.optString("event");
        JSONObject data = parsed.optJSONObject("data");
        Callbacks c = cb;
        if ("READY".equals(event)) {
            readySeen = true;
            loadS = data.optDouble("load_s", -1);
            lastArch = data.optString("arch", "");
            logLine("[bmoe] READY arch=" + lastArch + " load_s=" + loadS
                    + " n_ctx=" + data.optInt("n_ctx", -1)
                    + " think_ctl=" + data.optString("think_ctl", "?"));
            if (c != null) c.onEngineState(LlamaServerManager.ST_RUNNING, "");
        } else if ("BEGIN".equals(event)) {
            logLine("[bmoe] BEGIN id=" + data.optInt("id", -1));
        } else if ("LOAD".equals(event)) {
            logLine("[bmoe] LOAD " + data.optDouble("mb", 0) + "MB in " + data.optDouble("ms", 0) + "ms");
        } else if ("PROGRESS".equals(event)) {
            if (firstTokMs < 0) firstTokMs = System.currentTimeMillis() - genStartMs;
            genTokens = Math.max(genTokens, data.optInt("step", 0));
            double ioMs = data.optDouble("io_ms", 0);
            double wallMs = data.optDouble("wall_ms", 0);
            if (wallMs > 0) lastTokS = genTokens / (wallMs / 1000.0);
            lastCacheHitPct = data.optDouble("cache_hit_pct", -1);
            String dt = progressDelta(data, "delta_text");
            String dr = progressDelta(data, "delta_reasoning");
            if (c != null) {
                if (dr.length() > 0) c.onReasoning(dr);
                if (dt.length() > 0) c.onChunk(dt);
            }
            if (ioMs > 0 && genTokens % 64 == 0) {
                logLine("[bmoe] tok " + genTokens + " hit=" + lastCacheHitPct + "%");
            }
        } else if ("DONE".equals(event)) {
            generating = false;
            int tokens = data.optInt("tokens", genTokens);
            double tokS = data.optDouble("tok_s", -1);
            double prefillS = data.optDouble("prefill_s", -1);
            lastTokS = tokS;
            lastCacheHitPct = data.optDouble("cache_hit_pct", lastCacheHitPct);
            long totalMs = System.currentTimeMillis() - genStartMs;
            logLine("[bmoe] DONE tok=" + tokens + " tok_s=" + tokS + " prefill_s=" + prefillS
                    + " hit=" + lastCacheHitPct + "% n_prompt=" + data.optInt("n_prompt", -1)
                    + " cancelled=" + data.optBoolean("cancelled", false));
            String stats = "{\"completion_tokens\":" + tokens
                    + ",\"total_ms\":" + totalMs
                    + ",\"first_token_ms\":" + Math.max(0, firstTokMs)
                    + ",\"tok_s\":" + (tokS >= 0 ? tokS : 0)
                    + ",\"cache_hit_pct\":" + (lastCacheHitPct >= 0 ? lastCacheHitPct : 0)
                    + ",\"load_s\":" + loadS
                    + ",\"prefill_s\":" + (prefillS >= 0 ? prefillS : 0)
                    + (data.optBoolean("cancelled", false) ? ",\"aborted\":true" : "")
                    + "}";
            if (c != null) c.onGenDone("200", stats);
        } else if ("ERROR".equals(event)) {
            boolean fatal = data.optBoolean("fatal", false);
            String msg = data.optString("msg", "unknown");
            logLine("[bmoe] ERROR fatal=" + fatal + " msg=" + msg);
            if (generating) {
                generating = false;
                if (c != null) c.onGenDone("ERR", "{\"error\":\"" + jsonEscapeLite(msg) + "\"}");
            }
            if (fatal && c != null) {
                c.onEngineState(LlamaServerManager.ST_ERROR, msg);
            }
        } else {
            logLine("[bmoe:" + event + "] " + data);
        }
    }

    static String jsonEscapeLite(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /* ---------- process plumbing ---------- */

    private void startPump(java.io.InputStream in, final boolean errStream) {
        Thread t = new Thread(() -> {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16384);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (errStream) logLine("[bmoe:err] " + line);
                    else handleLine(line);
                }
            } catch (IOException ignored) {
            } finally {
                try { r.close(); } catch (IOException ignored) {}
            }
        }, errStream ? "bmoe-err-pump" : "bmoe-out-pump");
        t.setDaemon(true);
        t.start();
    }

    private void startWaiter(Process p) {
        Thread t = new Thread(() -> {
            int code = -999;
            try { code = p.waitFor(); } catch (InterruptedException ignored) {}
            if (stopping.get()) return;
            logLine("[bmoe] 进程退出 exitCode=" + code);
            readySeen = false;
            Callbacks c = cb;
            if (c != null) c.onEngineDied(code);
        }, "bmoe-waiter");
        t.setDaemon(true);
        t.start();
    }

    private void startReadyWatchdog() {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (stopping.get()) return;
                if (readySeen) return;
                if (proc == null || !proc.isAlive()) return; // died() handles reporting
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            if (stopping.get() || readySeen) return;
            logLine("[bmoe] " + (READY_TIMEOUT_MS / 1000) + "s 未就绪（冷加载可慢属正常，超时放弃）");
            Callbacks c = cb;
            if (c != null) c.onEngineState(LlamaServerManager.ST_ERROR, "bmoe 加载超时（" + (READY_TIMEOUT_MS / 1000) + "s）");
            stop();
        }, "bmoe-ready-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private synchronized boolean writeStdin(String json) {
        try {
            if (stdin == null) {
                stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), 16384);
            }
            stdin.write(json);
            stdin.write("\n");
            stdin.flush();
            return true;
        } catch (IOException e) {
            logLine("[bmoe stdin] " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void logLine(String s) {
        if (log != null) log.append((s + "\n").toCharArray(), s.length() + 1);
    }

    /** Build the bmoe argv from a profile args template (bmoe engine: no host/port/reasoning).
     *  {MODEL} substituted after split; --threads replaced in place / appended when >0.
     *  Static + package-private for JVM tests. */
    static String[] buildCommand(String exe, String profileArgs, String modelPath, int threads) {
        String[] base = LlamaServerManager.splitArgs(profileArgs);
        java.util.List<String> cmd = new java.util.ArrayList<>(base.length + 4);
        cmd.add(exe);
        boolean hasThreads = false;
        for (int i = 0; i < base.length; i++) {
            String t = base[i];
            if ("{MODEL}".equals(t)) { cmd.add(modelPath); continue; }
            if ("--threads".equals(t) && threads > 0) {
                hasThreads = true;
                cmd.add(t);
                if (i + 1 < base.length) cmd.add(String.valueOf(threads));
                i++;
                continue;
            }
            if ("--threads".equals(t) && threads <= 0) { i++; continue; } // 0 = strip (engine default)
            cmd.add(t);
        }
        if (threads > 0 && !hasThreads) { cmd.add("--threads"); cmd.add(String.valueOf(threads)); }
        return cmd.toArray(new String[0]);
    }

    /**
     * v1.4.0(vc76): build/refresh a cache-dir symlink dir exposing ONLY the
     * bmoe closure (libbmoecli + lbm* libs) from nativeLibraryDir. Returns the
     * dir path, or null when symlinking is impossible (degraded callers fall
     * back). The closure names collide with nothing in /system, so pointing
     * LD_LIBRARY_PATH here cannot shadow system libs (S25 vc75 libinput.so
     * CANNOT LINK incident). Package-private static for JVM tests.
     */
    static String linkClosureDir(File cacheDir, String nativeDir) {
        try {
            ensureBmoeClosureLibs(new File(nativeDir), cacheDir);
            java.nio.file.Path dir = new File(cacheDir, "bmoe-libs").toPath();
            java.nio.file.Files.createDirectories(dir);
            File nat = new File(nativeDir);
            String[] files = nat.list();
            if (files == null) return null;
            boolean any = false;
            for (String f : files) {
                if (!(f.startsWith("lbm") || f.startsWith("libbmoecli"))) continue;
                java.nio.file.Path link = dir.resolve(f);
                java.nio.file.Path target = new File(nat, f).toPath();
                boolean fresh = false;
                try {
                    fresh = java.nio.file.Files.isSymbolicLink(link)
                            && target.equals(java.nio.file.Files.readSymbolicLink(link));
                } catch (IOException ignored) {}
                if (!fresh) {
                    try { java.nio.file.Files.deleteIfExists(link); } catch (IOException ignored) {}
                    java.nio.file.Files.createSymbolicLink(link, target);
                }
                any = true;
            }
            // vc115: NPU driver chain — the Hexagon backend dlopens these QTI libs
            // from the closure dir (LD_LIBRARY_PATH). adb-domain A/B on 1014 proved
            // they are REQUIRED: without libcdsprpc.so in the closure the dlopen
            // fails and ggml-hex reports "devices found: CPU" (prefill falls back).
            // QTI-specific names only — no AOSP system libs, so no namespace
            // shadowing risk (vc113 design constraint preserved).
            String[] qti = {"libcdsprpc.so", "vendor.qti.hardware.dsp-V1-ndk.so",
                    "libdmabufheap.so", "libvmmem.so", "libhardware.so"};
            for (String f : qti) {
                if (!new File(nat, f).canRead()) continue;   // not in APK -> skip, prefill degrades to CPU
                java.nio.file.Path link = dir.resolve(f);
                java.nio.file.Path target = new File(nat, f).toPath();
                boolean fresh = false;
                try {
                    fresh = java.nio.file.Files.isSymbolicLink(link)
                            && target.equals(java.nio.file.Files.readSymbolicLink(link));
                } catch (IOException ignored) {}
                if (!fresh) {
                    try { java.nio.file.Files.deleteIfExists(link); } catch (IOException ignored) {}
                    try { java.nio.file.Files.createSymbolicLink(link, target); } catch (IOException ignored) {}
                    any = true;
                }
            }
            return any ? dir.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * vc113: self-extract the non-"lib*" bmoe closure entries (lbm*.so, 5 libs)
     * out of the APK. OEM package managers on Android <= 14 silently skip every
     * jniLibs entry that does not start with "lib" (vc87 Samsung A14 family;
     * vc112 S23U first report: bridge alive, CLI dies instantly with
     * CANNOT LINK lbmllama-common.so, stuck at 启动中). Android 16+ extracts
     * everything, so the fast path is: all five present -> zero work.
     * Extracts into cacheDir/bmoe-libs (the LD dir itself) beside the nativeDir
     * symlinks — same-name preference never triggers, but keeping one dir means
     * one LD entry. linkClosureDir already filters nativeDir links to the
     * lbm-prefixed + libbmoecli names, so no collision is possible.
     * (vc72 lesson, second occurrence: never write a slash-star-star sequence
     * inside a comment — this docblock was closed early by lbm-star-slash.)
     * Idempotent via versionCode+mtime stamp (extractVqti vc97 pattern);
     * tmp->rename atomic swap; never throws.
     */
    private static void ensureBmoeClosureLibs(File nativeDir, File cacheDir) {
        final String[] CLOSURE = {"lbmggml.so", "lbmggml-base.so", "lbmggml-cpu.so",
                "lbmllama.so", "lbmllama-common.so",
                // vc115: NPU driver chain (QTI) — partial-extract devices need them from the APK
                "libcdsprpc.so", "vendor.qti.hardware.dsp-V1-ndk.so",
                "libdmabufheap.so", "libvmmem.so", "libhardware.so"};
        try {
            boolean missing = false;
            for (String f : CLOSURE) {
                if (!new File(nativeDir, f).canRead()) { missing = true; break; }
            }
            if (!missing) return;   // Android 16+ fast path: PM already extracted all
            File outDir = new File(cacheDir, "bmoe-libs");
            java.io.File apkFile = new File(AppCtx.get().getApplicationInfo().sourceDir);
            long stamp = apkFile.lastModified() ^ ((long) android.os.Process.myUid() << 24);
            stamp ^= getAppVersionCode();
            File okMark = new File(outDir, ".stamp");
            if (okMark.canRead() && okMark.lastModified() >= stamp) return;
            java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkFile);
            int failed = 0;
            try {
                outDir.mkdirs();
                for (String f : CLOSURE) {
                    if (new File(nativeDir, f).canRead()) continue;  // PM got this one
                    java.util.zip.ZipEntry e = apk.getEntry("lib/arm64-v8a/" + f);
                    if (e == null) { failed++; continue; }   // malformed APK; retry next launch
                    java.io.File tmp = new java.io.File(outDir, f + ".tmp");
                    java.io.InputStream is = apk.getInputStream(e);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); is.close();
                    java.io.File dst = new java.io.File(outDir, f);
                    dst.delete();
                    if (!tmp.renameTo(dst)) { tmp.delete(); failed++; continue; }
                    dst.setLastModified(stamp);
                }
            } finally {
                apk.close();
            }
            // stamp only when every missing entry landed — a partial pass must
            // retry on next launch, not be pinned by a success marker
            if (failed == 0) {
                okMark.delete();
                if (!okMark.createNewFile()) throw new java.io.IOException("stamp create failed");
                okMark.setLastModified(stamp);
            } else {
                System.out.println("[bmoe-extract] closure self-extract incomplete: " + failed + " failed");
            }
        } catch (Throwable t) {
            // vc113/A2 lesson: never swallow silently — surface one breadcrumb
            System.out.println("[bmoe-extract] closure self-extract failed: " + t);
        }
    }

    /** current application versionCode (0 when unavailable — never throws) */
    private static long getAppVersionCode() {
        try {
            android.content.pm.PackageInfo pi = AppCtx.get().getPackageManager()
                    .getPackageInfo(AppCtx.get().getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }
}
