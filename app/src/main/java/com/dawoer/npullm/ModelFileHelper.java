package com.dawoer.npullm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.3.0: quant whitelist only. All SAF/content-URI resolution (fd probe,
 * MediaStore, cache copy) was removed together with the v0.2.0 model-pick
 * flow — models now come from the native directory browser as real paths
 * under /storage/emulated/0 (llama-npu.apk verified core).
 */
final class ModelFileHelper {
    private ModelFileHelper() {}

    private static final Pattern OK_QUANT =
            Pattern.compile("(Q4_0|Q4_1|Q8_0|IQ4_NL|MXFP4|F16|F32)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAD_QUANT =
            Pattern.compile("(Q[2-8]_K|IQ[1-3]_XXS|IQ[1-3]_S|IQ[1-3]_M|IQ[1-3]_L)", Pattern.CASE_INSENSITIVE);

    static final class Quant {
        final String label;
        final boolean npuOk;
        Quant(String label, boolean npuOk) { this.label = label; this.npuOk = npuOk; }
    }

    static Quant quantOf(String filename) {
        Matcher bad = BAD_QUANT.matcher(filename);
        if (bad.find()) return new Quant(bad.group(1).toUpperCase(Locale.US), false);
        Matcher ok = OK_QUANT.matcher(filename);
        if (ok.find()) return new Quant(ok.group(1).toUpperCase(Locale.US), true);
        return new Quant("未知量化", true);
    }
}
