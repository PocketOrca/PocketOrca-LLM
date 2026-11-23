package com.dawoer.npullm.mcp;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 鉴权：单 key + Origin 白名单。
 * key 由 :mcp 进程自己生成并落盘（写入方唯一，避免 vc170b 那类跨进程竞态）。
 * Origin 语义照规范：存在**且**不在白名单才 403，不是"带 Origin 就拒"。
 */
public final class McpAuth {

    public static final String KEY_PREFIX = "sk-mcp-";

    private McpAuth() {}

    public static String generateKey() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** 常量时间比较，避免按字节泄露长度/前缀。 */
    public static boolean keyEquals(String expected, String given) {
        if (expected == null || given == null) return false;
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = given.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    /** 从 Authorization: Bearer xxx 或 X-Api-Key 取出候选 key。 */
    public static String extractKey(String authorization, String apiKeyHeader) {
        if (apiKeyHeader != null && apiKeyHeader.trim().length() > 0) return apiKeyHeader.trim();
        if (authorization == null) return null;
        String v = authorization.trim();
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) return v.substring(7).trim();
        return v;
    }

    /** allowList 为空 = 拒绝任何带 Origin 的请求（防 DNS rebinding），与规范一致但可控。 */
    public static boolean originAllowed(String origin, String[] allowList) {
        if (origin == null || origin.trim().length() == 0) return true;
        if (allowList == null || allowList.length == 0) return false;
        for (String a : allowList) {
            if (a != null && a.trim().equalsIgnoreCase(origin.trim())) return true;
        }
        return false;
    }

    /** UI 上只显示后 4 位。 */
    public static String fingerprint(String key) {
        if (key == null || key.length() < 4) return "****";
        return KEY_PREFIX + "…" + key.substring(key.length() - 4);
    }

    /** trace 里只存参数摘要，不存原文。 */
    public static String sha16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }
}
