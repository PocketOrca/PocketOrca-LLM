package com.dawoer.npullm.mcp.shell;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * vc183（S3）：`​:mcp` 进程侧的 shell 客户端 —— 通过同 uid 私有 provider 把命令转发到主进程。
 *
 * 为什么不能直接调 ShizukuShell：binder 只在主进程（见 ShellProxyProvider 的类注释）。
 * 为什么不用 AIDL：ContentProvider.call 天然跨进程、同 uid 免权限、免维护 .aidl 文件。
 *
 * 失败语义：拿不到结果一律返回 null，由工具层转 NOT_AVAILABLE 并给「Shizuku 未就绪」的话术，
 * 绝不返回空的成功结果（静默失败率必须为零）。
 */
public final class ShellClient {

    private static final Uri URI = Uri.parse("content://" + ShellProxyProvider.AUTHORITY);

    private ShellClient() {}

    /** Shizuku 状态（经主进程代查）。1.2 秒内复用，避免 4 个工具的闸门各打一次 binder。 */
    private static volatile String cachedState = "";
    private static volatile long cachedAt = 0L;

    public static String state(Context ctx) {
        long now = System.currentTimeMillis();
        String s = cachedState;
        if (s.length() > 0 && now - cachedAt < 1200L) return s;
        String got = "";
        try {
            ContentResolver cr = ctx.getContentResolver();
            Bundle b = cr.call(URI, ShellProxyProvider.M_STATE, null, null);
            if (b != null) {
                String v = b.getString(ShellProxyProvider.K_STATE);
                if (v != null) got = v;
            }
        } catch (Throwable t) {
            got = "";   // provider 不在（主进程被杀且拉不起来）→ 当未就绪
        }
        cachedState = got;
        cachedAt = System.currentTimeMillis();
        return got;
    }

    public static boolean ready(Context ctx) {
        return "ready".equals(state(ctx));
    }

    /** 执行一条命令。cmd 必须是 argv 形式（全路径 + 分开的参数），不拼 shell 串。 */
    public static Res run(Context ctx, String... cmd) {
        if (cmd == null || cmd.length == 0) return null;
        try {
            Bundle in = new Bundle();
            in.putStringArray(ShellProxyProvider.K_CMD, cmd);
            ContentResolver cr = ctx.getContentResolver();
            Bundle b = cr.call(URI, ShellProxyProvider.M_RUN, null, in);
            if (b == null) return null;
            int code = b.getInt(ShellProxyProvider.K_CODE, -1);
            if (code < 0) return null;      // 代理层失败（未就绪/exec null）→ 不冒充命令结果
            String out = b.getString(ShellProxyProvider.K_OUT);
            String err = b.getString(ShellProxyProvider.K_ERR);
            return new Res(code, out == null ? "" : out, err == null ? "" : err);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 命令结果。 */
    public static final class Res {
        public final int code;
        public final String out;
        public final String err;

        Res(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        public boolean ok() {
            return code == 0;
        }

        /** 超时/未就绪的统一话术。 */
        public static String unavailableHint(String st) {
            if (st == null || st.length() == 0) return "Shizuku 代理不可用（主进程未运行）";
            if ("not_installed".equals(st)) return "手机未安装 Shizuku";
            if ("not_running".equals(st)) return "Shizuku 服务未运行，请在手机 Shizuku 里重新激活（无线调试）";
            if ("no_permission".equals(st)) return "Shizuku 未授权本应用，请在 Shizuku 里允许";
            return "Shizuku 状态异常: " + st;
        }
    }
}
