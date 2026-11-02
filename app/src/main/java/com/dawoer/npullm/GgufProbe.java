package com.dawoer.npullm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * v1.4.3 (vc105): GGUF header probe — MoE detection for the service page.
 *
 * Parses ONLY the metadata KV section of the GGUF header (never tensor data),
 * looking for "<arch>.expert_count" > 0 — the on-disk signal that the model
 * routes tokens through experts (llama/qwen2moe/qwen3moe/deepseek2/gpt-oss
 * all declare it; dense models never do). The parse costs a few KB of reads
 * even on 20 GB files, safe to run on the WebView bridge thread on every
 * model pick (the JS caller caches the result per path).
 *
 * GGUF v2/v3 (little-endian): magic "GGUF", u32 version, u64 tensor_count,
 * u64 kv_count, then kv_count × { u32 key_len, key bytes, u32 value_type,
 * value }. Value types: 0..7 fixed-width, 8 string, 9 array, 10..12
 * fixed-width. Unknown keys are skipped without materializing them.
 *
 * d8 constraints: no enums, no anonymous classes. JVM-testable; never throws
 * (any failure ⇒ parsed=false, moe=false — the engine form simply does not
 * appear for an unreadable file, same fallback as pre-vc105).
 */
final class GgufProbe {
    private GgufProbe() {}

    static final class Result {
        final boolean moe;        // expert_count > 0
        final boolean parsed;     // header walked to the end cleanly
        final long expertCount;
        final String arch;        // general.architecture ("" when absent)
        // vc113/A3: NPU preflight fields (read while walking the KV + tensor dir)
        final long ftype;         // general.file_type (-1 absent / -2 unreadable)
        final boolean hasMtp;     // <arch>.blk.<N>.nextn tensors present
        Result(boolean moe, boolean parsed, long expertCount, String arch) {
            this(moe, parsed, expertCount, arch, -1, false);
        }
        Result(boolean moe, boolean parsed, long expertCount, String arch,
               long ftype, boolean hasMtp) {
            this.moe = moe; this.parsed = parsed; this.expertCount = expertCount;
            this.arch = arch; this.ftype = ftype; this.hasMtp = hasMtp;
        }
    }

    /** NPU/MTP HTP repack whitelist (vc113/A3): NPU device negation maps the
     *  core quant families onto these kernels; everything else (IQ*, Q2, TQ,
     *  exotic K variants) silently falls back to CPU at load and toasts a
     *  useless slowness complaint instead of a real diagnosis. */
    static final int[] NPU_QUANT_FTYPES = {2, 3, 7, 10, 11, 12, 13, 14, 15, 16, 17, 18, 25, 38};
    static final String NPU_QUANT_NAMES =
            "Q4_0 / Q4_1 / Q8_0 / IQ4_NL / MXFP4 / Q2_K / Q3_K / Q4_K / Q5_K / Q6_K";

    static boolean npuQuantSupported(long ftype) {
        for (int v : NPU_QUANT_FTYPES) if (v == ftype) return true;
        return false;
    }

    static final Result FAIL = new Result(false, false, 0, "");

    private static final int T_STRING = 8, T_ARRAY = 9;
    /** fixed byte width by gguf value type (index = type), -1 = variable */
    private static final int[] WIDTH = {1, 1, 2, 2, 4, 4, 4, 1, -1, -1, 8, 8, 8};

    static Result probe(File f) {
        return probe(f, false);
    }

    /**
     * vc113/A3: readTensorDir walks the gguf tensor directory AFTER the KV loop
     * (name strings + u32 dims + u32 type + u64 offsets/vals only, never tensor
     * bytes). Cost: ~1.5-3 MB seek+read for 256-expert 35B headers — safe on
     * the bridge thread. Logs the single first-seen unsupported quant via the
     * caller-supplied StringBuilder; call it only for NPU/MTP preflight.
     */
    static Result probe(File f, StringBuilder quantErrRef) {
        return probeInner(f, true, quantErrRef);
    }

    static Result probe(File f, boolean readTensorDir) {
        return probeInner(f, readTensorDir, null);
    }

    private static Result probeInner(File f, boolean readTensorDir, StringBuilder quantErrRef) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b4 = new byte[4], b8 = new byte[8];
            if (in.read(b4) != 4 || b4[0] != 'G' || b4[1] != 'G' || b4[2] != 'U' || b4[3] != 'F') return FAIL;
            if (in.read(b4) != 4) return FAIL;
            long ver = le32(b4, 0);
            if (ver < 2 || ver > 3) return FAIL;
            if (in.read(b8) != 8) return FAIL;            // tensor_count
            long tensorCount = le64(b8, 0);
            if (tensorCount < 0 || tensorCount > 1_000_000) return FAIL;
            if (in.read(b8) != 8) return FAIL;
            long kvCount = le64(b8, 0);
            if (kvCount < 0 || kvCount > 1_000_000) return FAIL;
            long experts = -1;
            String arch = "";
            long ftype = -1L;
            boolean hasMtp = false;
            for (long i = 0; i < kvCount; i++) {
                String key = readStr(in, b4, b8);
                if (key == null || in.read(b4) != 4) return FAIL;
                int vt = (int) le32(b4, 0);
                if (vt < 0 || vt > 12) return FAIL;
                if ("general.architecture".equals(key) && vt == T_STRING) {
                    String a = readStr(in, b4, b8);
                    if (a == null) return FAIL;
                    arch = a;
                } else if ("general.file_type".equals(key) && WIDTH[vt] > 0) {
                    Long v = readFixed(in, vt, b4, b8);
                    if (v == null) return FAIL;
                    ftype = v.longValue();
                } else if (key.endsWith(".expert_count") && WIDTH[vt] > 0) {
                    Long v = readFixed(in, vt, b4, b8);
                    if (v == null) return FAIL;
                    experts = v.longValue();
                } else if (!skip(in, vt, b4, b8, 0)) {
                    return FAIL;
                }
            }
            // vc113/A3: tensor directory — blk.<N>.nextn (MTP) + first
            // unsupported quant name for the NPU preflight message. BEST-EFFORT:
            // the parsed contract is the KV walk (vc105/vc108 fixtures declare
            // decorative tensor_count, and a 16 MiB head-dump truncates the dir
            // mid-walk — both must stay parsed=true). EOF/short-read just ends
            // the walk; whatever hasMtp/quant evidence we gathered is kept.
            // Name cap 256 chars (numel dims eat the 3.5M-token F16 outlier).
            // vc116 fix: entry layout per gguf.cpp L643-750 (verified against
            // the real Qwen3.5-4B-MTP Q4_K_M header on-device, 1014) is
            //   name, n_dims u32, dims[nDims] u64, type u32, offset u64
            // with NO trailing size field. vc113 read type BEFORE dims and
            // consumed an extra phantom u64 -> desync at tensor #2 -> readStr
            // null -> walk died on tensor #1 -> hasMtp stayed false for every
            // REAL model while hand-built fixtures (written in the same wrong
            // order) stayed green: the vc108 "self-consistent false green"
            // class of bug, second occurrence. Real-header regression now
            // lives in testdata/qwen35-4b-mtp-head16m.bin (testVc116).
            for (long t = 0; t < tensorCount; t++) {
                String name = readStr(in, b4, b8);
                if (name == null || name.length() > 256) break;
                if (in.read(b4) != 4) break;
                int nDims = (int) le32(b4, 0);
                if (nDims < 1 || nDims > 4) break;
                boolean eof = false;
                for (int d = 0; d < nDims && !eof; d++) eof = in.read(b8) != 8;
                if (eof || in.read(b4) != 4) break;   // type (u32) — AFTER dims
                int ggmlType = (int) le32(b4, 0);
                if (!readTensorDir) {                 // legacy MoE walk: consume offset, no checks
                    if (in.read(b8) != 8) break;      // offset (u64)
                    continue;
                }
                // GGUF tensor names carry NO arch prefix (KV keys do):
                // MTP layers are literally "blk.<N>.nextn.*" (0929 实录,
                // searching "mtp" finds nothing). startswith blk. + .nextn.
                if (!hasMtp && name.startsWith("blk.") && name.contains(".nextn.")) {
                    hasMtp = true;
                }
                if (quantErrRef != null && quantErrRef.length() == 0
                        && !isHtpWhitelistedGgml(ggmlType)) {
                    quantErrRef.append(quantName(ggmlType));
                }
                if (in.read(b8) != 8) break;          // offset (u64) — no size field in GGUF v2/v3
            }
            return new Result(experts > 0, true, Math.max(experts, 0L), arch, ftype, hasMtp);
        } catch (Exception e) {
            return FAIL;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** ggml type -> HTP-repack-whitelist membership (vc113/A3; kv_ quants absent = rejected).
     *  Values verified against ggml/include/ggml.h L390-430 (VM llama.cpp tree, 1013):
     *  0 F32, 1 F16, 2 Q4_0, 3 Q4_1, 6 Q5_0, 7 Q5_1, 8 Q8_0, 9 Q8_1, 10 Q2_K, 11 Q3_K,
     *  12 Q4_K, 13 Q5_K, 14 Q6_K, 15 Q8_K, 16-23 IQ family, 20 IQ4_NL, 29 IQ1_M, 30 BF16,
     *  34/35 TQ, 39 MXFP4, 40 NVFP4. */
    static boolean isHtpWhitelistedGgml(int t) {
        switch (t) {
            case 0:  return true;   // F32
            case 1:  return true;   // F16
            case 2:  return true;   // Q4_0
            case 3:  return true;   // Q4_1
            case 8:  return true;   // Q8_0
            case 10: return true;   // Q2_K
            case 11: return true;   // Q3_K
            case 12: return true;   // Q4_K
            case 13: return true;   // Q5_K
            case 14: return true;   // Q6_K
            case 20: return true;   // IQ4_NL
            case 39: return true;   // MXFP4 (task-书 v3 repack whitelist)
            default: return false;  // Q5_0/Q5_1/Q8_1/IQ1-3/IQ4_XS/TQ/NVFP4 -> CPU fallback
        }
    }

    /** ggml type number -> canonical short name for the user-facing toast
     *  (values from ggml.h L390-430, same provenance as the whitelist). */
    static String quantName(int t) {
        switch (t) {
            case 0: return "F32";
            case 1: return "F16";
            case 2: return "Q4_0";
            case 3: return "Q4_1";
            case 6: return "Q5_0";
            case 7: return "Q5_1";
            case 8: return "Q8_0";
            case 9: return "Q8_1";
            case 10: return "Q2_K";
            case 11: return "Q3_K";
            case 12: return "Q4_K";
            case 13: return "Q5_K";
            case 14: return "Q6_K";
            case 15: return "Q8_K";
            case 16: return "IQ2_XXS";
            case 17: return "IQ2_XS";
            case 18: return "IQ3_XXS";
            case 19: return "IQ1_S";
            case 20: return "IQ4_NL";
            case 21: return "IQ3_S";
            case 22: return "IQ2_S";
            case 23: return "IQ4_XS";
            case 29: return "IQ1_M";
            case 30: return "BF16";
            case 34: return "TQ1_0";
            case 35: return "TQ2_0";
            case 39: return "MXFP4";
            case 40: return "NVFP4";
            default: return "TYPE" + t;
        }
    }

    private static boolean isFixed(int vt) {
        return vt >= 0 && vt < WIDTH.length && WIDTH[vt] > 0;
    }

    /** unsigned fixed-width little-endian read (u8..u64); null on EOF */
    private static Long readFixed(InputStream in, int vt, byte[] b4, byte[] b8) throws IOException {
        int n = WIDTH[vt];
        byte[] buf = n > 4 ? b8 : b4;
        if (in.read(buf, 0, n) != n) return null;
        return n > 4 ? le64(buf, 0) : le32(buf, 0) & 0xFFFFFFFFL;
    }

    /** skip one value of the given type; false on malformed/truncated input */
    private static boolean skip(InputStream in, int vt, byte[] b4, byte[] b8, int depth) throws IOException {
        if (vt == T_STRING) {
            return readStr(in, b4, b8) != null;
        }
        if (vt == T_ARRAY) {
            if (depth > 1 || in.read(b4) != 4) return false;
            int et = (int) le32(b4, 0);
            if (et < 0 || et > 12) return false;
            if (in.read(b8) != 8) return false;
            long count = le64(b8, 0);
            if (count < 0 || count > 100_000_000) return false;
            if (et == T_ARRAY) return false;
            if (et == T_STRING) {
                for (long i = 0; i < count; i++) if (readStr(in, b4, b8) == null) return false;
                return true;
            }
            if (WIDTH[et] <= 0) return false;
            long total = count * WIDTH[et];   // count bounded, width ≤ 8 — no overflow
            return skipBytes(in, total);
        }
        if (!isFixed(vt)) return false;
        return skipBytes(in, WIDTH[vt]);
    }

    private static boolean skipBytes(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s > 0) { n -= s; continue; }
            if (in.read() == -1) return false;   // skip() may return 0 — consume 1 byte
            n--;
        }
        return true;
    }

    /** u64 length-prefixed UTF-8 string; null on EOF or implausible length */
    private static String readStr(InputStream in, byte[] b4, byte[] b8) throws IOException {
        // GGUF string length is u64 (little-endian) — was misread as u32 in vc105,
        // which desynced the KV walk by 4 bytes at the first string value and made
        // every probe return ok=false (MoE form never appeared). GGUF v2/v3 alike.
        if (in.read(b8) != 8) return null;
        long len = le64(b8, 0);
        if (len < 0 || len > 16_777_216) return null;
        byte[] s = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(s, off, (int) len - off);
            if (n < 0) return null;
            off += n;
        }
        return new String(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8
                | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }
}
