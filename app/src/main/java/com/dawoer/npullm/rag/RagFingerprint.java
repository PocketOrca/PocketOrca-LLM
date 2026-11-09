package com.dawoer.npullm.rag;

import java.security.MessageDigest;

/**
 * 文件指纹（纯 Java，无 android.*）。
 *
 * 增量索引的判定链刻意做成两段：
 *   1) mtime+size 都没变 → 视为未变（不算 hash，省 IO）——绝大多数重扫走这条；
 *   2) mtime/size 变了 → 算 sha256，和旧 hash 比：相同=只是 touch 过，
 *      不同=真改。
 * 这样「指纹不符」的判定既不会漏（内容变了 hash 必变），也不会把
 * 无关的 mtime 抖动当成重建。
 */
public final class RagFingerprint {

    public static final int SAME = 0;
    public static final int CHANGED = 1;
    public static final int NEW = 2;

    private RagFingerprint() {}

    public static String sha256Hex(byte[] data) {
        if (data == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 判定单文件状态。oldHash 为空=无旧记录。
     * 只看 size+mtime 的快速路径优先（省一次全文读取）。
     */
    public static int state(long oldMtime, long oldSize, String oldHash,
                            long newMtime, long newSize, String newHash) {
        if (oldHash == null || oldHash.length() == 0) return NEW;
        if (oldMtime == newMtime && oldSize == newSize) return SAME;
        if (newHash != null && newHash.equals(oldHash)) return SAME;
        return CHANGED;
    }

    /** 是否需要重算 hash（size 变了就一定变；mtime 变了先算了再说）。 */
    public static boolean needsHash(long oldMtime, long oldSize, long newMtime, long newSize) {
        return !(oldMtime == newMtime && oldSize == newSize);
    }
}
