package com.dawoer.npullm.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 * - 顺序确定性：tools/list 按 name 升序（影响 client 的 prompt cache，不能随注册顺序漂移）。
 * - 可见性：visibleByDefault=false 的工具（不可逆）默认不进目录；后续由 scope 开关控制。
 */
public final class McpToolRegistry {

    private final Map<String, McpToolDef> tools = new LinkedHashMap<>();
    private final McpRateLimit rate;
    private final McpTrace trace;

    public McpToolRegistry(McpRateLimit rate, McpTrace trace) {
        this.rate = rate;
        this.trace = trace;
    }

    public void register(McpToolDef def) {
        if (def == null || def.name == null) throw new IllegalArgumentException("tool def 非法");
        tools.put(def.name, def);
    }

    public McpToolDef get(String name) {
        return tools.get(name);
    }

    public int size() {
        return tools.size();
    }

    public List<String> names() {
        List<String> l = new ArrayList<>(tools.keySet());
        Collections.sort(l);
        return l;
    }

    /** tools/list 的 tools 数组（已按名排序，只含当前可见的）。 */
    public JSONArray listJson() {
        JSONArray arr = new JSONArray();
        for (String n : names()) {
            McpToolDef d = tools.get(n);
            if (d.visible()) arr.put(d.toJson());
        }
        return arr;
    }

    public int visibleCount() {
        int c = 0;
        for (McpToolDef d : tools.values()) if (d.visible()) c++;
        return c;
    }

    /**
     * tools/call。返回 McpResult，永不抛业务异常（协议错才抛 McpException）。
     * 顺序：可见性 → 参数校验 → 限流 → 串行门 → 执行（15s 超时）→ trace。
     */
    public McpResult call(String name, JSONObject args) {
        long t0 = System.currentTimeMillis();
        McpToolDef def = tools.get(name);
        if (def == null || !def.visible()) {
            // 被拒的调用也要进 trace（vc186 修正）：参数错/工具没开通是 agent 最常犯的错，
            // 不记下来就等于「静默失败」——诊断里只能看到成功记录，等于没有事实来源。
            McpResult r = McpResult.error("NOT_AVAILABLE",
                    "工具不存在或当前未开通: " + name + "（权限/scope 未开启时工具不会出现在 tools/list）");
            traceCall(name, args, r, t0);
            return r;
        }
        String bad = McpParamCheck.check(def.inputSchema, args);
        if (bad != null) {
            McpResult r = McpResult.error("INVALID_ARGUMENT", bad);
            traceCall(name, args, r, t0);
            return r;
        }

        if (!rate.tryAcquire(System.currentTimeMillis())) {
            McpResult r = McpResult.error("TIMEOUT", "超过 60 次/分限流，稍后再试");
            traceCall(name, args, r, t0);
            return r;
        }

        McpResult r;
        synchronized (rate.serialGate()) {
            r = execWithTimeout(def, args, t0);
        }
        traceCall(name, args, r, t0);
        return r;
    }

    /** 统一 trace 出口：成功/失败/被拒走同一条路径，避免「有的错误没记」。 */
    private void traceCall(String name, JSONObject args, McpResult r, long t0) {
        if (trace == null) return;
        String a = args == null ? "{}" : args.toString();
        trace.tool(name, a, r != null && !r.isError, System.currentTimeMillis() - t0,
                McpTrace.codeOf(r), McpTrace.errText(r));
    }

    private McpResult execWithTimeout(McpToolDef def, JSONObject args, long t0) {
        final Object[] box = new Object[1];
        Thread t = new Thread(new Runner(def, args, box), "mcp-tool-" + def.name);
        t.setDaemon(true);
        t.start();
        try {
            t.join(15000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpResult.error("TIMEOUT", "被中断");
        }
        if (t.isAlive()) return McpResult.error("TIMEOUT", "工具执行超过 15 秒: " + def.name);
        Object o = box[0];
        if (o instanceof McpResult) return (McpResult) o;
        if (o instanceof Throwable) {
            // 执行器异常必转 isError，禁止裸抛（静默失败率=0）
            return McpResult.error("INTERNAL", String.valueOf(((Throwable) o).getMessage()));
        }
        return McpResult.error("INTERNAL", "执行器没有返回结果");
    }

    /** 命名静态嵌套（d8 零匿名类铁律）。 */
    private static final class Runner implements Runnable {
        private final McpToolDef def;
        private final JSONObject args;
        private final Object[] box;

        Runner(McpToolDef def, JSONObject args, Object[] box) {
            this.def = def;
            this.args = args;
            this.box = box;
        }

        @Override
        public void run() {
            try {
                box[0] = def.executor.exec(args == null ? new JSONObject() : args);
            } catch (Throwable e) {
                box[0] = e;
            }
        }
    }
}
