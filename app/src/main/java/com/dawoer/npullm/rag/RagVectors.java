package com.dawoer.npullm.rag;

/**
 * 向量工具（纯 Java，无 android.* —— JVM 可测）。
 *
 * 存储形态：float[] ↔ little-endian byte[]（SQLite BLOB）。维度随模型走
 * （bge-small-zh-v1.5 = 512），检索时按向量长度校验，短/长不匹配的块跳过
 * 而不是崩。
 *
 * 检索指令前缀：bge-zh 官方用法是给 query 加一句中文指令（文档侧不加），
 * 能明显提升召回；这是 bge 系列的标准做法，不是本项目发明。
 */
public final class RagVectors {

    /** bge-zh 官方 query 指令（document 侧不加）。 */
    public static final String QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章：";

    private RagVectors() {}

    public static byte[] toBlob(float[] v) {
        if (v == null) return new byte[0];
        byte[] out = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            int b = Float.floatToIntBits(v[i]);
            out[i * 4] = (byte) (b & 0xff);
            out[i * 4 + 1] = (byte) ((b >>> 8) & 0xff);
            out[i * 4 + 2] = (byte) ((b >>> 16) & 0xff);
            out[i * 4 + 3] = (byte) ((b >>> 24) & 0xff);
        }
        return out;
    }

    /** blob → float[]；长度不是 4 的倍数或为空 → 空数组（调用方跳过）。 */
    public static float[] fromBlob(byte[] b) {
        if (b == null || b.length < 4 || (b.length % 4) != 0) return new float[0];
        float[] out = new float[b.length / 4];
        for (int i = 0; i < out.length; i++) {
            int bits = (b[i * 4] & 0xff)
                    | ((b[i * 4 + 1] & 0xff) << 8)
                    | ((b[i * 4 + 2] & 0xff) << 16)
                    | ((b[i * 4 + 3] & 0xff) << 24);
            out[i] = Float.intBitsToFloat(bits);
        }
        return out;
    }

    /**
     * 余弦相似度。任一向量为空/维度不一致/模长为 0 → 0（绝不 NaN，NaN 会
     * 让排序结果乱序且无法解释）。
     */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i], y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na <= 0 || nb <= 0) return 0f;
        double s = dot / (Math.sqrt(na) * Math.sqrt(nb));
        if (s > 1.0) s = 1.0;
        if (s < -1.0) s = -1.0;
        return (float) s;
    }

    /**
     * 前 k 个最相似的下标（降序）。返回长度 = min(k, n)；score 相同按原序
     * 稳定（插入排序，n 通常几千，O(n·k) 足够且无分配风暴）。
     */
    public static int[] topK(float[] query, float[][] docs, int k) {
        if (query == null || docs == null || k <= 0) return new int[0];
        int n = docs.length;
        int cap = Math.min(k, n);
        int[] idx = new int[cap];
        float[] sc = new float[cap];
        int size = 0;
        for (int i = 0; i < n; i++) {
            float s = cosine(query, docs[i]);
            if (size < cap) {
                int j = size++;
                while (j > 0 && sc[j - 1] < s) { sc[j] = sc[j - 1]; idx[j] = idx[j - 1]; j--; }
                sc[j] = s; idx[j] = i;
            } else if (s > sc[cap - 1]) {
                int j = cap - 1;
                while (j > 0 && sc[j - 1] < s) { sc[j] = sc[j - 1]; idx[j] = idx[j - 1]; j--; }
                sc[j] = s; idx[j] = i;
            }
        }
        int[] out = new int[size];
        System.arraycopy(idx, 0, out, 0, size);
        return out;
    }

    /** 与 topK 配套：取对应分数（同样的排序规则）。 */
    public static float[] topKScores(float[] query, float[][] docs, int k) {
        if (query == null || docs == null || k <= 0) return new float[0];
        int[] idx = topK(query, docs, k);
        float[] out = new float[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = cosine(query, docs[idx[i]]);
        return out;
    }

    /** L2 归一化（原地）；模长 0 原样返回。 */
    public static float[] l2normalize(float[] v) {
        if (v == null || v.length == 0) return v;
        double n = 0;
        for (int i = 0; i < v.length; i++) n += (double) v[i] * v[i];
        if (n <= 0) return v;
        float inv = (float) (1.0 / Math.sqrt(n));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }
}
