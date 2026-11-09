package com.dawoer.npullm.rag;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * RAG 模块本地配置（照 SearchConfig 同款隔离：rag 包不 import 引擎类，
 * 自己持有 context + 独立 prefs 文件）。
 *
 * 形状纪律（1022 路线图）：每服务=独立端口+最小兼容集+独立 FGS+key 门。
 * 因此这里的端口/钥匙/开关全部独立于主服务（llama_server_*）与搜索服务
 * （search_*），改一个不会动另一个。
 *
 * Keys:
 *   rag_enabled   — :8082 端点开关（默认关；开了才起 FGS，嵌入进程再按需拉）
 *   rag_port      — LAN 端口，默认 8082（拒绝 8080/8081，两服务不许撞）
 *   rag_gate_key  — key 门；空=不启用
 *   rag_dir_uri   — SAF tree uri（用户选的文档目录）
 *   rag_topk      — 检索返回条数 1..20，默认 5
 *   rag_min_score — 相似度下限 0..1，默认 0.30（低于此不返回）
 *   rag_auto_embed— 是否常驻嵌入进程（默认 false=按需拉起、空闲回收）
 */
public final class RagConfig {

    private static final String PREFS = "npullm_rag";
    public static final int DEFAULT_PORT = 8082;
    /** 嵌入进程内部端口（只绑 127.0.0.1，永不对外）。 */
    public static final int EMBED_PORT = 8083;
    public static final int DEFAULT_TOPK = 5;

    private static Context app;
    private static volatile SharedPreferences sp;

    private RagConfig() {}

    public static void init(Context c) {
        if (c != null) {
            app = c.getApplicationContext();
            sp = null;
        }
    }

    static Context ctx() {
        return app;
    }

    private static SharedPreferences prefs() {
        Context c = app;
        if (c == null) return null;
        SharedPreferences p = sp;
        if (p == null) {
            p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp = p;
        }
        return p;
    }

    public static boolean enabled() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("rag_enabled", false);
    }

    public static void setEnabled(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("rag_enabled", on).apply();
    }

    /** 合法端口：非特权、非 8080（主服务）、非 8081（搜索服务）。 */
    public static boolean validatePort(int v) {
        return v >= 1024 && v <= 65535 && v != 8080 && v != 8081;
    }

    public static int port() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("rag_port", -1);
        return validatePort(v) ? v : DEFAULT_PORT;
    }

    public static void setPort(int v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt("rag_port", v).apply();
    }

    public static String gateKey() {
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString("rag_gate_key", "");
        return v == null ? "" : v;
    }

    public static void setGateKey(String v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("rag_gate_key", v == null ? "" : v.trim()).apply();
    }

    /**
     * vc157: 取 key；空则生成并落盘。
     *
     * 背景：:8082 之前 apiKey 恒为空串 → gateOk() 直接放行，同网段任何人可读文档正文、
     * 可调 drop 删库（1104 8T 实测无 key 返回 hits[].text 与 {"ok":true}）。
     * 现在首次启动服务即生成，UI 只展示指纹，不提供手填。
     */
    public static String ensureGateKey() {
        String v = gateKey();
        if (v.length() == 0) {
            v = newGateKey();
            setGateKey(v);
        }
        return v;
    }

    /** vc157: 重新生成 key（副作用：调用方负责重启 :8082 使其生效）。 */
    public static String resetGateKey() {
        String v = newGateKey();
        setGateKey(v);
        return v;
    }

    /** vc157: 指纹（后 4 位），UI 展示用；不吐全串。 */
    public static String gateKeyFingerprint() {
        String v = gateKey();
        if (v.length() <= 12) return "";
        return v.substring(v.length() - 4);
    }

    private static String newGateKey() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("sk-rag-");
        for (int i = 0; i < b.length; i++) {
            int n = b[i] & 0xff;
            sb.append(Character.forDigit(n >> 4, 16)).append(Character.forDigit(n & 0xf, 16));
        }
        return sb.toString();
    }

    public static String dirUri() {
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString("rag_dir_uri", "");
        return v == null ? "" : v;
    }

    public static void setDirUri(String v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("rag_dir_uri", v == null ? "" : v).apply();
    }

    public static int topK() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("rag_topk", -1);
        return v < 1 || v > 20 ? DEFAULT_TOPK : v;
    }

    public static void setTopK(int v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt("rag_topk", v < 1 ? 1 : (v > 20 ? 20 : v)).apply();
    }

    public static float minScore() {
        SharedPreferences p = prefs();
        float v = p == null ? -1f : p.getFloat("rag_min_score", -1f);
        if (v < 0f || v > 1f) return 0.30f;
        return v;
    }

    public static void setMinScore(float v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putFloat("rag_min_score",
                v < 0f ? 0f : (v > 1f ? 1f : v)).apply();
    }

    /** 聊天记忆库的 dir_uri（不是 SAF 路径，是内部源标识）。 */
    public static final String CHAT_LIB_URI = "internal:chat";
    public static final String CHAT_LIB_NAME = "聊天记忆";

    /** 记忆召回时间衰减半衰期（天）：final = score * 0.5^(天数/该值)。 */
    public static int recallHalfLifeDays() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("rag_recall_halflife", -1);
        return v < 1 || v > 3650 ? 30 : v;
    }

    /** 发消息时自动召回记忆（默认开）。 */
    public static boolean recallOn() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean("rag_recall_on", true);
    }

    public static void setRecallOn(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("rag_recall_on", on).apply();
    }

    /**
     * vc152: chat 页 📚「本地知识库」注入开关（默认关）。
     *
     * 与召回是两件事：recall 只查聊天记忆库（带时间衰减），kb 只查文档库
     * （无衰减）。默认关是因为 bmoe 会话 n_ctx=8192，无条件注入会挤掉对话。
     */
    public static boolean kbOn() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("rag_kb_on", false);
    }

    public static void setKbOn(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("rag_kb_on", on).apply();
    }

    /** 知识库注入条数上限（默认 3；文档块比聊天窗口长，多了会撑窗口）。 */
    public static int kbTopK() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("rag_kb_topk", -1);
        return v < 1 || v > 6 ? 3 : v;
    }

    public static boolean autoEmbed() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("rag_auto_embed", false);
    }

    public static void setAutoEmbed(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("rag_auto_embed", on).apply();
    }
}
