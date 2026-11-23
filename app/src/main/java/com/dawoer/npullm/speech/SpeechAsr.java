package com.dawoer.npullm.speech;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;

/**
 * vc167: ASR 引擎薄封装（sherpa-onnx OfflineRecognizer，SenseVoice 一族，跑在 :asr 进程）。
 *
 * 只支持 SenseVoice 一族（用户 1110 拍板：先窄后宽）；其它族明确报「本版不支持」。
 * SenseVoice 是非流式：一段 16k mono float PCM 进去，一句话文本出来，还带
 * 语种/情绪/事件标签（模型能力，免费显示）。
 *
 * 串行纪律（D5）：引擎非线程安全，加载+推理全在一个 LOCK 下；两进程之间天然并行。
 */
public final class SpeechAsr {

    public static final String FAMILY = "sensevoice";

    private static final Object LOCK = new Object();

    private static OfflineRecognizer rec;
    private static String loadedPath = "";
    private static long lastUsedMs;

    private SpeechAsr() {}

    /** 识别结果（text 之外的字段是 SenseVoice 的白送信息）。 */
    public static final class Result {
        public String text = "";
        public String lang = "";
        public String emotion = "";
        public String event = "";
        public int durationMs;
    }

    public static File modelDir(Context c) {
        return SpeechConfig.activeDir(c, SpeechConfig.KIND_ASR);
    }

    public static String modelName(Context c) {
        return SpeechConfig.activeName(c, SpeechConfig.KIND_ASR);
    }

    public static boolean ready(Context c) {
        File d = modelDir(c);
        return d != null && ModelManifest.ready(d, SpeechConfig.KIND_ASR);
    }

    public static synchronized boolean loaded() {
        return rec != null;
    }

    public static void tick() {
        synchronized (LOCK) {
            if (rec != null && System.currentTimeMillis() - lastUsedMs > 5L * 60 * 1000) {
                releaseLocked();
            }
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            releaseLocked();
        }
    }

    private static void releaseLocked() {
        if (rec != null) {
            try { rec.release(); } catch (Throwable ignore) {}
        }
        rec = null;
        loadedPath = "";
    }

    /**
     * 识别。pcm 必须是 16k mono float（SpeechPcm 已统一重采样）。
     * @param lang "auto"/"zh"/"en"/"ja"/"ko"/"yue"；空=auto
     */
    public static Result recognize(Context c, float[] pcm, int sampleRate, String lang)
            throws Exception {
        if (pcm == null || pcm.length == 0) throw new Exception("音频为空");
        int sr = sampleRate > 0 ? sampleRate : 16000;
        if (sr != 16000) pcm = SpeechPcm.resample(pcm, sr, 16000);

        synchronized (LOCK) {
            File dir = modelDir(c);
            if (dir == null) throw new Exception("还没有 ASR 模型（先在语音识别页导入模型包）");
            if (!ModelManifest.ready(dir, SpeechConfig.KIND_ASR)) {
                throw new Exception("模型文件不齐：" + SpeechTts.join(ModelManifest.missing(dir,
                        ModelManifest.detectFamily(dir, SpeechConfig.KIND_ASR),
                        ModelManifest.load(dir))));
            }
            ensureLoaded(dir, lang);
            lastUsedMs = System.currentTimeMillis();

            OfflineStream stream = rec.createStream();
            try {
                stream.acceptWaveform(pcm, 16000);
                rec.decode(stream);
                OfflineRecognizerResult r = rec.getResult(stream);
                Result out = new Result();
                if (r != null) {
                    out.text = r.getText() == null ? "" : r.getText().trim();
                    out.lang = r.getLang() == null ? "" : r.getLang();
                    out.emotion = r.getEmotion() == null ? "" : r.getEmotion();
                    out.event = r.getEvent() == null ? "" : r.getEvent();
                }
                out.durationMs = (int) (1000L * pcm.length / 16000);
                return out;
            } finally {
                try { stream.release(); } catch (Throwable ignore) {}
            }
        }
    }

    private static void ensureLoaded(File dir, String lang) throws Exception {
        String path = dir.getAbsolutePath();
        String lg = lang == null ? "" : lang.trim();
        if (rec != null && path.equals(loadedPath)) return;
        if (rec != null) releaseLocked();
        ModelManifest m = ModelManifest.load(dir);
        String fam = (m != null && m.family.length() > 0)
                ? m.family : ModelManifest.detectFamily(dir, SpeechConfig.KIND_ASR);
        if (!FAMILY.equals(fam)) {
            throw new Exception("本版 ASR 只支持 SenseVoice 系模型（识别为 "
                    + ModelManifest.familyLabel(fam) + "）");
        }
        String onnx = ModelManifest.onnxName(dir, m);
        if (onnx == null) throw new Exception("模型目录里没有 model*.onnx");
        String tokens = new File(dir, ModelManifest.tokensName(m)).getPath();
        String language = lg.length() > 0 ? lg
                : (m != null && m.lang.length() > 0 ? m.lang : "auto");

        OfflineRecognizer engine;
        try {
            OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig(
                    new File(dir, onnx).getPath(),   // model
                    language,                         // language
                    true);                            // useInverseTextNormalization
            // vc169: Kotlin data class 参数非空——不用的引擎子配置必须传无参空实例，传 null 直接被
            // Intrinsics.checkNotNullParameter 拒（真机报 "parameter transducer"）
            OfflineModelConfig mc = new OfflineModelConfig(
                    new com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(),   // transducer（空=不启用）
                    new com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(),  // paraformer
                    new com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(),     // whisper
                    new com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig(),  // fireRed
                    new com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(),   // moonshine
                    new com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(),// nemo
                    sv,    // senseVoice
                    new com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig(),     // dolphin
                    new com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(),// zipformerCtc
                    new com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig(),    // wenetCtc
                    new com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig(),      // canary
                    "",    // teleSpeech
                    2,     // numThreads
                    false, // debug
                    "cpu", // provider
                    "",    // modelType
                    tokens,// tokens
                    "",    // modelingUnit
                    "");   // bpeVocab
            OfflineRecognizerConfig cfg = new OfflineRecognizerConfig(
                    new FeatureConfig(16000, 80, 0f),   // feat: 16k / 80 mel
                    mc,
                    new com.k2fsa.sherpa.onnx.HomophoneReplacerConfig(),  // hr（同音替换，vc169 不开）
                    "greedy_search",
                    4,      // maxActivePaths
                    "",     // hotwordsFile
                    1.5f,   // hotwordsScore
                    "",     // ruleFsts
                    "",     // ruleFars
                    0f);    // blankPenalty
            engine = new OfflineRecognizer(null, cfg);
        } catch (Throwable t) {
            throw new Exception("ASR 引擎加载失败：" + SpeechTts.msg(t));
        }
        rec = engine;
        loadedPath = path;
    }
}
