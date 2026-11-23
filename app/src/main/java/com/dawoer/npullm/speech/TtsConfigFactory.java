package com.dawoer.npullm.speech;

import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

/**
 * vc166: TTS 引擎装配工厂（按族装配 sherpa 配置）。
 *
 * vc166 只有一条真实分支：VITS（含 melo / piper 系）。其余族（Kokoro / Matcha /
 * Kitten）留 switch 位置，明确报「本版不支持」——不假装支持。
 *
 * ★vc165 的 melo 合成失败首嫌疑就在这里：旧代码把 jieba 词典目录塞进 dataDir、
 *   dictDir 传空。sherpa 的 VITS 契约是 dataDir = espeak-ng-data（piper 型才需要），
 *   dictDir = jieba 词典目录（melo 中文必须）。本类按这个语义装配。
 */
public final class TtsConfigFactory {

    private TtsConfigFactory() {}

    public static OfflineTtsConfig build(File root) throws Exception {
        if (root == null || !root.isDirectory()) throw new Exception("模型目录不存在");
        ModelManifest m = ModelManifest.load(root);
        String fam = (m != null && m.family.length() > 0)
                ? m.family : ModelManifest.detectFamily(root, SpeechConfig.KIND_TTS);
        if (fam.length() == 0) throw new Exception("无法识别模型族（本版只支持 VITS 系模型）");
        if (!ModelManifest.FAMILY_VITS.equals(fam)) {
            throw new Exception("本版 TTS 只支持 VITS 系模型（识别为 " + fam + "）");
        }
        return vits(root, m);
    }

    public static OfflineTtsConfig vits(File root, ModelManifest m) throws Exception {
        String onnx = ModelManifest.onnxName(root, m);
        if (onnx == null) throw new Exception("模型目录里没有 model*.onnx");
        String tokens = new File(root, ModelManifest.tokensName(m)).getPath();
        String lexicon = new File(root, ModelManifest.lexiconName(m)).isFile()
                ? new File(root, ModelManifest.lexiconName(m)).getPath() : "";
        String dictDir = "";
        File dict = new File(root, ModelManifest.dictName(m));
        if (dict.isDirectory()) dictDir = dict.getPath();
        // piper 型 VITS 需要 espeak-ng-data（dataDir）；melo/中文 VITS 不需要（留空）
        String dataDir = "";
        File esp = new File(root, "espeak-ng-data");
        if (esp.isDirectory()) dataDir = esp.getPath();

        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                new File(root, onnx).getPath(),   // model
                lexicon,                          // lexicon
                tokens,                           // tokens
                dataDir,                          // dataDir（仅 piper 型）
                dictDir,                          // dictDir（melo 中文 jieba）
                0.667f,                           // noiseScale
                0.8f,                             // noiseScaleW
                1.0f);                            // lengthScale

        OfflineTtsModelConfig mc = new OfflineTtsModelConfig(
                vits,
                // vc169: Kotlin 非空参数——空实例代替 null（真机报 "parameter matcha"）
                new com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(),   // matcha
                new com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(),   // kokoro
                new com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig(),   // kitten
                ttsThreads(),   // numThreads（vc173: 2 常量 → 按核心数，上限 6）
                false,     // debug
                "cpu");    // provider（TTS 走 CPU；NPU 不支持这套图）

        return new OfflineTtsConfig(
                mc,
                ruleFsts(root),
                "",        // ruleFars
                1,         // maxNumSentences
                0.2f);     // silenceScale
    }

    /**
     * vc173: VITS 合成线程数。vc172 写死 2 —— 实测 fp32 模型 RTF 2→6 线程从 0.538 降到 0.276，
     * 而 int8 拆包几乎不吃线程（同样 2~4 线程 RTF 仍 2.17，量化负优化）。上限 6 留 2 核给 UI/播放。
     */
    static int ttsThreads() {
        int n = Runtime.getRuntime().availableProcessors() - 1;
        if (n < 2) n = 2;
        if (n > 6) n = 6;
        return n;
    }

    /** 文本规则 fst（date/number/phone/新词多音字），存在的才拼进去。 */
    static String ruleFsts(File root) {
        String[] names = {"date.fst", "number.fst", "phone.fst", "new_heteronym.fst"};
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            File f = new File(root, n);
            if (!f.isFile()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(f.getPath());
        }
        return sb.toString();
    }
}
