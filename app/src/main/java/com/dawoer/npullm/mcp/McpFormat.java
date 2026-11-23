package com.dawoer.npullm.mcp;

/**
 * 数据类工具的纯格式化逻辑（零 Android 依赖 → 可 JVM 全测）。
 * 放在这里的原因：ContentResolver 那段没法在 JVM 里跑，但"拿到行之后怎么拼"必须能测。
 */
public final class McpFormat {

    private McpFormat() {}

    /** 号码规范化：去掉空格/横线/括号，保留前导 +。空/非法 → 空串。 */
    public static String normalizeNumber(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c == '+' && sb.length() == 0) sb.append(c);
        }
        return sb.toString();
    }

    /** 号码是否像手机号（宽松：规范化后 5-20 位，允许前导 +）。 */
    public static boolean looksLikeNumber(String raw) {
        String n = normalizeNumber(raw);
        int digits = n.startsWith("+") ? n.length() - 1 : n.length();
        return digits >= 5 && digits <= 20;
    }

    /** 短信 type（Telephony.Sms.MESSAGE_TYPE_*）：1 收件 2 发出 3 草稿 4 待发 5 失败 6 队列。 */
    public static String smsTypeLabel(int type) {
        switch (type) {
            case 1: return "inbox";
            case 2: return "sent";
            case 3: return "draft";
            case 4: return "outbox";
            case 5: return "failed";
            case 6: return "queued";
            default: return "unknown";
        }
    }

    /** 通话记录 type（CallLog.Calls.*）：1 来电 2 去电 3 未接 4 语音信箱 5 拒接 6 拦截。 */
    public static String callTypeLabel(int type) {
        switch (type) {
            case 1: return "incoming";
            case 2: return "outgoing";
            case 3: return "missed";
            case 4: return "voicemail";
            case 5: return "rejected";
            case 6: return "blocked";
            default: return "unknown";
        }
    }

    /** 联系人电话 type（Phone.TYPE_*）：1 住宅 2 手机 3 工作 …，未知统一 "other"。 */
    public static String phoneTypeLabel(int type) {
        switch (type) {
            case 1: return "home";
            case 2: return "mobile";
            case 3: return "work";
            case 4: return "fax";
            case 7: return "other";
            default: return "other";
        }
    }

    /**
     * scope 文件内容解析：坏 JSON / 缺键 / 非布尔一律当关（宁可少给工具，不可误开不可逆）。
     * 放在纯类里为了 JVM 可测 —— McpScope 依赖 Context，落不了本地测试。
     */
    public static boolean scopeEnabled(String json, String scope) {
        if (json == null || json.length() == 0 || scope == null) return false;
        try {
            return new org.json.JSONObject(json).optBoolean(scope, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 长文本截断（搜索结果里正文过长的兜底，避免单条吃掉整个上下文）。 */
    public static String clip(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
