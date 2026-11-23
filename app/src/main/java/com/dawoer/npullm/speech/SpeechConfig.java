package com.dawoer.npullm.speech;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * vc166: 语音子系统配置 + 目录契约（TTS / ASR 两套，对称）。
 *
 * vc166 起语音引擎跑在独立进程（:tts / :asr），所以这里的每一个持久值都必须
 * 是「进程重启后能从磁盘重新读到」的：模型目录与 active.txt 走文件系统
 * （三进程同 UID 共享 getFilesDir），key/端口/音色走 SharedPreferences
 * （改动后调用方负责重启对应服务，新进程从磁盘重读）。
 *
 * 目录布局（内部存储，不受外置卡卸载影响）：
 *   filesDir/models/tts/_tmp/           解压暂存
 *   filesDir/models/tts/<name>/         模型根（含 po-model.json 可选）
 *   filesDir/models/tts/active.txt      当前生效模型目录名
 *   filesDir/models/asr/...             同构
 */
public final class SpeechConfig {

    public static final String KIND_TTS = "tts";
    public static final String KIND_ASR = "asr";

    public static final int DEF_PORT_TTS = 8084;
    public static final int DEF_PORT_ASR = 8085;

    /** 已被推理(8080)/搜索(8081)/RAG(8082)/RAG 嵌入(8083) 占用的端口，语音侧禁用。 */
    private static final int[] TAKEN_PORTS = {8080, 8081, 8082, 8083};

    private static final String PREFS = "npullm_speech";
    private static Context app;

    private SpeechConfig() {}

    /** Idempotent; called from every service onCreate and every bridge entry. */
    public static void init(Context c) {
        if (c != null) app = c.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        Context c = app;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 归一化：只认 tts/asr，其它一律 tts。 */
    public static String kindOf(String kind) {
        return KIND_ASR.equals(kind) ? KIND_ASR : KIND_TTS;
    }

    // ===================== 模型目录 =====================

    public static File modelsRoot(Context c) {
        return new File(c.getFilesDir(), "models");
    }

    public static File kindDir(Context c, String kind) {
        return new File(modelsRoot(c), kindOf(kind));
    }

    public static File tmpDir(Context c, String kind) {
        return new File(kindDir(c, kind), "_tmp");
    }

    public static File activeTxt(Context c, String kind) {
        return new File(kindDir(c, kind), "active.txt");
    }

    /** active.txt 内容（trim），无则空串。只读文件——跨进程安全。 */
    public static String activeName(Context c, String kind) {
        String s = readSmall(activeTxt(c, kind));
        return s == null ? "" : s.trim();
    }

    public static void setActiveName(Context c, String kind, String name) {
        String v = name == null ? "" : name.trim();
        writeSmall(activeTxt(c, kind), v + "\n");
    }

    /** 当前生效模型目录；无 active 或目录不存在 → null。 */
    public static File activeDir(Context c, String kind) {
        String n = activeName(c, kind);
        if (n.length() == 0) return null;
        File d = new File(kindDir(c, kind), n);
        return d.isDirectory() ? d : null;
    }

    // ===================== 端口 =====================

    /** 另一侧端口（TTS 看 ASR、ASR 看 TTS），用于互斥校验。 */
    public static int otherPort(String kind) {
        return port(KIND_ASR.equals(kindOf(kind)) ? KIND_TTS : KIND_ASR);
    }

    public static boolean validatePort(String kind, int v) {
        if (v < 1024 || v > 65535) return false;
        for (int t : TAKEN_PORTS) {
            if (v == t) return false;
        }
        return v != otherPort(kindOf(kind));
    }

    public static int port(String kind) {
        String k = kindOf(kind);
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt(k + "_port", -1);
        if (v < 0 && KIND_TTS.equals(k)) {
            // vc162-165 旧键迁移
            v = p == null ? -1 : p.getInt("speech_port", -1);
        }
        if (v < 1024 || v > 65535) {
            return KIND_ASR.equals(k) ? DEF_PORT_ASR : DEF_PORT_TTS;
        }
        return v;
    }

    public static void setPort(String kind, int v) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(kindOf(kind) + "_port", v).apply();
    }

    // ===================== key 门 =====================

    public static String gateKey(String kind) {
        String k = kindOf(kind);
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString(k + "_gate_key", null);
        if (v == null && KIND_TTS.equals(k)) {
            v = p == null ? null : p.getString("speech_gate_key", null);
        }
        return v == null ? "" : v.trim();
    }

    public static void setGateKey(String kind, String v) {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putString(kindOf(kind) + "_gate_key", v == null ? "" : v.trim()).apply();
        }
    }

    /** 取 key；空则生成并落盘（照 RagConfig.ensureGateKey）。 */
    public static String ensureGateKey(String kind) {
        String v = gateKey(kind);
        if (v.length() == 0) {
            v = newGateKey(kind);
            setGateKey(kind, v);
        }
        return v;
    }

    /** 重新生成（副作用：调用方负责重启对应服务，新进程才读到新 key）。 */
    public static String resetGateKey(String kind) {
        String v = newGateKey(kind);
        setGateKey(kind, v);
        return v;
    }

    /** 指纹（后 4 位），UI 展示用。 */
    public static String gateKeyFingerprint(String kind) {
        String v = gateKey(kind);
        if (v.length() <= 12) return "";
        return v.substring(v.length() - 4);
    }

    /** 前缀：sk-tts- / sk-asr-（vc162-165 的 sk-speech- 前缀不再生成，旧值照用）。 */
    public static String keyPrefix(String kind) {
        return KIND_ASR.equals(kindOf(kind)) ? "sk-asr-" : "sk-tts-";
    }

    private static String newGateKey(String kind) {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(keyPrefix(kind));
        for (int i = 0; i < b.length; i++) {
            int n = b[i] & 0xff;
            if (n < 0x10) sb.append('0');
            sb.append(Integer.toHexString(n));
        }
        return sb.toString();
    }

    // ===================== 音色（TTS，按模型 numSpeakers 真实生成）=====================

    /** 当前 speaker id（默认 0）。 */
    public static int ttsSid() {
        SharedPreferences p = prefs();
        return p == null ? 0 : Math.max(0, p.getInt("tts_sid", 0));
    }

    public static void setTtsSid(int sid) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt("tts_sid", Math.max(0, sid)).apply();
    }

    /** 上次引擎加载时读到的 speaker 数（0 = 未知，引擎未加载过）。 */
    public static int ttsSpeakers() {
        SharedPreferences p = prefs();
        return p == null ? 0 : Math.max(0, p.getInt("tts_speakers", 0));
    }

    public static void setTtsSpeakers(int n) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt("tts_speakers", Math.max(0, n)).apply();
    }

    // ===================== 兼容重载（vc162-165 单侧调用点）=====================

    public static int port() { return port(KIND_TTS); }

    public static String gateKey() { return gateKey(KIND_TTS); }

    public static String ensureGateKey() { return ensureGateKey(KIND_TTS); }

    public static String resetGateKey() { return resetGateKey(KIND_TTS); }

    public static String gateKeyFingerprint() { return gateKeyFingerprint(KIND_TTS); }

    public static boolean validatePort(int v) { return validatePort(KIND_TTS, v); }

    public static void setPort(int v) { setPort(KIND_TTS, v); }

    // ===================== 小文件读写 =====================

    static String readSmall(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    static boolean writeSmall(File f, String s) {
        if (f == null) return false;
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) return false;
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(String.valueOf(s).getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignore) {}
        }
    }
}
