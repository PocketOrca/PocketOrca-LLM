package com.dawoer.npullm.mcp.shizuku;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Shizuku 桥（vc182 / S2）。
 *
 * 与 S1 的区别：S1 只有反射（AAR 未引入，app 侧恒 not_installed）；S2 引入官方 AAR
 * （dev.rikka.shizuku:api 13.1.0 + provider），因此能真正拿到 binder、申请权限、跑 shell。
 * 仍然**不写 Shizuku 类型的字段**，调用点用 try/catch 兜 NoClassDefFoundError ——
 * 这样在未引入 AAR 的构建里这段代码也不会炸，只是恒返回 not_installed。
 *
 * 四态：not_installed / not_running / no_permission / ready（UI 向导按此分支）。
 * Shizuku 服务重启后失效，必须重新激活（无线调试），所以 state() 每次重算，不缓存。
 */
public final class ShizukuShell {

    private static final String TAG = "PoMcpShizuku";

    public static final String ST_NOT_INSTALLED = "not_installed";
    public static final String ST_NOT_RUNNING = "not_running";
    public static final String ST_NO_PERMISSION = "no_permission";
    public static final String ST_READY = "ready";

    private static final String CLS = "rikka.shizuku.Shizuku";
    // newProcess 走 exec，不搜索 PATH —— 裸名 `pm` 会静默失败（exit 非 0、stdout 空），
    // 表现为「pm grant 执行了但权限没给上」。一律全路径。
    private static final String BIN_PM = "/system/bin/pm";
    private static final String BIN_CMD = "/system/bin/cmd";

    private ShizukuShell() {}

    /** 当前状态（每次都重新算，不缓存）。 */
    public static String state() {
        try {
            Class<?> c = Class.forName(CLS);
            boolean ping;
            try {
                Object r = c.getMethod("pingBinder").invoke(null);
                ping = r instanceof Boolean && (Boolean) r;
            } catch (NoSuchMethodException e) {
                ping = false;
            } catch (Throwable t) {
                ping = false;
            }
            if (!ping) {
                // 类在 = 装了 Shizuku 但服务没跑（或本进程还没收到 binder）
                return ST_NOT_RUNNING;
            }
            try {
                Object g = c.getMethod("checkSelfPermission").invoke(null);
                int code = g instanceof Integer ? (Integer) g : -1;
                return code == 0 ? ST_READY : ST_NO_PERMISSION;   // 0 = PERMISSION_GRANTED
            } catch (NoSuchMethodException e) {
                return ST_NO_PERMISSION;
            }
        } catch (ClassNotFoundException e) {
            return ST_NOT_INSTALLED;
        } catch (Throwable t) {
            return ST_NOT_RUNNING;
        }
    }

    public static boolean isReady() {
        return ST_READY.equals(state());
    }

    /**
     * 注册 binder 到达/断开监听（幂等，主进程与 :mcp 进程各调一次）。
     * 13.x 用 manifest 里的 moe.shizuku.client.V3_SUPPORT meta-data 让 Shizuku 主动分发 binder。
     */
    public static void init(Context ctx) {
        if (ctx == null) return;
        try {
            Class<?> c = Class.forName(CLS);
            if (binderListener == null) {
                Class<?> recvIface = Class.forName("rikka.shizuku.Shizuku$OnBinderReceivedListener");
                Class<?> deadIface = Class.forName("rikka.shizuku.Shizuku$OnBinderDeadListener");
                binderListener = java.lang.reflect.Proxy.newProxyInstance(
                        recvIface.getClassLoader(), new Class<?>[]{recvIface}, new BinderListener());
                deadListener = java.lang.reflect.Proxy.newProxyInstance(
                        deadIface.getClassLoader(), new Class<?>[]{deadIface}, new DeadListener());
                c.getMethod("addBinderReceivedListenerSticky", recvIface).invoke(null, binderListener);
                c.getMethod("addBinderDeadListener", deadIface).invoke(null, deadListener);
            }
            Log.i(TAG, "init ok, state=" + state());
        } catch (Throwable t) {
            Log.i(TAG, "init skipped: " + t.getClass().getSimpleName());
        }
    }

    private static Object binderListener;
    private static Object deadListener;

    /** 命名静态嵌套（d8 零匿名类铁律）：binder 到达。 */
    public static final class BinderListener implements java.lang.reflect.InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            Log.i(TAG, "binder received → state=" + state());
            return null;
        }
    }

    /** binder 断开（Shizuku 被杀/重启）：状态自动重算，UI 应刷新。 */
    public static final class DeadListener implements java.lang.reflect.InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            Log.i(TAG, "binder dead → 需重新激活 Shizuku");
            return null;
        }
    }

    /**
     * 申请 Shizuku 权限。**只能在有 Activity 的进程调**（:mcp 无界面，权限按 uid 共享所以主进程申请即可）。
     * 结果通过 Shizuku.addRequestPermissionResultListener 回传；为简化，调用方轮询 state() 即可。
     */
    public static void requestPermission(int requestCode) {
        try {
            Class<?> c = Class.forName(CLS);
            c.getMethod("requestPermission", int.class).invoke(null, requestCode);
        } catch (Throwable t) {
            Log.i(TAG, "requestPermission failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 以 shell(uid 2000) 身份执行命令，返回 stdout（stderr 合并）。
     * 不可用返回 null（上层转 NOT_AVAILABLE，不猜、不重试）。
     */
    public static Res exec(String... cmd) {
        if (cmd == null || cmd.length == 0) return null;
        Object rp = null;
        Class<?> rpIface = null;
        try {
            // ★13.x 已经没有 Shizuku.newProcess(...)：只有 Shizuku.getBinder() + IShizukuService
            //   （ShizukuRemoteProcess 的构造是 package-private，外部拿不到）。所以直接 AIDL 调用，
            //   类在 aidl-13.1.5.jar 里，已随 dex 打包。
            Class<?> c = Class.forName(CLS);
            Object binderObj = c.getMethod("getBinder").invoke(null);
            if (!(binderObj instanceof android.os.IBinder)) return null;
            Class<?> svcIface = Class.forName("moe.shizuku.server.IShizukuService");
            Object svc = Class.forName("moe.shizuku.server.IShizukuService$Stub")
                    .getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binderObj);
            rp = svcIface.getMethod("newProcess", String[].class, String[].class, String.class)
                    .invoke(svc, cmd, null, null);
            if (rp == null) return null;
            rpIface = Class.forName("moe.shizuku.server.IRemoteProcess");

            // stdout/stderr 必须并发读：单线程读一路会被另一路塞满 64K 缓冲卡死
            StringBuilder ob = new StringBuilder();
            StringBuilder eb = new StringBuilder();
            java.io.InputStream in = openStream(rpIface, rp, "getInputStream");
            java.io.InputStream er = openStream(rpIface, rp, "getErrorStream");
            Thread to = new Thread(new Drain(in, ob));
            Thread te = new Thread(new Drain(er, eb));
            to.start();
            te.start();
            Object codeObj = rpIface.getMethod("waitFor").invoke(rp);
            int code = codeObj instanceof Integer ? (Integer) codeObj : -1;
            to.join(3000L);
            te.join(3000L);
            if (code != 0) {
                Log.i(TAG, (cmd.length > 1 ? cmd[1] : cmd[0]) + " exit=" + code + " err=" + eb.toString().trim());
            }
            return new Res(ob.toString(), eb.toString(), code);
        } catch (Throwable t) {
            Log.i(TAG, "exec failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        } finally {
            try { if (rp != null) rpIface.getMethod("destroy").invoke(rp); } catch (Throwable ignored) { }
        }
    }

    /** IRemoteProcess 的三路流都是 ParcelFileDescriptor，转成普通 InputStream。 */
    private static java.io.InputStream openStream(Class<?> rpIface, Object rp, String method) {
        try {
            Object pfd = rpIface.getMethod(method).invoke(rp);
            if (!(pfd instanceof android.os.ParcelFileDescriptor)) return null;
            return new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    (android.os.ParcelFileDescriptor) pfd);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 命令结果（stdout/stderr/exit）。 */
    public static final class Res {
        public final String out;
        public final String err;
        public final int code;

        Res(String out, String err, int code) {
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.code = code;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    /** 命名静态嵌套的流读取器（d8 零匿名类）。 */
    private static final class Drain implements Runnable {
        private final InputStream in;
        private final StringBuilder sb;

        Drain(InputStream in, StringBuilder sb) {
            this.in = in;
            this.sb = sb;
        }

        @Override
        public void run() {
            if (in == null) return;
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 免掉"允许受限制的设置 → 通知使用权"四步：直接让系统把本应用的服务加进白名单。
     * 组件名必须与实际 manifest 一致（pkg/.mcp.access.NotificationCollector）。
     */
    public static boolean allowNotificationListener(String component) {
        if (component == null || component.length() == 0) return false;
        Res r = exec(BIN_CMD, "notification", "allow_listener", component);
        return r != null && r.ok();
    }

    /**
     * 免弹窗授予运行时权限：`pm grant <pkg> <perm>`，然后**用 checkSelfPermission 读回**判定。
     * 读回是必须的 —— pm grant 返回 0 不代表生效（OEM 可能拒绝）。
     */
    public static boolean grantRuntime(Context ctx, String pkg, String perm) {
        if (ctx == null || pkg == null || perm == null) return false;
        if (granted(ctx, perm)) return true;
        Res r = exec(BIN_PM, "grant", pkg, perm);
        boolean ok = granted(ctx, perm);
        Log.i(TAG, "pm grant " + perm + " → " + ok
                + (r == null ? " (exec null)" : (" code=" + r.code + " " + r.err.trim())));
        return ok;
    }

    /** 撤销（验收用：撤权后工具必须立刻从 tools/list 消失）。 */
    public static boolean revokeRuntime(Context ctx, String pkg, String perm) {
        if (ctx == null || pkg == null || perm == null) return false;
        exec(BIN_PM, "revoke", pkg, perm);
        return !granted(ctx, perm);
    }

    public static boolean granted(Context ctx, String perm) {
        try {
            return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测某条 shell 命令是否可用（不同 ROM 的 cmd 子命令差异大，先探后用）。 */
    public static boolean supports(String... probe) {
        Res r = exec(probe);
        return r != null && r.ok();
    }
}
