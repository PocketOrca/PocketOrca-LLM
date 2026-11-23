package com.dawoer.npullm.mcp;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * MCP 配置：端口、key 落盘、Origin 白名单。
 *
 * key 存**文件**而不是 SharedPreferences —— SharedPreferences 跨进程不共享，
 * vc170b 就是栽在"两个进程各生成一把 key"上。文件用 createNewFile() 原子占位，谁创建谁写。
 */
public final class McpConfig {

    private McpConfig() {}

    public static final int PORT = 8086;
    public static final int NOTIF_ID = 47;
    public static final String CHANNEL_ID = "mcp_server_v1";
    public static final String PREFS = "npullm_mcp";

    private static final String KEY_FILE = "mcp_key.txt";

    private static File keyFile(Context ctx) {
        return new File(ctx.getFilesDir(), KEY_FILE);
    }

    /** 幂等：已存在则读，不存在则原子创建。任何进程调用都安全。 */
    public static String ensureKey(Context ctx) {
        File f = keyFile(ctx);
        try {
            if (f.exists()) {
                String k = readFile(f);
                if (k != null && k.startsWith(McpAuth.KEY_PREFIX)) return k;
            }
            if (f.createNewFile()) {
                String k = McpAuth.generateKey();
                writeFile(f, k);
                return k;
            }
            // 别的进程正在写：等它落盘
            for (int i = 0; i < 25; i++) {
                try { Thread.sleep(20L); } catch (InterruptedException ignored) { }
                String k = readFile(f);
                if (k != null && k.startsWith(McpAuth.KEY_PREFIX)) return k;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** UI 只读，不触发创建（未启动服务时显示"未生成"）。 */
    public static String peekKey(Context ctx) {
        File f = keyFile(ctx);
        if (!f.exists()) return null;
        String k = readFile(f);
        return (k != null && k.startsWith(McpAuth.KEY_PREFIX)) ? k : null;
    }

    /** 重置：删文件后重新生成（运行中需重启服务）。 */
    public static String resetKey(Context ctx) {
        File f = keyFile(ctx);
        try { if (f.exists()) f.delete(); } catch (Exception ignored) { }
        return ensureKey(ctx);
    }

    /** 默认空 = 拒绝任何带 Origin 的请求（防 DNS rebinding），与规范一致但可控。 */
    public static String[] allowOrigins(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("allow_origins", "");
            if (raw == null || raw.trim().length() == 0) return new String[0];
            String[] parts = raw.split(",");
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (String p : parts) if (p.trim().length() > 0) out.add(p.trim());
            return out.toArray(new String[0]);
        } catch (Exception e) {
            return new String[0];
        }
    }

    private static String readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    private static void writeFile(File f, String s) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }
}
