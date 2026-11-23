package com.dawoer.npullm.speech;

import android.content.Context;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * vc166: TTS 引擎薄封装（sherpa-onnx OfflineTts，跑在 :tts 进程）。
 *
 * 与 vc165 的差别：
 *   - 模型目录不再是固定 models/tts，而是 active.txt 指向的模型根（多模型并存）
 *   - 装配交给 TtsConfigFactory（修 melo 的 dictDir/dataDir 语义）
 *   - spearker：按 OfflineTts.numSpeakers() 真实取值，sid 越界回落 0，并落 prefs
 *     供 UI 显示（:tts 与 main 是两进程，UI 只能通过 /health 读这个值）
 *   - 模型切换检测：active 目录变了 → 释放旧引擎重载
 *
 * 串行纪律：sherpa 引擎非线程安全，所有加载+推理都在 LOCK 下（D5）。
 */
public final class SpeechTts {

    private static final Object LOCK = new Object();

    private static OfflineTts tts;
    private static String loadedPath = "";
    private static long lastUsedMs;
    private static int speakers;
    private static int sampleRate;

    private SpeechTts() {}

    /** 当前生效模型根（无则 null）。 */
    public static File modelDir(Context c) {
        return SpeechConfig.activeDir(c, SpeechConfig.KIND_TTS);
    }

    public static String modelName(Context c) {
        return SpeechConfig.activeName(c, SpeechConfig.KIND_TTS);
    }

    /** 模型齐备（清单全 ✅）才谈得上可建引擎。 */
    public static boolean ready(Context c) {
        File d = modelDir(c);
        return d != null && ModelManifest.ready(d, SpeechConfig.KIND_TTS);
    }

    public static synchronized boolean loaded() {
        return tts != null;
    }

    public static synchronized int speakers() {
        return speakers;
    }

    public static synchronized int sampleRate() {
        return sampleRate;
    }

    /** 引擎空转回收：5 分钟无请求 release（省内存，下次自动重载）。 */
    public static void tick() {
        synchronized (LOCK) {
            if (tts != null && System.currentTimeMillis() - lastUsedMs > 5L * 60 * 1000) {
                releaseLocked();
            }
        }
    }

    /** 停服务 / 换模型 / admin reload 时硬释放。 */
    public static void shutdown() {
        synchronized (LOCK) {
            releaseLocked();
        }
    }

    private static void releaseLocked() {
        if (tts != null) {
            try { tts.release(); } catch (Throwable ignore) {}
        }
        tts = null;
        loadedPath = "";
    }

    /**
     * 合成 → wav 文件绝对路径。sid < 0 用当前选中音色；越界回落 0。
     * 失败抛带原因的异常（服务端转 500 JSON，UI 直接显示这条文字）。
     */
    public static String synthesize(Context c, String text, int sid, float speed)
            throws Exception {
        if (text == null || text.trim().length() == 0) throw new Exception("文本为空");
        String t = text.trim();
        if (t.length() > 1000) t = t.substring(0, 1000);
        if (speed < 0.5f || speed > 2.0f) speed = 1.0f;

        synchronized (LOCK) {
            File dir = modelDir(c);
            if (dir == null) throw new Exception("还没有可用模型（先在语音页导入模型包）");
            if (!ModelManifest.ready(dir, SpeechConfig.KIND_TTS)) {
                throw new Exception("模型文件不齐：" + join(ModelManifest.missing(dir,
                        ModelManifest.detectFamily(dir, SpeechConfig.KIND_TTS),
                        ModelManifest.load(dir))));
            }
            ensureLoaded(dir);

            int n = speakers > 0 ? speakers : 1;
            int use = sid < 0 ? SpeechConfig.ttsSid() : sid;
            if (use < 0 || use >= n) use = 0;
            SpeechConfig.setTtsSid(use);
            lastUsedMs = System.currentTimeMillis();

            long _t0 = System.currentTimeMillis();
            GeneratedAudio audio = tts.generate(t, use, speed);
            // vc173: 真机诊断——每段合成耗时/音频长度/首 8 字，logcat -s PoTts 可看接缝是否正常
            try {
                int _n = (audio == null || audio.getSamples() == null) ? 0 : audio.getSamples().length;
                int _sr = audio == null ? 0 : audio.getSampleRate();
                android.util.Log.i("PoTts", "ms=" + (System.currentTimeMillis() - _t0)
                        + " len=" + t.length() + " audio_ms=" + (_sr > 0 ? _n * 1000L / _sr : 0)
                        + " head=" + t.substring(0, Math.min(8, t.length())));
            } catch (Throwable ignore) {}

            if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                throw new Exception("合成结果为空（引擎未产出音频）");
            }
            File outDir = new File(c.getCacheDir(), "speech_out");
            if (!outDir.isDirectory() && !outDir.mkdirs()) throw new Exception("无法创建输出目录");
            File wav = new File(outDir, "tts-" + System.currentTimeMillis() + ".wav");
            writeWav(wav, audio.getSamples(), audio.getSampleRate());
            return wav.getPath();
        }
    }

    /** 兼容 vc162-165 单参重载。 */
    public static String synthesize(Context c, String text, float speed) throws Exception {
        return synthesize(c, text, -1, speed);
    }

    /** 需在 LOCK 内调用。 */
    private static void ensureLoaded(File dir) throws Exception {
        String path = dir.getAbsolutePath();
        if (tts != null && path.equals(loadedPath)) return;
        if (tts != null) releaseLocked();
        // 加载失败要把原因原样抛出去（vc165 的黑盒教训：异常被吞 → UI 只能猜）
        OfflineTts engine;
        try {
            engine = new OfflineTts(null, TtsConfigFactory.build(dir));
        } catch (Throwable t) {
            throw new Exception("TTS 引擎加载失败：" + msg(t));
        }
        tts = engine;
        loadedPath = path;
        try {
            speakers = engine.numSpeakers();
        } catch (Throwable t) {
            speakers = 0;
        }
        try {
            sampleRate = engine.sampleRate();
        } catch (Throwable t) {
            sampleRate = 0;
        }
        if (speakers <= 0) speakers = 1;
        SpeechConfig.setTtsSpeakers(speakers);
        if (SpeechConfig.ttsSid() >= speakers) SpeechConfig.setTtsSid(0);
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(a[i]);
        }
        return sb.toString();
    }

    /** float PCM → 16-bit mono WAV。 */
    static void writeWav(File out, float[] samples, int rate) throws Exception {
        int n = samples.length;
        int sr = rate > 0 ? rate : 22050;
        int dataLen = n * 2;
        OutputStream os = new FileOutputStream(out);
        try {
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream(dataLen);
            for (int i = 0; i < n; i++) {
                float v = samples[i];
                if (v > 1f) v = 1f;
                if (v < -1f) v = -1f;
                int s = (int) (v * 32767f);
                pcm.write(s & 0xff);
                pcm.write((s >> 8) & 0xff);
            }
            byte[] body = pcm.toByteArray();
            java.io.DataOutputStream w = new java.io.DataOutputStream(os);
            w.writeBytes("RIFF");
            w.writeInt(le(36 + body.length));
            w.writeBytes("WAVE");
            w.writeBytes("fmt ");
            w.writeInt(le(16));
            w.writeShort(le16(1));            // PCM
            w.writeShort(le16(1));            // mono
            w.writeInt(le(sr));
            w.writeInt(le(sr * 2));           // byte rate
            w.writeShort(le16(2));            // block align
            w.writeShort(le16(16));           // bits
            w.writeBytes("data");
            w.writeInt(le(body.length));
            w.write(body);
            w.flush();
        } finally {
            try { os.close(); } catch (Throwable ignore) {}
        }
    }

    /** 已经是 16bit PCM 字节流的 WAV 落盘（vc167 录音/上传共用）。 */
    static void writeWavPcm16(File out, byte[] body, int rate) throws Exception {
        int sr = rate > 0 ? rate : 16000;
        OutputStream os = new FileOutputStream(out);
        try {
            java.io.DataOutputStream w = new java.io.DataOutputStream(os);
            w.writeBytes("RIFF");
            w.writeInt(le(36 + body.length));
            w.writeBytes("WAVE");
            w.writeBytes("fmt ");
            w.writeInt(le(16));
            w.writeShort(le16(1));
            w.writeShort(le16(1));
            w.writeInt(le(sr));
            w.writeInt(le(sr * 2));
            w.writeShort(le16(2));
            w.writeShort(le16(16));
            w.writeBytes("data");
            w.writeInt(le(body.length));
            w.write(body);
            w.flush();
        } finally {
            try { os.close(); } catch (Throwable ignore) {}
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    static int le(int v) {
        return Integer.reverseBytes(v);
    }

    static int le16(int v) {
        return Short.reverseBytes((short) v) & 0xffff;
    }
}
