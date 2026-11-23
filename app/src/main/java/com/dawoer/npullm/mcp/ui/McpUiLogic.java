package com.dawoer.npullm.mcp.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vc184（S4 GUI）纯逻辑：uiautomator dump XML 解析 + 节点标签聚合 + 应用黑名单 + 划动坐标。
 *
 * 刻意零 android 依赖 —— JVM 自测直接调本类，不需要真机。
 *
 * 为什么需要「标签聚合」：S25 实测（A16）clickable 节点普遍没有 text / content-desc，
 * 文字都在子 TextView 里（OneUI 设置首页 6 个 clickable 全是空标签 LinearLayout，
 * 显示设置页的 Switch 自身 label 也空）。裸 dump 直接编号喂模型 = 模型拿到一堆空行，
 * 等于不可用。所以这里做两级回退：子节点文本拼接 → 同 y 邻近文本配对。
 *
 * 铁律：**坐标不出手机**。dump 只把「序号 + 标签」给模型，坐标留在手机侧快照里，
 * 由 ui.tap(index) 查表。模型因此不需要任何坐标能力。
 */
public final class McpUiLogic {

    public static final int MAX_NODES_DEFAULT = 40;
    /** 一次 dump 的快照最多活 30 秒：超过就要求重新观察（防拿旧坐标点错）。 */
    public static final long SNAPSHOT_TTL_MS = 30000L;

    private McpUiLogic() {}

    // ------------------------------------------------------------------ 节点模型

    public static final class Node {
        public int depth;
        public String cls = "";
        public String text = "";
        public String desc = "";
        public String pkg = "";
        public String rid = "";
        public boolean clickable;
        public boolean longClickable;
        public boolean scrollable;
        public boolean checkable;
        public boolean checked;
        public boolean enabled = true;
        public boolean password;
        public int l, t, r, b;
        public String label = "";
        public final List<Node> children = new ArrayList<Node>();

        public int cx() { return (l + r) / 2; }
        public int cy() { return (t + b) / 2; }
        public int width() { return r - l; }
        public int height() { return b - t; }
        public boolean onScreen() { return r > l && b > t; }
    }

    /** 一次压缩后的屏面：给模型的节点表 + 内部用的坐标表。 */
    public static final class Dump {
        public String pkg = "";
        public int w, h;
        public int rawCount;
        public boolean truncated;
        public final List<Node> nodes = new ArrayList<Node>();

        public JSONArray nodesJson() {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                JSONObject o = new JSONObject();
                try {
                    o.put("i", i);
                    o.put("label", n.label);
                    o.put("class", shortClass(n.cls));
                    if (n.checkable) o.put("checked", n.checked);
                    if (!n.enabled) o.put("enabled", false);
                    if (n.scrollable) o.put("scrollable", true);
                } catch (Exception ignored) { }
                arr.put(o);
            }
            return arr;
        }

        /** 屏面签名：label+坐标全等即视为「界面没变」（动作后 diff 校验用）。 */
        public String signature() {
            StringBuilder sb = new StringBuilder();
            for (Node n : nodes) {
                sb.append(n.label).append('|').append(n.l).append(',').append(n.t)
                        .append(',').append(n.r).append(',').append(n.b).append(';');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ 解析

    private static final Pattern NODE_TAG = Pattern.compile("<node\\b([^>]*)>|</node>");
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)=\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    /** 解析整棵 hierarchy，返回根节点列表（节点自动挂父子，深度自增）。 */
    public static List<Node> parseTree(String xml) {
        List<Node> roots = new ArrayList<Node>();
        if (xml == null || xml.length() == 0) return roots;
        List<Node> stack = new ArrayList<Node>();
        Matcher m = NODE_TAG.matcher(xml);
        while (m.find()) {
            if (m.group(1) == null) {           // </node>
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                continue;
            }
            String inner = m.group(1);
            boolean selfClose = inner.endsWith("/");
            Node n = parseNode(inner);
            n.depth = stack.size();
            if (stack.isEmpty()) {
                roots.add(n);
            } else {
                stack.get(stack.size() - 1).children.add(n);
            }
            if (!selfClose) stack.add(n);
        }
        return roots;
    }

    private static Node parseNode(String attrs) {
        Node n = new Node();
        Matcher a = ATTR.matcher(attrs);
        while (a.find()) {
            String k = a.group(1);
            String v = a.group(2);
            if ("text".equals(k)) n.text = v;
            else if ("content-desc".equals(k)) n.desc = v;
            else if ("class".equals(k)) n.cls = v;
            else if ("package".equals(k)) n.pkg = v;
            else if ("resource-id".equals(k)) n.rid = v;
            else if ("clickable".equals(k)) n.clickable = "true".equals(v);
            else if ("long-clickable".equals(k)) n.longClickable = "true".equals(v);
            else if ("scrollable".equals(k)) n.scrollable = "true".equals(v);
            else if ("checkable".equals(k)) n.checkable = "true".equals(v);
            else if ("checked".equals(k)) n.checked = "true".equals(v);
            else if ("enabled".equals(k)) n.enabled = "true".equals(v);
            else if ("password".equals(k)) n.password = "true".equals(v);
            else if ("bounds".equals(k)) {
                int[] bb = bounds(v);
                if (bb != null) { n.l = bb[0]; n.t = bb[1]; n.r = bb[2]; n.b = bb[3]; }
            }
        }
        return n;
    }

    /** "[30,928][1050,1203]" → {30,928,1050,1203}；非法返回 null。 */
    public static int[] bounds(String s) {
        if (s == null) return null;
        Matcher m = BOUNDS.matcher(s);
        if (!m.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 压缩成可点表

    /**
     * 整棵 XML → 可点节点编号表。
     * 候选 = clickable || long-clickable（且 bounds 有效）。
     * 顺序按 (top, left)，截断到 maxNodes（截断即 truncated=true，绝不静默丢弃）。
     */
    public static Dump compact(String xml, int maxNodes) {
        Dump d = new Dump();
        List<Node> roots = parseTree(xml);
        if (roots.isEmpty()) return d;
        List<Node> all = new ArrayList<Node>();
        for (Node r : roots) flatten(r, all);
        d.rawCount = all.size();

        Node root = roots.get(0);
        d.pkg = root.pkg;
        d.w = root.r;
        d.h = root.b;
        if (d.w <= 0 || d.h <= 0) {
            for (Node n : all) {
                if (n.r > d.w) d.w = n.r;
                if (n.b > d.h) d.h = n.b;
            }
        }

        List<Node> cand = new ArrayList<Node>();
        for (Node n : all) {
            if (!n.onScreen()) continue;
            if (!(n.clickable || n.longClickable)) continue;
            n.label = labelOf(n, all);
            cand.add(n);
        }
        sortByPos(cand);

        int cap = maxNodes <= 0 ? MAX_NODES_DEFAULT : maxNodes;
        if (cand.size() > cap) {
            d.truncated = true;
            cand = new ArrayList<Node>(cand.subList(0, cap));
        }
        d.nodes.addAll(cand);
        return d;
    }

    private static void flatten(Node n, List<Node> out) {
        out.add(n);
        for (Node c : n.children) flatten(c, out);
    }

    /**
     * 稳定排序：先上后下、同高先左后右。
     *
     * ★刻意手写插入排序，不用 java.util.Comparator：
     *   bt34 的 d8 处理「实现泛型接口的内部类」时会 NPE
     *   （`Cannot invoke "String.length()" because "<parameter1>" is null`，崩在 Xxx$Cmp.class），
     *   命名静态嵌套类也救不了（vc184 实录）。节点数量级 ≤ 几百，O(n²) 完全够。
     */
    public static void sortByPos(List<Node> list) {
        for (int i = 1; i < list.size(); i++) {
            Node cur = list.get(i);
            int j = i - 1;
            while (j >= 0 && less(cur, list.get(j))) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, cur);
        }
    }

    private static boolean less(Node a, Node b) {
        if (a.t != b.t) return a.t < b.t;
        return a.l < b.l;
    }

    /**
     * 标签求值三级回退：自身 text/desc → 子节点文本拼接 → 同 y 邻近文本配对。
     * password 节点一律不导出文本（只给掩码）。
     */
    public static String labelOf(Node n, List<Node> all) {
        if (n.password) return "•••";
        String own = firstNonEmpty(n.text, n.desc);
        if (own.length() > 0) return clip(own, 60);

        List<String> acc = new ArrayList<String>();
        collectText(n, 0, acc);
        if (!acc.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String s : acc) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s);
            }
            return clip(sb.toString(), 80);
        }

        // 同 y 邻近：Switch / 无标签容器 → 取同一行左侧最近的文本节点
        int halfH = Math.max(30, n.height() / 2 + 12);
        Node best = null;
        int bestDx = Integer.MAX_VALUE;
        for (Node o : all) {
            if (o == n) continue;
            if (o.clickable || o.longClickable) continue;
            if (o.password) continue;
            String ot = firstNonEmpty(o.text, o.desc);
            if (ot.length() == 0) continue;
            if (Math.abs(o.cy() - n.cy()) > halfH) continue;
            int dx = Math.abs(o.cx() - n.cx());
            if (dx < bestDx) { bestDx = dx; best = o; }
        }
        return best == null ? "" : clip(firstNonEmpty(best.text, best.desc), 60);
    }

    private static void collectText(Node n, int depth, List<String> acc) {
        if (depth > 3 || acc.size() >= 3) return;
        for (Node c : n.children) {
            if (acc.size() >= 3) return;
            if (c.password || !c.onScreen()) continue;
            String x = firstNonEmpty(c.text, c.desc).trim();
            if (x.length() > 0) {
                String v = clip(x, 40);
                if (!acc.contains(v)) acc.add(v);
            }
            collectText(c, depth + 1, acc);
        }
    }

    // ------------------------------------------------------------------ 应用黑名单

    /**
     * 拒绝操作的应用（产品红线：不做超级 App 的写操作）。
     * 依据 = 微信《软件许可及服务协议》7.3 明禁第三方自动化，腾讯把模拟点击判为外挂风控；
     * 后果由用户承担（封号）而产品零收益，且与「不做有失公平的事」冲突。
     * 金融/支付类同理：任何点击都可能触发资金动作。
     */
    private static final String[] BLOCK_EXACT = {
            "com.tencent.mm",                 // 微信
            "com.tencent.mobileqq",           // QQ
            "com.tencent.tim",
            "com.eg.android.AlipayGphone",    // 支付宝
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.sankuai.meituan",
            "me.ele",
            "com.unionpay",
            "com.antfortune.wealth",          // 蚂蚁财富
            "com.sina.weibo",
            "com.ss.android.ugc.aweme",
    };

    /** 包名分段关键词（任意一段包含即拒）。 */
    private static final String[] BLOCK_SEG = {
            "bank", "pay", "wallet", "alipay", "wechat", "tenpay", "unionpay",
            "icbc", "cmbchina", "ccb", "abchina", "boc", "spdb", "cmbc",
            "citicbank", "citibank", "hsbc", "alipayhk", "weixin",
    };

    public static boolean blockedPkg(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        String p = pkg.trim();
        for (String b : BLOCK_EXACT) if (b.equals(p)) return true;
        String lo = p.toLowerCase(Locale.US);
        String[] seg = lo.split("[.\\-_]");
        for (String s : seg) {
            if (s.length() == 0) continue;
            for (String k : BLOCK_SEG) if (s.contains(k)) return true;
        }
        return false;
    }

    public static String blockReason(String pkg) {
        return "拒绝在 " + pkg + " 上做界面操作：社交/电商/金融类应用禁止脚本点击"
                + "（会被判为外挂并导致用户封号，且可能触发资金动作）。"
                + "这类应用请改用读通知/短信，或让用户自己操作。";
    }

    // ------------------------------------------------------------------ 划动

    /**
     * 方向 → 起止坐标（屏幕中心向两侧展开，frac 是相对屏高的滑动幅度）。
     * "swipe up" 在 Android 语义 = 手指向上滑 = 内容向下滚动（看后面的内容）。
     */
    public static int[] swipePoints(String dir, double frac, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        double f = frac <= 0 ? 0.6 : Math.min(0.9, Math.max(0.15, frac));
        int cx = w / 2;
        int cy = h / 2;
        int dx = (int) (w * f / 2);
        int dy = (int) (h * f / 2);
        if ("up".equals(dir)) return new int[]{cx, cy + dy, cx, cy - dy};
        if ("down".equals(dir)) return new int[]{cx, cy - dy, cx, cy + dy};
        if ("left".equals(dir)) return new int[]{cx + dx, cy, cx - dx, cy};
        if ("right".equals(dir)) return new int[]{cx - dx, cy, cx + dx, cy};
        return null;
    }

    public static boolean validDir(String dir) {
        return "up".equals(dir) || "down".equals(dir) || "left".equals(dir) || "right".equals(dir);
    }

    // ------------------------------------------------------------------ 杂项

    /** "android.widget.TextView" → "TextView"（省 token）。 */
    public static String shortClass(String cls) {
        if (cls == null) return "";
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return "";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > max ? x.substring(0, max) + "…" : x;
    }
}
