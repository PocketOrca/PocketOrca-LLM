package com.dawoer.npullm.mcp.shell;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vc183（S3）纯逻辑：参数校验 + 命令输出解析 + settings 白名单。
 *
 * 刻意不 import 任何 android 类 —— JVM 测试直接调本类，不需要真机。
 * 所有「解析」都按真机实测输出建模（S25 SM-S931U / Android 16）：
 *   resolve-activity 输出两行，第二行是 "pkg/.Act"
 *   dumpsys activity activities → "    topResumedActivity=ActivityRecord{107257118 u0 pkg/.Act t50}"
 *   dumpsys window            → "  mCurrentFocus=Window{80cfa8 u0 pkg/com.pkg.Act}"
 */
public final class McpShellLogic {

    private McpShellLogic() {}

    /** 包名：至少两段、每段字母开头、只含 [A-Za-z0-9_]，总长 ≤ 100。 */
    public static boolean validPackage(String p) {
        if (p == null || p.length() == 0 || p.length() > 100) return false;
        String[] seg = p.split("\\.", -1);
        if (seg.length < 2) return false;
        for (String s : seg) {
            if (s.length() == 0) return false;
            char c0 = s.charAt(0);
            if (!((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z'))) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_';
                if (!ok) return false;
            }
        }
        return true;
    }

    /** 组件名 "pkg/.Act" 或 "pkg/com.pkg.Act"。 */
    public static boolean validComponent(String c) {
        if (c == null || c.length() > 200) return false;
        int i = c.indexOf('/');
        if (i <= 0 || i == c.length() - 1) return false;
        if (!validPackage(c.substring(0, i))) return false;
        String act = c.substring(i + 1);
        String body = act.startsWith(".") ? act.substring(1) : act;
        if (body.length() == 0) return false;
        for (int k = 0; k < body.length(); k++) {
            char ch = body.charAt(k);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.';
            if (!ok) return false;
        }
        return true;
    }

    /** "pkg/.Act" → 补全成 "pkg/full.Act"（am start 两种都认，这里只做展示统一）。 */
    public static String expandComponent(String c) {
        if (c == null) return "";
        int i = c.indexOf('/');
        if (i <= 0) return c;
        String pkg = c.substring(0, i);
        String act = c.substring(i + 1);
        if (act.startsWith(".")) return pkg + "/" + pkg + act;
        return c;
    }

    /**
     * 解析 `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`。
     * 真机输出：
     *   priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
     *   com.android.settings/.Settings
     * 取最后一行含 "/" 且不含 "=" 的。找不到返回 ""（上层报 NOT_FOUND，不猜组件）。
     */
    public static String parseResolveActivity(String out) {
        if (out == null) return "";
        String[] lines = out.split("\n");
        String found = "";
        for (String raw : lines) {
            String s = raw.trim();
            if (s.length() == 0) continue;
            if (s.indexOf('=') >= 0) continue;
            if (!validComponent(s)) continue;
            found = s;
        }
        return found;
    }

    /**
     * 从 dumpsys 输出解析前台组件。按优先级尝试三路：
     *   topResumedActivity（activity dump）> mFocusedApp（activity dump）> mCurrentFocus（window dump）
     * 返回 "pkg/.Act"（不补全，原样）；全落空返回 ""。
     */
    public static String parseForeground(String dump) {
        if (dump == null || dump.length() == 0) return "";
        String c = firstGroup(dump,
                "topResumedActivity=ActivityRecord\\{[^ ]+ u\\d+ ([^ }]+)");
        if (c.length() > 0) return c;
        c = firstGroup(dump,
                "ResumedActivity: ActivityRecord\\{[^ ]+ u\\d+ ([^ }]+)");
        if (c.length() > 0) return c;
        c = firstGroup(dump,
                "mFocusedApp=ActivityRecord\\{[^ ]+ u\\d+ ([^ }]+)");
        if (c.length() > 0) return c;
        c = firstGroup(dump,
                "mCurrentFocus=Window\\{[^ ]+ u\\d+ ([^ }]+)");
        return c;
    }

    private static String firstGroup(String s, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(s);
            if (m.find()) {
                String g = m.group(1);
                if (g != null && validComponent(g)) return g;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    // ---------------------------------------------------------------- settings

    /** settings 命名空间。 */
    public static boolean validNamespace(String ns) {
        return "system".equals(ns) || "secure".equals(ns) || "global".equals(ns);
    }

    /**
     * 可写白名单（vc183 定调：只放「可逆 + 幂等 + 不涉安全/隐私」的项）。
     * 不在表内一律拒绝 —— 宁可少给能力，也不给外部 agent 写崩系统的机会。
     */
    private static final String[] WRITABLE = {
            "screen_brightness",
            "screen_brightness_mode",
            "screen_brightness_float",
            "screen_off_timeout",
            "font_scale",
            "accelerometer_rotation",
            "display_night_theme",
            "vibrate_when_ringing",
            "haptic_feedback_enabled",
            "sound_effects_enabled",
            "show_touches",
            "pointer_location",
            "transition_animation_scale",
            "window_animation_scale",
            "animator_duration_scale",
            "master_mono",
            "min_refresh_rate",
            "peak_refresh_rate",
    };

    /** 读也要挡的敏感片段（读全量诊断有用，但 token/密码/设备唯一标识不能出设备）。 */
    private static final String[] READ_DENY = {
            "password", "passwd", "token", "secret", "_key", "key_",
            "adb_", "android_id", "serial", "imei", "mac_addr", "macaddr",
            "passphrase", "vpn_", "setup_complete", "user_credential",
    };

    public static boolean writable(String key) {
        if (key == null) return false;
        for (String k : WRITABLE) if (k.equals(key)) return true;
        return false;
    }

    /** 允许的 key 列表（返回给人看的一份说明）。 */
    public static String writableList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WRITABLE.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(WRITABLE[i]);
        }
        return sb.toString();
    }

    public static boolean readDenied(String key) {
        if (key == null || key.length() == 0) return true;
        String k = key.toLowerCase(java.util.Locale.US);
        for (String s : READ_DENY) if (k.contains(s)) return true;
        return false;
    }

    /** key 名字本身的合法性（settings key 只可能是小写字母/数字/下划线/点）。 */
    public static boolean validKey(String key) {
        if (key == null || key.length() == 0 || key.length() > 128) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** 写入值校验：≤512 字节、无控制字符（换行会把 settings 命令拆坏）。空串允许（=清除）。 */
    public static String checkValue(String v) {
        if (v == null) return "value 不能为 null";
        if (v.length() > 512) return "value 过长（上限 512 字符）";
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7f) return "value 含控制字符（换行/制表符等）";
        }
        return null;
    }

    /** 写入前的统一判定，返回 null = 放行，否则是人话原因。 */
    public static String writeDenyReason(String ns, String key) {
        if (!validNamespace(ns)) return "namespace 只能是 system / secure / global";
        if (!validKey(key)) return "key 名字不合法";
        if (readDenied(key)) return "该 key 属敏感项，禁止写入（" + key + "）";
        if (!writable(key)) return "该 key 不在可写白名单内。可写项：" + writableList();
        return null;
    }

    /** 读前判定，返回 null = 放行。 */
    public static String readDenyReason(String ns, String key) {
        if (!validNamespace(ns)) return "namespace 只能是 system / secure / global";
        if (!validKey(key)) return "key 名字不合法";
        if (readDenied(key)) return "该 key 属敏感项，不读取（" + key + "）";
        return null;
    }
}
