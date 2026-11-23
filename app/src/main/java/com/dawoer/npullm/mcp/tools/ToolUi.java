package com.dawoer.npullm.mcp.tools;

import android.content.Context;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpScope;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.shell.McpShellLogic;
import com.dawoer.npullm.mcp.shell.ShellClient;
import com.dawoer.npullm.mcp.ui.McpUiLogic;

import org.json.JSONObject;

/**
 * vc184（S4 GUI）L3 工具三件套：ui.dump / ui.tap / ui.swipe。
 *
 * 路线（1118-1120 定稿）：**uiautomator dump 优先，截图降为兜底**。
 *   - 坐标永不出手机：dump 只把「序号 + 标签」给模型，坐标留在手机侧快照里，tap 查表。
 *   - 一次 dump 对应一次动作：tap/swipe 执行完立刻重新 dump，把新屏面一起返回（省一次往返）。
 *   - 动作后 diff 校验：新旧屏面签名不同 = 动作生效；相同 = 视为没点动（由模型决定重观察或换目标）。
 *
 * 护栏（不可退让）：
 *   - scope `ui.control` 默认关：关闭时三个工具**根本不进 tools/list**（等价「这次会话不许操作界面」）
 *   - 应用黑名单：社交/电商/金融类一概拒绝（不做超级 App 的写操作）
 *   - 息屏 / 锁屏直接拒（远程点黑屏是纯事故）
 *   - 快照 30 秒过期 + 前台应用变更即失效（防拿旧坐标点错）
 *
 * 真机事实（S25 A16 实测）：uiautomator dump --compressed 稳定 2.0-2.4s（独立 JVM 进程启动开销，
 * 与界面复杂度无关），静止界面连续 dump 的 md5 完全相同 → 签名比较可用于 diff 校验；
 * /dev/tty 不可用，必须写文件再 cat。
 */
public final class ToolUi {

    private static final String BIN_SH = "/system/bin/sh";
    private static final String BIN_INPUT = "/system/bin/input";
    private static final String DUMP_PATH = "/data/local/tmp/po_ui_dump.xml";

    private ToolUi() {}

    /** 最近一次 dump 的屏面（:mcp 单进程，静态即可）。 */
    private static volatile McpUiLogic.Dump last;
    private static volatile long lastAt;

    // ------------------------------------------------------------------ 工具定义

    public static McpToolDef defDump(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("max_nodes", McpParamCheck.schemaInteger("最多返回多少个可点节点（默认 40）", 5, 80, McpUiLogic.MAX_NODES_DEFAULT));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "ui.dump",
                "查看当前屏幕可点元素",
                "读取手机当前屏幕，返回可点击元素的编号列表（序号 + 文字标签 + 类型）。"
                        + "拿到列表后用 ui.tap(index) 点击，不需要也不允许提供坐标。"
                        + "第一次看某个界面、或上一步动作没生效时调用它。"
                        + "微信/支付宝/银行等应用会被拒绝；Flutter/游戏/自绘界面可能返回空列表（无节点树）。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new Dumper(ctx, trace),
                true,
                new UiGate(ctx));
    }

    public static McpToolDef defTap(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("index", McpParamCheck.schemaInteger("上一次 ui.dump 返回的节点序号（必填）", 0, 200, 0));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "ui.tap",
                "点击屏幕元素（按序号）",
                "点击上一次 ui.dump 返回的编号对应元素。执行后自动重新读取屏幕并返回新屏面，"
                        + "changed=false 表示界面没有变化（可能没点中，或点了无反应的区域）。"
                        + "快照超过 30 秒或前台应用已变更时必须重新 ui.dump。",
                McpParamCheck.objectSchema(props, new String[]{"index"}),
                McpToolDef.LEVEL_REVERSIBLE,
                new Tapper(ctx, trace),
                true,
                new UiGate(ctx));
    }

    public static McpToolDef defSwipe(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("direction", McpParamCheck.schemaString("划动方向：up / down / left / right（必填）。up=内容向下滚动（看后面）"));
            props.put("amount", McpParamCheck.schemaInteger("滑动幅度，屏幕尺寸的百分比（默认 60，范围 15-90）", 15, 90, 60));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "ui.swipe",
                "滑动屏幕",
                "在屏幕中央按方向滑动，用于翻页/滚列表/切换卡片。执行后自动重新读取屏幕并返回新屏面。"
                        + "direction=up 表示手指向上滑（内容向下滚动，看到后面的内容）。",
                McpParamCheck.objectSchema(props, new String[]{"direction"}),
                McpToolDef.LEVEL_REVERSIBLE,
                new Swiper(ctx, trace),
                true,
                new UiGate(ctx));
    }

    // ------------------------------------------------------------------ 闸门

    /** Shizuku ready + scope 显式开通，二者同时满足才露出来。 */
    static final class UiGate implements McpToolDef.Gate {
        private final Context ctx;

        UiGate(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean open() {
            return ShellClient.ready(ctx) && McpScope.enabled(ctx, McpScope.UI);
        }
    }

    // ------------------------------------------------------------------ 执行器

    static final class Dumper implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Dumper(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            int max = args == null ? McpUiLogic.MAX_NODES_DEFAULT : args.optInt("max_nodes", McpUiLogic.MAX_NODES_DEFAULT);
            return observe(ctx, trace, max, "ui.dump");
        }
    }

    static final class Tapper implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Tapper(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            int idx = args == null ? -1 : args.optInt("index", -1);
            if (idx < 0) return McpResult.error("INVALID_ARGUMENT", "index 必须 >= 0");

            McpUiLogic.Dump s = last;
            long at = lastAt;
            if (s == null || s.nodes.isEmpty()) {
                return McpResult.error("INVALID_ARGUMENT", "还没有屏面快照：先调用 ui.dump 看当前界面");
            }
            long age = System.currentTimeMillis() - at;
            if (age > McpUiLogic.SNAPSHOT_TTL_MS) {
                return McpResult.error("STALE", "屏面快照已过期（" + (age / 1000) + " 秒前），请重新调用 ui.dump");
            }
            if (idx >= s.nodes.size()) {
                return McpResult.error("INVALID_ARGUMENT", "序号越界：当前快照只有 " + s.nodes.size() + " 个可点节点（0-" + (s.nodes.size() - 1) + "）");
            }
            String blocked = precheck(ctx);
            if (blocked != null) return McpResult.error("NOT_AVAILABLE", blocked);
            if (McpUiLogic.blockedPkg(s.pkg)) {
                return McpResult.error("BLOCKED", McpUiLogic.blockReason(s.pkg));
            }
            String cur = currentPkg(ctx);
            if (cur.length() > 0 && s.pkg.length() > 0 && !cur.equals(s.pkg)) {
                return McpResult.error("STALE", "前台应用已从 " + s.pkg + " 变成 " + cur + "，请重新 ui.dump");
            }

            McpUiLogic.Node n = s.nodes.get(idx);
            String oldSig = s.signature();

            ShellClient.Res r = ShellClient.run(ctx, BIN_INPUT, "tap",
                    String.valueOf(n.cx()), String.valueOf(n.cy()));
            if (r == null) {
                return McpResult.error("NOT_AVAILABLE", ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }

            JSONObject o = new JSONObject();
            try {
                JSONObject t = new JSONObject();
                t.put("index", idx);
                t.put("label", n.label);
                t.put("class", McpUiLogic.shortClass(n.cls));
                o.put("tapped", t);
            } catch (Exception ignored) { }

            // 动作后立即重观察（一次 capture 对应一次动作）
            McpResult after = observe(ctx, trace, McpUiLogic.MAX_NODES_DEFAULT, "ui.tap#" + idx);
            if (after.isError) {
                try { o.put("changed", (Object) null); o.put("note", "点击已发出，但重新读取屏幕失败，请再 ui.dump"); } catch (Exception ignored) { }
                return McpResult.json(o);
            }
            try {
                McpUiLogic.Dump now = last;
                o.put("changed", now != null && !oldSig.equals(now.signature()));
            } catch (Exception ignored) { }
            return appendScreen(o);
        }
    }

    static final class Swiper implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Swiper(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            String dir = args == null ? "" : args.optString("direction", "").trim();
            if (!McpUiLogic.validDir(dir)) {
                return McpResult.error("INVALID_ARGUMENT", "direction 只能是 up / down / left / right");
            }
            int amount = args.optInt("amount", 60);
            String blocked = precheck(ctx);
            if (blocked != null) return McpResult.error("NOT_AVAILABLE", blocked);

            McpUiLogic.Dump s = last;
            int w = s == null ? 0 : s.w;
            int h = s == null ? 0 : s.h;
            if (w <= 0 || h <= 0) {
                int[] wh = screenSize(ctx);
                if (wh != null) { w = wh[0]; h = wh[1]; }
            }
            if (w <= 0 || h <= 0) {
                return McpResult.error("INTERNAL", "拿不到屏幕尺寸（先 ui.dump 一次或检查 wm size）");
            }
            if (s != null && McpUiLogic.blockedPkg(s.pkg)) {
                return McpResult.error("BLOCKED", McpUiLogic.blockReason(s.pkg));
            }
            int[] pts = McpUiLogic.swipePoints(dir, amount / 100.0, w, h);
            if (pts == null) return McpResult.error("INTERNAL", "滑动坐标计算失败");

            String oldSig = s == null ? "" : s.signature();
            ShellClient.Res r = ShellClient.run(ctx, BIN_INPUT, "swipe",
                    String.valueOf(pts[0]), String.valueOf(pts[1]),
                    String.valueOf(pts[2]), String.valueOf(pts[3]), "300");
            if (r == null) {
                return McpResult.error("NOT_AVAILABLE", ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }

            JSONObject o = new JSONObject();
            try {
                o.put("direction", dir);
                o.put("amount", amount);
            } catch (Exception ignored) { }
            McpResult after = observe(ctx, trace, McpUiLogic.MAX_NODES_DEFAULT, "ui.swipe " + dir);
            if (after.isError) return appendScreen(o);
            try {
                McpUiLogic.Dump now = last;
                o.put("changed", oldSig.length() == 0 || (now != null && !oldSig.equals(now.signature())));
            } catch (Exception ignored) { }
            return appendScreen(o);
        }
    }

    // ------------------------------------------------------------------ 公共流程

    /** 观察当前屏：息屏/锁屏拒 → 黑名单拒 → dump → 压成编号表 → 存快照。 */
    private static McpResult observe(Context ctx, McpTrace trace, int maxNodes, String tag) {
        String blocked = precheck(ctx);
        if (blocked != null) return McpResult.error("NOT_AVAILABLE", blocked);

        long t0 = System.currentTimeMillis();
        // 一条 shell 跑完 dump+cat：少一次跨进程往返（每次 call 都有 binder 开销）
        ShellClient.Res r = ShellClient.run(ctx, BIN_SH, "-c",
                "/system/bin/uiautomator dump --compressed " + DUMP_PATH + " >/dev/null 2>&1; /system/bin/cat " + DUMP_PATH);
        long ms = System.currentTimeMillis() - t0;
        if (r == null) {
            return McpResult.error("NOT_AVAILABLE", ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
        }
        String xml = r.out;
        if (xml == null || xml.indexOf("<hierarchy") < 0) {
            String hint = r.out.trim();
            return McpResult.error("INTERNAL",
                    "uiautomator 没有返回节点树（界面持续动画/无节点树的应用，如游戏、部分自绘界面）"
                            + (hint.length() > 0 ? "：" + clip(hint, 120) : ""));
        }

        McpUiLogic.Dump d = McpUiLogic.compact(xml, maxNodes);
        if (d.nodes.isEmpty()) {
            // 空树不覆盖旧快照（旧快照可能有别的界面），但也不假装成功
            JSONObject o = new JSONObject();
            try {
                o.put("package", d.pkg);
                o.put("size", d.w + "x" + d.h);
                o.put("nodes", new org.json.JSONArray());
                o.put("count", 0);
                o.put("ms", ms);
                o.put("note", "这个界面没有可点击节点（Flutter 需开启 accessibility semantics；游戏/Canvas 自绘界面永远为空）。可改用用户手动操作。");
            } catch (Exception ignored) { }
            return McpResult.json(o);
        }

        last = d;
        lastAt = System.currentTimeMillis();

        JSONObject o = new JSONObject();
        try {
            o.put("package", d.pkg);
            o.put("size", d.w + "x" + d.h);
            o.put("count", d.nodes.size());
            o.put("raw_nodes", d.rawCount);
            if (d.truncated) o.put("truncated", true);
            o.put("nodes", d.nodesJson());
            o.put("ms", ms);
            if (McpUiLogic.blockedPkg(d.pkg)) o.put("blocked", McpUiLogic.blockReason(d.pkg));
        } catch (Exception ignored) { }
        if (trace != null) trace.proto(tag + " " + d.pkg + " nodes=" + d.nodes.size() + " " + ms + "ms");
        return McpResult.json(o);
    }

    /** 把最新屏面挂到结果里的 screen 字段。 */
    private static McpResult appendScreen(JSONObject o) {
        McpUiLogic.Dump s = last;
        if (s == null) return McpResult.json(o);
        try {
            JSONObject sc = new JSONObject();
            sc.put("package", s.pkg);
            sc.put("count", s.nodes.size());
            sc.put("nodes", s.nodesJson());
            o.put("screen", sc);
        } catch (Exception ignored) { }
        return McpResult.json(o);
    }

    /** 息屏 / 锁屏一律拒（远程操作黑屏没有任何意义，只会点错）。 */
    private static String precheck(Context ctx) {
        ShellClient.Res r = ShellClient.run(ctx, BIN_SH, "-c",
                "dumpsys power 2>/dev/null | grep -m1 mWakefulness; "
                        + "dumpsys window 2>/dev/null | grep -m1 mDreamingLockscreen");
        if (r == null) {
            return ShellClient.Res.unavailableHint(ShellClient.state(ctx));
        }
        String out = r.out;
        if (out.indexOf("mWakefulness=Awake") < 0) {
            return "屏幕未点亮（熄屏/休眠中），拒绝界面操作。请先让用户点亮并解锁屏幕。";
        }
        if (out.indexOf("mDreamingLockscreen=true") >= 0) {
            return "当前处于锁屏状态，拒绝界面操作。请先让用户解锁屏幕。";
        }
        return null;
    }

    private static String currentPkg(Context ctx) {
        ShellClient.Res r = ShellClient.run(ctx, BIN_SH, "-c",
                "dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'");
        if (r == null) return "";
        String comp = McpShellLogic.parseForeground(r.out);
        int i = comp.indexOf('/');
        return i > 0 ? comp.substring(0, i) : comp;
    }

    /** `wm size` → {w,h}（仅在还没有快照时用）。 */
    private static int[] screenSize(Context ctx) {
        ShellClient.Res r = ShellClient.run(ctx, BIN_SH, "-c", "wm size 2>/dev/null | grep -m1 'Override size\\|Physical size'");
        if (r == null) return null;
        return parseWmSize(r.out);
    }

    /** "Physical size: 1080x2340" → {1080,2340}。 */
    public static int[] parseWmSize(String out) {
        if (out == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*x\\s*(\\d+)").matcher(out);
        if (!m.find()) return null;
        try {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (w <= 0 || h <= 0) return null;
            return new int[]{w, h};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > max ? x.substring(0, max) + "…" : x;
    }
}
