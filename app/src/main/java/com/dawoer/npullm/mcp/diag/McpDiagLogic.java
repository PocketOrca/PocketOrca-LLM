package com.dawoer.npullm.mcp.diag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * vc186：环境自诊的纯逻辑层（零 Android 依赖，JVM 可全测）。
 *
 * 借鉴 google/artemis 的 mobile_diagnose：agent 出问题时不该靠猜，
 * 也不该让人类去翻 logcat —— 一次调用回「结论 + 每条检查 + 有序修复步骤」。
 *
 * 三档结论（与 ARTEMIS 同名，含义自定）：
 *   ready    全部正常
 *   degraded 能用但掉了能力（某项 fail/warn，非致命）
 *   blocked  核心不可用（服务没起 / key 没有），连请求都不该发
 *
 * 铁律：每条检查都必须有 name + detail；status 只能是 ok/warn/fail。
 * 检查项不许「静默略过」——读不到就写 fail 并把原因写进 detail。
 */
public final class McpDiagLogic {

    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";

    public static final String V_READY = "ready";
    public static final String V_DEGRADED = "degraded";
    public static final String V_BLOCKED = "blocked";

    private static final int MAX_NEXT_STEPS = 8;

    private McpDiagLogic() {}

    /** 一条检查结果。critical=true 表示它 fail 就是 blocked（不看别的项）。 */
    public static final class Check {
        public final String name;
        public final String status;
        public final String detail;
        public final String hint;
        public final boolean critical;

        public Check(String name, String status, String detail, String hint, boolean critical) {
            this.name = name == null ? "" : name;
            this.status = status == null ? WARN : status;
            this.detail = detail == null ? "" : detail;
            this.hint = hint == null ? "" : hint;
            this.critical = critical;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("status", status);
                o.put("detail", detail);
                o.put("critical", critical);
                if (hint.length() > 0) o.put("hint", hint);
            } catch (Exception ignored) { }
            return o;
        }
    }

    // ------------------------------------------------------------------ 构造检查

    public static Check ok(String name, String detail) {
        return new Check(name, OK, detail, "", false);
    }

    public static Check okCritical(String name, String detail) {
        return new Check(name, OK, detail, "", true);
    }

    public static Check warn(String name, String detail, String hint) {
        return new Check(name, WARN, detail, hint, false);
    }

    public static Check fail(String name, String detail, String hint) {
        return new Check(name, FAIL, detail, hint, false);
    }

    public static Check failCritical(String name, String detail, String hint) {
        return new Check(name, FAIL, detail, hint, true);
    }

    /** 布尔检查：good → ok，否则 fail（非致命）。 */
    public static Check bool(String name, boolean good, String okDetail, String badDetail, String hint) {
        return good ? ok(name, okDetail) : fail(name, badDetail, hint);
    }

    // ------------------------------------------------------------------ 结论与输出

    /** 任一 critical fail → blocked；否则任一 fail/warn → degraded；全 ok → ready。 */
    public static String verdict(List<Check> checks) {
        if (checks == null) return V_BLOCKED;
        boolean degraded = false;
        for (Check c : checks) {
            if (c == null) continue;
            if (FAIL.equals(c.status)) {
                if (c.critical) return V_BLOCKED;
                degraded = true;
            } else if (WARN.equals(c.status)) {
                degraded = true;
            }
        }
        return degraded ? V_DEGRADED : V_READY;
    }

    /** 有序修复步骤：critical fail → fail → warn；同名去重；最多 8 条。 */
    public static JSONArray nextSteps(List<Check> checks) {
        JSONArray out = new JSONArray();
        if (checks == null) return out;
        List<String> seen = new ArrayList<>();
        addSteps(out, seen, checks, 0);   // 0 = critical fail
        addSteps(out, seen, checks, 1);   // 1 = 普通 fail
        addSteps(out, seen, checks, 2);   // 2 = warn
        return out;
    }

    private static void addSteps(JSONArray out, List<String> seen, List<Check> checks, int bucket) {
        for (Check c : checks) {
            if (out.length() >= MAX_NEXT_STEPS) return;
            if (c == null || c.hint.length() == 0) continue;
            boolean want;
            if (bucket == 0) want = FAIL.equals(c.status) && c.critical;
            else if (bucket == 1) want = FAIL.equals(c.status) && !c.critical;
            else want = WARN.equals(c.status);
            if (!want || seen.contains(c.name)) continue;
            seen.add(c.name);
            try { out.put(c.name + ": " + c.hint); } catch (Exception ignored) { }
        }
    }

    /**
     * 完整诊断 JSON。
     * extra 里的键会平铺到顶层（server/version/tools 等），trace 是已脱敏的原始行。
     */
    public static JSONObject json(List<Check> checks, JSONObject extra, JSONArray traceTail) {
        JSONObject o = new JSONObject();
        try {
            o.put("verdict", verdict(checks));
            JSONArray arr = new JSONArray();
            if (checks != null) for (Check c : checks) if (c != null) arr.put(c.toJson());
            o.put("checks", arr);
            o.put("next_steps", nextSteps(checks));
            if (extra != null) {
                java.util.Iterator<String> it = extra.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!o.has(k)) o.put(k, extra.opt(k));
                }
            }
            o.put("trace", traceTail == null ? new JSONArray() : traceTail);
        } catch (Exception ignored) { }
        return o;
    }

    /** 端点摘要：截掉 query（可能带 key）并限长，用于把 URL 放进 detail 而不泄漏密钥。 */
    public static String endpointBrief(String url) {
        if (url == null) return "";
        String s = url.trim();
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        if (s.length() > 60) s = s.substring(0, 60) + "...";
        return s;
    }

    /**
     * 从 dumpsys 的 topResumedActivity 行里取出组件名。
     * 真实行是 "topResumedActivity=ActivityRecord{12ab u0 com.x/.Y t:42}" ——
     * 取第一个含 '/' 的 token，而不是第一个 token（第一个是 "ActivityRecord{..." 或 "u0"）。
     */
    public static String firstComponent(String dumpLine) {
        if (dumpLine == null) return "";
        String s = dumpLine.trim();
        int i = s.indexOf("topResumedActivity=");
        if (i >= 0) s = s.substring(i + "topResumedActivity=".length());
        String[] parts = s.split("\\s+");
        String first = parts.length > 0 ? parts[0] : "";
        for (String p : parts) {
            if (p.indexOf('/') > 0) {
                String c = p;
                if (c.length() > 120) c = c.substring(0, 120);
                return c;
            }
        }
        if (first.length() > 120) first = first.substring(0, 120);
        return first;
    }

    /** 统计某状态的数量（自测与 UI 用）。 */
    public static int count(List<Check> checks, String status) {
        if (checks == null || status == null) return 0;
        int n = 0;
        for (Check c : checks) if (c != null && status.equals(c.status)) n++;
        return n;
    }
}
