package com.dawoer.npullm.rag;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 模块业务编排（进程内单例）。
 *
 * 职责边界：只做「库/索引/检索/嵌入」四件事，HTTP 在 RagServer，生命周期在
 * RagForegroundService。这样 JVM 侧可测的部分（分块/向量/指纹）完全不需要
 * Android，Android 侧的逻辑也不互相缠。
 */
public final class RagService {

    private static volatile RagService inst;

    public static synchronized RagService get(Context c) {
        if (inst == null) inst = new RagService(c);
        return inst;
    }

    public static synchronized RagService peek() {
        return inst;
    }

    private final Context ctx;
    private final RagStore store;
    private final RagEmbedClient embed;
    private final RagIndexer indexer;
    private volatile long lastEmbedCall;

    private RagService(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new RagStore(this.ctx);
        this.embed = new RagEmbedClient(this.ctx);
        this.indexer = new RagIndexer(this.ctx, store, embed);
    }

    RagEmbedClient embedClient() { return embed; }

    /** 嵌入子进程是否已就绪（同步召回的前置判断，未就绪时不阻塞调用方）。 */
    public boolean embedReady() { return embed.isUp(); }

    /** JSON 字符串转义（供外层拼 error 用）。 */
    public static String escJs(String s) { return RagStore.esc(s); }

    // ---------------------------------------------------------------- index

    public String index(String dirUri, boolean rebuild) {
        long t0 = System.currentTimeMillis();
        RagIndexer.Result r = indexer.index(dirUri, rebuild);
        lastEmbedCall = System.currentTimeMillis();
        String json = r.toJson();
        return json.substring(0, json.length() - 1)
                + ",\"elapsed_ms\":" + (System.currentTimeMillis() - t0) + "}";
    }

    // ------------------------------------------------------- chat memory (vc147)

    /** 把 chat.db 里的记录切成窗口并索引进 internal:chat 库。 */
    public String indexChat(boolean rebuild) {
        long t0 = System.currentTimeMillis();
        List<MemoryWindows.Turn> turns = ChatStore.get(ctx).all();
        List<MemoryWindows.Win> wins = MemoryWindows.build(turns, MemoryWindows.MAX_CHARS);
        RagIndexer.Result r = indexer.indexChat(wins, rebuild);
        lastEmbedCall = System.currentTimeMillis();
        String json = r.toJson();
        return json.substring(0, json.length() - 1)
                + ",\"msgs\":" + turns.size()
                + ",\"elapsed_ms\":" + (System.currentTimeMillis() - t0) + "}";
    }

    /** 聊天记忆库的 lib id；没建过库返回 -1。 */
    private long chatLibId() {
        try {
            String js = store.libsJson();
            JSONArray arr = new JSONArray(js);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (RagConfig.CHAT_LIB_URI.equals(o.optString("dir", ""))) {
                    return o.optLong("id", -1);
                }
            }
        } catch (Throwable ignore) {}
        return -1L;
    }

    /**
     * 记忆召回：只查聊天库，语义分 × 时间衰减后重排。
     *
     * 为什么不直接用 search 的 topK：纯余弦只看相关性，三个月前和昨天的
     * 同分块分不出来。final = score * 0.5^(天数/半衰期)，越近加权越高。
     * 先取 topK*4 候选再衰减重排，避免衰减把语义好块挤出名单。
     */
    public String recall(String query, int topK) {
        try {
            if (query == null || query.trim().length() == 0) {
                return "{\"ok\":false,\"error\":\"query 为空\"}";
            }
            if (topK <= 0 || topK > 20) topK = 5;
            long libId = chatLibId();
            if (libId <= 0) {
                return "{\"ok\":true,\"hits\":[],\"note\":\"还没有聊天记忆（先索引一次）\"}";
            }
            float[] qv = embed.embed(RagVectors.QUERY_INSTRUCTION + query.trim());
            lastEmbedCall = System.currentTimeMillis();
            if (qv == null) {
                return "{\"ok\":false,\"error\":\"嵌入模型不可用（检查模型文件/内存）\"}";
            }
            List<RagStore.Row> all = store.rows(libId);
            if (all.isEmpty()) {
                return "{\"ok\":true,\"hits\":[],\"note\":\"聊天记忆库为空\"}";
            }
            float[][] mat = new float[all.size()][];
            for (int i = 0; i < all.size(); i++) mat[i] = all.get(i).vec;
            int want = Math.min(all.size(), topK * 4);
            int[] top = RagVectors.topK(qv, mat, want);
            long now = System.currentTimeMillis();
            int half = RagConfig.recallHalfLifeDays();
            JSONArray pool = new JSONArray();
            for (int i = 0; i < top.length; i++) {
                RagStore.Row row = all.get(top[i]);
                float score = RagVectors.cosine(qv, mat[top[i]]);
                if (score < RagConfig.minScore() * 0.6f) continue;
                double days = row.ts > 0 ? (now - row.ts) / 86400000.0 : 0;
                if (days < 0) days = 0;
                double decay = Math.pow(0.5, days / (double) half);
                JSONObject h = new JSONObject();
                h.put("score", Math.round(score * 1000f) / 1000f);
                h.put("decayed", Math.round(score * decay * 1000f) / 1000f);
                h.put("ts", row.ts);
                h.put("rel", row.relpath);
                h.put("text", row.text);
                pool.put(h);
            }
            // 按 decayed 降序：选择排序取 topK。
            // 不用 Comparator/匿名类——d8 8.2.2 在 Comparator 的 bridge method 上
            // 直接 NPE（String.length() on null），池子 <=80 条手写足够。
            JSONArray hits = new JSONArray();
            boolean[] used = new boolean[pool.length()];
            for (int n = 0; n < topK && n < pool.length(); n++) {
                int best = -1;
                double bestV = -1e18;
                for (int j = 0; j < pool.length(); j++) {
                    if (used[j]) continue;
                    double v = pool.getJSONObject(j).optDouble("decayed", 0);
                    if (v > bestV) { bestV = v; best = j; }
                }
                if (best < 0) break;
                used[best] = true;
                hits.put(pool.getJSONObject(best));
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("query", query);
            out.put("hits", hits);
            out.put("candidates", all.size());
            out.put("halflife_days", half);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}";
        }
    }

    // --------------------------------------------------------------- search

    /** vc157: 兼容入口——默认只查文档库（聊天记忆需显式 includeChat=true）。 */
    public String search(String query, int topK, float minScore, long libId) {
        return search(query, topK, minScore, libId, false);
    }

    public String search(String query, int topK, float minScore, long libId, boolean includeChat) {
        return search(query, topK, minScore, libId, includeChat, -1);
    }

    /**
     * vc157: 跨库检索。includeChat=false 时排除 internal:chat。
     *
     * 之前默认合并全部库 → 外部 /v1/rag/search 能把聊天记忆片段当文档返回
     * （1104 实测同网段无 key 就能读到 chat/main#N 正文）。
     *
     * vc159 修四处：
     *  1. libId>0 只查指定库 —— 旧实现 RagServer 恒传 -1，外部无法隔离验证某个库
     *     （也就无法判断"聊天块到底有没有参与排序"）。
     *  2. topK 上限 20 → 50，并回显 requested_k/k。旧实现 k=200 静默砍到 20，
     *     调用方看到 hits=20 无法得知是被截断还是只有 20 条。
     *  3. 单文件配额 fileCap（默认 max(2, k/4)，0=不限）：200 块的角色卡 JSON
     *     能把整个 topK 霸占（实测 20 条里 19 条同文件），检索退化成"一个文件
     *     的切片浏览"。候选池取 k*4 再做配额过滤，避免配额后不足 k 条。
     *  4. 响应回显 min_score / file_cap / candidates / returned —— 参数是否生效
     *     由响应自证，不靠文档承诺。
     */
    public String search(String query, int topK, float minScore, long libId,
                         boolean includeChat, int fileCap) {
        try {
            if (query == null || query.trim().length() == 0) {
                return "{\"ok\":false,\"error\":\"query 为空\"}";
            }
            int requestedK = topK;
            if (topK <= 0) topK = RagConfig.topK();
            if (topK > 50) topK = 50;
            if (minScore < 0f) minScore = RagConfig.minScore();
            // fileCap<0 走默认；fileCap==0 表示不限
            if (fileCap < 0) fileCap = Math.max(2, topK / 4);
            int cap = fileCap == 0 ? Integer.MAX_VALUE : fileCap;
            float[] qv = embed.embed(RagVectors.QUERY_INSTRUCTION + query.trim());
            lastEmbedCall = System.currentTimeMillis();
            if (qv == null) {
                return "{\"ok\":false,\"error\":\"嵌入模型不可用（检查模型文件/内存）\"}";
            }
            long[] libs;
            if (libId > 0) {
                if (!store.libExists(libId)) {
                    return "{\"ok\":false,\"error\":\"库不存在: " + libId + "\"}";
                }
                libs = new long[]{libId};
            } else {
                libs = includeChat ? allLibIds() : docLibIds();
            }
            // 合并目标库的块（跨库检索是默认行为）
            List<RagStore.Row> all = new ArrayList<RagStore.Row>();
            for (int i = 0; i < libs.length; i++) all.addAll(store.rows(libs[i]));
            if (all.isEmpty()) {
                return "{\"ok\":true,\"query\":\"" + RagStore.esc(query)
                        + "\",\"hits\":[],\"note\":\"库为空，先索引一个目录\"}";
            }
            float[][] mat = new float[all.size()][];
            for (int i = 0; i < all.size(); i++) mat[i] = all.get(i).vec;
            // 候选池放大（给单文件配额留位置）。取 20 倍而不是 4 倍：一个 200 块的
            // 大文件能把 4 倍池（k=5 → 20 条）整池占满，结果只剩配额允许的 2 条，
            // 其它文件根本没机会进候选。
            int want = Math.min(all.size(), Math.max(topK * 20, 200));
            int[] top = RagVectors.topK(qv, mat, want);
            JSONArray hits = new JSONArray();
            Map<String, Integer> perFile = new HashMap<String, Integer>();
            for (int i = 0; i < top.length && hits.length() < topK; i++) {
                float score = RagVectors.cosine(qv, mat[top[i]]);
                if (score < minScore) break; // top 为降序，后续只会更低
                RagStore.Row row = all.get(top[i]);
                Integer usedObj = perFile.get(row.relpath);
                int used = usedObj == null ? 0 : usedObj.intValue();
                if (used >= cap) continue;
                perFile.put(row.relpath, used + 1);
                JSONObject h = new JSONObject();
                h.put("score", Math.round(score * 1000f) / 1000f);
                h.put("title", row.title);
                h.put("relpath", row.relpath);
                h.put("text", row.text);
                hits.put(h);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("query", query);
            out.put("hits", hits);
            out.put("requested_k", requestedK);
            out.put("k", topK);
            out.put("min_score", Math.round(minScore * 1000f) / 1000f);
            out.put("file_cap", fileCap);
            out.put("returned", hits.length());
            out.put("candidates", all.size());
            out.put("libs", libs.length);
            out.put("dim", qv.length);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}";
        }
    }

    private long[] allLibIds() {
        try {
            String js = store.libsJson();
            JSONArray arr = new JSONArray(js);
            long[] out = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = arr.getJSONObject(i).getLong("id");
            }
            return out;
        } catch (Throwable t) {
            return new long[0];
        }
    }

    /** vc152: 文档库 id 列表 —— 排除聊天记忆库（internal:chat）。 */
    private long[] docLibIds() {
        try {
            String js = store.libsJson();
            JSONArray arr = new JSONArray(js);
            long[] tmp = new long[arr.length()];
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (RagConfig.CHAT_LIB_URI.equals(o.optString("dir", ""))) continue;
                long id = o.optLong("id", -1);
                if (id > 0) tmp[n++] = id;
            }
            long[] out = new long[n];
            for (int i = 0; i < n; i++) out[i] = tmp[i];
            return out;
        } catch (Throwable t) {
            return new long[0];
        }
    }

    /**
     * vc152 知识库检索（chat 页 📚 开关专用）：只查文档库，排除聊天记忆库。
     *
     * 为什么不直接复用 search()：search() 跨全部库（含 internal:chat），
     * 那样每次发送会和 recall() 的聊天记忆重复注入同一批块。
     * 也不做时间衰减——文档没有"新旧"语义，衰减只会把好资料踢出名单。
     */
    public String searchDocs(String query, int topK, float minScore) {
        try {
            if (query == null || query.trim().length() == 0) {
                return "{\"ok\":false,\"error\":\"query 为空\"}";
            }
            if (topK <= 0) topK = RagConfig.kbTopK();
            if (topK > 6) topK = 6;
            if (minScore < 0f) minScore = RagConfig.minScore();
            long[] libs = docLibIds();
            if (libs.length == 0) {
                return "{\"ok\":true,\"hits\":[],\"note\":\"还没有文档库（先到知识库页索引一个目录）\"}";
            }
            float[] qv = embed.embed(RagVectors.QUERY_INSTRUCTION + query.trim());
            lastEmbedCall = System.currentTimeMillis();
            if (qv == null) {
                return "{\"ok\":false,\"error\":\"嵌入模型不可用（检查模型文件/内存）\"}";
            }
            List<RagStore.Row> all = new ArrayList<RagStore.Row>();
            for (int i = 0; i < libs.length; i++) all.addAll(store.rows(libs[i]));
            if (all.isEmpty()) {
                return "{\"ok\":true,\"hits\":[],\"note\":\"文档库为空，先索引一个目录\"}";
            }
            float[][] mat = new float[all.size()][];
            for (int i = 0; i < all.size(); i++) mat[i] = all.get(i).vec;
            int[] top = RagVectors.topK(qv, mat, topK);
            JSONArray hits = new JSONArray();
            for (int i = 0; i < top.length; i++) {
                float score = RagVectors.cosine(qv, mat[top[i]]);
                if (score < minScore) continue;
                RagStore.Row row = all.get(top[i]);
                JSONObject h = new JSONObject();
                h.put("score", Math.round(score * 1000f) / 1000f);
                h.put("title", row.title);
                h.put("relpath", row.relpath);
                h.put("text", row.text);
                hits.put(h);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("query", query);
            out.put("hits", hits);
            out.put("candidates", all.size());
            out.put("libs", libs.length);
            out.put("source", "docs");
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}";
        }
    }

    // --------------------------------------------------------------- status

    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("proto", RagServer.PROTO_VERSION);
            int[] t = store.totals();
            JSONObject idx = new JSONObject();
            idx.put("libraries", t[0]);
            idx.put("files", t[1]);
            idx.put("chunks", t[2]);
            o.put("indexed", idx);
            JSONObject em = new JSONObject();
            em.put("port", RagConfig.EMBED_PORT);
            em.put("running", embed.isUp());
            em.put("model", embed.modelReady());
            em.put("auto", RagConfig.autoEmbed());
            o.put("embed", em);
            JSONObject p = new JSONObject();
            p.put("phase", RagIndexer.phase);
            p.put("done", RagIndexer.done);
            p.put("total", RagIndexer.total);
            p.put("file", RagIndexer.currentFile);
            o.put("progress", p);
            o.put("libraries", new JSONArray(store.libsJson()));
            o.put("running", RagForegroundService.alive);
            o.put("port", RagConfig.port());
            // vc157: key 状态（UI 只需指纹，绝不吐全串）
            String gk = RagConfig.gateKey();
            o.put("key_on", gk.length() > 0);
            o.put("key_fp", RagConfig.gateKeyFingerprint());
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}";
        }
    }

    public String drop(long libId) {
        try {
            if (libId <= 0) {
                return "{\"ok\":false,\"error\":\"library_id 必填\"}";
            }
            // vc157: 聊天记忆库禁删（recall 依赖它；要清就清聊天记录再重建索引）
            if (libId == chatLibId()) {
                return "{\"ok\":false,\"error\":\"聊天记忆库不可删除\"}";
            }
            // vc159: 旧实现对不存在的库照样跑 dropLib 再回 ok:true ——
            // 「删了个不存在的库」报成功，调用方无法区分真删与空操作。
            if (!store.libExists(libId)) {
                return "{\"ok\":false,\"error\":\"库不存在: " + libId + "\"}";
            }
            store.dropLib(libId);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + RagStore.esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 空闲回收（RagForegroundService 定时调）。 */
    public void tick() {
        embed.recycleIfIdle();
    }

    public void onStop() {
        embed.stop();
    }
}
