package com.dawoer.npullm.mcp;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * scope 开关（vc182 S2）：不可逆工具默认不进 tools/list，用户在 UI 里显式开通后才可见。
 *
 * 存储用**文件**，不用 SharedPreferences —— UI 在主进程写、:mcp 进程读，
 * SharedPreferences 跨进程不共享（vc170b 同源坑），文件写入走 tmp+rename 保证读到的是完整 JSON。
 */
public final class McpScope {

    public static final String SEND_SMS = "messages.send";
    /** vc184（S4 GUI）：界面操作 scope。默认关 = ui.dump/ui.tap/ui.swipe 不进 tools/list。 */
    public static final String UI = "ui.control";

    private static final String FILE = "mcp_scopes.json";

    private McpScope() {}

    /** 解析委托给纯类 McpFormat（便于 JVM 自测）。 */
    public static boolean parse(String json, String scope) {
        return McpFormat.scopeEnabled(json, scope);
    }

    public static boolean enabled(Context ctx, String scope) {
        return parse(read(ctx), scope);
    }

    /** 整份 scope JSON（UI 面板回显用）。 */
    public static String all(Context ctx) {
        return read(ctx);
    }

    /** 读-改-写：整份 JSON 重写，scope 少，不担心并发覆盖。 */
    public static synchronized void set(Context ctx, String scope, boolean on) {
        if (ctx == null || scope == null) return;
        try {
            JSONObject o = new JSONObject(read(ctx));
            o.put(scope, on);
            write(ctx, o.toString());
        } catch (Throwable ignored) { }
    }

    private static String read(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return "{}";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "{}";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void write(Context ctx, String s) {
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        File dst = new File(dir, FILE);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
        // rename 原子替换：另一个进程永远读不到写一半的 JSON
        if (!tmp.renameTo(dst)) {
            try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) { }
            tmp.renameTo(dst);
        }
    }
}
