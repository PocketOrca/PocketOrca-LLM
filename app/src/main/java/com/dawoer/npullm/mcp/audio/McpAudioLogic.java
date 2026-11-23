package com.dawoer.npullm.mcp.audio;

/**
 * vc190：音量换算与解析（纯 Java，零 android 依赖，便于 JVM 自测）。
 *
 * 流常量与 android.media.AudioManager 一致，但这里写字面量：
 *   STREAM_VOICE_CALL=0 STREAM_SYSTEM=1 STREAM_RING=2 STREAM_MUSIC=3
 *   STREAM_ALARM=4 STREAM_NOTIFICATION=5
 * 之所以不 import，是因为 JVM 测试用 android.jar 的 stub 会抛 Stub!，
 * 而 pure-Java 让这套换算能被 unit test 直接覆盖。
 */
public final class McpAudioLogic {

    private McpAudioLogic() {}

    public static final int STREAM_VOICE_CALL = 0;
    public static final int STREAM_SYSTEM = 1;
    public static final int STREAM_RING = 2;
    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_ALARM = 4;
    public static final int STREAM_NOTIFICATION = 5;

    /** 对外可用的流名（也是工具描述里列给模型的清单）。 */
    public static final String STREAM_LIST = "music / ring / alarm / call / system / notification";

    /** 流名 → AudioManager 流号；不认识返回 -1。空串按 music 处理（工具默认值在 schema 里）。 */
    public static int streamId(String name) {
        if (name == null) return -1;
        String s = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() == 0) return -1;
        if ("music".equals(s) || "media".equals(s) || "3".equals(s)) return STREAM_MUSIC;
        if ("ring".equals(s) || "ringtone".equals(s) || "2".equals(s)) return STREAM_RING;
        if ("alarm".equals(s) || "4".equals(s)) return STREAM_ALARM;
        if ("call".equals(s) || "voice".equals(s) || "voice_call".equals(s) || "0".equals(s)) return STREAM_VOICE_CALL;
        if ("system".equals(s) || "1".equals(s)) return STREAM_SYSTEM;
        if ("notification".equals(s) || "notif".equals(s) || "5".equals(s)) return STREAM_NOTIFICATION;
        return -1;
    }

    public static String streamName(int id) {
        switch (id) {
            case STREAM_VOICE_CALL: return "call";
            case STREAM_SYSTEM: return "system";
            case STREAM_RING: return "ring";
            case STREAM_MUSIC: return "music";
            case STREAM_ALARM: return "alarm";
            case STREAM_NOTIFICATION: return "notification";
            default: return "stream" + id;
        }
    }

    /** 0-100 百分比 → 档位；越界钳制，max<=0 返 0。 */
    public static int percentToIndex(int pct, int max) {
        if (max <= 0) return 0;
        int p = pct < 0 ? 0 : (pct > 100 ? 100 : pct);
        int idx = Math.round(p * max / 100f);
        if (idx < 0) return 0;
        return idx > max ? max : idx;
    }

    /** 档位 → 0-100 百分比（用于回显，让人看得懂）。 */
    public static int indexToPercent(int idx, int max) {
        if (max <= 0) return 0;
        int i = idx < 0 ? 0 : (idx > max ? max : idx);
        int p = Math.round(i * 100f / max);
        if (p < 0) return 0;
        return p > 100 ? 100 : p;
    }

    /**
     * 解析 shell 输出 `[V] volume is 6 in range [0..15]` → {6, 15}。
     * 解析不出来返回 null（不猜）。cmd media_session 的 `--get` 实测就是这个格式。
     */
    public static int[] parseGet(String out) {
        if (out == null) return null;
        String s = out.trim();
        int i = s.indexOf("volume is ");
        if (i < 0) return null;
        int j = i + "volume is ".length();
        int a = j;
        while (a < s.length() && (Character.isDigit(s.charAt(a)) || s.charAt(a) == '-')) a++;
        int lo = s.indexOf('[');
        int dot = s.indexOf("..", lo < 0 ? 0 : lo);
        int hi = s.indexOf(']', dot < 0 ? 0 : dot);
        if (a == j || dot < 0 || hi < 0) return null;
        try {
            int cur = Integer.parseInt(s.substring(j, a).trim());
            int max = Integer.parseInt(s.substring(dot + 2, hi).trim());
            if (max <= 0 || cur < 0) return null;
            return new int[]{cur, max};
        } catch (Exception e) {
            return null;
        }
    }

    /** 一档上下（up=true 加一档），钳制在 [0,max]。 */
    public static int step(int idx, int max, boolean up) {
        if (max <= 0) return 0;
        int n = up ? idx + 1 : idx - 1;
        if (n < 0) return 0;
        return n > max ? max : n;
    }
}
