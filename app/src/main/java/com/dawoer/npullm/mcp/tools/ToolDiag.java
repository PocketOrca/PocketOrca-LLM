package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.os.StatFs;
import android.provider.Settings;

import com.dawoer.npullm.mcp.McpAuth;
import com.dawoer.npullm.mcp.McpConfig;
import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpScope;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpToolRegistry;
import com.dawoer.npullm.mcp.access.NotificationCollector;
import com.dawoer.npullm.mcp.access.Permissions;
import com.dawoer.npullm.mcp.diag.McpDiagLogic;
import com.dawoer.npullm.mcp.diag.McpTraceFile;
import com.dawoer.npullm.mcp.outbox.OutboxWorker;
import com.dawoer.npullm.mcp.shell.ShellClient;
import com.dawoer.npullm.mcp.transport.McpForegroundService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * vc186：device.diagnose —— 环境自诊（L0 只读，无权限依赖）。
 *
 * 来自 google/artemis `mobile_diagnose` 的启发：agent 失败后第一件事不该是猜或重试，
 * 而是拿一份「哪一环断了 + 按顺序怎么修」。这也是我们「静默失败率=0」的唯一可执行抓手。
 *
 * 输出：{verdict: ready|degraded|blocked, checks[], next_steps[], trace[], ...}
 *   verdict  ready     全部 ok
 *            degraded  能用但掉能力（某项 fail/warn 非致命）
 *            blocked   服务没起 / key 没有 —— 连请求都不该发
 *
 * 与 HTTP GET /diagnose 共用 McpDiagLogic 的组装逻辑，两边永不分叉。
 */
public final class ToolDiag implements McpToolDef.Executor {

    public static final String TRACE_NAME = "trace.jsonl";
    private static final String TRACE_DIR = "mcp";

    private final Context ctx;
    private final McpToolRegistry reg;

    public ToolDiag(Context ctx, McpToolRegistry reg) {
        this.ctx = ctx.getApplicationContext();
        this.reg = reg;
    }

    public static McpToolDef def(Context ctx, McpToolRegistry reg) {
        JSONObject props = new JSONObject();
        try {
            props.put("include_trace", McpParamCheck.schemaInteger("附带最近多少条 trace 记录（0-50，默认 10）", 0, 50, 10));
            props.put("deep", McpParamCheck.schemaBool("深度检查（多花 1-2 秒：电池优化白名单等 shell 查询）", false));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "device.diagnose",
                "检查手机端服务是否正常",
                "检查 MCP 服务链路：HTTP 服务、key、通知使用权、Shizuku、运行时权限、scope 开关、"
                        + "可投递性、存储空间，并附最近失败调用。verdict=blocked 表示服务本身没起来，"
                        + "degraded 表示能用但掉了某项能力——按 next_steps 顺序修，不要盲目重试。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolDiag(ctx, reg));
    }

    @Override
    public McpResult exec(JSONObject args) {
        int lines = args == null ? 10 : args.optInt("include_trace", 10);
        if (lines < 0) lines = 0;
        if (lines > 50) lines = 50;
        boolean deep = args != null && args.optBoolean("deep", false);
        return McpResult.text(build(lines, deep).toString());
    }

    /** 供 McpServer 的 /diagnose 端点复用（同一份实现，避免两处结果不一致）。 */
    public static JSONObject build(Context ctx, McpToolRegistry reg, int lines, boolean deep) {
        return new ToolDiag(ctx, reg).build(lines, deep);
    }

    private JSONObject build(int traceLines, boolean deep) {
        List<McpDiagLogic.Check> checks = new ArrayList<>();

        // 1) 服务与 key（致命）
        checks.add(McpForegroundService.isRunning()
                ? McpDiagLogic.okCritical("server", "HTTP 服务已启动，端口 " + McpConfig.PORT)
                : McpDiagLogic.failCritical("server", "HTTP 服务未运行",
                        "在「服务页 → MCP 服务」点开关重新启动（重装 APK 后不会自启，这是已知行为）"));

        String key = McpConfig.peekKey(ctx);
        checks.add(key == null
                ? McpDiagLogic.failCritical("key", "key 尚未生成", "启动一次 MCP 服务即会自动生成")
                : McpDiagLogic.okCritical("key", "已生成，指纹 " + fp(key)));

        // 2) 通知使用权（一次性系统开关）
        boolean nlsEnabled = nlsEnabled();
        checks.add(McpDiagLogic.bool("notif_access", nlsEnabled,
                "通知使用权已授予", "通知使用权未授予（notifications.list 读不到任何通知）",
                "在「手机权限」面板点「通知使用权 → 开启」（Shizuku 就绪时零点击）"));
        checks.add(NotificationCollector.isConnected()
                ? McpDiagLogic.ok("notif_connected", "采集器已连接，缓存 " + NotificationCollector.size() + " 条")
                : McpDiagLogic.warn("notif_connected", "采集器未连接",
                        "先确认通知使用权，再重开 MCP 服务（服务重启后才会重连）"));

        // 3) Shizuku（L3 全部依赖它）
        String sz = ShellClient.state(ctx);
        boolean szReady = "ready".equals(sz);
        if (szReady) {
            checks.add(McpDiagLogic.ok("shizuku", "ready（shell uid 2000 可用）"));
        } else {
            checks.add(McpDiagLogic.warn("shizuku", "状态 " + (sz.length() == 0 ? "未知（主进程代理不可达）" : sz),
                    ShellClient.Res.unavailableHint(sz)));
        }

        // 4) 运行时权限（决定工具是否出现在 tools/list）
        JSONObject perms = new JSONObject();
        String[][] pm = new String[][]{
                {"sms", Permissions.READ_SMS},
                {"calllog", Permissions.READ_CALL_LOG},
                {"contacts", Permissions.READ_CONTACTS},
                {"send_sms", Permissions.SEND_SMS}
        };
        for (String[] one : pm) {
            boolean g = Permissions.granted(ctx, one[1]);
            try { perms.put(one[0], g); } catch (Exception ignored) { }
            if (!g) {
                checks.add(McpDiagLogic.warn("perm_" + one[0], Permissions.denyHint(one[1]) + "（未授予）",
                        "在「手机权限」面板一键授权（走 Shizuku 则零弹窗）"));
            }
        }

        // 5) scope 开关（不可逆 / GUI）
        boolean sendScope = McpScope.enabled(ctx, McpScope.SEND_SMS);
        boolean uiScope = McpScope.enabled(ctx, McpScope.UI);
        checks.add(McpDiagLogic.ok("scope_messages_send", sendScope ? "已开通" : "未开通（messages.send 不可见）"));
        if (uiScope && !szReady) {
            checks.add(McpDiagLogic.warn("scope_ui", "已开通但 Shizuku 未就绪 → ui.* 仍不可见",
                    "重新激活 Shizuku（无线调试），否则 GUI 三件套不会出现在 tools/list"));
        } else {
            checks.add(McpDiagLogic.ok("scope_ui", uiScope ? "已开通" : "未开通（ui.* 不可见）"));
        }

        // 6) 工具目录（15 个可见是模型选准率的临界点）
        int visible = reg == null ? -1 : reg.visibleCount();
        if (visible < 0) {
            checks.add(McpDiagLogic.warn("tools", "工具注册表未接入", "服务未启动时属正常"));
        } else if (visible > 15) {
            checks.add(McpDiagLogic.warn("tools", "可见工具 " + visible + " 个，超过 15 个临界点",
                    "关掉用不上的 scope（ui.control / messages.send）以提高模型选准率"));
        } else {
            checks.add(McpDiagLogic.ok("tools", "可见工具 " + visible + " 个"));
        }

        // 7) outbox 可投递性（vc185 的能力面，最容易「以为在跑其实没跑」）
        checks.addAll(outboxChecks());

        // 8) 存储
        long freeMb = freeMegabytes();
        checks.add(freeMb >= 500
                ? McpDiagLogic.ok("storage", "可用 " + freeMb + " MB")
                : McpDiagLogic.warn("storage", "可用仅 " + freeMb + " MB", "清理存储，RAG/模型缓存会先失败"));

        // 9) trace 最近失败（有失败就是 degraded 的证据）
        JSONArray tail = new JSONArray();
        int failCount = 0;
        if (traceLines > 0) {
            List<String> lines = McpTraceFile.tail(traceDir(), TRACE_NAME, traceLines);
            for (String l : lines) {
                tail.put(l);
                if (l.contains("\"ok\":false")) failCount++;
            }
        }
        checks.add(failCount == 0
                ? McpDiagLogic.ok("trace", "最近 " + traceLines + " 条无失败记录")
                : McpDiagLogic.warn("trace", "最近 " + traceLines + " 条里有 " + failCount + " 条失败",
                        "看 trace 里的 code：PERMISSION_DENIED=去授权，NOT_AVAILABLE=Shizuku/scope 没开，"
                                + "TIMEOUT=界面卡住或工具超 15s"));

        // 10) 深度检查（可选，走 shell）
        if (deep) checks.addAll(deepChecks(szReady));

        JSONObject extra = new JSONObject();
        try {
            extra.put("server", "PocketOrca-LLM");
            extra.put("proto", com.dawoer.npullm.mcp.McpServer.PROTO_TAG);
            extra.put("port", McpConfig.PORT);
            extra.put("perms", perms);
            extra.put("visible_tools", visible);
            extra.put("dev", android.os.Build.MODEL + "/sdk" + android.os.Build.VERSION.SDK_INT);
        } catch (Exception ignored) { }

        return McpDiagLogic.json(checks, extra, tail);
    }

    // ------------------------------------------------------------------ 子块

    private List<McpDiagLogic.Check> outboxChecks() {
        List<McpDiagLogic.Check> out = new ArrayList<>();
        JSONObject st;
        try {
            st = OutboxWorker.status(ctx);
        } catch (Throwable t) {
            out.add(McpDiagLogic.warn("outbox", "状态读取失败: " + t.getClass().getSimpleName(),
                    "重开 MCP 服务；仍失败则看 trace 里 outbox 相关记录"));
            return out;
        }
        boolean enabled = st.optBoolean("enabled", false);
        if (!enabled) {
            out.add(McpDiagLogic.ok("outbox", "未启用（不推送）"));
            return out;
        }
        boolean deliverable = st.optBoolean("deliverable", false);
        boolean running = st.optBoolean("running", false);
        String endpoint = st.optString("endpoint", "");
        String err = st.optString("last_error", "");

        out.add(deliverable
                ? McpDiagLogic.ok("outbox_endpoint", "端点合法: " + McpDiagLogic.endpointBrief(endpoint))
                : McpDiagLogic.fail("outbox_endpoint", "已启用但端点非法/为空",
                        "填 http://<PC-IP>:<端口>/v1/runs 形式的地址后保存"));
        out.add(running
                ? McpDiagLogic.ok("outbox_service", "投递服务运行中")
                : McpDiagLogic.fail("outbox_service", "开关开着但服务标记未运行 → 事件不会投递",
                        "退到服务页重开 outbox 开关；这是 vc185 已知问题（跨进程运行态），先看诊断结果再动代码"));
        if (err.length() > 0) {
            out.add(McpDiagLogic.warn("outbox_last_error", McpTraceFile.redact(err),
                    "上一轮投递失败：确认 PC 上 agent 的 /v1/runs 端点可达、key 正确"));
        }
        int pending = st.optInt("ev_pending", -1);
        if (pending > 0) {
            out.add(McpDiagLogic.warn("outbox_pending", "待投递 " + pending + " 条",
                    "若持续增长说明投递链路断了（端点/网络/唤醒限流）"));
        } else if (pending == 0) {
            out.add(McpDiagLogic.ok("outbox_pending", "无积压"));
        }
        return out;
    }

    private List<McpDiagLogic.Check> deepChecks(boolean szReady) {
        List<McpDiagLogic.Check> out = new ArrayList<>();
        if (!szReady) {
            out.add(McpDiagLogic.warn("deep", "跳过（Shizuku 未就绪）", "先修 shizuku 再做深度检查"));
            return out;
        }
        ShellClient.Res r = ShellClient.run(ctx, "/system/bin/dumpsys", "deviceidle", "whitelist");
        String pkg = ctx.getPackageName();
        if (r == null) {
            out.add(McpDiagLogic.warn("battery_opt", "查询失败（代理无返回）", "重开 MCP 服务后重试"));
        } else {
            boolean white = r.out != null && r.out.contains(pkg);
            out.add(white
                    ? McpDiagLogic.ok("battery_opt", "已在电池优化白名单")
                    : McpDiagLogic.warn("battery_opt", "不在电池优化白名单（Doze 会延迟投递到分钟级）",
                            "在系统设置里对本应用关闭电池优化（可选，不承诺准点）"));
        }
        ShellClient.Res fg = ShellClient.run(ctx, "/system/bin/sh", "-c",
                "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity");
        if (fg != null && fg.out != null && fg.out.trim().length() > 0) {
            out.add(McpDiagLogic.ok("foreground", McpDiagLogic.firstComponent(fg.out)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    private boolean nlsEnabled() {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fp(String key) {
        String h = McpAuth.sha16(key);
        return h.length() >= 8 ? h.substring(0, 8) : h;
    }

    private long freeMegabytes() {
        try {
            StatFs s = new StatFs(ctx.getFilesDir().getPath());
            return s.getAvailableBytes() / (1024L * 1024L);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** trace 目录（与 service 写入时一致）。 */
    public static java.io.File traceDir(Context ctx) {
        java.io.File d = new java.io.File(ctx.getFilesDir(), TRACE_DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private java.io.File traceDir() {
        return traceDir(ctx);
    }
}
