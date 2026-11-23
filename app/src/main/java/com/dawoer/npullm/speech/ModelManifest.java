package com.dawoer.npullm.speech;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * vc166: 模型清单（优先读包内 po-model.json；无 manifest 时按目录特征判族）。
 *
 * vc166 只支持一族：TTS=VITS、ASR=SenseVoice。判不出族时明确报错，不弹类型选择框、
 * 不假装支持——这是用户 1110 拍板的「先窄后宽」。
 *
 * po-model.json 形态：
 *   {"kind":"tts","family":"vits","name":"melo-zh_en",
 *    "files":{"model":"model.int8.onnx","tokens":"tokens.txt",
 *             "lexicon":"lexicon.txt","dict":"dict"},
 *    "display":{"lang":"zh-en","speakers":1}}
 */
public final class ModelManifest {

    public static final String FAMILY_VITS = "vits";
    public static final String FAMILY_SENSEVOICE = "sensevoice";

    public String kind = "";
    public String family = "";
    public String name = "";
    public String lang = "";
    public int speakers = 0;
    public String model = "";
    public String tokens = "";
    public String lexicon = "";
    public String dict = "";

    /** 读 <root>/po-model.json；无文件/坏 JSON → null。 */
    public static ModelManifest load(File root) {
        if (root == null) return null;
        File f = new File(root, "po-model.json");
        if (!f.isFile()) return null;
        try {
            String txt = SpeechConfig.readSmall(f);
            if (txt == null) return null;
            JSONObject o = new JSONObject(txt);
            ModelManifest m = new ModelManifest();
            m.kind = o.optString("kind", "");
            m.family = o.optString("family", "").toLowerCase(java.util.Locale.US);
            m.name = o.optString("name", "");
            JSONObject files = o.optJSONObject("files");
            if (files != null) {
                m.model = files.optString("model", "");
                m.tokens = files.optString("tokens", "");
                m.lexicon = files.optString("lexicon", "");
                m.dict = files.optString("dict", "");
            }
            JSONObject disp = o.optJSONObject("display");
            if (disp != null) {
                m.lang = disp.optString("lang", "");
                m.speakers = disp.optInt("speakers", 0);
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 无 manifest 时的特征推断；判不出返回 ""。 */
    public static String detectFamily(File root) {
        return detectFamily(root, SpeechConfig.KIND_TTS);
    }

    public static String detectFamily(File root, String kind) {
        if (root == null || !root.isDirectory()) return "";
        boolean onnx = findOnnx(root) != null;
        boolean tokens = new File(root, "tokens.txt").isFile();
        if (SpeechConfig.KIND_ASR.equals(SpeechConfig.kindOf(kind))) {
            // SenseVoice / paraformer / CTC 都是 model+tokens 单文件形态
            return (onnx && tokens) ? FAMILY_SENSEVOICE : "";
        }
        if (!onnx || !tokens) return "";
        // VITS 中文（melo / piper）需要 jieba 词典；只有 lexicon 无 dict 也可加载（英文类）
        if (new File(root, "lexicon.txt").isFile() || new File(root, "dict").isDirectory()) {
            return FAMILY_VITS;
        }
        return "";
    }

    /** 实际可用的 onnx 文件名；没有返回 null。manifest 声明优先。 */
    public static String onnxName(File root, ModelManifest m) {
        if (root == null) return null;
        if (m != null && m.model.length() > 0 && new File(root, m.model).isFile()) {
            return m.model;
        }
        File[] fs = root.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (f.isFile() && n.endsWith(".onnx") && n.startsWith("model")) return n;
        }
        // 单 onnx 兜底（VITS 包里可能叫 xxx.onnx）
        String only = null;
        int cnt = 0;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".onnx")) {
                only = f.getName();
                cnt++;
            }
        }
        return cnt == 1 ? only : null;
    }

    static File findOnnx(File root) {
        return depthFirst(root, 0);
    }

    private static File depthFirst(File dir, int depth) {
        if (dir == null || depth > 6) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".onnx")) return f;
        }
        List<File> subdirs = new ArrayList<File>();
        for (File f : fs) {
            if (f.isDirectory() && !f.getName().startsWith("_")) subdirs.add(f);
        }
        for (File d : subdirs) {
            File hit = depthFirst(d, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * 必需文件清单（按族，vc166 只有两族）→ 返回缺失项标签；空数组 = 齐。
     * 标签直接给 UI 用（✅/❌ 清单）。
     */
    public static String[] missing(File root, String family, ModelManifest m) {
        List<String> out = new ArrayList<String>();
        if (root == null || !root.isDirectory()) {
            out.add("模型目录");
            return out.toArray(new String[0]);
        }
        String onnx = onnxName(root, m);
        if (onnx == null) out.add("model*.onnx");
        if (!new File(root, tokensName(m)).isFile()) out.add("tokens.txt");
        if (FAMILY_VITS.equals(family)) {
            if (!new File(root, lexiconName(m)).isFile()) out.add("lexicon.txt");
            if (!new File(root, dictName(m)).isDirectory()) out.add("dict/");
        }
        return out.toArray(new String[0]);
    }

    static String tokensName(ModelManifest m) {
        return (m != null && m.tokens.length() > 0) ? m.tokens : "tokens.txt";
    }

    static String lexiconName(ModelManifest m) {
        return (m != null && m.lexicon.length() > 0) ? m.lexicon : "lexicon.txt";
    }

    static String dictName(ModelManifest m) {
        return (m != null && m.dict.length() > 0) ? m.dict : "dict";
    }

    /** 就绪判据（按 kind 取族）。 */
    public static boolean ready(File root, String kind) {
        if (root == null || !root.isDirectory()) return false;
        ModelManifest m = load(root);
        String fam = (m != null && m.family.length() > 0)
                ? m.family : detectFamily(root, kind);
        if (fam.length() == 0) return false;
        return missing(root, fam, m).length == 0;
    }

    /** 族的中文标签。 */
    public static String familyLabel(String family) {
        if (FAMILY_VITS.equals(family)) return "VITS";
        if (FAMILY_SENSEVOICE.equals(family)) return "SenseVoice";
        return family == null || family.length() == 0 ? "未知" : family;
    }
}
