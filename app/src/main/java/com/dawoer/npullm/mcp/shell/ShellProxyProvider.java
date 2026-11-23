package com.dawoer.npullm.mcp.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.dawoer.npullm.mcp.shizuku.ShizukuShell;

/**
 * vc183（S3）：shell 执行代理 provider（**跑在主进程**）。
 *
 * 为什么必须有这一层（真机实测取证，2026-11-22）：
 *   Shizuku 的 binder 由 ShizukuProvider 分发，而 provider 硬要求 multiprocess=false
 *   → 只有主进程拿得到 binder。日志铁证：同一时刻主进程 "state=ready"、
 *   :mcp 进程 "state=not_running"。而 S3 的 L3 工具（apps.launch / foreground.now /
 *   settings.*）全都要 shell uid 2000 才能执行，它们却长在 :mcp 进程里。
 *   → 用 exported=false 的同 uid provider 做跨进程转发（:mcp → 主进程 → Shizuku shell）。
 *
 * 安全：exported=false，非同 uid 的调用者直接被系统挡（Permission Denial）；
 * 不新增任何对外接口面。
 */
public final class ShellProxyProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dawoer.npullm.shellproxy";
    public static final String M_STATE = "state";
    public static final String M_RUN = "run";
    public static final String K_CMD = "cmd";
    public static final String K_CODE = "code";
    public static final String K_OUT = "out";
    public static final String K_ERR = "err";
    public static final String K_STATE = "state";

    /** exec 结果码：代理层自己的失败（不是命令 exit code）。 */
    public static final int E_NO_CMD = -1;
    public static final int E_NOT_READY = -2;
    public static final int E_EXEC = -3;

    private static final String TAG = "PoMcpShellProxy";
    /** 单次输出上限：dumpsys 可达 60MB，超过必然是把全量 dump 读进来了（设计错误）。 */
    private static final int CAP = 8192;

    @Override
    public boolean onCreate() {
        try {
            if (getContext() != null) {
                ShizukuShell.init(getContext().getApplicationContext());
            }
        } catch (Throwable ignored) { }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (M_STATE.equals(method)) {
            out.putString(K_STATE, waitReady(2000));
            return out;
        }
        if (!M_RUN.equals(method)) {
            out.putInt(K_CODE, E_NO_CMD);
            out.putString(K_ERR, "unknown method: " + method);
            return out;
        }
        String[] cmd = extras == null ? null : extras.getStringArray(K_CMD);
        if (cmd == null || cmd.length == 0) {
            out.putInt(K_CODE, E_NO_CMD);
            out.putString(K_ERR, "empty cmd");
            return out;
        }
        // 主进程可能刚被系统拉起（provider 冷启动），binder 还没到：给它一点时间。
        String st = waitReady(2500);
        if (!ShizukuShell.ST_READY.equals(st)) {
            out.putInt(K_CODE, E_NOT_READY);
            out.putString(K_STATE, st);
            out.putString(K_ERR, "shizuku " + st);
            return out;
        }
        ShizukuShell.Res r = ShizukuShell.exec(cmd);
        if (r == null) {
            out.putInt(K_CODE, E_EXEC);
            out.putString(K_ERR, "exec 返回 null（binder 断或进程被杀）");
            return out;
        }
        out.putInt(K_CODE, r.code);
        out.putString(K_OUT, cap(r.out));
        out.putString(K_ERR, cap(r.err));
        out.putString(K_STATE, ShizukuShell.ST_READY);
        return out;
    }

    /** 轮询到 ready 或超时（binder 到达是异步的，不能只看一次）。 */
    private static String waitReady(long ms) {
        String st = ShizukuShell.state();
        long end = System.currentTimeMillis() + ms;
        while (!ShizukuShell.ST_READY.equals(st) && System.currentTimeMillis() < end) {
            try { Thread.sleep(100L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            st = ShizukuShell.state();
        }
        if (!ShizukuShell.ST_READY.equals(st)) Log.i(TAG, "not ready: " + st);
        return st;
    }

    private static String cap(String s) {
        if (s == null) return "";
        if (s.length() <= CAP) return s;
        return s.substring(0, CAP) + "…(截断 " + (s.length() - CAP) + " 字符)";
    }

    // ---- ContentProvider 未使用的抽象方法（d8 要求全部实现） ----

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
