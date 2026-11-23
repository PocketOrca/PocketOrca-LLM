package com.dawoer.npullm.mcp.tools;

import android.content.Context;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.shell.McpShellLogic;
import com.dawoer.npullm.mcp.shell.ShellClient;

import org.json.JSONObject;

/**
 * vc183（S3）L3 工具：apps.launch + foreground.now。
 *
 * 都要 shell uid 2000：am start 绕 BAL（后台启动限制），dumpsys 拿前台组件（免 USAGE_STATS 特殊授权）。
 * 闸门 = Shizuku ready，未就绪时工具**不进 tools/list**（与 S2 的权限即可见性一致）。
 */
public final class ToolApps {

    private static final String BIN_AM = "/system/bin/am";
    private static final String BIN_CMD = "/system/bin/cmd";
    private static final String BIN_SH = "/system/bin/sh";

    private ToolApps() {}

    /** apps.launch：按包名启动应用。 */
    public static McpToolDef defLaunch(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("package", McpParamCheck.schemaString("应用包名，如 com.tencent.mm（必填）"));
            props.put("activity", McpParamCheck.schemaString("可选：指定 Activity（如 .ui.LauncherUI 或全名）；不填则用系统的 LAUNCHER 入口"));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "apps.launch",
                "打开应用",
                "按包名启动手机上的应用。用系统 LAUNCHER 入口，不需要无障碍服务。"
                        + "返回实际启动的组件名与耗时；应用未安装时返回 NOT_FOUND。"
                        + "仅在用户要求打开某个应用时调用（不要用它来点界面里的按钮）。",
                McpParamCheck.objectSchema(props, new String[]{"package"}),
                McpToolDef.LEVEL_REVERSIBLE,
                new Launcher(ctx, trace),
                true,
                new ShizukuGate(ctx));
    }

    /** foreground.now：当前前台应用。 */
    public static McpToolDef defForeground(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        return new McpToolDef(
                "foreground.now",
                "查看当前前台应用",
                "返回手机当前前台显示的应用与界面（包名 + Activity）。用来确认用户现在在用什么，"
                        + "或验证上一步启动/跳转是否成功。锁屏或界面切换瞬间可能返回系统界面。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new Foreground(ctx, trace),
                true,
                new ShizukuGate(ctx));
    }

    /** 命名静态嵌套（d8 零匿名类铁律）。 */
    static final class ShizukuGate implements McpToolDef.Gate {
        private final Context ctx;

        ShizukuGate(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean open() {
            return ShellClient.ready(ctx);
        }
    }

    /** apps.launch 执行器。 */
    static final class Launcher implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Launcher(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            String pkg = args.optString("package", "").trim();
            if (!McpShellLogic.validPackage(pkg)) {
                return McpResult.error("INVALID_ARGUMENT", "包名不合法: " + pkg);
            }
            String act = args.optString("activity", "").trim();
            String comp;
            if (act.length() > 0) {
                comp = act.indexOf('/') >= 0 ? act : pkg + "/" + act;
                if (!McpShellLogic.validComponent(comp)) {
                    return McpResult.error("INVALID_ARGUMENT", "activity 不合法: " + act);
                }
            } else {
                ShellClient.Res r = ShellClient.run(ctx, BIN_CMD, "package", "resolve-activity",
                        "--brief", "-c", "android.intent.category.LAUNCHER", pkg);
                if (r == null) {
                    return McpResult.error("NOT_AVAILABLE",
                            ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
                }
                comp = McpShellLogic.parseResolveActivity(r.out);
                if (comp.length() == 0) {
                    return McpResult.error("NOT_FOUND",
                            "找不到可启动的入口（应用未安装，或没有 LAUNCHER Activity）: " + pkg);
                }
            }

            long t0 = System.currentTimeMillis();
            ShellClient.Res rs = ShellClient.run(ctx, BIN_AM, "start", "-W", "-n", comp);
            long ms = System.currentTimeMillis() - t0;
            if (rs == null) {
                return McpResult.error("NOT_AVAILABLE",
                        ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }
            // am start 成败看输出，不看 exit code（部分 ROM 失败也返回 0）
            boolean ok = rs.out.indexOf("Status: ok") >= 0 && rs.out.indexOf("Error") < 0;
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("package", pkg);
                o.put("component", McpShellLogic.expandComponent(comp));
                o.put("ms", ms);
                o.put("output", clip(rs.out));
                if (!ok) o.put("err", clip(rs.err.length() > 0 ? rs.err : rs.out));
            } catch (Exception ignored) { }
            if (!ok) {
                return McpResult.error("INTERNAL", "启动失败: " + clip(rs.out.length() > 0 ? rs.out : rs.err));
            }
            if (trace != null) trace.proto("apps.launch " + comp + " " + ms + "ms");
            return McpResult.json(o);
        }
    }

    /** foreground.now 执行器。 */
    static final class Foreground implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Foreground(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            // ★ 绝不能不加管道直接读 dumpsys activity activities：真机实测全量输出 62.8MB。
            //   grep -m1 会提前关管道让 dumpsys 吃 SIGPIPE 提前结束，所以必须 2>/dev/null 吞掉噪音。
            ShellClient.Res r = ShellClient.run(ctx, BIN_SH, "-c",
                    "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity");
            if (r == null) {
                return McpResult.error("NOT_AVAILABLE",
                        ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }
            String comp = McpShellLogic.parseForeground(r.out);
            if (comp.length() == 0) {
                ShellClient.Res r2 = ShellClient.run(ctx, BIN_SH, "-c",
                        "dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'");
                if (r2 != null) comp = McpShellLogic.parseForeground(r2.out);
            }
            if (comp.length() == 0) {
                return McpResult.error("NOT_FOUND",
                        "无法确定前台应用（锁屏或界面正在切换时可能出现）");
            }
            int i = comp.indexOf('/');
            String pkg = i > 0 ? comp.substring(0, i) : comp;
            JSONObject o = new JSONObject();
            try {
                o.put("component", McpShellLogic.expandComponent(comp));
                o.put("package", pkg);
                o.put("activity", i > 0 ? comp.substring(i + 1) : "");
            } catch (Exception ignored) { }
            if (trace != null) trace.proto("foreground.now " + comp);
            return McpResult.json(o);
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 600 ? x.substring(0, 600) + "…" : x;
    }
}
