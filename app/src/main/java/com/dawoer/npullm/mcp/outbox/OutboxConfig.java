package com.dawoer.npullm.mcp.outbox;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * outbox 配置（vc185）：endpoint / key / 白名单 / 勿扰 / 预算。
 *
 * 存储用**文件**不用 SharedPreferences：UI 在主进程写、:mcp 进程读，跨进程不共享
 * （vc170b/vc183 同源坑）。写入 tmp+rename，读到的一定是完整 JSON。
 */
public final class OutboxConfig {

    private static final String FILE = "mcp_outbox.json";

    private OutboxConfig() {}

    public static JSONObject get(Context ctx) {
        JSONObject def = McpOutboxLogic.defaultConfig();
        if (ctx == null) return def;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return def;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            // 缺字段一律回默认，避免旧配置文件让新代码崩
            java.util.Iterator<String> it = def.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!o.has(k)) o.put(k, def.opt(k));
            }
            return o;
        } catch (Throwable t) {
            return def;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    public static void save(Context ctx, JSONObject o) {
        if (ctx == null || o == null) return;
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        File dst = new File(dir, FILE);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
        if (!tmp.renameTo(dst)) {
            try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) { }
            tmp.renameTo(dst);
        }
    }

    /** 只改某几个键（读-改-写）。 */
    public static synchronized void set(Context ctx, String key, Object value) {
        try {
            JSONObject o = get(ctx);
            o.put(key, value);
            save(ctx, o);
        } catch (Throwable ignored) { }
    }

    public static boolean enabled(Context ctx) {
        return McpOutboxLogic.cfgBool(get(ctx), "enabled", false);
    }

    public static String endpoint(Context ctx) {
        return McpOutboxLogic.cfg(get(ctx), "endpoint", "");
    }

    public static String key(Context ctx) {
        return McpOutboxLogic.cfg(get(ctx), "key", "");
    }

    public static String whitelist(Context ctx) {
        return McpOutboxLogic.cfg(get(ctx), "whitelist", "");
    }

    public static String quietHours(Context ctx) {
        return McpOutboxLogic.cfg(get(ctx), "quiet_hours", "23:00-07:30");
    }

    public static int maxWakeups(Context ctx) {
        int v = McpOutboxLogic.cfgInt(get(ctx), "max_wakeups_per_hour", McpOutboxLogic.MAX_WAKEUPS_PER_HOUR);
        return v < 0 ? 0 : Math.min(v, 60);
    }

    public static long windowMs(Context ctx) {
        int s = McpOutboxLogic.cfgInt(get(ctx), "aggregate_window_s", (int) (McpOutboxLogic.AGG_WINDOW_MS / 1000));
        if (s < 5) s = 5;
        if (s > 600) s = 600;
        return s * 1000L;
    }

    /** 可直接投递？（开关 + 端点合法），UI 与 worker 共用同一判据。 */
    public static boolean deliverable(Context ctx) {
        JSONObject o = get(ctx);
        return McpOutboxLogic.cfgBool(o, "enabled", false)
                && McpOutboxLogic.validEndpoint(McpOutboxLogic.cfg(o, "endpoint", ""));
    }
    // ===== vc185b：跨进程运行态（服务在 :mcp，UI 在主进程）=====
    private static final String RUN_FILE = "mcp_outbox.run";
    private static final String STATE_FILE = "mcp_outbox.state.json";

    /** 服务启停标记：:mcp 写，主进程只读。比读自己的静态变量可靠（进程不同）。 */
    public static void markRunning(Context ctx, boolean on) {
        writeFile(ctx, RUN_FILE, (on ? "1 " : "0 ") + System.currentTimeMillis());
    }

    public static boolean isRunning(Context ctx) {
        String s = readText(ctx, RUN_FILE);
        return s != null && s.startsWith("1");
    }

    /** worker 的最近一次投递结果（跨进程可读）。 */
    public static JSONObject runtimeState(Context ctx) {
        String s = readText(ctx, STATE_FILE);
        if (s == null || s.length() == 0) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static void writeState(Context ctx, JSONObject o) {
        if (ctx == null || o == null) return;
        writeFile(ctx, STATE_FILE, o.toString());
    }

    private static String readText(Context ctx, String name) {
        if (ctx == null) return null;
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void writeFile(Context ctx, String name, String text) {
        if (ctx == null) return;
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, name + ".tmp");
        File dst = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
        if (!tmp.renameTo(dst)) {
            try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) { }
            tmp.renameTo(dst);
        }
    }

}