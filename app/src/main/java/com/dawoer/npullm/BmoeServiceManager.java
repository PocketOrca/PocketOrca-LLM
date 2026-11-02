package com.dawoer.npullm;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * vc105: bmoe 对外服务进程管理器（服务页 MoE 模型「启动服务」）。
 *
 * 拉起 bmoe-http-bridge（OpenAI 兼容端点 /v1 + /health），由 bridge 再 spawn
 * libbmoecli.so --session 引擎。与 1006/1007 在 S25 adb 域验证过的"外挂桥"同
 * 一形态，只是两件产物改由 APK jniLibs 携带：
 *   - libbmoehttp.so = bridge（自包含，无 libggml/libllama 依赖）
 *   - libbmoecli.so  = bmoe CLI（+ lbm* 闭包，vc74 等长改名纪律）
 *
 * 关键机制：
 *  - bionic 对绝对路径 exec 不搜可执行文件所在目录解析 NEEDED → LD_LIBRARY_PATH
 *    必须前置闭包软链目录（linkClosureDir，vc75/vc76 铁律），CLI 由 bridge spawn
 *    时继承同一 LD（BMOE_CLI 绝对路径，buildCommand 语义由 bridge 侧负责）。
 *  - 进程树 = app → bridge → CLI：CLI 带 PR_SET_PDEATHSIG(SIGKILL)，bridge 被
 *    SIGKILL 时引擎随之退出；bridge 本体由 stop() destroy + 宽限 destroyForcibly。
 *  - readiness = GET /health 直到 ready=true（15G 级模型冷载数分钟级，上限 10min），
 *    /health 免鉴权（探活语义），cli_pid 从 health 读回作兜底 kill。
 */
final class BmoeServiceManager {

    /** vc105: launch config — 与共享参数区一一对应（无 bmoe 专属参数）。 */
    static final class Cfg {
        final String modelPath;   // 解析后的真实 /data 路径（vc84 resolveModelPath 之后）
        final int port;           // 共享「端口」输入框
        final int ctx;            // 共享上下文档位 → BMOE_CTX
        final int threads;        // 共享「CPU 线程」→ BMOE_THREADS
        final boolean think;      // 共享「深度思考」开关 → BMOE_THINK（默认关）
        final boolean publicOn;   // 共享「端口对外开放」→ 0.0.0.0 / 127.0.0.1
        final String apiKey;      // 共享「本地服务Key」→ BMOE_API_KEY（空=不鉴权）
        Cfg(String modelPath, int port, int ctx, int threads, boolean think,
            boolean publicOn, String apiKey) {
            this.modelPath = modelPath; this.port = port; this.ctx = ctx;
            this.threads = threads; this.think = think;
            this.publicOn = publicOn; this.apiKey = apiKey;
        }
    }

    /** host = LlamaServerManager 的回调面（logLine/pushState 转发）。 */
    interface Host {
        void bmoeLog(String line);
        void bmoeReady(String base);
        void bmoeFailed(String why);
    }

    // vc114 定稿（vc116 回退 vc115）：CPU prefill。90min 天花板是 NPU prefill
    // 专属（首建 repack ~1h），CPU 启动 READY 只需 1-3min，真 wedge 也用不了
    // 90min——回退同时恢复 10min。
    private static final long READY_TIMEOUT_MS = 600_000L;
    /** vc107: 1007 官方文档定稿档（start.sh 同款）——dense=anon（refault 走 zram
     *  不经 flash）+ 固定专家缓存 3072MiB。vc106 的 warm+auto 是官方明令废弃档：
     *  auto 在 S25 算出 ~6GB 缓存=LMKD 头号猎物死法，且 warm 预热期前两发
     *  20.3/17.1s vs anon 9.8-10.8s（1010 S25 同二进制 A/B 实测）。
     *  注意 --overlap 无参、--dense-weights anon 无参，--cache-mb 3072 带值。
     *  vc116 回退 vc115 的 NPU prefill 旗标（判定实验 1014 全链路激活，
     *  但装机实测未出判定结论前不进发布包；待判定：首建 warmup 后暖启是否秒级）。
     *  回退后 = vc114 热修定稿档，历史等价字节。 */
    private static final String CLI_ARGS_FIXED =
            "--moe-stream --dense-weights anon --cache-mb 3072 --overlap";

    /** vc109: 桥看门狗两面墙 env（bmoe-http-bridge Config 构造时 getenv 读取）。
     *  默认 180/600 会 cancel 2.5K+ tok 的 CPU prefill（引擎 prefill 期间不发行协议行，
     *  1010 死区实测）→ 拉高到 900/1800；CLI 子进程继承同一 env（上限语义对齐）。 */
    static final String ENV_STALL_WALL = "900";
    static final String ENV_TOTAL_WALL = "1800";

    private Process proc;
    private volatile boolean running;
    private volatile int cliPid = -1;   // /health 读回的引擎 pid（兜底 kill）
    private Thread healthThread;

    synchronized boolean alive() {
        return running && proc != null && proc.isAlive();
    }

    synchronized void start(final Cfg cfg, final Host host) {
        if (running) return;
        running = true;
        final Context app = AppCtx.get();
        final String nativeDir = app.getApplicationInfo().nativeLibraryDir;
        // vc76 机制：闭包软链目录只暴露 lbm*/libbmoecli 前缀，避免 nativeLibraryDir
        // 里的 QTI vendor 库遮蔽系统命名空间。CLI 由此目录解析 NEEDED。
        final String libDir = BmoeSession.linkClosureDir(app.getCacheDir(), nativeDir);
        final String bridgeExe = nativeDir + "/libbmoehttp.so";

        // vc113/B: restore the lbm-named HTP skels to their real libggml-htp-v7x
        // names in cacheDir/bmoe-skels — the DSP loader finds skels ONLY through
        // ADSP_LIBRARY_PATH by file name, so the runtime name must be exact.
        // Prepend bmoe-skels; the llama.cpp-side skels stay in nativeDir untouched
        // (fastrpc searches directories in order, bmoe first = its own build wins;
        // llama.cpp NPU path never reads this env — BmoeServiceManager spawns
        // only the bmoe bridge). Process-scoped: no global pollution.
        String skelDir = "";
        try {
            File skDir = new File(app.getCacheDir(), "bmoe-skels");
            skDir.mkdirs();
            String[] skels = {"73", "75", "79", "81"};
            int restored = 0;
            for (String v : skels) {
                File src = new File(nativeDir, "lbmlibggml-htp-v" + v + ".so");
                File dst = new File(skDir, "libggml-htp-v" + v + ".so");
                if (!src.canRead()) continue;
                long want = src.lastModified();
                if (!dst.canRead() || dst.lastModified() != want) {
                    java.nio.file.Files.deleteIfExists(dst.toPath());
                    java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath());
                }
                restored++;
            }
            if (restored > 0) skelDir = skDir.getPath() + ";";
        } catch (Throwable t) {
            host.bmoeLog("[bmoe-skels] 还原失败（NPU prefill 将退 CPU）: " + t);
        }

        final ProcessBuilder pb = new ProcessBuilder(bridgeExe);
        // vc118 批1（A17 热修）：syslib-stub 目录放 libc++.so / libdmabufheap.so
        // -> /system/lib64 的 symlink，置于 LD_LIBRARY_PATH 最前——A17 上捆绑旧库
        // 按 soname 遮蔽系统库（__hash_memory / 3 参 Alloc 悬空）→ CANNOT LINK；
        // 系统 lib 胜出即解。独立 try/catch：失败不阻断（缺哪条 symlink 就维持
        // 现状解析，S25 等旧系统零回归）。CLI 由 bridge spawn 时继承 env。
        String sysStubPath = null;
        try {
            File sysStub = new File(app.getCacheDir(), "syslib-stub");
            sysStubPath = sysStub.getPath(); // 先取路径再 ensure：部分成功也前置（缺失条目自然回落 libDir）
            java.util.List<String> sl = new java.util.ArrayList<>();
            SysLibStubs.ensure(sysStub, new String[]{"libc++.so", "libdmabufheap.so"}, "/system/lib64", sl);
            for (String line : sl) host.bmoeLog(line);
        } catch (Throwable t) {
            host.bmoeLog("[syslib-stub] bmoe 路径系统库 symlink 失败（维持现状解析，旧系统零回归）: " + t);
            sysStubPath = null;
        }
        pb.environment().put("LD_LIBRARY_PATH",
                sysStubPath == null ? libDir : sysStubPath + ":" + libDir); // bridge 自身不需要，但其 spawn 的 CLI 继承
        // vc113/B: bmoe skels first, then the llama.cpp adsp defaults —
        // process-scoped, does not touch other engines
        pb.environment().put("ADSP_LIBRARY_PATH", skelDir
                + nativeDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/dsp/cdsp");
        pb.environment().put("BMOE_CLI", nativeDir + "/libbmoecli.so");
        pb.environment().put("BMOE_MODEL", cfg.modelPath);
        pb.environment().put("BMOE_BRIDGE_HOST", cfg.publicOn ? "0.0.0.0" : "127.0.0.1");
        pb.environment().put("BMOE_BRIDGE_PORT", String.valueOf(cfg.port));
        pb.environment().put("BMOE_CTX", String.valueOf(cfg.ctx));
        pb.environment().put("BMOE_THREADS", String.valueOf(cfg.threads));
        pb.environment().put("BMOE_THINK", cfg.think ? "1" : "0");
        pb.environment().put("BMOE_CHATML", "1");
        pb.environment().put("BMOE_CLI_ARGS", CLI_ARGS_FIXED);
        // vc116: NPU-prefill env pair (HOSTBUF/OPPOLL) removed together with the
        // NPU prefill flag — CPU prefill establishes no HTP session.
        pb.environment().put("BMOE_STALL_CEILING_S", ENV_STALL_WALL); // vc109: 180 默认 cancel 2.5K+ tok prefill
        pb.environment().put("BMOE_TOTAL_CEILING_S", ENV_TOTAL_WALL); // vc109: 600 默认同死区
        if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
            pb.environment().put("BMOE_API_KEY", cfg.apiKey);   // 进程 env，永不过 WebView
        }
        final File logFile = new File(app.getFilesDir(), "bmoe-bridge.log");
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);

        try {
            proc = pb.start();
        } catch (Exception e) {
            running = false;
            host.bmoeFailed("bridge 启动失败: " + e.getMessage());
            return;
        }
        host.bmoeLog("[cmd] " + bridgeExe + "  (env: port=" + cfg.port + " ctx=" + cfg.ctx
                + " threads=" + cfg.threads + " think=" + (cfg.think ? 1 : 0)
                + " host=" + (cfg.publicOn ? "0.0.0.0" : "127.0.0.1")
                + " auth=" + (cfg.apiKey != null && !cfg.apiKey.isEmpty() ? "on" : "off")
                + " walls=" + ENV_STALL_WALL + "/" + ENV_TOTAL_WALL + ")");
        host.bmoeLog("[bmoe] 引擎加载中（15G 级模型冷载可达数分钟）· 日志 " + logFile);

        final Process p = proc;
        healthThread = new Thread(() -> {
            final String url = "http://127.0.0.1:" + cfg.port + "/health";
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            boolean ok = false;
            String lastErr = "";
            while (running && System.currentTimeMillis() < deadline) {
                if (!p.isAlive()) {
                    host.bmoeFailed("bridge 进程退出 exitCode=" + safeExit(p));
                    return;
                }
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(1500);
                    int code = c.getResponseCode();
                    String body = readAll(c);
                    if (code == 200 && body.length() > 0) {
                        JSONObject j = new JSONObject(body);
                        int pid = j.optInt("cli_pid", -1);
                        if (pid > 0) cliPid = pid;
                        if (j.optBoolean("ready")) {
                            JSONObject eng = j.optJSONObject("engine");
                            host.bmoeLog("[bmoe] READY arch=" + (eng == null ? "?" : eng.optString("arch", "?"))
                                    + " load=" + (eng == null ? "?" : eng.optString("load_s", "?")) + "s"
                                    + " cli_pid=" + pid);
                            ok = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    lastErr = String.valueOf(e.getMessage());
                }
                try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
            }
            if (!running) return;
            if (!ok) {
                host.bmoeFailed("引擎加载超时（>" + (READY_TIMEOUT_MS / 60000) + " 分钟）" + (lastErr.isEmpty() ? "" : " · " + lastErr));
                return;
            }
            host.bmoeReady("http://127.0.0.1:" + cfg.port);
            // 就绪后转入存活监视：bridge 意外退出 → 状态回落 ERROR
            while (running) {
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                if (!p.isAlive()) {
                    host.bmoeFailed("bridge 进程退出 exitCode=" + safeExit(p));
                    return;
                }
            }
        }, "bmoe-health");
        healthThread.start();
    }

    synchronized void stop() {
        running = false;
        Thread h = healthThread;
        if (h != null) h.interrupt();
        final Process p = proc;
        proc = null;
        final int stalePid = cliPid;
        cliPid = -1;
        if (p != null) {
            p.destroy(); // SIGTERM：bridge 无 handler，直接退出；CLI 由 PDEATHSIG 带走
            new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                if (p.isAlive()) p.destroyForcibly();
            }, "bmoe-stop").start();
        }
        if (stalePid > 0) {
            try { android.os.Process.killProcess(stalePid); } catch (Throwable ignored) {}
        }
    }

    private static int safeExit(Process p) {
        try { return p.exitValue(); } catch (IllegalThreadStateException e) { return -1; }
    }

    private static String readAll(HttpURLConnection c) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
