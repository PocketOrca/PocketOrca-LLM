package com.dawoer.npullm.mcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏：正文里 4-8 位连续数字 → ****。
 * 这是资损防线（验证码），不是洁癖。
 * 注意：会连带遮住订单号/金额等，属可接受代价；需要原文必须显式 include_sensitive。
 */
public final class McpRedact {

    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");

    private McpRedact() {}

    public static String mask(String s) {
        if (s == null || s.length() == 0) return s;
        Matcher m = CODE.matcher(s);
        return m.replaceAll("****");
    }

    /** 只在需要原文时调用（参数 include_sensitive=true），调用点必须写 trace。 */
    public static String maybeMask(String s, boolean includeSensitive) {
        return includeSensitive ? s : mask(s);
    }
}
