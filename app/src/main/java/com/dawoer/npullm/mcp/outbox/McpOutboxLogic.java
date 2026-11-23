package com.dawoer.npullm.mcp.outbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * outbox 纯逻辑（零 Android 依赖，JVM 可测）。
 *
 * 职责边界铁律：这里**没有 LLM，一行也没有**。采集 → 确定性规则闸（白名单/勿扰/速率/聚合/脱敏）
 * → 入队 → 投递。判断"这条重不重要、该怎么说话"全在 agent 侧。
 *
 * 为什么每道闸都必须在这里（而不是在 agent）：每次唤醒 = 一次完整 agent run（token + 秒级延迟），
 * 通知风暴会把 agent 打爆，成本与体验都崩。
 */
public final class McpOutboxLogic {

    private McpOutboxLogic() {}

    // ---------------- 常量（设计文档 §1/§5） ----------------

    /** 单表上限：pending 超过即丢最旧并记 dropped。 */
    public static final int MAX_PENDING = 2000;
    /** delivered 保留天数。 */
    public static final int KEEP_DELIVERED_DAYS = 7;
    /** body_excerpt 上限（出设备前的硬截断）。 */
    public static final int EXCERPT_MAX = 200;
    /** dedupe 窗口：同 dedupe_key 30s 内合并。 */
    public static final long DEDUPE_WINDOW_MS = 30000L;
    /** 聚合窗口默认值（30-60s 区间，设计定 45s）。 */
    public static final long AGG_WINDOW_MS = 45000L;
    /** 每小时唤醒上限默认值。 */
    public static final int MAX_WAKEUPS_PER_HOUR = 6;
    /** 最大重试次数：5 次后 failed。 */
    public static final int MAX_ATTEMPTS = 5;

    public static final int P0 = 0;
    public static final int P1 = 1;
    public static final int P2 = 2;

    // ---------------- 幂等键 / 去重 ----------------

    /** SHA-256 前 16 个十六进制字符。 */
    public static String sha16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论不可达（SHA-256 是 JDK 必备算法）；降级成 hash code，宁可弱也不要 null
            return Integer.toHexString(String.valueOf(s).hashCode());
        }
    }

    /**
     * event_id = sha16(source|dedupe_key|ts|digest)。
     * 同一事件重发必得同一 id（幂等键同时用作 /v1/runs 的 idempotency_key）。
     */
    public static String eventId(String source, String dedupeKey, long ts, String digest) {
        return sha16(nz(source) + "|" + nz(dedupeKey) + "|" + ts + "|" + nz(digest));
    }

    /** dedupe_key：同源同应用同标题归一化（用于 30s 合并）。 */
    public static String dedupeKey(String source, String app, String title) {
        return (nz(source) + ":" + nz(app) + ":" + nz(title)).toLowerCase().trim();
    }

    // ---------------- 脱敏 ----------------

    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");
    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("(?i)\\b(https?://|www\\.)\\S+");
    private static final java.util.regex.Pattern MONEY =
            java.util.regex.Pattern.compile("(?i)([¥$]\\s?[0-9][0-9,.]*|[0-9][0-9,.]*\\s?(元|块|元整))");
    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    private static final java.util.regex.Pattern LONGID =
            java.util.regex.Pattern.compile("(?<![0-9A-Za-z])[0-9A-Za-z]{12,}(?![0-9A-Za-z])");

    /**
     * 出设备前的脱敏：验证码/金额/链接/手机号/长串 ID。
     * 这是资损与隐私防线，不是洁癖：手机通知正文里这些东西占比很高。
     */
    public static String redactOut(String s) {
        if (s == null || s.length() == 0) return "";
        String out = URL.matcher(s).replaceAll("[链接]");
        out = MONEY.matcher(out).replaceAll("[金额]");
        out = CODE.matcher(out).replaceAll("****");
        out = PHONE.matcher(out).replaceAll("[号码]");
        out = LONGID.matcher(out).replaceAll("[长串]");
        return out;
    }

    /** 是否含敏感片段（脱敏前判断，用于 sensitive 标记）。 */
    public static boolean hasSensitive(String s) {
        if (s == null || s.length() == 0) return false;
        return CODE.matcher(s).find() || URL.matcher(s).find() || MONEY.matcher(s).find()
                || PHONE.matcher(s).find();
    }

    // ---------------- 摘要 / 白名单 ----------------

    /** 截到 max 字符；超长补省略号（不破坏 UTF-16 边界之外的语义，按 char 截足够）。 */
    public static String excerpt(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        int lim = max <= 0 ? EXCERPT_MAX : max;
        if (t.length() <= lim) return t;
        return t.substring(0, lim) + "…";
    }

    /** 白名单判定：逗号分隔包名，支持前缀通配 "com.tencent."。 */
    public static boolean whitelisted(String pkg, String csv) {
        if (pkg == null || pkg.length() == 0 || csv == null || csv.trim().length() == 0) return false;
        for (String raw : csv.split(",")) {
            String p = raw.trim();
            if (p.length() == 0) continue;
            if (p.endsWith("*")) {
                if (pkg.startsWith(p.substring(0, p.length() - 1))) return true;
            } else if (p.equalsIgnoreCase(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 出设备的正文：白名单外只留「X 应用有 1 条通知」——不给原文，因为用户没授权这个应用。
     */
    public static String bodyFor(boolean ws, String appLabel, String title, String text) {
        String app = (appLabel == null || appLabel.length() == 0) ? "某应用" : appLabel;
        if (!ws) return app + " 有 1 条通知";
        String t = redactOut(title);
        String b = redactOut(text);
        if (t.length() == 0 && b.length() == 0) return app + " 有 1 条通知";
        if (b.length() == 0) return t;
        if (t.length() == 0) return b;
        return t + "：" + b;
    }

    // ---------------- 优先级 / 勿扰 ----------------

    /** 优先级：白名单外 P2（只入队）；来电 P0（立即唤醒）；其余白名单 P1（聚合唤醒）。 */
    public static int priorityFor(String source, boolean ws) {
        if (!ws) return P2;
        if ("call".equals(nz(source))) return P0;
        return P1;
    }

    /** "23:00-07:30" → {fromMin,toMin}；非法/空返回 null。 */
    public static int[] quietRange(String quietHours) {
        if (quietHours == null) return null;
        String s = quietHours.trim();
        int dash = s.indexOf('-');
        if (dash <= 0) return null;
        int a = minuteOfDay(s.substring(0, dash));
        int b = minuteOfDay(s.substring(dash + 1));
        if (a < 0 || b < 0) return null;
        if (a == b) return null;   // 空区间 = 不静音
        return new int[]{a, b};
    }

    /** "HH:MM" → 分钟（0-1439），非法 -1。 */
    public static int minuteOfDay(String hhmm) {
        if (hhmm == null) return -1;
        String s = hhmm.trim();
        int c = s.indexOf(':');
        if (c <= 0) return -1;
        try {
            int h = Integer.parseInt(s.substring(0, c).trim());
            int m = Integer.parseInt(s.substring(c + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 跨午夜区间（23:00-07:30）正确判定。 */
    public static boolean inQuiet(int minuteOfDay, int[] range) {
        if (range == null) return false;
        int from = range[0], to = range[1];
        if (from == to) return false;
        if (from < to) return minuteOfDay >= from && minuteOfDay < to;
        return minuteOfDay >= from || minuteOfDay < to;
    }

    public static boolean inQuiet(int minuteOfDay, String quietHours) {
        return inQuiet(minuteOfDay, quietRange(quietHours));
    }

    /** 静音时段只入队不唤醒；P0 例外（可配，这里默认允许 P0 穿透）。 */
    public static boolean mayWakeInQuiet(int priority) {
        return priority == P0;
    }

    // ---------------- 退避 ----------------

    /** 30s → 2m → 10m → 1h → 6h。attempts 是已经失败的次数（1 表示第一次失败后）。 */
    public static long backoffMs(int attempts) {
        switch (attempts) {
            case 1: return 30000L;
            case 2: return 120000L;
            case 3: return 600000L;
            case 4: return 3600000L;
            default: return 21600000L;
        }
    }

    public static long nextRetry(long now, int attempts) {
        return now + backoffMs(attempts);
    }

    /** 重试耗尽：5 次后 failed（不再投递，也不删，留给 events.poll 看）。 */
    public static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }

    // ---------------- 速率闸（唤醒预算） ----------------

    /**
     * 每小时唤醒 ≤ N。stamps 是历史唤醒时间（毫秒，乱序也可）。
     * 返回剔除 1 小时外的时间戳后的数组长度不受影响，判定只看窗口内计数。
     */
    public static boolean allowWake(long[] stamps, long now, int maxPerHour) {
        if (maxPerHour <= 0) return false;
        int c = 0;
        for (long s : stamps) {
            if (now - s >= 0 && now - s < 3600000L) c++;
        }
        return c < maxPerHour;
    }

    /** 只保留 1 小时内的戳（写回存储前调用，防数组无限增长）。 */
    public static long[] pruneStamps(long[] stamps, long now) {
        List<Long> keep = new ArrayList<>();
        for (long s : stamps) {
            if (now - s >= 0 && now - s < 3600000L) keep.add(s);
        }
        long[] out = new long[keep.size()];
        for (int i = 0; i < out.length; i++) out[i] = keep.get(i);
        return out;
    }

    // ---------------- 聚合 ----------------

    /** 首尾时间差落在窗口内即可合并成一次唤醒。 */
    public static boolean sameBatch(long firstTs, long ts, long windowMs) {
        long w = windowMs <= 0 ? AGG_WINDOW_MS : windowMs;
        return ts - firstTs <= w;
    }

    // ---------------- 单表裁剪 ----------------

    /** pending 超上限要丢多少条（丢最旧）。 */
    public static int dropCount(int pendingCount, int max) {
        int lim = max <= 0 ? MAX_PENDING : max;
        return pendingCount > lim ? pendingCount - lim : 0;
    }

    // ---------------- 配置 ----------------

    public static String cfg(JSONObject o, String key, String def) {
        if (o == null) return def;
        Object v = o.opt(key);
        return (v instanceof String) ? (String) v : def;
    }

    public static boolean cfgBool(JSONObject o, String key, boolean def) {
        if (o == null) return def;
        Object v = o.opt(key);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    public static int cfgInt(JSONObject o, String key, int def) {
        if (o == null) return def;
        Object v = o.opt(key);
        return (v instanceof Number) ? ((Number) v).intValue() : def;
    }

    /** 端点校验：只接受 http(s)://host[:port][/path]，不接受空、非 http、含空白。 */
    public static boolean validEndpoint(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.length() < 8 || s.length() > 200) return false;
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false;
        if (s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0) return false;
        String rest = s.substring(s.indexOf("://") + 3);
        if (rest.length() == 0 || rest.charAt(0) == '/') return false;
        return true;
    }

    /** 去掉末尾斜杠后拼 /v1/runs。 */
    public static String runUrl(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s + "/v1/runs";
    }

    /** 配置默认值（UI 首次打开回显用）。 */
    public static JSONObject defaultConfig() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", false);
            o.put("endpoint", "");
            o.put("key", "");
            o.put("whitelist", "");
            o.put("quiet_hours", "23:00-07:30");
            o.put("max_wakeups_per_hour", MAX_WAKEUPS_PER_HOUR);
            o.put("aggregate_window_s", (int) (AGG_WINDOW_MS / 1000));
        } catch (Exception ignored) { }
        return o;
    }

    // ---------------- 唤醒载荷 ----------------

    /**
     * 渲染成 /v1/runs 的 input 文本。刻意短、结构化、可追溯：
     * agent 拿到的是"发生了什么"，不是原始屏幕/原始数据。
     */
    public static String renderInput(JSONArray events) {
        if (events == null || events.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        long firstSeq = events.optJSONObject(0) == null ? 0 : events.optJSONObject(0).optLong("seq", 0);
        long lastSeq = firstSeq;
        sb.append("【手机推送】").append(events.length()).append(" 条事件");
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            long seq = e.optLong("seq", 0);
            lastSeq = seq;
            sb.append("\n- #").append(seq).append(" [").append(e.optString("kind", "event")).append("] ")
                    .append(e.optString("title", ""))
                    .append(e.optString("body_excerpt", "").length() > 0 ? " — " : "")
                    .append(e.optString("body_excerpt", ""));
        }
        sb.append("\n可用 events.poll(since_seq=").append(lastSeq)
                .append(") 取完整事件，或 notifications.list 看通知原文。是否需要开口说话用 tts.speak。");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
