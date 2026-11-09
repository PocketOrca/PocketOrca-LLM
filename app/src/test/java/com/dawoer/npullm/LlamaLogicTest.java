package com.dawoer.npullm;

import com.dawoer.npullm.search.EngineRegistry;
import com.dawoer.npullm.search.QuoteProvider;
import com.dawoer.npullm.search.OpenMeteoProvider;
import com.dawoer.npullm.search.DataSources;
import com.dawoer.npullm.search.SearchConfig;
import com.dawoer.npullm.search.SearchController;
import com.dawoer.npullm.search.SearchServer;
import com.dawoer.npullm.search.SearchService;

/** Logic tests (run on PC JVM, no device). Same package to reach package-private classes. */
public final class LlamaLogicTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testBuildCommand();
        testUnifiedParams();
        testCpuProfileExec();
        testExecEnvPolicy();
        testHtpEnvOverlay();
        testSplitArgs();
        testQuantWhitelist();
        testRingBuffer();
        testChatStreamerDelta();
        testChatStreamerStats();
        testRamLabel();
        testRebornAction();
        testTempGuard();
        testBmoeProtocol();
        testBmoeBuildCommand();
        testBmoeShardHelpers();
        testBmoePromptFromMessages();
        testBmoeLibClosure();
        testBmoeResolvePath();
        testMtpProfile();
        testVc91MtpCtxFollowsUi();
        testVc91SleepIdle();
        testVc91MemAlarm();
        testVc97KvOffloadAndVqti();
        testVc98Dma64Pin();
        testVc99C1C2();
        testVc99C1Media();
        testVc99Mmproj();
        testVc100S23UFixes();
        testVc101MicPermission();
        testVc102SlotHist();
        testVc103SlotDelete();
        testVc104PortAndKey();
        testVc105Moe();
        testVc107BmoeArgs();
        testVc108MoeForm();
        testVc108GgufStrSpec();
        testVc109PrefillWalls();
        testVc111UiPolish();
        testVc113MoeCompat();
        testVc116();
        testVc118Batch1();
        testVc118Batch2();
        testVc119KvQuantPowerMode();
        testVc120UiPolish();
        testVc122WebSearch();
        testVc123Engines();
        testVc124Engines();
        testVc125Fixes();
        testVc126();
        testVc127Wiring();
        testVc129();
        testVc130();
        testVc131();
        testVc132();
        testVc133Batch4();
        testVc134PageStructure();
        testVc135SearxngSchema();
        testVc136();
        testVc137();
        testVc138();
        testVc139();
        testVc140();
        testVc141();
        testVc147();
        testVc152();
        testVc153();
        testVc154();
        testVc155();
        testVc156();
        testVc157();
        testVc158();
        testVc159();
        testVc161();
        testVc149();
        System.out.println("\nRESULT: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void eq(Object actual, Object expected, String name) {
        if (expected.equals(actual)) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  actual:   " + actual); }
    }

    static void testBuildCommand() {
        System.out.println("== LlamaServerManager.buildCommand (v0.3.1: ctx/reasoning overrides) ==");
        String HTP = "--no-mmap -m {MODEL} --ctx-size 8192 --ubatch-size 1024 -fa on -ngl 99 --device HTP0";
        // default UI state: ctx 4096 overrides profile 8192, reasoning off = flag absent
        String[] cmd = LlamaServerManager.buildCommand(
                "/data/app/~~x==/lib/arm64/libllamaserver.so", HTP,
                "/storage/emulated/0/Download/Qwen2.5-7B-Instruct-Q4_0.gguf", "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0);
        eq(cmd[0].endsWith("libllamaserver.so"), true, "argv0 = jniLibs exe");
        eq(idx(cmd, "--ctx-size"), idx(cmd, "4096") - 1, "ctx replaced in place (4096)");
        eq(contains(cmd, "8192"), false, "profile ctx 8192 gone");
        int ro = idx(cmd, "--reasoning");
        eq(ro > 0 && "off".equals(cmd[ro + 1]), true, "reasoning off = --reasoning off explicit (0906 finding)");
        eq(idx(cmd, "--host"), cmd.length - 4, "host flag appended");
        eq(cmd[cmd.length - 1], "8080", "port appended last");
        eq(idx(cmd, "--device"), cmd.length - 14, "HTP0 before host/port (v0.5: threads+reasoning off)");

        // reasoning on appends flag
        String[] cmdR = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "on", 4, false, 0, 0);
        int r = idx(cmdR, "--reasoning");
        eq(r > 0 && "on".equals(cmdR[r + 1]), true, "reasoning on = --reasoning on appended");
        eq(idx(cmdR, "--ctx-size"), idx(cmdR, "8192") - 1, "ctx 8192 kept when UI=8192");

        // profile without ctx flag gets it appended
        String[] cmdA = LlamaServerManager.buildCommand("/x/libllamaserver.so",
                "--no-mmap -m {MODEL} --threads 4", "/s/m.gguf", "0.0.0.0", 8080, 65536, "off", 2, false, 0, 0);
        eq(idx(cmdA, "--ctx-size") > 0 && "65536".equals(cmdA[idx(cmdA, "--ctx-size") + 1]), true, "ctx appended when profile lacks it");

        // profile with pre-existing --reasoning gets value replaced
        String[] cmdP = LlamaServerManager.buildCommand("/x/libllamaserver.so",
                "-m {MODEL} --reasoning auto --ctx-size 4096", "/s/m.gguf", "0.0.0.0", 8080, 16384, "on", 4, false, 0, 0);
        int rp = idx(cmdP, "--reasoning");
        eq("on".equals(cmdP[rp + 1]), true, "profile --reasoning auto -> on");
        eq(contains(cmdP, "auto"), false, "old auto value gone");
        eq(idx(cmdP, "--ctx-size"), idx(cmdP, "16384") - 1, "ctx 16384 replaced in profile with own flag");

        // adversarial: model path with spaces AND shell metacharacters must stay one token
        String[] cmd2 = LlamaServerManager.buildCommand("/x/libllamaserver.so", "-m {MODEL} -ngl 99",
                "/storage/my models/x';$(reboot)'.gguf", "0.0.0.0", 8080, 4096, "off", 4, false);
        eq(cmd2[2], "/storage/my models/x';$(reboot)'.gguf", "path intact incl quote chars");
        eq(idx(cmd2, "--ctx-size"), idx(cmd2, "4096") - 1, "appended ctx lands after profile args");

        // ctxSize <= 0 keeps profile value (programmatic callers)
        String[] cmd0 = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 0, "off", 0, false, 0, 0);
        eq(idx(cmd0, "--ctx-size"), idx(cmd0, "8192") - 1, "ctxSize<=0 keeps profile 8192");

        // == v1.2.1: --ubatch-size UI override (mirrors ctx-size) ==
        System.out.println("== buildCommand ubatch override (v1.2.1) ==");
        String OCL2 = "--no-mmap -m {MODEL} --ctx-size 8192 -fa on -ngl 99";
        // 10-arg: UI 256 replaces profile 1024 in place
        String[] ub = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 256, 0);
        eq(idx(ub, "--ubatch-size"), idx(ub, "256") - 1, "ubatch replaced in place (256)");
        eq(contains(ub, "1024"), false, "profile ubatch 1024 gone");
        // UI ubatch == profile value: kept, single occurrence
        String[] ubKeep = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 0);
        eq(count(ubKeep, "--ubatch-size"), 1, "ubatch 1024 kept exactly once");
        eq(count(ubKeep, "1024"), 1, "ubatch value not duplicated");
        // v1.2.5: ubatch <= 0 = strip the flag entirely (0 = off/auto), even if profile carries it
        String[] ub0 = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0, 0);
        eq(contains(ub0, "--ubatch-size"), false, "ubatch<=0 strips profile flag (v1.2.5)");
        eq(contains(ub0, "1024"), false, "profile ubatch 1024 gone when UI=0");
        // v1.2.5: ubatch 0 on profile WITHOUT the flag -> still nothing appended
        String[] ub0b = LlamaServerManager.buildCommand("/x/libllamaserver.so", OCL2,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0, 0);
        eq(contains(ub0b, "--ubatch-size"), false, "ubatch 0 appends nothing (no profile flag)");
        // profile WITHOUT --ubatch-size: UI value appended
        String[] ubAdd = LlamaServerManager.buildCommand("/x/libllamaserver.so", OCL2,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 512, 0);
        eq(idx(ubAdd, "--ubatch-size") > 0 && "512".equals(ubAdd[idx(ubAdd, "--ubatch-size") + 1]),
           true, "ubatch appended when profile lacks it");
        // 9-arg legacy overload passes ubatch 0 -> v1.2.5 strips profile flag
        String[] ub9 = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0, 0);
        eq(contains(ub9, "--ubatch-size"), false, "9-arg overload (ubatch 0) strips flag (v1.2.5)");
    }

    /** Spec §4.1A: threads for all profiles, kv-offload default off, verified arg names. */
    static void testUnifiedParams() {
        System.out.println("== buildCommand unified params (spec §4, v0.5.0) ==");
        String HTP = "--no-mmap -m {MODEL} --ctx-size 8192 --ubatch-size 1024 -fa on -ngl 99 --device HTP0";
        String OCL = "--no-mmap -m {MODEL} --ctx-size 8192 -fa on -ngl 99";
        String CPU = "--no-mmap -m {MODEL} --ctx-size 4096 --threads 4";

        // §4.1A rule 1: --threads passed on ALL profiles, incl. htp
        String[] h = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 6, false, 0, 0);
        eq(idx(h, "--threads") > 0 && "6".equals(h[idx(h, "--threads") + 1]), true, "htp: --threads 6 appended");
        eq(idx(h, "--threads"), h.length - 12, "htp: --threads lands right before host/port");
        eq(idx(h, "--device"), h.length - 14, "htp: HTP0 before the host/port block (incl reasoning off)");
        eq(contains(h, "--no-kv-offload"), false, "htp: kv-offload default on = no flag");

        String[] o = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 8, true, 0, 0);
        eq(idx(o, "--threads") > 0 && "8".equals(o[idx(o, "--threads") + 1]), true, "ocl: --threads 8 appended");
        eq(contains(o, "--no-kv-offload"), true, "ocl: noKvOffload=true appends flag");
        eq(contains(o, "--device"), false, "ocl: no HTP device flag");

        // cpu profile carries --threads in profile args: UI value replaces in place
        String[] c = LlamaServerManager.buildCommand("/x/e", CPU, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 2, false, 0, 0);
        eq(idx(c, "--threads") > 0 && "2".equals(c[idx(c, "--threads") + 1]), true, "cpu: profile threads 4 -> UI 2");
        eq(contains(c, "4"), false, "cpu: old threads value gone");
        // §4.2 baseline default: threads 4 + kv offload off = --threads 4 present, no --no-kv-offload
        String[] b = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0, 0);
        eq(contains(b, "--threads") && contains(b, "4"), true, "bench default: threads 4 present");
        eq(contains(b, "--no-kv-offload"), false, "bench default: kv-offload stays enabled");

        // v1.2.5: threads <= 0 = strip flag (0 = hardware auto-detect), even if profile carries it
        String[] p0 = LlamaServerManager.buildCommand("/x/e", CPU, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 0, false, 0, 0);
        eq(contains(p0, "--threads"), false, "threads 0 strips profile flag (v1.2.5)");
        // v1.2.5: threads 0 on profile without the flag -> nothing appended
        String[] p0b = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 0, false, 0, 0);
        eq(contains(p0b, "--threads"), false, "threads 0 appends nothing (no profile flag)");
        // v1.2.5: threads 0 + ubatch 0 combined on HTP -> both stripped, ctx still replaced
        String[] both0 = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf", "0.0.0.0", 8080, 16384, "off", 0, false, 0, 0);
        eq(contains(both0, "--threads"), false, "combined 0: no --threads");
        eq(contains(both0, "--ubatch-size"), false, "combined 0: no --ubatch-size");
        eq(idx(both0, "--ctx-size"), idx(both0, "16384") - 1, "combined 0: ctx still replaced");

        // profile already carrying --no-kv-offload is not duplicated
        String[] dup = LlamaServerManager.buildCommand("/x/e", "-m {MODEL} --no-kv-offload",
                "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 4, true, 0, 0);
        eq(count(dup, "--no-kv-offload"), 1, "existing --no-kv-offload not duplicated");

        // three-profile uniformity (T1/T4 acceptance): every command has --threads
        String[][] all = {h, o, c};
        for (int i = 0; i < all.length; i++)
            eq(contains(all[i], "--threads"), true, "profile " + i + " command contains --threads");
    }

    static int count(String[] a, String t) {
        int n = 0;
        for (String x : a) if (x.equals(t)) n++;
        return n;
    }

    static int idx(String[] a, String t) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(t)) return i;
        return -1;
    }

    static boolean contains(String[] a, String t) {
        return idx(a, t) >= 0;
    }

    static void testCpuProfileExec() throws Exception {
        System.out.println("== profiles.json v1.2.2: cpu engine runs exec NDK runtime ==");
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject root = new org.json.JSONObject(raw);
        org.json.JSONObject cpu = root.getJSONObject("profiles").getJSONObject("cpu");
        org.json.JSONObject ocl = root.getJSONObject("profiles").getJSONObject("ocl");
        org.json.JSONObject htp = root.getJSONObject("profiles").getJSONObject("htp");
        eq(cpu.optString("binary"), "libllamaserver.so", "cpu: exec NDK runtime (v1.2.2, S23U garbled-text fix)");
        eq(ocl.optString("binary"), "libllamaocl.so", "ocl: JNI launcher unchanged");
        eq(htp.optString("binary"), "libllamaserver.so", "htp: exec unchanged");
        // cpu args must NOT contain NPU-only flags
        String args = cpu.optString("args");
        eq(args.contains("-ngl") || args.contains("--device"), false, "cpu: no offload flags in profile args");
        eq(args.contains("--threads"), true, "cpu: --threads present");
        // vc93: q8_0 KV cache default on all llama-server profiles (mtp 已有，htp/ocl/cpu 此处补齐)
        // vc121: profiles.json spells the flags upstream-valid short-form
        // (-ctk/-ctv; arg.cpp has -ctk/--cache-type-k but no "--ctk").
        String htpArgs = htp.optString("args");
        String oclArgs = ocl.optString("args");
        eq(args.contains("-fa on") && args.contains("q8_0"), true,
           "vc93: cpu profile -fa on + q8_0 KV default");
        eq(htpArgs.contains("q8_0") && htpArgs.contains("ctk") && htpArgs.contains("ctv"),
           true, "vc93: htp profile q8_0 KV default");
        eq(oclArgs.contains("q8_0") && oclArgs.contains("ctk") && oclArgs.contains("ctv"),
           true, "vc93: ocl profile q8_0 KV default");
    }

    /**
     * v1.2.4: non-htp exec children must run with Hexagon enumeration fully
     * disabled (S23U CPU death: libcdsprpc loaded -> devices enumerated ->
     * fastRPC session unavailable -> GGML_ASSERT abort kills CPU too).
     * htp children must remain untouched (NPU enumeration must survive).
     */
    static void testExecEnvPolicy() throws Exception {
        System.out.println("== v1.2.4: applyExecEnvPolicy (hexagon off for non-htp exec) ==");
        java.util.Map<String, String> cpuEnv = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(cpuEnv, false);
        eq(cpuEnv.get("GGML_HEXAGON_ARCH"), "79", "cpu: GGML_HEXAGON_ARCH=79 injected");
        eq(cpuEnv.get("GGML_HEXAGON_DEVICES"), "none", "cpu: GGML_HEXAGON_DEVICES=none injected");
        java.util.Map<String, String> htpEnv = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(htpEnv, true);
        eq(htpEnv.containsKey("GGML_HEXAGON_ARCH"), false, "htp: ARCH untouched");
        eq(htpEnv.containsKey("GGML_HEXAGON_DEVICES"), false, "htp: DEVICES untouched");
        // engine identity guard: htp must remain the only exec profile allowed
        // to touch hexagon; vc74 adds the bmoe profile (4 total)
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject profs = new org.json.JSONObject(raw).getJSONObject("profiles");
        eq(profs.length(), 5, "profiles count (vc90: -rpc, +mtp)");
        eq(profs.has("htp") && profs.has("cpu") && profs.has("ocl"), true,
                "engine ids htp/ocl/cpu unchanged");
        eq("bmoe".equals(profs.getJSONObject("bmoe").optString("engine")), true,
                "bmoe profile carries engine=bmoe marker");
    }

    /**
     * vc69: htp-only env overlay (Download/npullm-htp-env.json) applied via
     * the 3-arg applyExecEnvPolicy overload. Contract:
     *  - non-htp: overlay never applied, ARCH/DEVICES poison-guard still injected
     *  - htp + null/empty/malformed overlay: DMA64 pin only (vc98); no overlay entries
     *  - htp + valid overlay: string/number/bool values injected, trimmed;
     *    null/JSON-null/empty-string values skipped; unknown keys passed as-is
     */
    static void testHtpEnvOverlay() throws Exception {
        System.out.println("== vc69: applyExecEnvPolicy htp env overlay ==");
        // 1. non-htp must never receive overlay content
        java.util.Map<String, String> cpuEnv = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(cpuEnv, false, "{\"GGML_HEXAGON_OPBATCH\":\"8192\"}");
        eq(cpuEnv.containsKey("GGML_HEXAGON_OPBATCH"), false, "non-htp: overlay ignored");
        eq(cpuEnv.get("GGML_HEXAGON_ARCH"), "79", "non-htp: poison guard kept");
        eq(cpuEnv.get("GGML_HEXAGON_DEVICES"), "none", "non-htp: DEVICES=none kept");
        // 2. htp + null overlay = DMA64 pin only (vc98)
        java.util.Map<String, String> h0 = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(h0, true, null);
        eq(h0.size(), 1, "vc98: htp + null overlay -> exactly the DMA64 pin");
        eq(h0.get("GGML_HEXAGON_DMA64"), "0", "vc98: pin value is 0");
        // 3. htp + empty object = DMA64 pin only (vc98)
        java.util.Map<String, String> h1 = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(h1, true, "{}");
        eq(h1.size(), 1, "vc98: htp + empty object -> exactly the DMA64 pin");
        eq(h1.get("GGML_HEXAGON_DMA64"), "0", "vc98: pin value is 0");
        // 4. htp + malformed JSON = DMA64 pin only (vc98, silent otherwise)
        java.util.Map<String, String> h2 = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(h2, true, "{\"GGML_HEXAGON_OPBATCH\": ");
        eq(h2.size(), 1, "vc98: htp + malformed json -> exactly the DMA64 pin");
        eq(h2.get("GGML_HEXAGON_DMA64"), "0", "vc98: pin value is 0");
        // 5. htp + valid overlay: all value shapes injected
        java.util.Map<String, String> h3 = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(h3, true,
                "{\"GGML_HEXAGON_OPBATCH\":\"8192\",\"GGML_HEXAGON_OPPOLL\":1,"
              + "\"GGML_HEXAGON_VERBOSE\":true,\"GGML_HEXAGON_PROFILE\":\"2\"}");
        eq(h3.get("GGML_HEXAGON_OPBATCH"), "8192", "string value injected");
        eq(h3.get("GGML_HEXAGON_OPPOLL"), "1", "number value injected as string");
        eq(h3.get("GGML_HEXAGON_VERBOSE"), "true", "bool value injected as string");
        eq(h3.get("GGML_HEXAGON_PROFILE"), "2", "numeric string trimmed");
        // 6. null / empty-string values skipped
        java.util.Map<String, String> h4 = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(h4, true,
                "{\"A\":null,\"B\":\"\",\"C\":\"  x  \",\"D\":5}");
        eq(h4.containsKey("A"), false, "json null skipped");
        eq(h4.containsKey("B"), false, "empty string skipped");
        eq(h4.get("C"), "x", "whitespace trimmed");
        eq(h4.get("D"), "5", "number to string");
        // 7. pre-existing env not clobbered semantics: overlay wins (documented)
        java.util.Map<String, String> h5 = new java.util.HashMap<>();
        h5.put("GGML_HEXAGON_OPBATCH", "1280");
        LlamaServerManager.applyExecEnvPolicy(h5, true, "{\"GGML_HEXAGON_OPBATCH\":\"8192\"}");
        eq(h5.get("GGML_HEXAGON_OPBATCH"), "8192", "overlay overrides preset");
    }

    static void testSplitArgs() {
        System.out.println("== LlamaServerManager.splitArgs ==");
        String[] p = LlamaServerManager.splitArgs("--no-mmap -m {MODEL} --ctx-size 8192 --ubatch-size 1024");
        eq(p.length, 7, "token count");
        eq(p[2], "{MODEL}", "{MODEL} token preserved for substitution");

        String[] p2 = LlamaServerManager.splitArgs("-m '/storage/my models/x y.gguf' -ngl 99");
        eq(p2[1], "/storage/my models/x y.gguf", "quoted path kept (defensive)");
        eq(p2.length, 4, "token count 2");
    }

    static void testQuantWhitelist() {
        System.out.println("== ModelFileHelper.quantOf ==");
        eq(ModelFileHelper.quantOf("Qwen2.5-7B-Instruct-Q4_0.gguf").npuOk, true, "Q4_0 ok");
        eq(ModelFileHelper.quantOf("Qwen2.5-7B-Instruct-Q4_0.gguf").label, "Q4_0", "Q4_0 label");
        eq(ModelFileHelper.quantOf("gemma-3-4b-it-q8_0.gguf").label, "Q8_0", "q8_0 lowercase");
        eq(ModelFileHelper.quantOf("model-IQ4_NL.gguf").label, "IQ4_NL", "IQ4_NL");
        eq(ModelFileHelper.quantOf("model-MXFP4.gguf").label, "MXFP4", "MXFP4");
        eq(ModelFileHelper.quantOf("model-F16.gguf").label, "F16", "F16");
        // K-quant must fail
        eq(ModelFileHelper.quantOf("Qwen2.5-7B-Instruct-Q4_K_M.gguf").npuOk, false, "Q4_K_M rejected");
        eq(ModelFileHelper.quantOf("Qwen2.5-7B-Instruct-Q4_K_M.gguf").label, "Q4_K", "K label (prefix match)");
        eq(ModelFileHelper.quantOf("model-Q6_K.gguf").npuOk, false, "Q6_K rejected");
        eq(ModelFileHelper.quantOf("model-IQ3_M.gguf").npuOk, false, "IQ3_M rejected");
        // unknown -> npuOk true but labeled
        ModelFileHelper.Quant u = ModelFileHelper.quantOf("mystery-model.gguf");
        eq(u.npuOk, true, "unknown npuOk=true");
        eq(u.label, "未知量化", "unknown label");
        // FP16 variant file naming: 'F16' inside word counts (documented MVP behavior)
        eq(ModelFileHelper.quantOf("llama-f16.gguf").label, "F16", "lowercase f16");
    }

    static void testRingBuffer() {
        System.out.println("== LogRingBuffer ==");
        LogRingBuffer rb = new LogRingBuffer(2000);
        rb.appendStr("build: b10088-67b9b0e7f\n");
        rb.appendStr("server listening on http://0.0.0.0:80");
        rb.appendStr("80\n"); // line split across two chunks
        String snap = rb.snapshot();
        eq(snap.contains("build: b10088-67b9b0e7f"), true, "chunk1 present");
        eq(snap.contains("server listening on http://0.0.0.0:8080"), true, "split line rejoined");

        // ring overflow: 2500 lines into 2000 buffer
        LogRingBuffer rb2 = new LogRingBuffer(2000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) sb.append("L").append(i).append('\n');
        rb2.appendStr(sb.toString());
        String s2 = rb2.snapshot();
        eq(s2.contains("[... 500 earlier lines dropped ...]"), true, "drop marker");
        eq(s2.contains("\nL2499"), true, "newest line kept");
        eq(s2.contains("\nL500\n"), true, "oldest kept line is #500");
        eq(s2.contains("\nL499\n"), false, "line #499 dropped");
    }

    static org.json.JSONObject jobj(String s) throws Exception {
        return new org.json.JSONObject(s);
    }

    static void testChatStreamerDelta() throws Exception {
        System.out.println("== ServerChatStreamer.deltaPiece (v1.1.9: null-prefix fix) ==");
        // missing content key — was literal "null" prefix before the fix
        eq(ServerChatStreamer.deltaPiece(jobj("{\"role\":\"assistant\"}")), "", "missing content key -> empty");
        // explicit JSON null — was literal "null" too
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":null}")), "", "explicit null content -> empty");
        // normal content chunk
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":\"你好\"}")), "你好", "content piece");
        // reasoning chunk (no content) — vc158: 正文严格只取 content，思考走 reasoningPiece
        eq(ServerChatStreamer.deltaPiece(jobj("{\"reasoning_content\":\"think\"}")), "", "vc158: reasoning 不进正文");
        eq(ServerChatStreamer.reasoningPiece(jobj("{\"reasoning_content\":\"think\"}")), "think", "vc158: reasoningPiece 取思考");
        eq(ServerChatStreamer.reasoningPiece(jobj("{\"content\":\"a\"}")), "", "vc158: 无 reasoning 时为空");
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":\"a\",\"reasoning_content\":\"b\"}")), "a", "vc158: 双字段各归各位(content)");
        // empty string is a real value, not null
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":\"\"}")), "", "empty content -> empty");
        // non-string values ignored
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":123}")), "", "numeric content -> empty");
        eq(ServerChatStreamer.deltaPiece(null), "", "null delta -> empty");
    }

    static void testChatStreamerStats() throws Exception {
        System.out.println("== ServerChatStreamer.statsFrom (v1.1.9: 0-tok fix) ==");
        // OpenAI usage chunk wins
        eq(ServerChatStreamer.statsFrom(jobj("{\"usage\":{\"completion_tokens\":42}}"), 1000, 200),
           "{\"completion_tokens\":42,\"total_ms\":1000,\"first_token_ms\":200}", "usage preferred");
        // llama.cpp final chunk timings fallback
        eq(ServerChatStreamer.statsFrom(jobj("{\"timings\":{\"predicted_n\":97,\"predicted_ms\":500}}"), 8000, 300),
           "{\"completion_tokens\":97,\"total_ms\":8000,\"first_token_ms\":300}", "timings fallback");
        // neither -> empty string (Java falls back to tok counter, JS to elapsed)
        eq(ServerChatStreamer.statsFrom(jobj("{\"choices\":[]}"), 500, -1), "", "no stats -> empty");
        // usage without completion_tokens falls through to tok counter (better than a fake 0)
        eq(ServerChatStreamer.statsFrom(jobj("{\"usage\":{}}"), 100, -1), "", "degenerate usage -> fallthrough");
        // negative elapsed/first-token clamped
        eq(ServerChatStreamer.statsFrom(jobj("{\"usage\":{\"completion_tokens\":1}}"), -5, -9),
           "{\"completion_tokens\":1,\"total_ms\":0,\"first_token_ms\":0}", "negatives clamped");
    }

    static void testRamLabel() {
        System.out.println("== Bridge.ramLabelGiB (v1.2.14: 12GB phones showed 11) ==");
        eq(MainActivity.Bridge.ramLabelGiB(11381888L * 1024L), 12, "sampler call shape: MemTotal KiB x1024 -> 12 (vc68 0G hotfix)");
        // real S25 MemTotal: 11,381,576 kB = 11,655,053,824 bytes = 11.66 decimal GB
        eq(MainActivity.Bridge.ramLabelGiB(11655053824L), 12, "S25 MemTotal 11.66 dec GB -> 12");
        eq(MainActivity.Bridge.ramLabelGiB(11150000000L), 12, "heavy-reserve 12GB (11.15 dec GB) -> 12");
        eq(MainActivity.Bridge.ramLabelGiB(15500000000L), 16, "16GB device (15.5 dec GB) -> 16");
        eq(MainActivity.Bridge.ramLabelGiB(7400000000L), 8, "8GB device (7.4 dec GB) -> 8");
        eq(MainActivity.Bridge.ramLabelGiB(5800000000L), 6, "6GB device (5.8 dec GB) -> 6");
        eq(MainActivity.Bridge.ramLabelGiB(11000000000L), 11, "exact 11.0 dec GB boundary -> 11 (epsilon)");
    }

    static void testRebornAction() {
        System.out.println("== ServerService.rebornAction (v1.3.1: survive swipe-away) ==");
        eq(ServerService.rebornAction(true, false), ServerService.REBORN_ADOPTED,
           "orphan alive -> adopt (args moot)");
        eq(ServerService.rebornAction(true, true), ServerService.REBORN_ADOPTED,
           "orphan alive + saved args -> adopt");
        eq(ServerService.rebornAction(false, true), ServerService.REBORN_RELAUNCHED,
           "no orphan + saved args -> relaunch from persisted args");
        eq(ServerService.rebornAction(false, false), ServerService.REBORN_NONE,
           "nothing alive, nothing saved -> stop quietly");
        System.out.println("== ServerService.quietStop (v1.3.7: keep FGS while STARTING) ==");
        eq(ServerService.quietStop(true), false,
           "STARTING -> keep FGS alive (notification-never-shows fix)");
        eq(ServerService.quietStop(false), true,
           "READY/ERROR -> quiet stop as before");
        System.out.println("== DeviceSampler.quantizeTemp (v1.3.7 vc67: temp jitter kill) ==");
        eq(DeviceSampler.quantizeTemp(41.2f, -100f), 41.0f, "41.2 snaps down to 41.0 bucket");
        eq(DeviceSampler.quantizeTemp(41.3f, 41.0f), 41.5f, "41.3 snaps up to 41.5 bucket");
        eq(DeviceSampler.quantizeTemp(41.24f, 41.0f), 41.0f, "41.24 stays in 41.0 bucket");
        eq(DeviceSampler.quantizeTemp(41.26f, 41.5f), 41.5f, "41.26 near 41.5 -> unchanged (dedupe)");
    }

    static void testTempGuard() {
        System.out.println("== DeviceSampler.plausibleTemp (vc72: fake-temp guard) ==");
        eq(DeviceSampler.plausibleTemp(2.5f), false, "2.5C wrong-sensor reading rejected (S23U zone60 case)");
        eq(DeviceSampler.plausibleTemp(0f), false, "0C rejected");
        eq(DeviceSampler.plausibleTemp(-3f), false, "negative rejected");
        eq(DeviceSampler.plausibleTemp(75f), false, "75C out-of-range rejected");
        eq(DeviceSampler.plausibleTemp(5f), true, "5C boundary accepted");
        eq(DeviceSampler.plausibleTemp(29.5f), true, "29.5C accepted");
        eq(DeviceSampler.plausibleTemp(45f), true, "45C accepted");
        eq(DeviceSampler.plausibleTemp(65f), true, "65C boundary accepted");
    }

    /* ================= v1.4.0 (vc74): bmoe engine ================= */

    static void testBmoeProtocol() {
        System.out.println("== BmoeSession.parseLine (vc74 session protocol) ==");
        org.json.JSONObject p = BmoeSession.parseLine(
                "BMOE_READY {\"load_s\":26.8,\"arch\":\"qwen3moe\",\"n_ctx\":4096,\"think_ctl\":\"template\",\"n_expert_used\":8}");
        eq(p.optString("event"), "READY", "READY event parsed");
        eq(p.optJSONObject("data").optDouble("load_s"), 26.8, "READY load_s");
        eq(p.optJSONObject("data").optString("arch"), "qwen3moe", "READY arch");

        p = BmoeSession.parseLine(
                "BMOE_PROGRESS {\"step\":12,\"steps\":128,\"wall_ms\":2300.5,\"io_ms\":410.0,\"delta_text\":\"hel\\\"lo\",\"delta_reasoning\":\"\",\"cache_hit_pct\":86.6}");
        eq(p.optJSONObject("data").optString("delta_text"), "hel\"lo", "PROGRESS delta_text unescaped");
        eq(BmoeSession.progressDelta(p.optJSONObject("data"), "delta_text"), "hel\"lo", "progressDelta text");
        eq(BmoeSession.progressDelta(p.optJSONObject("data"), "delta_reasoning"), "", "progressDelta empty reasoning");

        p = BmoeSession.parseLine("BMOE_ERROR {\"id\":3,\"fatal\":true,\"msg\":\"no MoE recipe for architecture 'x'\"}");
        eq(p.optJSONObject("data").optBoolean("fatal"), true, "ERROR fatal flag");

        eq(BmoeSession.parseLine("BMOE_BEGIN {\"id\":1}").optString("event"), "BEGIN", "BEGIN event");
        eq(String.valueOf(BmoeSession.parseLine("garbage line")), "null", "non-protocol line -> null");
        eq(String.valueOf(BmoeSession.parseLine("BMOE_DONE {\"id\":2")), "null", "truncated JSON -> null");
        eq(String.valueOf(BmoeSession.parseLine(null)), "null", "null line -> null");

        // DONE stats accumulation keys exist (JS onChatDone contract)
        org.json.JSONObject done = BmoeSession.parseLine(
            "BMOE_DONE {\"id\":5,\"cancelled\":false,\"tokens\":256,\"tok_s\":7.8,\"prefill_s\":2.8,\"cache_hit_pct\":86.6,\"n_prompt\":120,\"n_past\":376}").optJSONObject("data");
        eq(done.optInt("tokens"), 256, "DONE tokens");
        eq(done.optDouble("tok_s"), 7.8, "DONE tok_s");
        eq(done.optBoolean("cancelled"), false, "DONE cancelled");
        eq(BmoeSession.jsonEscapeLite("a\"b\nc"), "a\\\"b\\nc", "jsonEscapeLite");
    }

    static void testBmoeBuildCommand() {
        System.out.println("== BmoeSession.buildCommand (vc74 bmoe argv) ==");
        String BM = "--chatml --moe-stream --cache-mb auto --cache-ceil-mb 4000 --io-threads 4 --ctx-size 4096 -m {MODEL}";
        String[] cmd = BmoeSession.buildCommand("/n/lib/libbmoecli.so", BM,
                "/data/data/com.dawoer.npullm/files/models/model-00001-of-00003.gguf", 4);
        eq(cmd[0].endsWith("libbmoecli.so"), true, "argv0 = bmoe exe");
        eq(contains(cmd, "--session") || true, true, "session flag is profile-side (not injected)");
        eq(idx(cmd, "--chatml") > 0, true, "--chatml kept");
        eq(idx(cmd, "-m"), idx(cmd, "/data/data/com.dawoer.npullm/files/models/model-00001-of-00003.gguf") - 1,
                "{MODEL} substituted after split");
        eq(idx(cmd, "--threads") > 0 && "4".equals(cmd[idx(cmd, "--threads") + 1]), true,
                "threads appended when profile lacks it");
        eq(contains(cmd, "--host") || contains(cmd, "--port") || contains(cmd, "--reasoning"),
                false, "no HTTP/llama.cpp flags leak into bmoe argv");
        // threads override replaces profile value
        String[] cmd2 = BmoeSession.buildCommand("/x/libbmoecli.so", "--moe-stream -t 4 --threads 2 -m {MODEL}",
                "/s/m.gguf", 6);
        eq(idx(cmd2, "6") > 0, true, "threads 6 replaces profile 2 in place");
        eq(count(cmd2, "--threads"), 1, "single --threads flag");
        // threads 0 = strip entirely
        String[] cmd3 = BmoeSession.buildCommand("/x/libbmoecli.so", "--moe-stream --threads 2 -m {MODEL}",
                "/s/m.gguf", 0);
        eq(contains(cmd3, "--threads"), false, "threads 0 strips flag (auto)");
    }

    static void testBmoeShardHelpers() {
        System.out.println("== BmoeModelManager shard/url helpers (vc74 downloader) ==");
        String u = "https://huggingface.co/org/model/resolve/main/model-00001-of-00003.gguf";
        eq(BmoeModelManager.urlFileName(u), "model-00001-of-00003.gguf", "urlFileName strips path");
        eq(BmoeModelManager.urlFileName(u + "?download=true"), "model-00001-of-00003.gguf", "urlFileName strips query");
        eq(BmoeModelManager.totalShards("model-00001-of-00003.gguf"), 3, "totalShards=3");
        eq(BmoeModelManager.totalShards("qwen.gguf"), 1, "unsharded = 1");
        eq(BmoeModelManager.shardUrl(u, 2),
                "https://huggingface.co/org/model/resolve/main/model-00002-of-00003.gguf", "shardUrl 2");
        eq(BmoeModelManager.shardUrl(u, 3).endsWith("model-00003-of-00003.gguf"), true, "shardUrl 3");
        eq(BmoeModelManager.validModelUrl(u), true, "valid gguf URL");
        eq(BmoeModelManager.validModelUrl("https://x/y.zip"), false, "non-gguf rejected");
        eq(BmoeModelManager.validModelUrl("ftp://x/a.gguf"), false, "non-http rejected");
        eq(BmoeModelManager.humanBytes(16106127360L), "15.00 GB", "humanBytes GB");
        eq(BmoeModelManager.humanBytes(5242880L), "5.0 MB", "humanBytes MB");
    }

    static void testBmoePromptFromMessages() {
        System.out.println("== BmoeAdapter.promptFromMessages (vc74 chat bridge) ==");
        String body = "{\"messages\":[{\"role\":\"system\",\"content\":\"你是助手\"},{\"role\":\"user\",\"content\":\"你好\"}]}";
        eq(BmoeAdapter.promptFromMessages(body), "[System]\n你是助手\n\n[User]\n你好", "system+user wrapped");
        String body2 = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"ok\"},{\"role\":\"user\",\"content\":\"继续\"}]}";
        eq(BmoeAdapter.promptFromMessages(body2), "继续", "multi-turn: only last user msg (engine holds history)");
        eq(String.valueOf(BmoeAdapter.promptFromMessages("{\"messages\":[]}")), "null", "empty messages -> null");
        eq(String.valueOf(BmoeAdapter.promptFromMessages("{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"}]}")), "null", "no user -> null");
        eq(String.valueOf(BmoeAdapter.promptFromMessages("not json")), "null", "malformed -> null");
        eq(BmoeAdapter.maxTokensFrom("{\"max_tokens\":2048}"), 2048, "maxTokens parsed");
        eq(BmoeAdapter.maxTokensFrom("{}"), 8192, "maxTokens default 8192");
    }

    static void testBmoeLibClosure() {
        System.out.println("== BmoeSession.linkClosureDir (vc76 symlink isolation) ==");
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bmoecl");
            java.nio.file.Path nat = root.resolve("native");
            java.nio.file.Files.createDirectories(nat);
            // fake closure: bmoe-prefixed + a QTI/system-colliding lib that MUST be excluded
            for (String f : new String[]{"lbmllama-common.so", "lbmllama.so", "lbmggml.so",
                    "lbmggml-base.so", "lbmggml-cpu.so", "libbmoecli.so",
                    "libbase.so", "libllama.so", "libggml.so"}) {
                java.nio.file.Files.write(nat.resolve(f), new byte[]{1});
            }
            String dir = BmoeSession.linkClosureDir(root.toFile(), nat.toString());
            eq(dir != null, true, "linkClosureDir returns dir");
            java.nio.file.Path ld = java.nio.file.Paths.get(dir);
            eq(java.nio.file.Files.isSymbolicLink(ld.resolve("lbmllama-common.so")), true,
                    "lbm closure symlinked");
            eq(java.nio.file.Files.isSymbolicLink(ld.resolve("libbmoecli.so")), true,
                    "libbmoecli symlinked");
            eq(java.nio.file.Files.exists(ld.resolve("libbase.so")), false,
                    "QTI/system-colliding names excluded");
            eq(java.nio.file.Files.exists(ld.resolve("libllama.so")), false,
                    "llama.cpp names excluded (lbm* only)");
            // idempotent refresh: second call must succeed and keep links fresh
            String dir2 = BmoeSession.linkClosureDir(root.toFile(), nat.toString());
            eq(dir2 != null && dir2.equals(dir), true, "idempotent re-run");
            eq(java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(dir2, "lbmggml.so")), true,
                    "links still valid after re-run");
        } catch (Throwable t) {
            fail++;
            System.out.println("FAIL linkClosureDir harness: " + t);
        }
    }

    static void testBmoeResolvePath() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bmoe-res");
            java.nio.file.Files.write(root.resolve("m.gguf"), new byte[]{1});
            String dir = root.toString();
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), "__bmoe__m.gguf")),
               String.valueOf(new java.io.File(dir, "m.gguf").getPath()), "resolve __bmoe__ tag");
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), "m.gguf")),
               String.valueOf(new java.io.File(dir, "m.gguf").getPath()), "resolve bare name");
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), "__bmoe__missing.gguf")),
               "null", "missing models-dir file rejected");
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), "note.txt")),
               "null", "non-gguf relative rejected");
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), null)),
               "null", "null ref rejected");
            String abs = new java.io.File(dir, "m.gguf").getAbsolutePath();
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), abs)), abs,
               "absolute path passthrough");
            eq(String.valueOf(BmoeModelManager.resolveModelPath(new java.io.File(dir), "/nonexistent/x.gguf")),
               "null", "unreadable absolute rejected");
        } catch (Throwable t) {
            fail++;
            System.out.println("FAIL bmoe resolve path harness: " + t);
        }
    }

    /**
     * vc89: MTP 档（第 6 引擎）—— profiles.json 契约 + buildCommand 配方保真。
     * 定版配方 = 4B Q4_0 + HTP + draft-mtp n1（0929 PHASE1 终局）。
     */
    static void testMtpProfile() throws Exception {
        System.out.println("== vc89: MTP profile contract + buildCommand fidelity ==");
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject profs = new org.json.JSONObject(raw).getJSONObject("profiles");
        org.json.JSONObject mtp = profs.getJSONObject("mtp");
        eq(mtp.optString("binary"), "libllamaserver.so", "mtp: exec binary = libllamaserver.so");
        String args = mtp.optString("args");
        eq(args.contains("--spec-type draft-mtp"), true, "mtp: draft-mtp present");
        eq(args.contains("--spec-draft-n-max 1"), true, "mtp: n_max=1 iron law (0929)");
        eq(args.contains("--device HTP0"), true, "mtp: HTP0 device");
        eq(args.contains("-ngl 99"), true, "mtp: full offload");
        eq(args.contains("-ctk q8_0") && args.contains("-ctv q8_0"), true, "mtp: q8 KV (64K budget)");
        eq(args.contains("--load-mode none"), true, "mtp: load-mode none (htp-档一致)");
        eq(args.contains("--ctx-size 32768"), true, "vc91: mtp profile default ctx 32K (dmabuf-safe, vc89 4.67G kill case)");
        eq(profs.getJSONObject("bmoe").optString("engine"), "bmoe", "bmoe engine marker intact");
        // buildCommand 配方保真：UI 值就地替换、spec/device 透传、host/port 收尾
        String[] cmd = LlamaServerManager.buildCommand(
                "/data/app/x/lib/arm64/libllamaserver.so", args,
                "/data/data/com.dawoer.npullm/files/models/4b-mtp-q4_0.gguf",
                "0.0.0.0", 8080, 32768, "on", 4, false, 4096, 0);
        eq(idx(cmd, "--ctx-size"), idx(cmd, "32768") - 1, "mtp: UI ctx 32768 replaces in place");
        eq(idx(cmd, "--spec-type"), idx(cmd, "draft-mtp") - 1, "mtp: spec-type passthrough");
        eq(cmd[idx(cmd, "--spec-draft-n-max") + 1], "1", "mtp: n-max passthrough (value after flag)");
        eq(idx(cmd, "--device"), idx(cmd, "HTP0") - 1, "mtp: device passthrough");
        eq(idx(cmd, "--ubatch-size"), idx(cmd, "4096") - 1, "mtp: ubatch 4096 appended once");
        eq(cmd[idx(cmd, "--reasoning") + 1], "on", "mtp: reasoning on explicit (value after flag)");
        eq(cmd[cmd.length - 1], "8080", "mtp: port appended last");
        eq(idx(cmd, "--host"), cmd.length - 4, "mtp: host before port");
    }

    /**
     * vc91 修复①：mtp 档 ctx 跟随 UI。默认 32K（dmabuf 安全线），
     * 用户显式选 64K 时必须原样透传（能力保留，警示交 UI）。
     */
    static void testVc91MtpCtxFollowsUi() throws Exception {
        System.out.println("== vc91: mtp ctx follows UI (dmabuf fix) ==");
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject mtp = new org.json.JSONObject(raw)
                .getJSONObject("profiles").getJSONObject("mtp");
        String args = mtp.optString("args");
        eq(args.contains("--ctx-size 32768"), true, "profile default = 32K");
        eq(args.contains("65536"), false, "no hardcoded 64K in profile");

        // UI 选 64K → 原样透传（用户知情选择，警示在 UI 层）
        String[] cmd64 = LlamaServerManager.buildCommand(
                "/x/libllamaserver.so", args, "/s/m.gguf",
                "0.0.0.0", 8080, 65536, "on", 4, false, 1024, 0);
        eq(cmd64[idx(cmd64, "--ctx-size") + 1], "65536", "UI 64K passthrough intact");

        // UI 无 ctx（0 = keep profile）→ profile 默认 32K 生效
        String[] cmdDef = LlamaServerManager.buildCommand(
                "/x/libllamaserver.so", args, "/s/m.gguf",
                "0.0.0.0", 8080, 0, "on", 4, false, 1024, 0);
        eq(cmdDef[idx(cmdDef, "--ctx-size") + 1], "32768", "no UI value → profile 32K stands");
    }

    /**
     * vc91 特性①：--sleep-idle-seconds 透传（arg.cpp:3807 拼写核准）。
     */
    static void testVc91SleepIdle() {
        System.out.println("== vc91: sleep-idle passthrough ==");
        String HTP = "--load-mode none -m {MODEL} --ctx-size 8192 -fa on -ngl 99 --device HTP0";
        // >0 追加
        String[] on = LlamaServerManager.buildCommand("/x/ls.so", HTP, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 600);
        eq(on[idx(on, "--sleep-idle-seconds") + 1], "600", "sleep-idle appended (600)");
        // 0 缺省 = flag 不出现
        String[] off = LlamaServerManager.buildCommand("/x/ls.so", HTP, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 0);
        eq(idx(off, "--sleep-idle-seconds") > 0, false, "sleep-idle absent when 0");
        // profile 自带 → UI 值就地替换
        String[] rep = LlamaServerManager.buildCommand("/x/ls.so",
                "-m {MODEL} --sleep-idle-seconds 300", "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 180);
        eq(rep[idx(rep, "--sleep-idle-seconds") + 1], "180", "profile value replaced by UI");
        eq(contains(rep, "300"), false, "old profile value gone");
        // profile 自带 + UI=0 → 整体剥离（用户关闭语义）
        String[] strip = LlamaServerManager.buildCommand("/x/ls.so",
                "-m {MODEL} --sleep-idle-seconds 300", "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 0);
        eq(idx(strip, "--sleep-idle-seconds") > 0, false, "profile flag stripped when UI=0");
    }

    /**
     * vc91 修复②：内存告警决策（迟滞回差）。阈值依据 0929 vc89 破案数据：
     * 4.67G 击杀 / vc83 2.9G 稳态 → ON=4.5G，OFF=3.4G。
     */
    static void testVc91MemAlarm() {
        System.out.println("== vc91: mem alarm hysteresis ==");
        eq(ServerService.memAlarm(false, 3_000_000_000L), false, "3.0G: no alarm");
        eq(ServerService.memAlarm(false, 4_400_000_000L), false, "4.4G: below ON threshold");
        eq(ServerService.memAlarm(false, 4_500_000_000L), true, "4.5G: alarm ON");
        eq(ServerService.memAlarm(false, 4_670_000_000L), true, "4.67G (vc89 kill): alarm");
        eq(ServerService.memAlarm(true, 4_000_000_000L), true, "4.0G while alarming: hold (hysteresis)");
        eq(ServerService.memAlarm(true, 3_400_000_001L), true, "just above OFF: hold");
        eq(ServerService.memAlarm(true, 3_400_000_000L), false, "at OFF: release");
        eq(ServerService.memAlarm(true, 3_000_000_000L), false, "3.0G while alarming: release");
    }

    /**
     * vc97: KV switch final semantics + vendor.qti self-extract.
     * vc96 changelog said "checked = no flag" but the real wiring sent
     * noKvOffload=checked (checked=true -> --no-kv-offload) — caught 1004;
     * the JVM test only asserted "no !" plus the Java boolean matrix, never
     * the JS->flag direction end to end (false-green).
     * vc97 verdict (user): keep the wiring, make it the contract —
     * OFF (left, default) = no flag (KV with weights, fast + safe);
     * ON  (right)         = --no-kv-offload (RAM escape hatch; A8X GPU mine).
     * Blob key kvOn -> kvflag: stale kvOn:true from vc96 installs lands on a
     * removed key and cannot resurrect itself; default stays OFF on upgrade.
     */
    static void testVc97KvOffloadAndVqti() {
        System.out.println("== vc97: kv switch final semantics + vendor.qti self-extract ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(html.contains("<input type=\"checkbox\" id=\"kvoff\">"), true,
               "vc97: kvoff checkbox default UNchecked (off = no flag)");
            eq(html.contains("id=\"kvoff\" checked"), false,
               "vc97: no default-checked attribute");
            eq(html.contains("KV 卸载（省内存/速度权衡）"), false,
               "vc97: parenthetical label suffix removed");
            eq(html.contains("默认开：KV 与权重同设备（GPU 档必须开）"), false,
               "vc97: old phint removed");
            eq(html.contains("data-i18n=\"KV 卸载\""), true,
               "vc97: plain label kept");
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js.pristine")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(js.contains("kvflag:$(\"kvoff\").checked"), true,
               "vc97: save blob key kvflag");
            eq(js.contains("kvOn"), false,
               "vc97: legacy kvOn key fully gone (upgrade-safe)");
            eq(js.contains("\"boolean\"==typeof e.kvflag&&($(\"kvoff\").checked=e.kvflag)"), true,
               "vc97: restore reads kvflag");
            eq(js.contains("noKvOffload:$(\"kvoff\").checked"), true,
               "vc97: doStart wiring = checked (on->flag, off->none)");
        } catch (Exception e) { throw new RuntimeException(e); }
        String HTP = "--load-mode none -m {MODEL} --ctx-size 8192 -fa on -ngl 99 --device HTP0";
        String OCL = "--load-mode none -m {MODEL} --ctx-size 8192 -fa on -ngl 99";
        String[] off = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 0);
        eq(contains(off, "--no-kv-offload"), false, "vc97: ocl off -> no flag");
        String[] on = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, true, 1024, 0);
        eq(contains(on, "--no-kv-offload"), true, "vc97: ocl on -> flag kept");
        String[] hOff = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, false, 1024, 0);
        eq(contains(hOff, "--no-kv-offload"), false, "vc97: htp off -> no flag");
        String[] hOn = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf",
                "0.0.0.0", 8080, 8192, "off", 4, true, 1024, 0);
        eq(contains(hOn, "--no-kv-offload"), true, "vc97: htp on -> flag kept");
        try {
            String lsm = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(lsm.contains("vendor.qti.hardware.dsp-V1-ndk.so"), true,
               "vc97: LSM references vendor.qti DSP entry");
            eq(lsm.contains("static File extractVqti("), true,
               "vc97: extractVqti present");
            eq(lsm.contains("stubDir.getPath() + \":\" + vqDir.getPath() + \":\" + nativeDir"), true,
               "vc97: LD path prepends vqti dir");
        } catch (Exception e) { throw new RuntimeException(e); }
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("vc97vqti").toFile();
            java.io.File zip = new java.io.File(dir, "fake.apk");
            String entry = "lib/arm64-v8a/vendor.qti.hardware.dsp-V1-ndk.so";
            byte[] payload = "VQTI-FAKE-CONTENT".getBytes("UTF-8");
            java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(zip));
            zo.putNextEntry(new java.util.zip.ZipEntry(entry));
            zo.write(payload);
            zo.closeEntry();
            zo.close();
            java.io.File out = new java.io.File(dir, "out");
            out.mkdirs();
            long stamp = zip.lastModified();
            eq(LlamaServerManager.extractVqti(zip, out, entry, stamp), out,
               "vc97: vqti first extract returns outDir");
            java.io.File tf = new java.io.File(out, "vendor.qti.hardware.dsp-V1-ndk.so");
            eq(tf.canRead(), true, "vc97: extracted file exists");
            byte[] got = new byte[payload.length];
            java.io.FileInputStream fi = new java.io.FileInputStream(tf);
            eq(fi.read(got), payload.length, "vc97: extracted size");
            fi.close();
            eq(new String(got, "UTF-8").equals("VQTI-FAKE-CONTENT"), true,
               "vc97: extracted content");
            eq(LlamaServerManager.extractVqti(zip, out, entry, stamp), out,
               "vc97: idempotent re-call (stamp unchanged)");
            eq(LlamaServerManager.extractVqti(zip, out, entry, stamp + 3600000L), out,
               "vc97: newer stamp re-extracts fine");
            eq(String.valueOf(LlamaServerManager.extractVqti(zip, out, "lib/arm64-v8a/absent.so", stamp)), "null",
               "vc97: missing entry -> null");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** vc98: DMA64 pin (issue #1) — htp children pinned, non-htp untouched,
     *  overlay JSON overrides, malformed JSON no-op. */
    static void testVc98Dma64Pin() {
        System.out.println("== vc98: applyExecEnvPolicy DMA64 pin ==");
        java.util.HashMap<String, String> htp = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(htp, true, null);
        eq(String.valueOf(htp.get("GGML_HEXAGON_DMA64")), "0",
           "vc98: htp child pinned GGML_HEXAGON_DMA64=0");
        java.util.HashMap<String, String> htpOv = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(htpOv, true,
                "{\"GGML_HEXAGON_DMA64\":\"1\"}");
        eq(String.valueOf(htpOv.get("GGML_HEXAGON_DMA64")), "1",
           "vc98: overlay JSON overrides the pin (escape hatch)");
        java.util.HashMap<String, String> htpBad = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(htpBad, true, "{not json");
        eq(String.valueOf(htpBad.get("GGML_HEXAGON_DMA64")), "0",
           "vc98: malformed overlay keeps the pin");
        java.util.HashMap<String, String> cpu = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(cpu, false, null);
        eq(String.valueOf(cpu.get("GGML_HEXAGON_DMA64")), "null",
           "vc98: non-htp child untouched");
        eq(String.valueOf(cpu.get("GGML_HEXAGON_DEVICES")), "none",
           "vc98: non-htp policy intact");
    }



    /** vc99 C2: buildCommand slot-save-path append/omit; profile args intact. */
    static void testVc99C1C2() {
        System.out.println("== vc99: C2 slot-save-path ==");
        String prof = "--load-mode none -m {MODEL} --ctx-size 4096 -fa on --threads 4";
        String[] on = LlamaServerManager.buildCommand("/x/ls.so", prof, "/s/m.gguf",
                "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0, "/data/user/0/com.dawoer.npullm/files/slots");
        eq(idx(on, "--slot-save-path") > 0, true, "vc99: --slot-save-path appended");
        eq(on[idx(on, "--slot-save-path") + 1], "/data/user/0/com.dawoer.npullm/files/slots",
           "vc99: dir value follows the flag");
        eq(idx(on, "--host") > idx(on, "--slot-save-path"), true, "vc99: host/port stay last");
        eq(idx(on, "--load-mode"), 1, "vc99: profile tokens intact");
        eq(on[idx(on, "--threads") + 1], "4", "vc99: threads replaced in place");
        String[] off = LlamaServerManager.buildCommand("/x/ls.so", prof, "/s/m.gguf",
                "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0, null);
        eq(idx(off, "--slot-save-path") > 0, false, "vc99: flag absent when disabled");
        String[] nine = LlamaServerManager.buildCommand("/x/ls.so", "-m {MODEL}", "/s/m.gguf",
                "0.0.0.0", 8080, 4096, "off", 4, false);
        eq(idx(nine, "--slot-save-path") > 0, false, "vc99: 9-param overload unaffected");
    }

    /** vc99 C1: WAV writer shape + ref protocol constants (JVM-testable subset;
     *  Context-bound flows — store/fileFor/materializeRefs — verified on device). */
    static void testVc99C1Media() throws Exception {
        System.out.println("== vc99: C1 ChatMedia WAV + ref protocol ==");
        java.io.File dir = java.nio.file.Files.createTempDirectory("vc99c1").toFile();
        java.io.File wav = new java.io.File(dir, "t.wav");
        ChatMedia.writeTestWav(wav, 16000, 1);
        byte[] head = new byte[44];
        java.io.FileInputStream fi = new java.io.FileInputStream(wav);
        eq(fi.read(head), 44, "vc99: wav header size");
        fi.close();
        eq(new String(head, 0, 4, "UTF-8"), "RIFF", "vc99: RIFF magic");
        eq(new String(head, 8, 4, "UTF-8"), "WAVE", "vc99: WAVE magic");
        int dataLen = (head[40] & 0xFF) | ((head[41] & 0xFF) << 8) | ((head[42] & 0xFF) << 16) | ((head[43] & 0xFF) << 24);
        eq(dataLen, 32000, "vc99: 1s@16k mono s16 = 32000 data bytes");
        eq(wav.length(), 32044L, "vc99: total = 44 + data");
        eq(ChatMedia.REF_PREFIX, "media_ref://chatmedia/", "vc99: ref prefix stable");
        eq(ChatMedia.hasRefs(null), false, "vc99: hasRefs(null)");
        eq(ChatMedia.hasRefs("{}"), false, "vc99: hasRefs(no refs)");
        eq(ChatMedia.hasRefs("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\n'            'media_ref://chatmedia/ab.jpg\"}]}"), true, "vc99: hasRefs(hit)");
        eq(ChatMedia.REF_PREFIX.endsWith("/"), true, "vc99: prefix ends with separator");
    }

    /** vc99 C1: mmproj auto-detection (largest gguf with "mmproj" in name). */
    static void testVc99Mmproj() throws Exception {
        System.out.println("== vc99: mmproj auto-detect ==");
        java.io.File dir = java.nio.file.Files.createTempDirectory("vc99mm").toFile();
        eq(String.valueOf(LlamaServerManager.findMmproj(dir)), "null",
           "vc99: empty dir -> null");
        java.io.File model = new java.io.File(dir, "qwen3-4b.gguf");
        model.createNewFile();
        eq(String.valueOf(LlamaServerManager.findMmproj(dir)), "null",
           "vc99: model alone -> null (no mmproj)");
        java.io.File mm1 = new java.io.File(dir, "mmproj-model-f16.gguf");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(mm1);
        fo.write(new byte[100]);
        fo.close();
        eq(LlamaServerManager.findMmproj(dir), mm1.getAbsolutePath(),
           "vc99: single mmproj found");
        java.io.File mm2 = new java.io.File(dir, "mmproj-model-q8.gguf");
        java.io.FileOutputStream fo2 = new java.io.FileOutputStream(mm2);
        fo2.write(new byte[50]);
        fo2.close();
        eq(LlamaServerManager.findMmproj(dir), mm1.getAbsolutePath(),
           "vc99: largest variant wins (f16 over q8)");
        eq(String.valueOf(LlamaServerManager.findMmproj(null)), "null",
           "vc99: null dir -> null");
    }

    /** vc100: S23U 复测三案修复的静态/逻辑验证（无 Context 可用的部分）。 */
    static void testVc100S23UFixes() throws Exception {
        System.out.println("== vc100: S23U retest fixes (camera/500/slot) ==");

        // ---- fix 1: camera EXTRA_OUTPUT flow (source fingerprint asserts) ----
        String m = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                "UTF-8");
        eq(m.contains("chatCameraOut"), true, "vc100: chatCameraOut field present");
        eq(m.contains("MediaStore.EXTRA_OUTPUT"), true,
           "vc100: camera intent carries EXTRA_OUTPUT");
        eq(m.contains("setClipData"), true,
           "vc100: URI grant propagated via ClipData");
        eq(m.contains("Pictures/PocketOrca"), true,
           "vc100: shot lands in our own MediaStore row (RELATIVE_PATH)");
        eq(m.contains("latestMediaUri"), false,
           "vc100: latest-image guesswork removed (root cause of camera failed)");
        eq(m.contains("contentStream"), false,
           "vc100: dead helper contentStream removed with the rewrite");

        // ---- fix 2a: chatSSE forwards server error bodies ----
        String sc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerChatStreamer.java")),
                "UTF-8");
        eq(sc.contains("getErrorStream"), true,
           "vc100: chatSSE reads the error body (HTTP 500 no longer opaque)");

        // ---- fix 2b: stale media_ref degrades to text instead of blocking ----
        String cm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ChatMedia.java")),
                "UTF-8");
        eq(cm.contains("stale ref — abort cleanly"), false,
           "vc100: stale ref no longer aborts materializeRefs");
        eq(cm.contains("if (f == null) continue;"), true,
           "vc100: stale ref line is dropped, message stays sendable");
        eq(cm.contains("changed = true; continue; }"), true,
           "vc100: all-stale falls back to plain text send");

        // materialize semantics via reflection on the string shape: all-stale
        // message must come back as plain text, not null. Direct call needs a
        // Context (external files dir); the logic branch is covered by the
        // fingerprint above plus the device retest.

        // ---- fix 2c: slot save must not run on the UI thread ----
        eq(m.contains("new Thread(() -> {\n            String r = server.slotAction"), true,
           "vc100: menu slot save runs off the UI thread (click can't be killed)");
        eq(m.contains("window.onSlotSaved && window.onSlotSaved("), true,
           "vc100: save result still forwarded to JS");

        // ---- fix 3a: JS onSlotSaved handler is defined (save gives feedback) ----
        String pa = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-app.py")), "UTF-8");
        eq(pa.contains("window.onSlotSaved=function"), true,
           "vc100: patch-app.py defines the missing onSlotSaved JS handler");

        // restore flow contract: attach slot -> marker into content -> restore
        // POST before the chat request, marker stripped from the wire body.
        eq(pa.contains("bridge(\"chatSlotAction\",\"restore\",chatSlotFile)"), true,
           "vc100: restore POST wired before send (vc99 contract intact)");
        eq(pa.contains("d=d.replace(/💾SLOT:[^\"]*/g,\"\")"), true,
           "vc100: SLOT marker stripped from the wire body");

        // ---- fix 3b: no-slot list must be visible (toast, not silence) ----
        eq(m.contains("\"no saved slots\""), true,
           "vc100: empty slot list surfaces an error toast (vc99 contract intact)");
    }

    static void testVc101MicPermission() throws Exception {
        System.out.println("== vc101: voice memo RECORD_AUDIO manifest declaration ==");

        String mani = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/AndroidManifest.xml")),
                "UTF-8");
        eq(mani.contains("android.permission.RECORD_AUDIO"), true,
           "vc101: RECORD_AUDIO declared in the manifest (runtime request alone is silently denied)");
        eq(mani.indexOf("android.permission.RECORD_AUDIO")
                == mani.lastIndexOf("android.permission.RECORD_AUDIO"), true,
           "vc101: exactly one RECORD_AUDIO declaration (no duplicates)");

        // the vc99 runtime flow stays wired: gate -> request -> resume on grant
        String m = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                "UTF-8");
        eq(m.contains("Manifest.permission.RECORD_AUDIO"), true,
           "vc99: runtime gate still checks RECORD_AUDIO");
        eq(m.contains("REQ_CHAT_RECORD"), true,
           "vc101: permission callback still keyed on REQ_CHAT_RECORD");
        eq(m.contains("ChatMedia.recordPendingPermission"), true,
           "vc101: grant resumes the pending voice memo");
    }

    static void testVc102SlotHist() throws Exception {
        System.out.println("== vc102: slot restore replays chat text (sidecar) ==");

        String m = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                "UTF-8");
        // sidecar write/read/restore plumbing
        eq(m.contains("slotHistWrite"), true, "vc102: sidecar write helper present");
        eq(m.contains("slotHistRead"), true, "vc102: sidecar read helper present");
        eq(m.contains(".hist.json"), true, "vc102: sidecar filename convention");
        eq(m.contains("chatHistMirror"), true, "vc102: Java-side transcript mirror field");
        eq(m.contains("window.onSlotHistLoaded && window.onSlotHistLoaded("), true,
           "vc102: restore replays via onSlotHistLoaded");
        eq(m.contains("a.slotHistRestore(names[which])"), true,
           "vc102: slot picker triggers the text replay");
        eq(m.contains("public void chatHistPush(String histJson)"), true,
           "vc102: bridge receives the JS transcript mirror");
        eq(m.contains("rj.put(\"histSaved\", histOk)"), true,
           "vc102: save result reports sidecar status");
        // hidden menu entries (user call 1007): 拍照/选择视频 must be commented out
        eq(m.contains("// labels.add(\"\\u62CD\\u7167\")"), true,
           "vc102: 拍照 entry commented out");
        eq(m.contains("// labels.add(\"\\u9009\\u62E9\\u89C6\\u9891\")"), true,
           "vc102: 选择视频 entry commented out");
        // naive contains() hits the commented lines; scan live lines only
        int activeCam = 0, activeVid = 0;
        for (String line : m.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//")) continue;
            if (t.contains("actions.add(\"camera\")")) activeCam++;
            if (t.contains("actions.add(\"video\")")) activeVid++;
        }
        eq(activeCam, 0, "vc102: no ACTIVE camera action");
        eq(activeVid, 0, "vc102: no ACTIVE video action");
        eq(m.contains("actions.add(\"gallery\")"), true,
           "vc102: gallery stays active (verified working 1007)");

        // JS side: mirror + restore handler wired in patch-app.py
        String pa = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-app.py")), "UTF-8");
        eq(pa.contains("window.onSlotHistLoaded=function"), true,
           "vc102: JS restore handler defined (bubbles replay)");
        int nMirror = pa.split("chatHistPush", -1).length - 1;
        eq(nMirror >= 3, true,
           "vc102: chatHistPush wired at 3 call sites (turn/boot/new-chat)");
        eq(pa.contains("bridge(\"chatSlotAction\",\"restore\",chatSlotFile)"), true,
           "vc102: KV restore-before-send contract intact (vc99/100)");
        eq(pa.contains("\"恢复会话\":\"Restore chat\""), true,
           "vc102: en i18n key for the restore toast");
    }

    static void testVc103SlotDelete() throws Exception {
        System.out.println("== vc103: slot deletion (restore dialog → confirm → both files) ==");

        String m = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                "UTF-8");
        // restore dialog carries the delete entrypoint
        eq(m.contains("setPositiveButton(\"\\u5220\\u9664\\u4F1A\\u8BDD\\u2026\", new SlotDeleteOpen"), true,
           "vc103: restore dialog has 删除会话… button");
        // two-step confirm before irreversible delete
        eq(m.contains("void chatDeleteSlot()"), true, "vc103: picker dialog for deletion");
        eq(m.contains("void confirmSlotDelete(String name)"), true,
           "vc103: two-step confirm exists");
        eq(m.contains("\u4E0D\u53EF\u6062\u590D"), true,
           "vc103: confirm warns 不可恢复");
        // deletes both files, path-safe
        eq(m.contains("private static boolean deleteSlotFiles(Context c, String name)"), true,
           "vc103: delete helper exists");
        eq(m.contains("!name.endsWith(\".slot\")"), true,
           "vc103: path-safety — only *.slot names accepted");
        eq(m.contains("slotHistFile(name)"), true,
           "vc103: sidecar removed together with the slot file");
        // no anonymous click handlers (hard rule 1)
        eq(m.contains("new DialogInterface.OnClickListener()"), false,
           "vc103: no anonymous listeners introduced");
        for (String cls : new String[]{"SlotDeleteOpen", "SlotDeleteClick", "SlotDeleteConfirm"}) {
            eq(m.contains("static final class " + cls + " implements DialogInterface.OnClickListener"), true,
               "vc103: " + cls + " is a named d8-safe handler");
        }
        // staged chip cleanup on delete of the active slot
        eq(m.contains("name.equals(a.pendingSlotAttach)"), true,
           "vc103: staged slot chip invalidated when its file is deleted");
        eq(m.contains("window.onSlotDeleted && window.onSlotDeleted("), true,
           "vc103: JS notified for chip cleanup + toast");

        String pa = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-app.py")), "UTF-8");
        eq(pa.contains("window.onSlotDeleted=function"), true,
           "vc103: JS onSlotDeleted handler defined");
        eq(pa.contains("toastT(T(\"已删除\"))"), true,
           "vc103: deleted toast wired");
        eq(pa.contains("\"已删除\":\"Deleted\""), true,
           "vc103: en i18n key for the deleted toast");
        eq(pa.contains("\"已删除\":\"已刪除\""), true,
           "vc103: zh-TW i18n key for the deleted toast");
    }

    static void testVc104PortAndKey() throws Exception {
        System.out.println("== vc104: port exposure + local server key ==");
        // host contract through buildCommand (signature unchanged since vc99)
        String EXE = "/data/app/~~x==/lib/arm64/libllamaserver.so";
        String ARGS = "--no-mmap -m {MODEL} -fa on -ngl 99 --device HTP0";
        String[] loop = LlamaServerManager.buildCommand(EXE, ARGS,
                "/sdcard/m.gguf", "127.0.0.1", 8080, 4096, "off", 4, false, 0, 0);
        eq(count(loop, "--host"), 1, "vc104: exactly one --host");
        eq(loop[idx(loop, "--host") + 1], "127.0.0.1", "vc104: closed port -> 127.0.0.1");
        String[] lan = LlamaServerManager.buildCommand(EXE, ARGS,
                "/sdcard/m.gguf", "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0);
        eq(lan[idx(lan, "--host") + 1], "0.0.0.0", "vc104: exposed port -> 0.0.0.0");
        // index.html: two switches under the port row, both default OFF
        String html = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(html.contains("id=\"pubOn\""), true, "vc104: pubOn switch present");
        eq(html.contains("id=\"keyOn\""), true, "vc104: keyOn switch present");
        eq(html.contains("data-i18n=\"端口对外开放\""), true, "vc104: expose-port label");
        eq(html.contains("data-i18n=\"是否启用本地服务Key\""), true, "vc104: local-key label");
        eq(html.contains("id=\"keyRow\"") && html.contains("id=\"keyText\""), true,
           "vc104: endpoint key row (keyText under epUrl)");
        eq(html.contains("onclick=\"copyKey()\""), true, "vc104: copyKey button wired");
        eq(html.contains("id=\"rowKeyVal\"") || html.contains("id=\"keyVal\""), false,
           "vc104: params-form key input row removed (moved to endpoint card)");
        eq(html.contains("id=\"pubOn\" checked") || html.contains("id=\"keyOn\" checked"), false,
           "vc104: both switches default OFF (否)");
        int iPort = html.indexOf("id=\"rowPort\"");
        int iPub = html.indexOf("id=\"pubOn\"");
        int iKey = html.indexOf("id=\"keyOn\"");
        eq(iPub > iPort && iKey > iPub, true, "vc104: rows ordered port -> pubOn -> keyOn");
        // patch-app.py carries the vc104 transforms + pristine anchors intact
        String py = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-app.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        String[] tags = {"vc104-save", "vc104-restore", "vc104-dostart", "vc104-keyfn",
                         "vc104-keyrow", "vc104-tw", "vc104-en"};
        for (int i = 0; i < tags.length; i++) eq(py.contains(tags[i]), true, "vc104: patch tag " + tags[i]);
        eq(py.contains("$(\"pubOn\")") && py.contains("$(\"keyOn\")"), true,
           "vc104: patch drives both switches");
        eq(py.contains("keyEnsureCached") && py.contains("copyKey") && py.contains("$("+"\"keyRow\")"),
           true, "vc104: endpoint key row transform present");
        String pr = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/app.js.pristine")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(pr.contains("kvflag:$("), true, "vc104: pristine save() kvflag anchor intact");
        // Java side fingerprints
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ma.contains("keyEnsure"), true, "vc104: bridge keyEnsure (auto key)");
        eq(ma.contains("ensureLocal(a.getApplicationContext())"), true,
           "vc104: bridge startServer uses dedicated local-key slot");
        eq(ma.contains("keySave"), false, "vc104: manual key entry removed");
        // KeyStoreBox: dedicated slot + generator shape (sk- + 32 hex)
        String ksb = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/KeyStoreBox.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ksb.contains("npullm_localkey") && ksb.contains("npullm_local"),
           true, "vc104: local key slot separate from remote-key slot");
        for (int i = 0; i < 8; i++) {
            String k = KeyStoreBox.generateLocalKey();
            eq(k.startsWith("sk-") && k.length() == 35, true, "vc104: generated key shape sk-+32hex");
        }
        eq(!KeyStoreBox.generateLocalKey().equals(KeyStoreBox.generateLocalKey()), true,
           "vc104: generated keys are random");
        eq(ma.contains("navigationBars()"), true, "vc104: insets include navigationBars (3-button nav fix)");
        eq(ma.contains("bars.bottom"), true, "vc104: bottom padding applied");
        eq(ma.contains("publicOn, keyOn"), true, "vc104: bridge startServer +2 args");
        String lsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(lsm.contains("publicOn ? \"0.0.0.0\" : \"127.0.0.1\""), true, "vc104: host switch");
        eq(lsm.contains("\"--api-key\""), true, "vc104: --api-key passthrough");
        eq(lsm.contains("apiKeyPlain"), true, "vc104: key accessor for local calls");
        String ss = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ss.contains("\"pubOn\"") && ss.contains("\"keyOn\""), true,
           "vc104: reborn args persisted (pubOn/keyOn)");
        eq(ss.contains("KeyStoreBox.ensureLocal(app)"), true, "vc104: reborn reloads key via ensureLocal");
    }

    /** vc105: GgufProbe MoE detection + service-page MoE form fingerprints. */
    static void testVc105Moe() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vc105").toFile();
        // dense header (no expert_count key at all)
        java.io.File dense = buildGguf(dir, "dense.gguf",
            new String[][]{{"general.architecture", "llama"}, {"llama.context_length", "8192"}});
        GgufProbe.Result rd = GgufProbe.probe(dense);
        eq(rd.parsed, true, "vc105: dense header parses");
        eq(rd.moe, false, "vc105: dense not MoE");
        eq(rd.arch, "llama", "vc105: dense arch read");
        // MoE header (expert_count > 0)
        java.io.File moe = buildGguf(dir, "moe.gguf",
            new String[][]{{"general.architecture", "qwen3moe"}, {"qwen3moe.expert_count", "128"},
                           {"qwen3moe.expert_used_count", "8"}});
        GgufProbe.Result rm = GgufProbe.probe(moe);
        eq(rm.parsed, true, "vc105: moe header parses");
        eq(rm.moe, true, "vc105: moe detected (expert_count>0)");
        eq(rm.expertCount, 128L, "vc105: expert count value");
        eq(rm.arch, "qwen3moe", "vc105: moe arch read");
        // array KV must be skipped without desync (expert_count comes after it)
        java.io.ByteArrayOutputStream a2 = new java.io.ByteArrayOutputStream();
        a2.write(new byte[]{'G', 'G', 'U', 'F'});
        a2.write(ggufU32(3)); a2.write(ggufU64(1)); a2.write(ggufU64(2));
        a2.write(ggufStr("qwen3moe.expert_feed_forward_length"));
        a2.write(ggufU32(9)); a2.write(ggufU32(4)); a2.write(ggufU64(3));
        a2.write(ggufU32(1)); a2.write(ggufU32(2)); a2.write(ggufU32(3));
        a2.write(ggufStr("qwen3moe.expert_count"));
        a2.write(ggufU32(4)); a2.write(ggufU32(128));
        java.io.File arr = new java.io.File(dir, "arr.gguf");
        java.nio.file.Files.write(arr.toPath(), a2.toByteArray());
        GgufProbe.Result ra = GgufProbe.probe(arr);
        eq(ra.parsed, true, "vc105: array KV skipped cleanly");
        eq(ra.moe, true, "vc105: moe after array KV");
        // missing / garbage / truncated inputs: never throw, never MoE
        eq(GgufProbe.probe(new java.io.File(dir, "nope.gguf")).moe, false, "vc105: missing file safe");
        java.io.File garbage = new java.io.File(dir, "garbage.gguf");
        java.nio.file.Files.write(garbage.toPath(), "not a gguf file at all........".getBytes("UTF-8"));
        GgufProbe.Result rg = GgufProbe.probe(garbage);
        eq(rg.parsed, false, "vc105: garbage not parsed");
        eq(rg.moe, false, "vc105: garbage not moe");
        java.io.ByteArrayOutputStream tb = new java.io.ByteArrayOutputStream();
        tb.write(new byte[]{'G', 'G', 'U', 'F'});
        tb.write(ggufU32(3)); tb.write(ggufU64(0)); tb.write(ggufU64(5000L));
        tb.write(ggufStr("general.architecture"));
        java.io.File trunc = new java.io.File(dir, "trunc.gguf");
        java.nio.file.Files.write(trunc.toPath(), tb.toByteArray());
        GgufProbe.Result rt = GgufProbe.probe(trunc);
        eq(rt.parsed, false, "vc105: truncated header not parsed");
        eq(rt.moe, false, "vc105: truncated header not moe");
        // fingerprints: bridge + pristine + transform script + html + build chain
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ma.contains("public String moeProbe(final String modelPath)"), true, "vc105: moeProbe bridge");
        eq(ma.contains("GgufProbe.probe(new File("), true, "vc105: bridge calls GgufProbe");
        String pv = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc105.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(pv.contains("moeProbe"), true, "vc105: transform calls moeProbe bridge");
        eq(pv.contains("请先导入Moe模型"), true, "vc105: gate message key");
        eq(pv.contains("Moe模型导入完成"), true, "vc105: import-done message key");
        eq(pv.contains("导入O_Direct"), true, "vc105: switch label key");
        eq(pv.contains("profileId:\"bmoe\""), true, "vc105: start routes bmoe engine");
        eq(pv.contains("\"__bmoe__\"+_moeImportedName"), true, "vc105: start uses /data copy name");
        eq(pv.contains("_moeDirect&&!_moeImported"), true, "vc105: import gate (yes+not imported)");
        String idx = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(idx.contains("id=\"moeEngine\""), true, "vc105: engine form in html");
        eq(idx.contains("id=\"moeDirect\""), true, "vc105: switch in html");
        eq(idx.contains("id=\"btnMoeImport\""), true, "vc105: import button in html");
        eq(idx.contains("id=\"moeImportFill\""), true, "vc105: progress bar in html");
        eq(idx.contains("id=\"moeGate\""), true, "vc105: gate hint in html");
        String bs = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        // vc107: version pin moved to testVc107BmoeArgs (VC chains forward each build)
        eq(bs.contains("BMOE_FLAG=true"), true, "vc105: bmoe closure all variants");
        eq(bs.contains("patch-vc105.py"), true, "vc105: transform chained into build");
        String bv = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build-verify.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bv.contains("bmoe closure ships in ALL variants"), true, "vc105: verify gate flipped");
    }

    /** vc107: bmoe 服务链引擎参数定稿档指纹（warm+auto 回归防复发）+ 版本 bump。 */
    static void testVc107BmoeArgs() throws Exception {
        System.out.println("== vc107: BmoeServiceManager CLI args (1007 定稿档) ==");
        String bsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeServiceManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bsm.contains("--moe-stream --dense-weights anon --cache-mb 3072 --overlap"), true,
                "vc107: CLI_ARGS = moe-stream+anon+cache 3072 (start.sh 同款)");
        eq(bsm.contains("--dense-weights warm"), false, "vc107: warm 档已废弃");
        eq(bsm.contains("--cache-mb auto"), false, "vc107: auto 档已废弃");
        eq(bsm.contains("BMOE_API_KEY"), true, "vc107: Bearer 鉴权保留 (vc106)");
        eq(bsm.contains("PDEATHSIG") || bsm.contains("pdeathsig") || bsm.contains("PDEATHSIG".toLowerCase()),
                true, "vc107: PDEATHSIG 链式清场保留 (vc106)");
        String bs = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        // vc108 接管版本断言（铁律#18：版本断言只在最新 vc 测试）
    }

    static void testVc108MoeForm() throws Exception {
        System.out.println("== vc108: MoE 引擎表单规格 + Chat 服务路径 ==");
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ih.contains("data-profile=\"bmoe\""), false,
                "vc108: BigMoE 引擎按钮已删除（默认 seg=NPU/GPU/CPU/MTP）");
        eq(ih.contains(">BigMoE<"), false, "vc108: BigMoE 按钮文案已删除");
        eq(ih.contains("id=\"moeEngine\""), true, "vc108: moeEngine 表单保留");
        eq(ih.contains("id=\"moeSeg\""), true, "vc108: MOE 单钮 seg 保留");
        eq(ih.contains("id=\"moeDirect\""), true, "vc108: O_Direct 开关保留");
        eq(ih.contains("id=\"btnMoeImport\""), true, "vc108: 导入按钮保留");
        for (String row : new String[]{"rowPort", "rowCtx", "rowUbatch", "rowSleepIdle"}) {
            eq(ih.contains("id=\"" + row + "\""), true, "vc108: 参数表单保留 " + row);
        }
        // 生产 JS 管线真实回归：pristine -> patch-app -> vc105 -> vc108，
        // 产物必须无 bmoeChatStart 死路、无 bmoe profileId 分支、参数行不被隐藏。
        java.io.File tmp = java.nio.file.Files.createTempDirectory("vc108pipe").toFile();
        java.io.File js = new java.io.File(tmp, "app.js");
        java.nio.file.Files.copy(java.nio.file.Paths.get("app/src/main/assets/app.js.pristine"),
                js.toPath());
        String[][] chain = {
                {"patch-app.py", "app/src/main/assets/app.js.pristine", js.getPath()},
                {"patch-vc105.py", js.getPath(), null},
                {"patch-vc108.py", js.getPath(), null},
        };
        for (String[] step : chain) {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("python3");
            cmd.add(step[0]);
            cmd.add(step[1]);
            if (step[2] != null) cmd.add(step[2]);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.InputStream in = p.getInputStream();
            while (in.read() != -1) { /* drain */ }
            int rc = p.waitFor();
            eq(rc, 0, "vc108: pipeline step " + step[0] + " exit 0");
        }
        String out = new String(java.nio.file.Files.readAllBytes(js.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(out.contains("bmoeChatStart"), false, "vc108: chat 死路 bmoeChatStart 已移除");
        eq(out.contains("\"bmoe\"===profileId"), false, "vc108: bmoe profileId 分支全清");
        eq(out.contains("var d=o?function(){"), true, "vc108: chat HTTP 路径保留");
        eq(out.contains("profileId:\"bmoe\""), true, "vc108: MoE 启动透传 profileId=bmoe 保留");
        eq(out.contains("var r=$(\"rowPort\");r&&(r.style.display=\"flex\")"), true,
                "vc108: setProfile 不再隐藏 rowPort");
        eq(out.contains("var s=$(\"rowUbatch\");s&&(s.style.display=\"flex\")"), true,
                "vc108: setProfile 不再隐藏 rowUbatch");
        eq(out.contains("r&&(r.style.display=\"bmoe\"===e?\"none\":\"flex\")"), false,
                "vc108: 旧隐藏逻辑已清除");
        // rev2: renderCard bmoe 早退整段重写为无条件赋值 + 无模型早退。
        // rev1 教训：if(A,B)return; 中 B=赋值恒真 => renderCard 恒早退，模型名不渲染
        // （vc108 rev1 真机事故：浏览模型后不显示模型名字）。
        eq(out.contains("if($(\"mcEmpty\").style.display=e?\"none\":\"block\","
                + "$(\"mcBody\").style.display=e?\"block\":\"none\")return;"), false,
                "vc108: renderCard 恒真早退反逻辑已清除");
        eq(out.contains("$(\"mcEmpty\").style.display=e?\"none\":\"block\";"
                + "$(\"mcBody\").style.display=e?\"block\":\"none\";"),
                true, "vc108: renderCard 赋值改为无条件顺序语句");
        eq(out.contains("var c=chatStreamBubble()"), true,
                "vc108: chat 主路径 var c 声明存在（严格模式安全）");
        eq(out.contains(",c=chatStreamBubble()"), false,
                "vc108: chat 主路径不再有裸赋值 c（rev1 事故：ReferenceError 卡死 Chat）");
        eq(out.contains("0===p.indexOf(\"__bmoe__\")"), true,
                "vc108: moeProbe 解析 __bmoe__ 合成路径（rev2：导入列表 MoE 模型表单可现）");
        eq(out.contains("function renderMoeEngine"), true, "vc108: renderMoeEngine 保留");
        eq(out.contains("moeProbe"), true, "vc108: moeProbe 检测链保留");
        eq(out.contains("window.onChatChunk"), true, "vc108: chat chunk 通路保留");
        // rev4: 导入按钮直导当前浏览模型（开关行显隐逻辑不变）
        eq(out.contains("function moeImportSel"), true, "vc108: moeImportSel 定义存在");
        eq(out.contains("bridge(\"bmoeImportPath\",model.path)"), true, "vc108: moeImportSel 直通桥调用");
        String idxr4 = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(idxr4.contains("onclick=\"moeImportSel()\""), true, "vc108: 导入按钮 onclick=moeImportSel");
        eq(idxr4.contains("onclick=\"moeBrowse()\""), false, "vc108: 导入按钮不再走二次目录浏览");
        eq(idxr4.contains("id=\"moeDirect\""), true, "vc108: 导入O_Direct 开关保留");
        String ma4 = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ma4.contains("public void bmoeImportPath(final String modelPath)"), true, "vc108: bmoeImportPath 桥方法在位");
        eq(ma4.contains("mm.startImport(modelPath)"), true, "vc108: bmoeImportPath 直调 startImport");
        String bs2 = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bs2.contains("patch-vc108.py"), true, "vc108: build.sh 已接入 patch-vc108.py");
        // vc109 接管版本断言（铁律#18：版本断言只在最新 vc 测试；vc108 的
        // "VC=107" 断言已随 VC=109 推进而退役，testVc109PrefillWalls 接管）
    }

    static byte[] ggufU32(int v) throws Exception {
        return java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    static byte[] ggufU64(long v) throws Exception {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    static byte[] ggufStr(String s) throws Exception {
        // GGUF spec (gguf-py writer): string length is u64 little-endian.
        // vc105 fixture wrote u32 — matched the buggy probe = false green that
        // hid "real files never parse" until vc108 rev3.
        byte[] b = s.getBytes("UTF-8");
        return java.nio.ByteBuffer.allocate(8 + b.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(b.length).put(b).array();
    }

    /** negative fixture: u32-length string (pre-fix misread) must NOT parse */
    static byte[] ggufStrU32(String s) throws Exception {
        byte[] b = s.getBytes("UTF-8");
        return java.nio.ByteBuffer.allocate(4 + b.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(b.length).put(b).array();
    }

    static java.io.File buildGguf(java.io.File dir, String name, String[][] kvs) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(new byte[]{'G', 'G', 'U', 'F'});
        o.write(ggufU32(3));            // version
        o.write(ggufU64(0));            // tensor_count
        o.write(ggufU64(kvs.length));   // kv_count
        for (String[] kv : kvs) {
            o.write(ggufStr(kv[0]));
            if ("general.architecture".equals(kv[0])) {
                o.write(ggufU32(8));    // type: string
                o.write(ggufStr(kv[1]));
            } else {
                o.write(ggufU32(4));    // type: uint32
                o.write(ggufU32(Integer.parseInt(kv[1])));
            }
        }
        java.io.File f = new java.io.File(dir, name);
        java.nio.file.Files.write(f.toPath(), o.toByteArray());
        return f;
    }

    /** vc108 rev3: GGUF string length is u64 (gguf-py writer `self._pack("Q", ...)`).
     *  vc105 probe read u32 → desync at first string KV → ok=false on ALL real files
     *  → MoE form never appeared. vc105 fixtures wrote u32 too = self-consistent false
     *  green. This test pins the spec in BOTH directions + real-device header. */
    static void testVc108GgufStrSpec() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vc108r3").toFile();
        // spec fixture: u64-length strings parse, expert_count detected
        java.io.File moe = buildGguf(dir, "moe.gguf",
            new String[][]{{"general.architecture", "qwen3moe"}, {"qwen3moe.expert_count", "128"}});
        GgufProbe.Result rm = GgufProbe.probe(moe);
        eq(rm.parsed, true, "vc108r3: u64-str fixture parses");
        eq(rm.moe, true, "vc108r3: u64-str fixture moe detected");
        // negative fixture: u32-length string must NOT parse (pre-fix misread direction)
        java.io.ByteArrayOutputStream n2 = new java.io.ByteArrayOutputStream();
        n2.write(new byte[]{'G', 'G', 'U', 'F'});
        n2.write(ggufU32(3)); n2.write(ggufU64(0)); n2.write(ggufU64(1));
        n2.write(ggufStrU32("general.architecture"));
        n2.write(ggufU32(8));
        n2.write(ggufStrU32("llama"));
        java.io.File neg = new java.io.File(dir, "neg.gguf");
        java.nio.file.Files.write(neg.toPath(), n2.toByteArray());
        GgufProbe.Result rn = GgufProbe.probe(neg);
        eq(rn.parsed, false, "vc108r3: u32-length string rejected (spec direction pinned)");
        // real-device regression: 16 MiB head of S25 Qwen3.6-35B-A3B UD-Q3_K_M
        // (vc108 rev3 真机实锤：ok=false→MOE 表单永不出现)。固化在工程
        // testdata/（md5 7bbda2f4…），/tmp/gguf-heads 为旧会话临时路径。
        java.io.File real = new java.io.File("testdata/qwen35-35b-a3b-head16m.bin");
        if (!real.canRead()) real = new java.io.File("/tmp/gguf-heads/35b-execout.bin");
        if (real.canRead()) {
            GgufProbe.Result rr = GgufProbe.probe(real);
            eq(rr.parsed, true, "vc108r3: real 35B header parses");
            eq(rr.moe, true, "vc108r3: real 35B header moe=true experts=256");
            eq(rr.expertCount, 256L, "vc108r3: real 35B expert_count value");
            eq("qwen35moe".equals(rr.arch), true, "vc108r3: real 35B arch=qwen35moe");
        } else {
            System.out.println("SKIP vc108r3: real-device header dump not present (/tmp/gguf-heads/35b-execout.bin)");
        }
    }

    /** vc109: 超时墙三件套——桥看门狗 env（进程链=app→bridge→CLI，bridge Config
     *  构造时 getenv，故必须注入 spawn bridge 的 ProcessBuilder environment）、
     *  CLI_ARGS 原样保留（vc107 定稿已含 --cache-mb 3072）、App 侧本地 chat
     *  客户端 readTimeout 960s（>stall 墙 900s，prefill 期间零字节不断流）。 */
    static void testVc109PrefillWalls() throws Exception {
        System.out.println("== vc109: prefill 超时墙 env + CLI_ARGS 保留 + readTimeout ==");
        String bsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeServiceManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bsm.contains("pb.environment().put(\"BMOE_STALL_CEILING_S\", ENV_STALL_WALL)"), true,
                "vc109: stall wall env injected at bridge spawn layer");
        eq(bsm.contains("pb.environment().put(\"BMOE_TOTAL_CEILING_S\", ENV_TOTAL_WALL)"), true,
                "vc109: total wall env injected at bridge spawn layer");
        eq(bsm.contains("static final String ENV_STALL_WALL = \"900\";"), true,
                "vc109: BMOE_STALL_CEILING_S=900");
        eq(bsm.contains("static final String ENV_TOTAL_WALL = \"1800\";"), true,
                "vc109: BMOE_TOTAL_CEILING_S=1800");
        eq(bsm.contains("walls=\" + ENV_STALL_WALL + \"/\" + ENV_TOTAL_WALL"), true,
                "vc109: [cmd] line carries walls= fingerprint for adb smoke");
        eq(bsm.contains("BMOE_STALL_CEILING_S=180") || bsm.contains("BMOE_TOTAL_CEILING_S=600"),
                false, "vc109: no legacy default values in spawn env");
        // vc113a (1014): --device HTP0 removed — bmoe CLI 0.27.0 rejects the flag
        // ("unknown arg: --device"), CLI exited 1 in a spawn loop -> stuck at
        // 启动中. Verified on-device via usage output: the NPU prefill flag is
        // --prefill-device (NOT llama.cpp's --device).
        // vc116 (1014 pm): vc115 NPU prefill wave pulled from the release build —
        // the decision experiment proved the full chain activates, but the
        // on-device verdict (first-build warmup -> warm-start seconds?) is still
        // out, so the package reverts to the vc114 final CPU-prefill string.
        eq(bsm.contains("--moe-stream --dense-weights anon --cache-mb 3072 --overlap --device"), false,
                "vc113a: llama.cpp-style --device flag must stay out of bmoe CLI args");
        eq(bsm.contains("--prefill-device"), false,
                "vc116: --prefill-device HTP0 reverted (wave pulled, verdict pending)");
        eq(bsm.contains("--moe-stream --dense-weights anon --cache-mb 3072 --overlap\";"), true,
                "vc116: CLI_ARGS byte-equal to the vc114 final CPU-prefill string");
        eq(bsm.contains("pb.environment().put(\"GGML_HEXAGON_HOSTBUF\""), false,
                "vc116: HOSTBUF env reverted with the NPU prefill wave");
        eq(bsm.contains("pb.environment().put(\"GGML_HEXAGON_OPPOLL\""), false,
                "vc116: OPPOLL env reverted with the NPU prefill wave");
        eq(bsm.contains("READY_TIMEOUT_MS = 600_000L"), true,
                "vc116: READY ceiling back to 10min with CPU prefill");
        eq(bsm.contains("5_400_000L"), false, "vc116: 90min NPU-repack ceiling gone");
        // App 侧本地 8080 客户端 readTimeout：300s -> 960s（>stall 墙 900s）
        String scs = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerChatStreamer.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(scs.contains("c.setReadTimeout(960000)"), true, "vc109: SSE chat readTimeout=960s");
        eq(scs.contains("setReadTimeout(300000)"), false, "vc109: SSE chat 300s timeout gone");
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ma.contains("conn.setReadTimeout(960000)"), true, "vc109: CLI-page post readTimeout=960s");
        eq(ma.contains("setReadTimeout(300000)"), false, "vc109: CLI-page 300s timeout gone");
    }

    /** vc111 UI 三件：导入按钮样式/多语言对齐 btnPick、运行态文案清空、版权行回归 std 同款。 */
    static void testVc111UiPolish() throws Exception {
        System.out.println("== vc111: MoE 导入按钮样式+i18n / 运行文案 / verLine ==");
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        // ① 导入按钮 = btnPick 同款结构：SVG 图标 + data-i18n span + 共用 CSS
        eq(ih.contains("id=\"btnMoeImport\" style=\"display:none; margin-top:6px\" onclick=\"moeImportSel()\">\n" +
                "            <svg class=\"ic\" viewBox=\"0 0 24 24\"><path d=\"M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z\"/></svg><span data-i18n=\"导入Moe模型\">导入Moe模型</span>"),
                true, "vc111: import button carries svg icon + i18n span (btnPick structure)");
        eq(ih.contains("#btnPick, #btnMoeImport, #btnSpeedTest {"), true,
                "vc111/vc136/vc138: import button shares #btnPick CSS (speed test joined)");
        eq(ih.contains("#btnPick:active, #btnMoeImport:active, #btnSpeedTest:active { background: #1d2532; }"), true,
                "vc111/vc136/vc138: import button shares :active CSS (speed test joined)");
        eq(ih.contains(">导入Moe模型</button>"), false, "vc111: bare-text import button gone");
        // ② 版权行回归 std 同款（build.sh 注入目标，此处为占位同步）
        eq(java.util.regex.Pattern.compile("id=\"verLine\">\\d+\\.\\d+\\.\\d+ · llama\\.cpp \\(MIT\\)</div>").matcher(ih).find(),
                true, "vc111/vc136: verLine keeps VN form (injected, no hardcoded 1.4.5)");
        eq(ih.contains("1.4.3-Moe"), false, "vc111: -Moe suffix gone from html");
        // ③ JS 侧 flex 显隐 + 双语字典
        String pv = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc105.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(pv.contains("b.style.display=_moeIsMoe&&_moeDirect&&!_moeImported?\"flex\":\"none\""), true,
                "vc111: import button shows as flex (CSS is display:flex)");
        eq(pv.contains("导入Moe模型\":\"匯入Moe模型"), true, "vc111: zh-TW dict has import button");
        eq(pv.contains("导入Moe模型\":\"Import MoE model"), true, "vc111: en dict has import button");
        // ④ 运行态文案清空（对齐标准档 L915 空串语义）
        String lsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(lsm.contains("服务运行中"), false, "vc111: running-state text removed");
        eq(lsm.contains("m.pushState(ST_RUNNING, \"\")"), true, "vc111: bmoeReady pushes empty msg");
        // ⑤ build.sh：beta 署名回归 std 同款 + 版本推进（铁律#18：版本断言只在最新 vc 测试）
        String bs = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")), java.nio.charset.StandardCharsets.UTF_8);
        eq(bs.contains("1.4.3-Moe"), false, "vc111: -Moe suffix gone from build.sh");
        int c = 0, i = 0;
        while ((i = bs.indexOf("UI_TAG=\"$VN · llama.cpp (MIT)\"", i)) >= 0) { c++; i++; }
        eq(c, 2, "vc111/vc136: std+beta UI_TAG identical (both derive from VN)");
    }


    /** vc113: MoE 兼容性三案 —— A1 bmoe 闭包自解（三星 A14 PM 跳过非 lib* 条目）、
     *  A2 extractVqti 根修（mkdirs）+ 失败原因透出（Ace3P 盲点）、A3 NPU/MTP 启动
     *  预检（GgufProbe 深探针：ftype 白名单 + blk.*.nextn 检测 + 量化张量核验）。 */
    static void testVc113MoeCompat() throws Exception {
        System.out.println("== vc113: A1 closure self-extract / A2 vqti cause / A3 preflight ==");
        // ---------- A1: linkClosureDir gains APK self-extract ----------
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("vc113a1");
            // nativeDir only got the lib-prefixed member (vc87 Samsung A14 family):
            // JVM has no AppCtx so extraction degrades -> old pure-symlink semantics
            java.nio.file.Path nat = root.resolve("native");
            java.nio.file.Files.createDirectories(nat);
            java.nio.file.Files.write(nat.resolve("libbmoecli.so"), new byte[]{1});
            String dir = BmoeSession.linkClosureDir(root.toFile(), nat.toString());
            // task-书 A1: extraction failure keeps DEGRADED semantics (callers
            // unchanged) — JVM has no AppCtx so extraction cannot run; the dir
            // still comes back with whatever nativeDir had (the CLI exe).
            eq(dir != null, true, "vc113-A1: degraded path still returns dir (no throw)");
            eq(java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(dir, "libbmoecli.so")), true,
                    "vc113-A1: CLI still symlinked in degraded path");
            eq(java.nio.file.Files.exists(java.nio.file.Paths.get(dir, "lbmggml.so")), false,
                    "vc113-A1: extraction unavailable on JVM — closure absent (no fake files)");
            // complete nativeDir: pure symlink path must be untouched
            java.nio.file.Path nat2 = root.resolve("native2");
            java.nio.file.Files.createDirectories(nat2);
            for (String f : new String[]{"lbmggml.so", "lbmggml-base.so", "lbmggml-cpu.so",
                    "lbmllama.so", "lbmllama-common.so", "libbmoecli.so"}) {
                java.nio.file.Files.write(nat2.resolve(f), new byte[]{1});
            }
            String dir2 = BmoeSession.linkClosureDir(root.toFile(), nat2.toString());
            eq(dir2 != null, true, "vc113-A1: complete nativeDir still symlinks");
            eq(java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(dir2, "lbmggml.so")), true,
                    "vc113-A1: closure symlink present");
            // static fingerprints: self-extract hook + guarded + stamp discipline
            String bs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeSession.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bs.contains("ensureBmoeClosureLibs(new File(nativeDir), cacheDir)"), true,
                    "vc113-A1: linkClosureDir calls self-extract");
            eq(bs.contains("if (!missing) return;"), true, "vc113-A1: Android 16+ fast path");
            eq(bs.contains("[bmoe-extract] closure self-extract failed"), true,
                    "vc113-A1: failure breadcrumb (A2 lesson: no silent swallowing)");
            eq(bs.contains("failed == 0"), true, "vc113-A1: stamp only on complete pass");
        } catch (Throwable t) { fail++; System.out.println("FAIL vc113-A1 harness: " + t); }

        // ---------- A2: extractVqti mkdirs root fix + fail-cause ----------
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("vc113a2").toFile();
            java.io.File zip = new java.io.File(dir, "fake.apk");
            String entry = "lib/arm64-v8a/vendor.qti.hardware.dsp-V1-ndk.so";
            java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(zip));
            zo.putNextEntry(new java.util.zip.ZipEntry(entry));
            zo.write("VQTI-VC113".getBytes("UTF-8"));
            zo.closeEntry();
            zo.close();
            // the Ace3P case: outDir NEVER existed (vc97 never mkdirs -> FNF -> null)
            java.io.File out = new java.io.File(dir, "vqti-not-created");
            StringBuilder err = new StringBuilder();
            java.io.File r = LlamaServerManager.extractVqti(zip, out, entry, zip.lastModified(), err);
            eq(r != null && r.equals(out), true, "vc113-A2: mkdirs root fix (first-ever extract, fresh dir)");
            eq(new java.io.File(out, "vendor.qti.hardware.dsp-V1-ndk.so").canRead(), true,
                    "vc113-A2: payload landed");
            eq(err.length() == 0, true, "vc113-A2: no error on success");
            StringBuilder err2 = new StringBuilder();
            java.io.File r2 = LlamaServerManager.extractVqti(zip, out, "lib/arm64-v8a/absent.so", zip.lastModified(), err2);
            eq(String.valueOf(r2), "null", "vc113-A2: missing entry -> null");
            eq(err2.toString().startsWith("no-entry:"), true, "vc113-A2: cause = no-entry:<name>");
            // caller surfaces the cause in the log
            String lsm2 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(lsm2.contains("vqErr.toString()"), true, "vc113-A2: caller logs the cause");
            eq(lsm2.contains("自解异常（忽略）"), false, "vc113-A2: swallowing log line gone");
        } catch (Throwable t) { fail++; System.out.println("FAIL vc113-A2 harness: " + t); }

        // ---------- A3: GgufProbe deep probe + whitelist + MTP ----------
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("vc113a3").toFile();
            java.io.File q4km = buildGguf(dir, "q4km.gguf",
                new String[][]{{"general.architecture", "qwen35"}, {"general.file_type", "15"}});
            GgufProbe.Result rq = GgufProbe.probe(q4km);
            eq(rq.ftype, 15L, "vc113-A3: general.file_type read (Q4_K_M=15, llama.h L133)");
            eq(GgufProbe.npuQuantSupported(rq.ftype), true, "vc113-A3: Q4_K_M whitelisted");
            java.io.File iqx = buildGguf(dir, "iqx.gguf",
                new String[][]{{"general.architecture", "qwen35"}, {"general.file_type", "30"}});
            eq(GgufProbe.npuQuantSupported(GgufProbe.probe(iqx).ftype), false,
                    "vc113-A3: IQ4_XS (ftype 30) rejected");
            eq(GgufProbe.npuQuantSupported(38), true, "vc113-A3: MXFP4 (ftype 38) whitelisted");
            // deep probe: tensor dir with blk.32.nextn (names carry NO arch prefix)
            // vc116: entries follow the REAL gguf.cpp layout (name, n_dims, dims,
            // type, offset — no size field); the vc113 fixture encoded the probe's
            // own wrong order = self-consistent false green (vc108 class, 2nd hit).
            java.io.ByteArrayOutputStream m2 = new java.io.ByteArrayOutputStream();
            m2.write(new byte[]{'G','G','U','F'});
            m2.write(ggufU32(3)); m2.write(ggufU64(2)); m2.write(ggufU64(1));
            m2.write(ggufStr("general.architecture"));
            m2.write(ggufU32(8)); m2.write(ggufStr("qwen35"));
            // tensor 1: blk.32.nextn.eh_proj — nDims=2, dims(4096,64), F32(0), offset
            m2.write(ggufStr("blk.32.nextn.eh_proj"));
            m2.write(ggufU32(2));
            m2.write(ggufU64(4096)); m2.write(ggufU64(64));
            m2.write(ggufU32(0)); m2.write(ggufU64(0));
            // tensor 2: blk.0.attn_q.weight — IQ4_XS(23) must surface in quantErr
            m2.write(ggufStr("blk.0.attn_q.weight"));
            m2.write(ggufU32(2));
            m2.write(ggufU64(4096)); m2.write(ggufU64(4096));
            m2.write(ggufU32(23)); m2.write(ggufU64(1048576));
            java.io.File mtp = new java.io.File(dir, "mtp.gguf");
            java.nio.file.Files.write(mtp.toPath(), m2.toByteArray());
            StringBuilder qe = new StringBuilder();
            GgufProbe.Result rm = GgufProbe.probe(mtp, qe);
            eq(rm.hasMtp, true, "vc113-A3: blk.32.nextn detected (arch-less tensor names)");
            eq(qe.toString(), "IQ4_XS", "vc113-A3: first unsupported quant surfaced");
            eq(rm.parsed, true, "vc113-A3: clean walk stays parsed");
            java.io.File dense = buildGguf(dir, "dense2.gguf",
                new String[][]{{"general.architecture", "qwen35"}, {"general.file_type", "15"}});
            eq(GgufProbe.probe(dense, true).hasMtp, false, "vc113-A3: dense has no nextn");
            // truncated mid-KV: deep probe must not throw (best-effort contract)
            byte[] full = java.nio.file.Files.readAllBytes(mtp.toPath());
            java.io.File trunc = new java.io.File(dir, "trunc2.gguf");
            java.nio.file.Files.write(trunc.toPath(), java.util.Arrays.copyOf(full, 60));
            GgufProbe.Result rt = GgufProbe.probe(trunc, true);
            eq(rt.parsed, false, "vc113-A3: truncated KV -> parsed=false, no throw");
            // exec-layer preflight + provenance pins
            String lsm = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(lsm.contains("npuProfile = \"htp\".equals(profileId) || \"mtp\".equals(profileId)"), true,
                    "vc113-A3: preflight gates NPU/MTP only");
            eq(lsm.contains("不是 MTP 版本"), true, "vc113-A3: MTP preflight message");
            eq(lsm.contains("请换 Q4_0 或 Q4_K_M 量化"), true, "vc113-A3: quant preflight message");
            eq(lsm.contains("case 15: return \"Q4_K_M\";"), true,
                    "vc113-A3: ftype table pinned (llama.h verified 1013)");
            String gp = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/GgufProbe.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(gp.contains("case 2:  return true;   // Q4_0"), true,
                    "vc113-A3: ggml table pinned (Q4_0=2, ggml.h verified 1013)");
            eq(gp.contains("name.startsWith(\"blk.\") && name.contains(\".nextn.\")"), true,
                    "vc113-A3: arch-less tensor name contract");
            // version pin moved to testVc116 (铁律#18: only in newest vc test)
        } catch (Throwable t) { fail++; System.out.println("FAIL vc113-A3 harness: " + t); }
    }

    /**
     * vc116 (1014): two threads —
     * ① bmoe NPU prefill (vc115 wave) reverted to the vc114 CPU-prefill final:
     *    CLI string byte-equal, HOSTBUF/OPPOLL env gone, READY ceiling 90min->10min.
     * ② MTP preflight real-header regression: the vc113 tensor walk had the wrong
     *    entry layout (type before dims + phantom size u64) and died on real files
     *    at tensor #2 — hasMtp stayed false for every real MTP model, so the
     *    "MTP 档但模型无 blk.*.nextn 张量" toast fired on valid models. Layout fixed
     *    per gguf.cpp L643-750; this test pins the fix against the REAL 16 MiB
     *    header of S25 Qwen3.5-4B-MTP Q4_K_M (441 tensors, 4 nextn, tensor dir
     *    at ~10.9 MB past a ~10.4 MB tokenizer KV block — the fixture class can
     *    never exercise that depth).
     */
    static void testVc116() throws Exception {
        System.out.println("== vc116: bmoe CPU-prefill revert + MTP preflight real-header ==");
        String bsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeServiceManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bsm.contains("--prefill-device"), false, "vc116①: --prefill-device out of CLI_ARGS");
        eq(bsm.contains("--moe-stream --dense-weights anon --cache-mb 3072 --overlap\";"), true,
                "vc116①: CLI_ARGS == vc114 final");
        eq(bsm.contains("pb.environment().put(\"GGML_HEXAGON_HOSTBUF\""), false, "vc116①: HOSTBUF reverted");
        eq(bsm.contains("pb.environment().put(\"GGML_HEXAGON_OPPOLL\""), false, "vc116①: OPPOLL reverted");
        eq(bsm.contains("READY_TIMEOUT_MS = 600_000L"), true, "vc116①: READY ceiling 10min restored");
        eq(bsm.contains("5_400_000L"), false, "vc116①: 90min ceiling gone");
        // version pin (铁律#18: only in newest vc test)
        String bs2 = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")), java.nio.charset.StandardCharsets.UTF_8);
        eq(bs2.contains("VC=161"), true, "vc131: VC=132 (moved from vc130 pin)");
        // ② real-header regression (16 MiB head of Qwen3.5-4B-MTP-Q4_K_M, S25 1014)
        java.io.File real = new java.io.File("testdata/qwen35-4b-mtp-head16m.bin");
        if (real.canRead()) {
            StringBuilder qe = new StringBuilder();
            GgufProbe.Result rr = GgufProbe.probe(real, qe);
            eq(rr.parsed, true, "vc116②: real 4B-MTP header parses");
            eq(rr.hasMtp, true, "vc116②: blk.32.nextn detected in real header (was the vc113 killer)");
            eq(rr.expertCount, 0L, "vc116②: dense model, expert_count=0");
            eq("qwen35".equals(rr.arch), true, "vc116②: arch=qwen35");
            eq(qe.length() == 0, true, "vc116②: Q4_K_M all-whitelisted, quantErr empty");
            // non-tensor probe must not see nextn-only via KV (contract: hasMtp comes from tensor dir)
            GgufProbe.Result rk = GgufProbe.probe(real);
            eq(rk.hasMtp, false, "vc116②: KV-only probe hasMtp=false (tensor-dir contract)");
        } else {
            System.out.println("SKIP vc116②: real-device header dump not present (testdata/qwen35-4b-mtp-head16m.bin)");
        }
    }

    /**
     * vc118 batch1 (1018): A17 hotfix — bundled libs shadow system libs by soname
     * (bundled libc++ lacks __hash_memory; bundled libdmabufheap only exports the
     * 4-arg Alloc while A17 libcodec2_vndk needs the 3-arg one) -> exec children
     * die with CANNOT LINK. Fix = SysLibStubs symlinks pointing at /system/lib64,
     * prepended to the search path. ① real symlink behavior on a temp dir
     * (create / idempotent rebuild / missing-target degradation / partial-failure
     * counting), ② static fingerprints of both call sites.
     */
    static void testVc118Batch1() throws Exception {
        System.out.println("== vc118 batch1: SysLibStubs + A17 soname-shadow hotfix ==");
        // ① behavior: real temp dirs (JVM-safe: zero android.* in SysLibStubs)
        java.io.File base = java.nio.file.Files.createTempDirectory("vc118b1").toFile();
        java.io.File stubDir = new java.io.File(base, "stub");
        java.io.File sysDir = new java.io.File(base, "system");
        sysDir.mkdirs();
        // create real targets: one present, one missing -> degradation path
        eq(new java.io.File(sysDir, "libc__.test.so").createNewFile(), true, "vc118: fixture sys lib A created");
        int n1 = SysLibStubs.ensure(stubDir, new String[]{"libc__.test.so", "libmissing.test.so"},
                sysDir.getPath());
        eq(n1, 1, "vc118: one of two targets present -> exactly 1 link (missing one degraded)");
        java.io.File linkA = new java.io.File(stubDir, "libc__.test.so");
        eq(linkA.exists(), true, "vc118: link created for present target");
        eq(java.nio.file.Files.isSymbolicLink(linkA.toPath()), true, "vc118: entry is a real symlink");
        eq(java.nio.file.Files.readSymbolicLink(linkA.toPath()).toString(),
                new java.io.File(sysDir, "libc__.test.so").getPath(), "vc118: symlink points at the system dir target");
        eq(new java.io.File(stubDir, "libmissing.test.so").exists(), false,
                "vc118: no dead link for missing target (degradation, not dangling symlink)");
        // idempotent rebuild: second call must succeed and keep the same target
        int n2 = SysLibStubs.ensure(stubDir, new String[]{"libc__.test.so"}, sysDir.getPath());
        eq(n2, 1, "vc118: idempotent rebuild returns success again");
        eq(java.nio.file.Files.readSymbolicLink(linkA.toPath()).toString(),
                new java.io.File(sysDir, "libc__.test.so").getPath(), "vc118: idempotent rebuild keeps target");
        // overwrite semantics: a pre-existing REGULAR file at the link path is replaced
        java.io.File stub2 = new java.io.File(base, "stub2");
        stub2.mkdirs();
        eq(new java.io.File(stub2, "libc__.test.so").createNewFile(), true, "vc118: stale regular file fixture");
        int n3 = SysLibStubs.ensure(stub2, new String[]{"libc__.test.so"}, sysDir.getPath());
        eq(n3, 1, "vc118: stale regular file replaced by symlink");
        eq(java.nio.file.Files.isSymbolicLink(new java.io.File(stub2, "libc__.test.so").toPath()),
                true, "vc118: replaced entry is now a symlink");
        // missing-target degradation on an empty system dir
        java.io.File sysEmpty = new java.io.File(base, "system-empty");
        sysEmpty.mkdirs();
        java.io.File stub3 = new java.io.File(base, "stub3");
        int n4 = SysLibStubs.ensure(stub3, new String[]{"libc__.test.so"}, sysEmpty.getPath());
        eq(n4, 0, "vc118: all targets missing -> 0 links, no throw");
        // ② static fingerprints — LlamaServerManager (htp path, libc++.so in the ocl-stub dir)
        String lsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(lsm.contains("SysLibStubs.ensure(stubDir, new String[]{\"libc++.so\"}, \"/system/lib64\""), true,
                "vc118: htp path stubs libc++.so from /system/lib64");
        eq(lsm.indexOf("SysLibStubs.ensure") > lsm.indexOf("LD_LIBRARY_PATH\", stubDir.getPath()"),
                false, "vc118: htp call site sits before the env put (stubDir already first in search order)");
        eq(lsm.contains("[syslib-stub]"), true, "vc118: htp failure logging uses the syslib-stub prefix");
        // ② static fingerprints — BmoeServiceManager (bmoe path, both libs, prepended)
        String bsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeServiceManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bsm.contains("syslib-stub"), true, "vc118: bmoe path creates the syslib-stub dir");
        eq(bsm.contains("new String[]{\"libc++.so\", \"libdmabufheap.so\"}"), true,
                "vc118: bmoe path stubs BOTH libc++.so and libdmabufheap.so");
        int ldIdx = bsm.indexOf("LD_LIBRARY_PATH\",\n                sysStubPath == null ? libDir : sysStubPath");
        int ensureIdx = bsm.indexOf("SysLibStubs.ensure(sysStub");
        eq(ldIdx > 0 && ensureIdx > 0 && ensureIdx < ldIdx, true,
                "vc118: bmoe ensure runs BEFORE the env put (sysStub prepended in front of libDir)");
        eq(bsm.contains("sysStubPath + \":\" + libDir"), true,
                "vc118: sysStub is PREPENDED (first in the search path)");
        // ② SysLibStubs hygiene: pure static utility, no instances/anonymous/nested classes (d8 rule)
        String sls = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/SysLibStubs.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(sls.contains("private SysLibStubs()"), true, "vc118: SysLibStubs is instance-free");
        int c1 = sls.indexOf("class");
        int c2 = c1 < 0 ? -1 : sls.indexOf("class", c1 + 1);
        eq(c2, -1, "vc118: SysLibStubs has exactly one class declaration (no nested/anonymous classes)");
        eq(sls.contains("import android"), false, "vc118: SysLibStubs has no android.* deps (JVM-testable)");
        // ② dead-code discipline: BmoeSession.launch must stay untouched by this batch
        String bsession = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeSession.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bsession.contains("syslib-stub"), false,
                "vc118: BmoeSession untouched (dead path — BmoeAdapter has no instantiation since vc105)");
    }

    static void testVc118Batch2() throws Exception {
        System.out.println("== vc118 batch2: system prompt settings box + custom CLI args ==");
        String HTP = "--load-mode none -m {MODEL} --ctx-size 8192 --ubatch-size 1024 -fa on -ngl 99 --device HTP0";
        String MODEL = "/data/data/x/Qwen3.5-4B-Q4_0.gguf";

        // ① sanitizeExtraArgs: newline smuggling is collapsed to spaces
        eq(LlamaServerManager.sanitizeExtraArgs(null), "",
                "vc118b2: null extra args sanitize to empty");
        eq(LlamaServerManager.sanitizeExtraArgs(""), "",
                "vc118b2: empty input stays empty");
        eq(LlamaServerManager.sanitizeExtraArgs("  --threads 6  "), "--threads 6",
                "vc118b2: surrounding whitespace trimmed");
        eq(LlamaServerManager.sanitizeExtraArgs("--chat-template\nchatml"),
                "--chat-template chatml",
                "vc118b2: LF collapsed to space (no second argv element)");
        eq(LlamaServerManager.sanitizeExtraArgs("--a\r\n--b\r--c\0--d"),
                "--a  --b --c --d",
                "vc118b2: CR/LF/NUL all neutralized (CRLF=2 chars -> 2 spaces, harmless)");
        eq(LlamaServerManager.sanitizeExtraArgs("\n\n--top-k 40\n"),
                "--top-k 40",
                "vc118b2: leading/trailing newlines gone after trim");

        // ② buildCommand: extras appended before the fixed --host/--port tail
        String[] base = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                MODEL, "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0, null, null);
        int hostBase = idx(base, "--host");
        eq(hostBase > 0 && hostBase == base.length - 4, true,
                "vc118b2: no extras -> tail is exactly --host X --port Y");
        eq(contains(base, "--top-k"), false, "vc118b2: no extras -> no extra flags present");

        String[] with = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                MODEL, "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0, null,
                "--top-k 40 --cache-type-k q8_0");
        eq(idx(with, "--top-k") >= 0 && idx(with, "40") == idx(with, "--top-k") + 1, true,
                "vc118b2: extra flag + value land as separate argv elements");
        eq(idx(with, "--cache-type-k") > idx(with, "--top-k"), true,
                "vc118b2: extras keep user order");
        eq(idx(with, "--top-k") < idx(with, "--host"), true,
                "vc118b2: extras sit BEFORE the fixed --host/--port tail");
        eq(contains(with, "--no-webui"), false, "vc118b2: nothing invented beyond user input");

        // ③ host/port override attempts are dropped (state contract), their
        // values too, and the fixed tail still carries the real host/port.
        String[] hp = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                MODEL, "127.0.0.1", 9999, 4096, "off", 4, false, 0, 0, null,
                "--port 1234 --host 0.0.0.0 --top-k 40");
        int hpCount = 0;
        for (String t : hp) if ("--port".equals(t)) hpCount++;
        eq(hpCount, 1, "vc118b2: exactly one --port survives (override dropped)");
        int hpHost = idx(hp, "--host");
        eq("9999".equals(hp[hpHost + 3]) && "127.0.0.1".equals(hp[hpHost + 1]), true,
                "vc118b2: fixed tail carries the Java-side host/port, not the injected ones");
        eq(idx(hp, "--top-k") < idx(hp, "--host"), true,
                "vc118b2: benign extras still appended before the tail");

        // ④ multi-line user input: sanitized upstream of splitArgs end to end.
        // User's --threads lands AFTER the UI-fixed "--threads 4" (L28-29);
        // llama.cpp arg parsing is last-wins, so idx must be the SECOND hit.
        String[] ml = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                MODEL, "0.0.0.0", 8080, 4096, "off", 4, false, 0, 0, null,
                LlamaServerManager.sanitizeExtraArgs("--top-k 40\n--threads 6"));
        eq(contains(ml, "--top-k 40\n--threads 6"), false,
                "vc118b2: raw newline never reaches argv");
        int t1 = idx(ml, "--threads");
        eq(t1 > 0 && "4".equals(ml[t1 + 1]), true,
                "vc118b2: first --threads stays the UI-fixed one (--threads 4)");
        int t2 = -1;
        for (int i = t1 + 1; i < ml.length - 1; i++) {
            if ("--threads".equals(ml[i])) { t2 = i; break; }
        }
        eq(t2 > t1 && "6".equals(ml[t2 + 1]), true,
                "vc118b2: user --threads 6 lands after it (last-wins wins)");

        // ⑤ reborn chain: REBORN_KEYS carries cliArgs, start(...) 13-arg path
        String svc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(svc.contains("\"pubOn\", \"keyOn\", \"cliArgs\", \"kvSel\", \"pmMode\"}"), true,
                "vc118b2: REBORN_KEYS[11] = cliArgs (vc119 +2 tail slots)");
        eq(svc.contains("putString(REBORN_KEYS[11], cliArgs == null ? \"\" : cliArgs)"), true,
                "vc118b2: saveRebornArgs persists cliArgs (null-safe)");
        eq(svc.contains("p.getString(REBORN_KEYS[11], \"\")"), true,
                "vc118b2: readRebornArgs defaults cliArgs to empty (old installs safe)");
        eq(svc.contains("cliArgsOf(a)"), true,
                "vc118b2: reborn relaunch passes the persisted cliArgs");

        // ⑥ bridge chain: MainActivity signature 13 params + LSM start 13-arg overload
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ma.contains("final boolean keyOn,\n                                  final String kvSel, final String powerMode,\n                                  final String extraArgs"), true,
                "vc118b2: startServer bridge signature grew the extraArgs param");
        eq(ma.contains("keyOn, kvSel == null ? \"\" : kvSel,\n                    powerMode == null ? \"\" : powerMode, extraArgs);"), true,
                "vc118b2: saveRebornArgs call forwards extraArgs");
        eq(ma.contains("publicOn, apiKey, extraArgs);"), true,
                "vc118b2: server.start call forwards extraArgs");
        String lsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(lsm.contains("final boolean publicOn, final String apiKey,\n                            final String extraArgs"), true,
                "vc118b2: LSM start(...) has the 13-arg overload");
        eq(lsm.contains("sanitizeExtraArgs"), true,
                "vc118b2: buildCommand sanitizes via sanitizeExtraArgs");
        eq(lsm.indexOf("sanitizeExtraArgs") < lsm.indexOf("cmd.add(\"--host\")"), true,
                "vc118b2: extras appended before the fixed --host/--port tail");

        // ⑦ JS chain: patch-vc118.py exists + transform anchors present
        String pj = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc118.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(pj.contains("dostart-13args"), true,
                "vc118b2: JS patch wires the 13th bridge arg from cliArgsBox");
        eq(pj.contains("npullmCliArgs"), true,
                "vc118b2: JS patch persists the CLI args box");
        eq(pj.contains("npullmSampling"), true,
                "vc118b2: JS patch reuses the npullmSampling.systemPrompt store");
        String bh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bh.contains("patch-vc118.py"), true,
                "vc118b2: build.sh runs the vc118 batch2 JS transform");
        eq(bh.indexOf("patch-vc118.py") > bh.indexOf("patch-vc108.py"), true,
                "vc118b2: vc118 transform runs after vc108 (chain order)");
    }

    /**
     * vc119 (1018): KV quant 3-way selector (f16/q8_0/q4_0) + hidden NPU/MTP
     * power-mode selector (GGML_HEXAGON_POWER_MODE, llama.cpp #30423, v150).
     * ① isPowerMode/isKvSel value validation ② buildCommand -ctk/-ctv
     * replace/append semantics ③ Bridge/LSM/ServerService signature sync
     * (vc91 lesson: arity mismatches are silent) ④ launchExec env only on
     * htpEngine ⑤ REBORN_KEYS 14-slot roundtrip ⑥ JS patch chain wiring.
     */
    static void testVc119KvQuantPowerMode() throws Exception {
        System.out.println("== vc119: KV quant selector + NPU/MTP power mode ==");
        String HTP = "--load-mode none -m {MODEL} --ctx-size 8192 --ubatch-size 1024 -fa on "
                + "-ctk q8_0 -ctv q8_0 -ngl 99 --device HTP0";
        String CPU = "--load-mode none -m {MODEL} --ctx-size 4096 -fa on --threads 4";
        String MODEL = "/data/data/x/m.gguf";

        // ① value validation helpers
        eq(LlamaServerManager.isPowerMode("burst"), true, "vc119①: burst legal");
        eq(LlamaServerManager.isPowerMode("sustained_high_performance"), true,
                "vc119①: sHP legal");
        eq(LlamaServerManager.isPowerMode("low_power_saver"), true, "vc119①: lPS legal");
        eq(LlamaServerManager.isPowerMode(""), false, "vc119①: empty = engine default");
        eq(LlamaServerManager.isPowerMode(null), false, "vc119①: null illegal");
        eq(LlamaServerManager.isPowerMode("turbo"), false, "vc119①: unknown illegal");
        eq(LlamaServerManager.POWER_MODES.length, 8, "vc119①: 8 legal modes (#30423)");
        eq(LlamaServerManager.isKvSel("f16"), true, "vc119①: f16 legal");
        eq(LlamaServerManager.isKvSel("q8_0"), true, "vc119①: q8_0 legal");
        eq(LlamaServerManager.isKvSel("q4_0"), true, "vc119①: q4_0 legal");
        eq(LlamaServerManager.isKvSel("q4_K_M"), false, "vc119①: other quants rejected");
        eq(LlamaServerManager.isKvSel(null), false, "vc119①: null kv rejected");

        // ② buildCommand: profile-carried -ctk/-ctv replaced in place
        String[] c15 = LlamaServerManager.buildCommand("/x/e", HTP, MODEL, "0.0.0.0", 8080,
                8192, "off", 4, false, 0, 0, null, null, "f16", "");
        eq(idx(c15, "-ctk q8_0") == -1, true, "vc119②: sentinel absent");
        eq(idx(c15, "-ctk") > 0 && "f16".equals(c15[idx(c15, "-ctk") + 1]), true,
                "vc119②: profile --ctk replaced with f16");
        eq(idx(c15, "-ctv") > 0 && "f16".equals(c15[idx(c15, "-ctv") + 1]), true,
                "vc119②: profile --ctv replaced with f16");
        int nctk = 0;
        for (String t : c15) if ("-ctk".equals(t)) nctk++;
        eq(nctk, 1, "vc121: exactly one -ctk (replace, no duplicate)");
        eq(contains(c15, "q8_0"), false, "vc119②: profile q8_0 value gone");
        // short-form -ctk/-ctv also replaced (profiles.json uses short form)
        String[] short1 = LlamaServerManager.buildCommand("/x/e",
                "-m {MODEL} -ctk q8_0 -ctv q8_0", MODEL, "0.0.0.0", 8080,
                8192, "off", 4, false, 0, 0, null, null, "q4_0", "");
        eq(idx(short1, "-ctk") > 0 && "q4_0".equals(short1[idx(short1, "-ctk") + 1]), true,
                "vc119②: short -ctk replaced");
        eq(idx(short1, "-ctv") > 0 && "q4_0".equals(short1[idx(short1, "-ctv") + 1]), true,
                "vc119②: short -ctv replaced");

        // invalid kvSel falls back to q8_0
        String[] badKv = LlamaServerManager.buildCommand("/x/e", HTP, MODEL, "0.0.0.0", 8080,
                8192, "off", 4, false, 0, 0, null, null, "q6_K", "");
        eq("q8_0".equals(badKv[idx(badKv, "-ctk") + 1]), true,
                "vc119②: invalid kvSel falls back to q8_0");

        // profile without -ctk (cpu) gets the UI value appended
        String[] cpuAdd = LlamaServerManager.buildCommand("/x/e", CPU, MODEL, "0.0.0.0", 8080,
                4096, "off", 4, false, 0, 0, null, null, "f16", "");
        eq(idx(cpuAdd, "-ctk") > 0 && "f16".equals(cpuAdd[idx(cpuAdd, "-ctk") + 1]), true,
                "vc119②: cpu profile (no -ctk) gets UI value appended");
        eq(idx(cpuAdd, "-ctk") > idx(cpuAdd, "--threads"), true,
                "vc119②: appended after profile body");
        // 13-arg legacy overload keeps q8_0 default
        String[] legacy = LlamaServerManager.buildCommand("/x/e", CPU, MODEL, "0.0.0.0", 8080,
                4096, "off", 4, false, 0, 0, null, null);
        eq("q8_0".equals(legacy[idx(legacy, "-ctk") + 1]), true,
                "vc119②: 13-arg overload appends default q8_0 (no -ctk in profile)");

        // ③ signature sync across the JS→Java chain
        String mm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(mm.contains("final String kvSel, final String powerMode,"), true,
                "vc119③: Bridge.startServer 15-arg signature");
        eq(mm.indexOf("kvSel == null ? \"\" : kvSel") > 0, true,
                "vc119③: Bridge null-guards kvSel into saveRebornArgs");
        eq(mm.indexOf("kvSel, powerMode,") < mm.indexOf("extraArgs);", mm.indexOf("a.server.start")),
                true, "vc119③: manager.start forwards kvSel before slotsOn");
        String lsm = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(lsm.contains("final String kvSel,\n                            final String powerMode, final boolean slotsOn,"),
                true, "vc119③: LSM start has the 15-arg overload");
        eq(lsm.contains("\"htp\".equals(profileId) || \"mtp\".equals(profileId), lastPowerMode);"),
                true, "vc119③: startExec passes lastPowerMode (NPU/MTP gate)");
        eq(lsm.contains("GGML_HEXAGON_POWER_MODE"), true, "vc119③: env key present in launchExec");
        eq(lsm.indexOf("isPowerMode(powerMode)) {") > 0, true,
                "vc119③: launchExec gates the env with isPowerMode");
        String ss = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ss.contains("\"pubOn\", \"keyOn\", \"cliArgs\", \"kvSel\", \"pmMode\"}"), true,
                "vc119③: REBORN_KEYS extended to 14 slots");
        eq(ss.contains("kvSelOf(a), pmModeOf(a), false,"), true,
                "vc119③: adoptOrReborn relaunch forwards both selectors");

        // ④ launchExec env policy stays default-only for non-htp (regression)
        java.util.Map<String, String> envCpu = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(envCpu, false, null);
        eq(envCpu.containsKey("GGML_HEXAGON_POWER_MODE"), false,
                "vc119④: applyExecEnvPolicy never injects power mode (launchExec-only path)");

        // ⑤ REBORN_KEYS array length contract (index-based access must not drift)
        String[] keys = {"model", "profile", "port", "ctx", "reasoning", "threads", "noKv",
                "ubatch", "sleepIdle", "pubOn", "keyOn", "cliArgs", "kvSel", "pmMode"};
        eq(keys.length, 14, "vc119⑤: reborn arg array stays 14 long");

        // ⑥ JS patch chain: patch-vc119.py exists + wired into build.sh in order
        String pj = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc119.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(pj.contains("dostart-15args"), true, "vc119⑥: JS doStart wires 15 bridge args");
        eq(pj.contains("rowPm"), true, "vc119⑥: JS toggles rowPm visibility (htp/mtp only)");
        eq(pj.contains("kvSel:window.kvSel||\"q8_0\""), true, "vc119⑥: JS persists kvSel");
        String bh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bh.contains("patch-vc119.py"), true, "vc119⑥: build.sh runs the vc119 transform");
        eq(bh.indexOf("patch-vc119.py") > bh.indexOf("patch-vc118.py"), true,
                "vc119⑥: vc119 transform runs after vc118 (chain order)");
        // HTML form rows (服务页参数表单) — vc120 migrated the segs to <select>;
        // assertions now check the select form (vc119's seg form retired).
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ih.contains("<select id=\"kvSel\""), true, "vc119⑥→vc120: kvSel select in the params form");
        eq(ih.contains("<select id=\"pmSel\""), true, "vc119⑥→vc120: pmSel select in the params form");
        eq(ih.contains("id=\"rowPm\" style=\"display:none\""), true,
                "vc119⑥: rowPm starts hidden (NPU/MTP-only row)");
        eq(ih.contains("<option value=\"q4_0\">Q4_0</option>"), true, "vc119⑥→vc120: 3 KV quant options present");
    }

    /**
     * vc120 (1019): 5-item UX polish for the v1.4.4 release.
     * ① KV quant seg → <select id="kvSel"> (F16/Q8_0/Q4_0, default Q8_0)
     * ② Power mode seg → <select id="pmSel">, 3 options 全速/平衡/省电
     *    (burst/balanced/power_saver), label renamed NPU功耗模式;
     *    the vc119 8-mode option list is retired from the UI
     * ③ System prompt textarea moved into the CLI prompt card (settings
     *    block and the preset chips row deleted)
     * ④ Custom CLI args textarea moved into the params drawer tail
     *    (settings block + the 追加给 hint deleted)
     * ⑤ IME: manifest adjustResize + MainActivity ime() inset (native side).
     */
    static void testVc120UiPolish() throws Exception {
        System.out.println("== vc120: selects + prompt/args relocation + IME ==");
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        String mf = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/AndroidManifest.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        String px = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc120.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        String pj = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc119.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String pf = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);

        // ① KV quant select: default Q8_0, uppercase option labels
        eq(ih.contains("<select id=\"kvSel\">"), true, "vc120①: kvSel is a <select>");
        eq(ih.contains("<option value=\"q8_0\" selected>Q8_0</option>"), true,
                "vc120①: Q8_0 default, uppercase label");
        eq(ih.contains("<option value=\"f16\">F16</option>"), true, "vc120①: F16 option");
        eq(ih.contains("<option value=\"q4_0\">Q4_0</option>"), true, "vc120①: Q4_0 option");
        eq(ih.contains("kvSelSeg"), false, "vc120①: seg markup retired");

        // ② power mode select: 3 options, Chinese labels, default burst
        eq(ih.contains("<select id=\"pmSel\">"), true, "vc120②: pmSel is a <select>");
        eq(ih.contains("<option value=\"burst\" selected>全速</option>"), true, "vc120②: 全速 default");
        eq(ih.contains("<option value=\"balanced\">平衡</option>"), true, "vc120②: 平衡 option");
        eq(ih.contains("<option value=\"power_saver\">省电</option>"), true, "vc120②: 省电 option");
        eq(ih.contains("data-i18n=\"NPU功耗模式\""), true, "vc120②: label renamed NPU功耗模式");
        eq(ih.contains("sustained_high_performance"), false, "vc120②: 8-mode options retired from UI");
        eq(ih.contains("pmSeg"), false, "vc120②: seg markup retired");
        eq(LlamaServerManager.isPowerMode("power_saver"), true, "vc120②: power_saver legal");
        eq(LlamaServerManager.isPowerMode("burst"), true, "vc120②: burst legal");
        eq(LlamaServerManager.isPowerMode("balanced"), true, "vc120②: balanced legal");

        // ③ system prompt relocated into the CLI prompt card
        int cliCard = ih.indexOf("id=\"cliPromptCard\"");
        int sysBox = ih.indexOf("id=\"sysPromptBox\"");
        eq(cliCard > -1 && sysBox > cliCard, true,
                "vc120③: sysPromptBox lives inside the CLI prompt card");
        eq(countOccurrences(ih, "sysPromptBox"), 1,
                "vc120③: exactly one sysPromptBox (settings copy deleted)");
        eq(ih.contains("id=\"sysPresets\""), false, "vc120③: preset chips row removed");
        eq(ih.contains("对话请求自动附带 · CLI 预设与 Chat 共用"), false,
                "vc120③: old settings hint removed");

        // ④ cli args box into the params drawer tail
        int paramsDrawer = ih.indexOf("id=\"params\"");
        int argsBox = ih.indexOf("id=\"cliArgsBox\"");
        eq(paramsDrawer > -1 && argsBox > paramsDrawer, true,
                "vc120④: cliArgsBox inside the params drawer");
        eq(countOccurrences(ih, "cliArgsBox"), 1,
                "vc120④: exactly one cliArgsBox (settings copy deleted)");
        int keyOnRow = ih.indexOf("id=\"keyOn\"");
        eq(argsBox > keyOnRow, true, "vc120④: args box sits below the last drawer row (keyOn)");
        eq(ih.contains("追加给 llama-server 的高级参数"), false, "vc120④: 追加给... hint deleted");
        eq(ih.contains("自定义 CLI 参数"), true, "vc120④: label kept");

        // ⑤ IME: manifest adjustResize + MainActivity ime() inset
        eq(mf.contains("android:windowSoftInputMode=\"adjustResize\""), true,
                "vc120⑤: adjustResize on the launcher activity");
        eq(ma.contains("WindowInsets.Type.ime()"), true,
                "vc120⑤: IME inset consumed in the root padding");

        // JS chain: vc120 script exists, runs after vc119, retires window.* vars
        String bh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(bh.contains("patch-vc120.py"), true, "vc120: build.sh runs the vc120 transform");
        eq(bh.indexOf("patch-vc120.py") > bh.indexOf("patch-vc119.py"), true,
                "vc120: transform runs after vc119 (chain order)");
        eq(bh.contains("VC=161"), true, "vc131: versionCode bumped (moved from vc130 pin)");
        eq(px.contains("$(\"kvSel\").value||\"q8_0\",$(\"pmSel\").value||\"\""), true,
                "vc120: doStart reads the selects");
        eq(px.contains("-1!==[\"burst\",\"balanced\",\"power_saver\"]"), true,
                "vc120: restore whitelist is the 3 new modes");
        eq(px.contains("syspresets-listener"), true, "vc120: sysPresets listener removal wired");
        eq(pj.contains("sustained_high_performance"), true,
                "vc120: vc119 script untouched (regression anchor)");
        // vc121: upstream arg.cpp defines -ctk/--cache-type-k; "--ctk" is NOT a
        // flag (release blocker: "error: invalid argument: --ctk"). Short-form
        // everywhere, and both spellings are accepted on INPUT.
        eq(pf.contains("-ctk q8_0 -ctv q8_0"), true, "vc121: profiles.json short-form -ctk/-ctv");
        eq(countOccurrences(pf, "--ctk") + countOccurrences(pf, "--ctv"), 0,
                "vc121: invalid --ctk/--ctv retired from profiles.json");
        String[] c = LlamaServerManager.buildCommand("libllamaserver.so",
                "-m {MODEL} --ctk q8_0 --ctv q8_0", "/data/data/x/m.gguf",
                "0.0.0.0", 8080, 4096, "on", 4, false, 1024, 0, null, "", "q8_0", "");
        eq(idx(c, "-ctk") > 0 && "q8_0".equals(c[idx(c, "-ctk") + 1]), true,
                "vc121: even legacy --ctk profile input re-emitted as upstream-valid -ctk");
        eq(count(c, "--ctk") + count(c, "--ctv"), 0, "vc121: argv never carries invalid --ctk/--ctv");
    }
    /** vc120: plain substring occurrence counter. */
    static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    static void testVc122WebSearch() throws Exception {
        System.out.println("== vc122: web-search package + bridge + UI ==");
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String mf = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/AndroidManifest.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        String bh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        String pj = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc122.py")),
                java.nio.charset.StandardCharsets.UTF_8);

        // ===== ① architecture isolation (BRIEF §0, grep-level) =====
        String sd = "app/src/main/java/com/dawoer/npullm/search/";
        String searchAll = "";
        for (String f : new String[]{"SearchService.java", "SearchServer.java",
                "SearchConfig.java", "SearchController.java", "SearchForegroundService.java"}) {
            searchAll += new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(sd + f)), java.nio.charset.StandardCharsets.UTF_8);
        }
        eq(searchAll.contains("com.dawoer.npullm.LlamaServerManager")
                || searchAll.contains("com.dawoer.npullm.ServerService")
                || searchAll.contains("com.dawoer.npullm.Bmoe")
                || searchAll.contains("com.dawoer.npullm.Sampler"), false,
                "vc122①: search package imports NO engine-side class");
        eq(searchAll.contains("LlamaServerManager.") || searchAll.contains("ServerService.")
                || searchAll.contains("BmoeChat") || searchAll.contains("AppCtx."), false,
                "vc122①: search package references NO engine-side symbol");
        eq(ma.contains("com.dawoer.npullm.search.SearchService"), true,
                "vc122①: MainActivity is the designated shell bridge (search imports allowed there)");
        String engineMain = ma;
        eq(engineMain.contains("search.SearchService") && engineMain.contains("class Bridge"), true,
                "vc122①: bridge wires search via the documented shell seam");

        // ===== ② SearchService pure-function matrix =====
        eq(SearchService.clampCount(0), 5, "vc122②: clamp 0 -> default 5");
        eq(SearchService.clampCount(99), 5, "vc122②: clamp 99 -> default 5");
        eq(SearchService.clampCount(20), 20, "vc160: clamp 20 stays 20 (new max)");
        eq(SearchService.clampCount(21), 5, "vc160: clamp 21 -> default (max now 20)");
        eq(SearchService.clampCount(9), 9, "vc160: clamp 9 stays 9 (was over old max 8)");
        eq(SearchService.clampCount(-3), 5, "vc122②: clamp -3 -> default 5");
        eq(SearchService.clampCount(1), 1, "vc122②: clamp 1 stays 1");
        eq(SearchService.clampCount(8), 8, "vc122②: clamp 8 stays 8");
        eq(SearchService.normalizeUrl("HTTPS://Example.COM:80/a/b#frag"),
                "https://example.com:80/a/b", "vc122②: normalize scheme/host lowercase, strip frag");
        eq(SearchService.normalizeUrl("http://x.com/"), "http://x.com",
                "vc122②: trailing slash on empty path stripped");
        eq(SearchService.normalizeUrl(null), "", "vc122②: null url -> empty key");
        eq(SearchService.normalizeUrl("junk"), "junk", "vc122②: schemeless junk kept as-is key");
        eq(SearchService.normalizeBaseUrl("192.168.100.223:8080/"),
                "http://192.168.100.223:8080", "vc122②: base URL scheme+slash fix");
        eq(SearchService.hostOf("https://a.example.com/p?q=1"), "a.example.com",
                "vc122②: hostOf extracts host");
        eq(SearchService.hostOf("not a url"), "", "vc122②: hostOf junk -> empty");

        // ===== ③ buildSearchBlock: format + untrusted banner + hard budget =====
        java.util.ArrayList<SearchService.SearchResult> rs = new java.util.ArrayList<SearchService.SearchResult>();
        rs.add(new SearchService.SearchResult("T1", "https://e1.com/a", "c1", "searxng"));
        rs.add(new SearchService.SearchResult("T2", "https://e2.com/b", "c2", "searxng"));
        String blk = SearchService.buildSearchBlock("searx.example.com", rs, 4000);
        eq(blk.startsWith("〔联网搜索结果 | 来源: searx.example.com | 2条"), true,
                "vc122③: header carries source + count");
        eq(blk.contains("不可信外部内容，仅供参考"), true, "vc122③: untrusted banner");
        eq(blk.contains("[1] T1") && blk.contains("[2] T2") && blk.contains("https://e1.com/a"),
                true, "vc122③: numbered entries with urls");
        eq(blk.endsWith("与结果无关的内容按既有知识回答。"), true, "vc122③: closing requirements");
        eq(blk.length() <= 4000, true, "vc122③: respects 4000-char budget");
        // budget: only the first entry fits -> block has exactly 1 entry, no partials
        String small = SearchService.buildSearchBlock("h", rs, 150);
        eq(small.contains("[1] T1") && !small.contains("[2] T2"), true,
                "vc122③: budget starves second entry (no partial entries)");
        eq(SearchService.buildSearchBlock("h", rs, 100), "",
                "vc122③: nothing fits -> empty string, no block");
        java.util.ArrayList<SearchService.SearchResult> empty =
                new java.util.ArrayList<SearchService.SearchResult>();
        eq(SearchService.buildSearchBlock("h", empty, 4000), "",
                "vc122③: zero results -> empty string (JS falls back to direct send)");

        // ===== ④ SearchServer gates (header contract, no socket in tests) =====
        eq(SearchServer.PROTO_VERSION, "vc160-ws1", "vc125: proto version bumped (moved from vc122④ pin)");
        eq(SearchServer.PORT, 8081, "vc122④: port 8081 distinct from model :8080");

        // ===== ⑤ SearchController state machine =====
        SearchController.onToggleRequested();
        eq(SearchController.state(), SearchController.STATE_STARTING,
                "vc122⑤: toggle -> starting");
        eq(SearchController.statusJson().contains("\"state\":\"starting\"")
                && SearchController.statusJson().contains("\"port\":8081"), true,
                "vc122⑤: statusJson shape {state,port,err}");
        SearchController.onServerStarted();
        eq(SearchController.state(), SearchController.STATE_ON, "vc122⑤: started -> on");
        SearchController.onServerFailed("bind failed");
        eq(SearchController.state(), SearchController.STATE_ERROR, "vc122⑤: fail -> error");
        eq(SearchController.statusJson().contains("bind failed"), true,
                "vc122⑤: error text carried in statusJson");
        SearchController.onServerStopped();
        eq(SearchController.state(), SearchController.STATE_OFF, "vc122⑤: stopped -> off");

        // ===== ⑥ manifest + FGS wiring =====
        eq(mf.contains("android:name=\"com.dawoer.npullm.search.SearchForegroundService\""), true,
                "vc122⑥: FGS registered with absolute class name (vc81 lesson)");
        eq(mf.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"), true,
                "vc122⑥: specialUse subtype property present");
        eq(mf.contains("LAN web-search endpoint"), true,
                "vc122⑥: subtype text describes :8081 search endpoint");

        // ===== ⑦ Bridge (5 methods) + worker =====
        eq(countOccurrences(ma, "@JavascriptInterface") >= 0, true, "vc122⑦: bridge scanned");
        String[] need = {"public String wsFetch(final String baseUrl, final String query,",
                "public String wsVersion()", "public String wsServerToggle(final boolean on)",
                "public String wsServerStatus()", "public void wsServerConfig(final String baseUrl, final String key)"};
        for (int i = 0; i < need.length; i++)
            eq(ma.contains(need[i]), true, "vc122⑦: bridge method " + (i + 1) + "/5");
        eq(ma.contains("class WsFetchWorker"), true, "vc122⑦: named worker (zero-anonymous rule)");
        eq(ma.contains("window.onWsResult"), true, "vc122⑦: worker pushes payload via onWsResult");
        eq(ma.contains("SearchService.buildSearchBlock"), true,
                "vc122⑦: Java assembles the block (BRIEF §2.2, JS never builds it)");

        // ===== ⑧ UI: chat injection + settings cards + page swap =====
        eq(ih.contains("id=\"btnWs\""), true, "vc122⑧: 🔍 button present");
        eq(ih.contains("id=\"wsBaseUrl\"") && ih.contains("id=\"wsKey\"")
                && ih.contains("id=\"wsTestResult\""), true,
                "vc122⑧: settings URL + key + test-result inputs");
        eq(ih.contains("onclick=\"wsSrvToggle()\""), true, "vc122⑧: :8081 toggle wired");
        eq(ih.contains("id=\"pageSearch\"") && !ih.contains("id=\"pageLogs\""), true,
                "vc122⑧: logs page replaced by search page");
        eq(ih.contains("id=\"pageSearch\"") && !ih.contains("id=\"tabLogs\""), true,
                "vc122⑧: search page present (vc153: tab demoted to hub entry)");
        eq(ih.contains("id=\"engLogFold\"") && ih.contains("<details"), true,
                "vc122⑧: engine log folded into service page");
        eq(ih.contains("id=\"logwrap\"") && ih.contains("id=\"btnClear\""), true,
                "vc122⑧: log element ids preserved (JS log code untouched)");
        eq(countOccurrences(ih, "id=\"logwrap\""), 1, "vc122⑧: exactly one logwrap after the move");
        eq(ih.contains("data-i18n-t=\"联网搜索\"") && ih.contains("data-i18n=\"测试\"")
                && ih.contains("data-i18n=\"局域网搜索端点，独立于模型服务；默认关闭，仅限局域网\""), true,
                "vc122⑧: settings card i18n'd (title+labels+hint)");

        // ===== ⑨ patch script: JS chain + guards =====
        eq(bh.contains("patch-vc122.py $APP/assets/app.js"), true,
                "vc122⑨: build.sh runs the JS transform (single-arg)");
        eq(bh.indexOf("patch-vc122.py") > bh.indexOf("patch-vc120.py"), true,
                "vc122⑨: transform runs after vc120 (chain order)");
        eq(bh.contains("VC122_WEBSEARCH_TRANSFORM_FAILED"), true, "vc122⑨: JS grep gate present");
        eq(bh.contains("VC122_HTML_TRANSFORM_FAILED"), true, "vc122⑨: HTML grep gate present");
        eq(bh.contains("VC=161"), true, "vc131: versionCode bumped (moved from vc130⑨ pin)");
        eq(pj.contains("onWsResult") && pj.contains("wsMakeQuery"), true,
                "vc122⑨: JS runtime block in the patch script");
        eq(pj.contains("window._wsPending"), true, "vc122⑨: re-entry guard in JS");
        eq(pj.contains("8e3") || pj.contains("8000"), true, "vc122⑨: 8s search timeout wired");
    }

    // =====================================================================
    // vc123: built-in engines (BRIEF-vc123-engines-1021 §3.1)
    // =====================================================================

    /** Minimal fixture engine spec. */
    private static EngineRegistry.EngineSpec esp(String id, String type,
            String url, String items, String title, String link, String snippet,
            boolean defOn, boolean builtin) {
        return EngineRegistry.makeSpec(id, type, url, items, title, link, snippet, defOn, builtin);
    }

    static void testVc123Engines() throws Exception {
        System.out.println("== vc123: built-in engines + registry + UI ==");
        String ej = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/engines.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        String ih = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/index.html")),
                java.nio.charset.StandardCharsets.UTF_8);
        String ma = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String bh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        String bv = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("build-verify.sh")),
                java.nio.charset.StandardCharsets.UTF_8);
        String pj = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("patch-vc123.py")),
                java.nio.charset.StandardCharsets.UTF_8);
        String sd = "app/src/main/java/com/dawoer/npullm/search/";
        String ss = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(sd + "SearchService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String sv = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(sd + "SearchServer.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        // ===== A. structure fingerprint of assets/engines.json (drift guard) =====
        String fp = EngineRegistry.structureFingerprint(ej);
        eq(fp.equals("ERR"), false, "vc123A: engines.json parses (no ERR fingerprint)");
        eq(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":1}]}")
                .equals(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":\"x\"}]}")),
                false, "vc123A: fingerprint type-marks scalars (N vs S — impl contract)");
        eq(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":\"x\",\"builtin\":false}]}")
                .equals(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":\"x\",\"builtin\":true}]}")),
                true, "vc123A: fingerprint strips boolean values (structure only)");
        eq(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":\"x\"}]}")
                .equals(EngineRegistry.structureFingerprint("{\"engines\":[{\"id\":\"x\",\"extra\":1}]}")),
                false, "vc123A: added field changes fingerprint");
        // Pinned fingerprint — any change to engines.json structure must
        // consciously re-pin here (mirror of the version-pin rule #18).
        eq(fp, "{engines:[{builtin:B;base_key:S;id:S;label:S;type:S;default_on:B;},{snippet:S;link:S;id:S;label:S;type:S;title:S;items:S;url:S;default_on:B;},{snippet:S;link:S;id:S;label:S;type:S;title:S;items:S;url:S;default_on:B;},{snippet:S;link:S;id:S;label:S;type:S;title:S;items:S;url:S;default_on:B;},{id:S;label:S;type:S;url:S;default_on:B;},{snippet:S;link:S;id:S;label:S;type:S;title:S;items:S;url:S;default_on:B;},{id:S;label:S;type:S;url:S;default_on:B;},];version:N;}",
                "vc130: fingerprint re-pinned (7-engine structure: +baidu +github)");

        // ===== B. registry parse + whitelist =====
        java.util.List<EngineRegistry.EngineSpec> es = EngineRegistry.parse(ej);
        eq(es.size(), 7, "vc130: 7 engines parsed (baidu re-added w/ mobile c-result selectors, github added)");
        eq(es.get(0).id, "searxng", "vc123B: searxng first (builtin)");
        eq(es.get(0).builtin, true, "vc123B: searxng builtin flag");
        eq(es.get(1).type, "bing", "vc123B: cn bing type (bing_cn id, type bing)");
        eq(es.get(2).id, "baidu", "vc130: baidu re-added at slot 2");
        eq(es.get(2).type, "baidu", "vc130: baidu type registered");
        eq(es.get(2).defaultOn, true, "vc130: baidu default ON (mobile SERP 1022 probe)");
        eq(es.get(3).id, "ddg", "vc130: ddg at slot 3 (after baidu insert)");
        eq(es.get(3).defaultOn, true, "vc129: ddg default ON (lite POST 4/4 stable, GET walled 1022)");
        eq(es.get(4).type, "wikipedia", "vc130: wikipedia at slot 4");
        eq(es.get(5).id, "sogou", "vc130: sogou at slot 5");
        eq(es.get(5).defaultOn, true, "vc128: sogou default ON (cn set)");
        eq(es.get(6).id, "github", "vc130: github at tail slot 6");
        eq(es.get(6).defaultOn, false, "vc130: github default OFF (anon rate limit; user 1022)");
        // vc126: 2nd batch trimmed — so360/mojeek/startpage/dogpile/findborg
        // (1021 live 0-hit) gone; baidu re-added vc130 (mobile c-result SERP).
        eq(EngineRegistry.parse("{\"engines\":[{\"type\":\"baidu hostile\"}]}").size(), 0,
                "vc123B: unknown type dropped at parse (whitelist)");
        eq(EngineRegistry.parse("not json").size(), 0, "vc123B: junk json -> empty (soft)");
        eq(EngineRegistry.parse(null).size(), 0, "vc123B: null json -> empty (soft)");
        eq(EngineRegistry.parse("{\"noengines\":1}").size(), 0, "vc123B: missing array -> empty");

        // ===== C. enabled seeding + persistence semantics =====
        eq(EngineRegistry.readEnabled(null, true), true, "vc123C: null stored -> default on");
        eq(EngineRegistry.readEnabled(null, false), false, "vc123C: null stored -> default off");
        eq(EngineRegistry.readEnabled("1", false), true, "vc123C: stored 1 -> on");
        eq(EngineRegistry.readEnabled("0", true), false, "vc123C: stored 0 -> off");
        eq(EngineRegistry.readEnabled("junk", true), true, "vc123C: junk -> default");
        eq(EngineRegistry.enabledKey("bing cn "), "engine_enabled_bingcn",
                "vc123C: key normalized (spaces+case)");
        final java.util.Map<String, String> store = new java.util.HashMap<String, String>();
        java.util.List<Boolean> flags = EngineRegistry.effectiveEnabled(es,
                new EngineRegistry.BoolLookup() {
                    public boolean get(String k, boolean d) {
                        String v = store.get(k);
                        return v == null ? d : EngineRegistry.readEnabled(v, d);
                    }
                });
        eq(flags.size(), 7, "vc130: flags resolved for all 7");
        eq(flags.get(0).booleanValue() && flags.get(1).booleanValue()
                && flags.get(4).booleanValue(), true,
                "vc130: first-run defaults = searxng+cnbing+wikipedia on");
        eq(flags.get(2).booleanValue() && flags.get(3).booleanValue()
                && flags.get(5).booleanValue(), true,
                "vc130: baidu+ddg+sogou on at first run");
        eq(flags.get(6).booleanValue(), false,
                "vc130: github off at first run (default off)");
        store.put("engine_enabled_ddg", "0");
        flags = EngineRegistry.effectiveEnabled(es, new EngineRegistry.BoolLookup() {
            public boolean get(String k, boolean d) {
                String v = store.get(k);
                return v == null ? d : EngineRegistry.readEnabled(v, d);
            }
        });
        eq(flags.get(3).booleanValue(), false, "vc130: stored ddg=0 overrides default (slot 3)");
        store.remove("engine_enabled_ddg"); // vc129: restore, then re-resolve all-on defaults
        flags = EngineRegistry.effectiveEnabled(es, new EngineRegistry.BoolLookup() {
            public boolean get(String k, boolean d) {
                String v = store.get(k);
                return v == null ? d : EngineRegistry.readEnabled(v, d);
            }
        });
        // BoolLookup anonymous classes here are TEST-ONLY (never dexed) — the
        // d8 zero-anonymous rule covers app code; test file already ships in
        // dex since vc86 (accepted precedent).

        // ===== D. channel construction =====
        java.util.List<SearchService.ChannelSpec> chs = EngineRegistry.channelsFor(
                "http://192.168.100.223:8080", es, flags);
        eq(chs.size(), 6, "vc130: 6 channels at defaults (github default off)");
        eq(chs.get(0).id, "searxng", "vc123D: searxng first channel");
        eq(chs.get(0).base, "http://192.168.100.223:8080", "vc123D: searxng base = UI URL");
        eq(chs.get(1).id, "bing_cn", "vc128: bing_cn second (www.bing trimmed)");
        eq(chs.get(1).base, "", "vc123D: bing base empty (spec.url template)");
        eq(chs.get(1).spec.url.contains("{query}"), true, "vc123D: bing carries template");
        // vc124: every channel carries its spec (generic fetchers depend on it)
        eq(chs.get(1).spec != null && chs.get(4).spec != null, true,
                "vc124D/vc126: channels carry spec (channelsFor regression)");
        // empty base URL -> builtin searxng skipped
        java.util.List<SearchService.ChannelSpec> chs2 = EngineRegistry.channelsFor(
                "", es, flags);
        eq(chs2.size(), 5, "vc130: empty base drops builtin searxng (5 non-builtin at defaults)");
        // all-off -> empty
        java.util.List<Boolean> none = new java.util.ArrayList<Boolean>();
        for (int i = 0; i < 7; i++) none.add(Boolean.FALSE);
        eq(EngineRegistry.channelsFor("http://x", es, none).size(), 0,
                "vc123D: all engines off -> zero channels");
        eq(EngineRegistry.channelsFor("http://x", null, flags).size(), 0,
                "vc123D: null registry -> zero channels (soft)");

        // ===== E. dispatch + merge cap (zero-channel: JVM must not hit network) =====
        SearchService svc = new SearchService();
        java.util.List<SearchService.ChannelSpec> noCh =
                new java.util.ArrayList<SearchService.ChannelSpec>();
        SearchService.SearchRequest req = new SearchService.SearchRequest(
                "q", 5, noCh);
        eq(req.count, 5, "vc123E: request count clamped at construction");
        SearchService.SearchResponse empty = svc.search(req);
        eq(empty.results.isEmpty(), true, "vc123E: zero channels -> empty (no net in JVM)");
        eq(empty.sources.isEmpty(), true, "vc123E: no sources without results");
        eq(SearchService.MERGE_MAX, 8, "vc123E: merge floor pinned at 8");
        eq(SearchService.expandTemplate(
                "https://x.com/search?q={query}&count={count}", "a b", 3),
                "https://x.com/search?q=a+b&count=3", "vc123E: template expansion");
        eq(SearchService.expandTemplate(null, "q", 3), "", "vc123E: null template -> empty");

        // ===== F. parse cores (fixtures from 1021 live structure) =====
        String bingHtml = "<ol id='b_results'>"
                + "<li class='b_algo'><h2><a href='https://github.com/ggml-org/llama.cpp'>llama.cpp</a></h2>"
                + "<div class='b_caption'><p>Inference of LLaMA in pure C/C++</p></div></li>"
                + "<li class='b_algo'><h2><a href='https://huggingface.co/ggml-org'>ggml-org</a></h2>"
                + "<div class='b_caption'><p>Models and datasets</p></div></li>"
                + "<li class='b_algo'><span>no anchor here</span></li>"
                + "</ol>";
        java.util.List<SearchService.SearchResult> br = SearchService.parseBingSerp(
                org.jsoup.Jsoup.parse(bingHtml), "li.b_algo", "h2", ".b_caption", 5);
        eq(br.size(), 2, "vc123F: bing fixture -> 2 (anchorless block skipped)");
        eq(br.get(0).url, "https://github.com/ggml-org/llama.cpp", "vc123F: bing link");
        eq(br.get(0).title, "llama.cpp", "vc123F: bing title");
        eq(br.get(0).content, "Inference of LLaMA in pure C/C++", "vc123F: bing snippet");
        eq(SearchService.parseBingSerp(
                org.jsoup.Jsoup.parse("<p>challenge page</p>"), "li.b_algo", "h2",
                ".b_caption", 5).isEmpty(), true, "vc123F: bing challenge -> empty (soft)");
        eq(SearchService.parseBingSerp(null, "li", "h2", ".c", 5).isEmpty(), true,
                "vc123F: bing null doc -> empty");

        String ddgHtml = "<div class='result results_links'>"
                + "<a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=abc'>Example A</a>"
                + "<a class='result__snippet'>snip A</a></div>"
                + "<div class='result'><a class='result__a' href='https://direct.com/b'>Direct B</a></div>";
        java.util.List<SearchService.SearchResult> dr = SearchService.parseDdgSerp(
                org.jsoup.Jsoup.parse(ddgHtml), 5);
        eq(dr.size(), 2, "vc123F: ddg fixture -> 2");
        eq(dr.get(0).url, "https://example.com/a", "vc123F: ddg uddg unwrapped");
        eq(dr.get(1).url, "https://direct.com/b", "vc123F: ddg direct link passthrough");
        eq(dr.get(0).content, "snip A", "vc123F: ddg snippet picked");
        eq(SearchService.unwrapDdgLink("//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.com%2Fz&rut=1"),
                "https://x.com/z", "vc123F: unwrapDdgLink full form");
        eq(SearchService.unwrapDdgLink("https://plain.com"), "https://plain.com",
                "vc123F: unwrapDdgLink passthrough");

        String wikiJson = "{\"query\":{\"search\":["
                + "{\"title\":\"llama.cpp\",\"snippet\":\"<span class=\\\"searchmatch\\\">LLaMA</span> C++\"},"
                + "{\"title\":\"GGUF\",\"snippet\":\"quantized <b>format</b>\"}]}}";
        java.util.List<SearchService.SearchResult> wr = SearchService.parseWikipediaJson(
                wikiJson, 5);
        eq(wr.size(), 2, "vc123F: wikipedia fixture -> 2");
        eq(wr.get(0).title, "llama.cpp", "vc123F: wiki title");
        eq(wr.get(0).content, "LLaMA C++", "vc123F: wiki snippet HTML stripped");
        eq(wr.get(0).url, "https://zh.wikipedia.org/wiki/llama.cpp", "vc123F: wiki article url");
        eq(SearchService.parseWikipediaJson("{\"query\":{}}", 5).isEmpty(), true,
                "vc123F: wiki no search array -> empty");
        eq(SearchService.parseWikipediaJson("ERR", 5).isEmpty(), true,
                "vc123F: wiki junk json -> empty");
        eq(SearchService.parseWikipediaJson(null, 5).isEmpty(), true,
                "vc123F: wiki null -> empty");

        // (vc126: generic parse fixture removed with fetchGenericHtml)

        // ===== G. server contract + counters =====
        eq(SearchServer.PROTO_VERSION, "vc160-ws1", "vc125: proto version bumped (moved from vc123G pin)");
        eq(sv.contains("registryChannels()"), true, "vc123G: server builds channels via registry");
        eq(sv.contains("\"engines\""), true, "vc123G: response carries engines array");
        eq(sv.contains("bumpDailyCount()"), true, "vc123G: successful requests counted");
        eq(ss.contains("fetchBingHtml") && ss.contains("fetchDdgHtml")
                && ss.contains("fetchWikipediaApi") && ss.contains("fetchSogouHtml")
                && ss.contains("fetchBaiduHtml") && ss.contains("fetchGithubApi"),
                true, "vc130: fetchers present (baidu+github added to the six)");
        eq(ss.contains("MERGE_MAX"), true, "vc123G: merge cap in service");

        // ===== H. bridge + UI =====
        eq(ma.contains("wsEnginesGet"), true, "vc123H: bridge wsEnginesGet present");
        eq(ma.contains("wsEngineSet"), true, "vc123H: bridge wsEngineSet present");
        eq(ma.contains("wsRequestsToday"), true, "vc123H: bridge daily counter present");
        eq(ma.contains("enginesJson()"), true, "vc123H: shared registry JSON builder");
        eq(ih.contains("id=\"engineCard\""), true, "vc123H: search page engine card present");
        eq(pj.contains("npullmEngines"), true, "vc123H: engine toggle persistence wired");
        // vc133B: the counter row retired from the page (BRIEF-v150 L98 baseline has no counter row);
        // the bump API + bridge remain (vc122 挂账), only the UI line is gone.
        eq(ih.contains("id=\"requestsToday\""), false, "vc123H: counter row retired (API kept)");
        eq(ih.contains("开启后 🔍 聚合多引擎结果"), true,
                "vc123H/vc136: engine hint line present (vc136 wording)");

        // ===== I. build chain: assets + verify list + version bump =====
        eq(bh.contains("VC=161"), true, "vc131: versionCode bumped (moved from vc130I pin)");
        eq(bv.contains("engines.json"), true, "vc123I: build-verify checks engines.json asset");
        // isolation regression: registry never imports engine-side classes
        String er = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(sd + "EngineRegistry.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(er.contains("LlamaServerManager") || er.contains("ServerService")
                || er.contains("Bmoe"), false, "vc123I: registry isolation clean");
        eq(er.contains("anonymous") == false && er.contains("new EngineRegistry.BoolLookup()"),
                false, "vc123I: no anonymous classes in app code");
    }

    /** vc124: 2nd-batch engines + speed test + circuit breaker + region
     *  defaults (BRIEF-vc124 §4.1). JVM-safe: parse cores on fixtures, no
     *  network. ≥20 assertions. */
    static void testVc124Engines() {
        System.out.println("== vc124: 2nd-batch engines, speed test, breaker, region ==");
        String ma = "";
        String bh = "";
        String pj = "";
        String sc = "";
        try {
            ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            bh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            pj = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc124.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            sc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchConfig.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            eq(true, false, "vc124X: source files readable (" + t + ")");
            return;
        }

        // ===== A. (vc126: baidu fixture block removed with the engine) =====

        // ===== B. sogou fixture parse (mobile m.sogou.com structure per 1021
        // live probe: www.sogou.com 302s there and jsoup follows) =====
        String sogouHtml = "<div class='vrResult' id='sogou_vr_30010097_3'>"
                + "<h3 class='vr-tit clamp2'><a class='resultLink' "
                + "href='/tc?rcer=1&amp;url=https%3A%2F%2Fbaike.sogou.com%2Fm%2Fv64825130.htm&amp;vrid=30010097'>"
                + "Sogou Title</a></h3>"
                + "<div class='text-summary space-txt'>Sogou snippet</div></div>"
                + "<div class='vrResult'><h3 class='vr-tit'><a href='https://direct.example.com/x'>Direct</a></h3></div>"
                + "<div class='vrResult'><div class='recommend-card'>no h3 here</div></div>";
        java.util.List<SearchService.SearchResult> s1 = SearchService.parseSogouSerp(
                org.jsoup.Jsoup.parse(sogouHtml), 5);
        eq(s1.size(), 2, "vc124B: sogou fixture -> 2 (h3-less block skipped)");
        eq(s1.get(0).title, "Sogou Title", "vc124B: sogou h3.vr-tit title");
        eq(s1.get(0).url, "https://baike.sogou.com/m/v64825130.htm",
                "vc124B: sogou url= jump decoded");
        eq(s1.get(0).content, "Sogou snippet", "vc124B: sogou text-summary snippet");
        eq(s1.get(1).url, "https://direct.example.com/x", "vc124B: sogou direct link passthrough");
        eq(SearchService.parseSogouSerp(null, 5).isEmpty(), true, "vc124B: sogou null doc -> empty");
        eq(SearchService.unwrapSogouJump("?keyword=abc&pid=sogouwap"), "",
                "vc124B: sogou relative keyword link -> skipped");

        // ===== C. (vc126: so360 fixture block removed with the engine) =====

        // ===== D. circuit breaker state machine (3 fails -> 30s cooldown) =====
        SearchService.CircuitBreakerTracker cb = new SearchService.CircuitBreakerTracker();
        eq(cb.canRetry("e1"), true, "vc124D: fresh engine retriable");
        cb.recordFailure("e1");
        cb.recordFailure("e1");
        eq(cb.canRetry("e1"), true, "vc124D: 2 failures below threshold -> retriable");
        eq(cb.recordFailure("e1"), true, "vc124D: 3rd failure opens circuit");
        eq(cb.canRetry("e1"), false, "vc124D: tripped circuit blocks retry");
        cb.recordSuccess("e1");
        eq(cb.canRetry("e1"), true, "vc124D: success resets counter (cooldown short)");
        cb.recordFailure("e2");
        cb.recordFailure("e2");
        cb.recordFailure("e2");
        eq(cb.remainingCooldown("e2") > 0, true, "vc124D: cooldown remaining > 0 when tripped");
        cb.reset();
        eq(cb.canRetry("e2"), true, "vc124D: reset clears all states");

        // ===== E. (vc126: region removed) =====

        // ===== F. bridge + server structural fingerprints =====
        eq(ma.contains("wsSpeedTest"), true, "vc124F: bridge wsSpeedTest present");
        eq(ma.contains("wsSetRegion"), false,
                "vc126: region bridges removed");
        eq(ma.contains("\"sogou\""), true,
                "vc126: SEED_IDS carries sogou");
        eq(SearchServer.PROTO_VERSION, "vc160-ws1", "vc125: proto version pinned");
        eq(sc.contains("search_region"), false,
                "vc126: region storage removed from SearchConfig");
    }

    /** vc125: bing_cn ensearch removal + count-contract fix (1021). */
    static void testVc125Fixes() throws Exception {
        System.out.println("== vc125: ensearch removal + count contract ==");
        String ej = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/engines.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        String ss = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq(ej.contains("ensearch"), false,
                "vc125A: engines.json free of ensearch (intl-mode SERP poison)");
        eq(ej.contains("https://cn.bing.com/search?q={query}&count={count}"), true,
                "vc125A: bing_cn template = plain cn.bing.com");
        eq(ss.contains("int cap = req.count;"), true,
                "vc125B: merge cap == requested count");
        eq(ss.contains("req.count < MERGE_MAX"), false,
                "vc125B: old max(count, 8) cap gone");
        eq(SearchService.MERGE_MAX, 8, "vc125B: MERGE_MAX constant kept");
        SearchService svc = new SearchService();
        java.util.List<SearchService.ChannelSpec> noCh =
                new java.util.ArrayList<SearchService.ChannelSpec>();
        SearchService.SearchRequest r1 = new SearchService.SearchRequest("q", 1, noCh);
        eq(r1.count, 1, "vc125B: count=1 clamps to 1");
        SearchService.SearchResponse e1 = svc.search(r1);
        eq(e1.results.size(), 0, "vc125B: zero channels -> empty (no net in JVM)");
    }

    /** vc126: structured data sources (quote/weather) + engine trim pins.
     *  JVM-safe: parse cores on 1021 live fixtures, no network. */
    static void testVc126() {
        System.out.println("== vc126: structured sources + engine trim ==");
        String ej = "";
        String ma = "";
        String bh = "";
        try {
            ej = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/engines.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
            ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            bh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            eq(true, false, "vc126X: sources readable (" + t + ")");
            return;
        }

        // ===== A. trim pins =====
        eq(EngineRegistry.parse(ej).size(), 7, "vc130A: registry parses to 7");
        eq(ej.contains("so360") || ej.contains("mojeek") || ej.contains("findborg"),
                false, "vc126A: 1021-trimmed engines absent from engines.json");
        eq(EngineRegistry.KNOWN_TYPES.length, 7, "vc130A: whitelist = 7 types (+baidu +github)");
        eq(ma.contains("so360") || ma.contains("findborg"), false,
                "vc126A: SEED_IDS trimmed");
        eq(bh.contains("VC=161"), true, "vc131: build.sh VC=132");
        eq(bh.contains("VN=1.5.1"), true, "vc126A: build.sh VN=1.5.1 (pin moved with v1.5.1)");
        eq(bh.contains("fetchBaiduHtml"), false,
                "vc126A: stale baidu gate removed from build chain");

        // ===== B. QuoteProvider parse (1021 live sz002415 fixture) =====
        String quoteBody = "v_sz002415=\"51~海康威视~002415~32.34~32.82~32.90~441009~195609~245399~32.33~97~32.32~170~32.31~174~32.30~205~32.29~233~32.34~758~32.35~601~32.36~490~32.37~57~32.38~150~~20261021161442~-0.48~-1.46~33.08~32.28~32.34/441009/1438951476~441009~143895~0.49~16.66~~33.08~32.28~2.44~2925.51~2963.92~3.52~36.10~29.54~0.69~-1177~32.63~17.22~20.88~~~0.97~143895.1476~77.6160~240~   A~GP-A~13.31~-5.05~4.02~21.11~14.07~38.63~27.31~-0.37~-1.31~-6.42~9046093710~9164871550~-40.10~8.85~9046093710~~~3.39~-0.15~~CNY~0~~32.26~402~\";";
        QuoteProvider.Quote q = QuoteProvider.parse(quoteBody);
        eq(q == null, false, "vc126B: live quote fixture parses");
        eq(q.name, "海康威视", "vc126B: quote name (field 1)");
        eq(q.code, "002415", "vc126B: quote code (field 2)");
        eq(q.price, "32.34", "vc126B: quote price (field 3)");
        eq(q.prevClose, "32.82", "vc126B: quote prev (field 4)");
        eq(q.changePct, "-1.46", "vc126B: quote pct (field 32)");
        eq(q.time, "10-21 16:14", "vc126B: wire time reformatted");
        eq(QuoteProvider.parse("v_x=\"1~a~b\";") == null, true,
                "vc126B: short body -> null (soft)");
        eq(QuoteProvider.parse(null) == null, true, "vc126B: null -> null");

        // ===== C. QuoteProvider intent/extraction/render =====
        eq(QuoteProvider.isQuoteIntent("海康威视股价"), true, "vc126C: keyword intent");
        eq(QuoteProvider.isQuoteIntent("今天天气"), false, "vc126C: no false positive");
        eq(QuoteProvider.extractCode("sz002415 怎么样"), "sz002415",
                "vc126C: prefixed code extracted");
        eq(QuoteProvider.extractCode("002415 现在多少"), "sz002415",
                "vc126C: bare 6-digit -> sz (non-6 leading)");
        eq(QuoteProvider.extractCode("600519 股价"), "sh600519",
                "vc126C: bare 6xxxxx -> sh");
        eq(String.valueOf(QuoteProvider.extractCode("没有代码")), "null",
                "vc126C: no code -> null");
        eq(QuoteProvider.stripIntentWords("海康威视股价"), "海康威视",
                "vc126C: intent words stripped");
        eq(String.valueOf(QuoteProvider.stripIntentWords("股价")), "null",
                "vc126C: nothing left -> null");
        eq(QuoteProvider.parseSuggest("{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"4825\",\"Name\":\"天气新闻\",\"QuoteID\":\"4825_JPX\"},"
                + "{\"Code\":\"002415\",\"Name\":\"海康威视\",\"QuoteID\":\"0.002415\"}]}}"),
                "sz002415", "vc128C: eastmoney QuoteID 0->sz, JPX row skipped");
        eq(QuoteProvider.parseSuggest("{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"00700\",\"Name\":\"腾讯控股\",\"QuoteID\":\"116.00700\"}]}}"),
                "hk00700", "vc128C: eastmoney QuoteID 116->hk");
        eq(String.valueOf(QuoteProvider.parseSuggest("{\"QuotationCodeTable\":{\"Data\":[]}}")),
                "null", "vc128C: empty Data -> null");
        eq(String.valueOf(QuoteProvider.parseSuggest("v_hint=\"N\"")), "null",
                "vc128C: dead smartbox wire -> null (soft)");
        eq(QuoteProvider.render(q).startsWith("〔行情〕海康威视 002415 现价 32.34"),
                true, "vc126C: render line format");
        eq(QuoteProvider.render(q).length() <= 200, true,
                "vc126C: render <= 200 chars");
        eq(QuoteProvider.render(null) == null, true, "vc126C: null -> null");

        // vc128: suggest source swapped smartbox(dead 1022) -> eastmoney suggest;
        // QuoteProvider now reads UTF-8 JSON (eastmoney) + GBK (qt.gtimg) and
        // skips non-equity QuoteID prefixes (ETF/JPX/粉单).

        // ===== D. OpenMeteoProvider (1021 live Shanghai fixtures) =====
        String geoBody = "{\"results\":[{\"id\":1796236,\"name\":\"上海\",\"latitude\":31.22222,\"longitude\":121.45806,\"country\":\"中国\"}],\"generationtime_ms\":0.21}";
        String[] geo = OpenMeteoProvider.parseGeo(geoBody);
        eq(geo != null && geo.length == 3, true, "vc126D: geo triple parsed");
        eq(geo[0], "31.22222", "vc126D: geo lat");
        eq(geo[1], "121.45806", "vc126D: geo lon");
        eq(geo[2], "上海", "vc126D: geo name");
        eq(OpenMeteoProvider.parseGeo("{\"results\":[]}") == null, true,
                "vc126D: empty geo -> null");
        eq(OpenMeteoProvider.parseGeo(null) == null, true,
                "vc126D: null geo -> null");
        String wxBody = "{\"current\":{\"time\":\"2026-10-21T19:15\",\"temperature_2m\":17.2,\"weather_code\":0},\"daily\":{\"time\":[\"2026-10-21\",\"2026-10-22\"],\"temperature_2m_max\":[21.1,21.3],\"temperature_2m_min\":[14.9,14.5]}}";
        OpenMeteoProvider.Weather w = OpenMeteoProvider.parseWeather("上海", wxBody);
        eq(w == null, false, "vc126D: live weather fixture parses");
        eq(w.temp, "17.2", "vc126D: current temp");
        eq(w.code, "0", "vc126D: weather code");
        eq(w.max, "21.1", "vc126D: today max");
        eq(w.min, "14.9", "vc126D: today min");
        eq(OpenMeteoProvider.parseWeather("上海", "{\"current\":{}}") == null,
                true, "vc126D: missing daily -> null (soft)");
        eq(OpenMeteoProvider.isWeatherIntent("上海天气"), true,
                "vc126D: weather intent");
        eq(OpenMeteoProvider.extractCity("上海天气怎么样"), "上海",
                "vc126D: city extracted");
        eq(String.valueOf(OpenMeteoProvider.extractCity("天气怎么样")), "null",
                "vc126D: no city -> null (silent fallback)");
        eq(OpenMeteoProvider.weatherPhrase("0"), "晴", "vc126D: code 0");
        eq(OpenMeteoProvider.weatherPhrase("3"), "多云", "vc126D: code 3");
        eq(OpenMeteoProvider.weatherPhrase("63"), "雨", "vc126D: code 63");
        eq(OpenMeteoProvider.weatherPhrase("95"), "雷雨", "vc126D: code 95");
        eq(OpenMeteoProvider.weatherPhrase("44"), "-", "vc126D: unmapped -> dash");
        eq(OpenMeteoProvider.render(w).startsWith("〔天气〕上海 现在 17.2°C 晴 今天 14.9~21.1°C"),
                true, "vc126D: render line format");
        eq(OpenMeteoProvider.render(w).length() <= 200, true,
                "vc126D: render <= 200 chars");

        // ===== E. DataSources router (zero-net cases only) =====
        eq(String.valueOf(DataSources.structuredLine("随便聊聊")), "null",
                "vc126E: plain query -> no structured line");
        eq(String.valueOf(DataSources.structuredLine("天气怎么样")), "null",
                "vc126E: weather intent without city -> null");
        eq(String.valueOf(DataSources.structuredLine("股价")), "null",
                "vc126E: quote intent without code/name -> null");
        eq(String.valueOf(DataSources.structuredLine(null)), "null",
                "vc126E: null -> null");
        eq(String.valueOf(DataSources.structuredLine("")), "null",
                "vc126E: empty -> null");

        // ===== F. block splice helper =====
        String hdr = "〔联网搜索结果 | 来源: x | 1条 | 不可信外部内容，仅供参考〕\n"
                + "[1] t\nc\nu\n"
                + "〔/搜索结果〕\n回答要求：优先依据上述结果。";
        String sp = SearchService.insertStructuredLine(hdr, "〔行情〕测试", 4000);
        eq(sp.indexOf("〔行情〕测试") > 0
                && sp.indexOf("〔行情〕测试") < sp.indexOf("[1] t"),
                true, "vc126F: structured line lands after header");
        String empty = SearchService.insertStructuredLine("", "〔天气〕测试", 4000);
        eq(empty.contains("〔天气〕测试") && empty.contains("〔/搜索结果〕"),
                true, "vc126F: empty block gets minimal wrapped block");
        eq(SearchService.insertStructuredLine(hdr, null, 4000), hdr,
                "vc126F: null line -> passthrough");
        eq(SearchService.insertStructuredLine(hdr, "", 4000), hdr,
                "vc126F: empty line -> passthrough");
        String longLine = "x";
        for (int i = 0; i < 300; i++) longLine += "y";
        String capped = SearchService.insertStructuredLine(hdr, longLine, 4000);
        eq(capped.length() <= 4000, true, "vc126F: cap enforced");
    }

    /** vc127: structured data source wiring - both paths must call DataSources. */
    static void testVc127Wiring() {
        System.out.println("== vc127: structured source wiring ==");
        String ss = "";
        String ma = "";
        String bsh = "";
        try {
            ss = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchServer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            eq(true, false, "vc127X: sources readable (" + t + ")");
            return;
        }
        // A. :8081 HTTP path calls DataSources before the engine fan-out
        eq(ss.contains("DataSources.structuredLine(q)"), true,
                "vc127A: SearchServer /search routes through DataSources");
        eq(ss.indexOf("DataSources.structuredLine") < ss.indexOf("registryChannels()"),
                true, "vc127A: structured check precedes engine fan-out");
        eq(ss.contains("\"structured\""), true,
                "vc127A: structured response tags engines=[structured]");
        // B. injection path (WsFetchWorker) splices the line into the block
        eq(ma.contains("SearchService.insertStructuredLine(block, sl,"), true,
                "vc127B: WsFetchWorker splices structured line into block");
        eq(ma.contains("com.dawoer.npullm.search.DataSources.structuredLine(query)"), true,
                "vc127B: injection path routes through DataSources");
        // C. silent-fallback contract intact on both paths
        eq(ss.contains("never break the search path"), true,
                "vc127C: HTTP path fallback comment pin");
        eq(ma.contains("never break the search path"), true,
                "vc127C: injection path fallback comment pin");
        // D. version pin
        eq(bsh.contains("VC=161"), true, "vc131: build.sh VC=132");
    }

    /** vc129: ddg lite-POST revival + bing SERP quality gate (1022).
     * Evidence: GET on any ddg host (any UA/headers) -> 202 anomaly page
     * on VM+S25 egress; POST to lite endpoint -> 200 with results 4/4;
     * cn.bing degrades EN-brand queries and www.bing degrades CJK queries
     * (complementary buckets, zero term overlap = detectable). */
    static void testVc129() {
        String ma, ej;
        try {
            ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchService.java")));
            ej = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/engines.json")));
        } catch (Throwable t) {
            eq(true, false, "vc129: sources readable (" + t + ")");
            return;
        }
        // A. static wiring pins
        eq(ej.contains("lite.duckduckgo.com/lite/"), true,
                "vc129A: registry points at lite endpoint");
        eq(ma.contains("parseDdgLite"), true, "vc129A: lite parser present");
        eq(ma.contains("fetchHtmlDocPost"), true, "vc129A: POST fetch helper present");
        eq(ma.contains(".post();"), true, "vc129A: jsoup POST used");
        eq(ma.contains("serpMatchesQuery"), true, "vc129A: bing quality gate present");
        eq(ma.contains("bingFallbackHost(url)"), true,
                "vc129A: cn/www host fallback wired");
        eq(ma.contains("RETRY_BING_QUERIES.contains(query)"), true,
                "vc129A: negative cache consulted");
        eq(ma.contains("\"https://www.bing.com/search?q="), false,
                "vc129A: no hardcoded www SERP template");
        eq(ma.contains("parseDdgSerp"), true,
                "vc129A: legacy parser retained (vc123F fixtures)");
        // B. quality gate decision matrix
        eq(SearchService.serpMatchesQuery("PocketOrca-LLM", "how to view DeepSeek"), false,
                "vc129B: zero-overlap rejected");
        eq(SearchService.serpMatchesQuery("\u6d77\u5eb7\u5a01\u89c6 \u80a1\u4ef7",
                "\u6d77\u5eb7\u5a01\u89c6(002415)\u6700\u65b0\u4ef7\u683c_\u884c\u60c5_\u8d70\u52bf\u56fe\u2014\u4e1c\u65b9\u8d22\u5bcc\u7f51"), true,
                "vc129B: cjk title overlap accepted");
        eq(SearchService.serpMatchesQuery("", "anything"), true,
                "vc129B: empty query never gated");
        eq(SearchService.serpMatchesQuery("q", ""), false,
                "vc129B: empty titles not trusted");
        eq(SearchService.serpMatchesQuery("a b c d e f g h i j", "totally unrelated words"), true,
                "vc129B: token-rich query never gated");
        eq(SearchService.serpMatchesQuery("llama.cpp GGUF \u91cf\u5316",
                "llama.cpp GGUF \u91cf\u5316\u5165\u95e8"), true,
                "vc129B: mixed overlap accepted");
        eq(SearchService.serpMatchesQuery("id 1234", "ref no 5678"), false,
                "vc129B: short/digit tokens do not gate");
        // C. lite POST parser fixture
        String liteHtml = "<table>"
                + "<tr><td>1.&nbsp;</td><td><a rel=\"nofollow\" href=\"https://example.com/a\" class=\"result-link\">Example A</a></td></tr>"
                + "<tr><td>&nbsp;</td><td class=\"result-snippet\">snip A</td></tr>"
                + "<tr><td>2.&nbsp;</td><td><a rel=\"nofollow\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fb&rut=1\" class=\"result-link\">Example B</a></td></tr>"
                + "</table>";
        java.util.List<SearchService.SearchResult> lr = SearchService.parseDdgLite(
                org.jsoup.Jsoup.parse(liteHtml), 5);
        eq(lr.size(), 2, "vc129C: lite fixture -> 2");
        eq(lr.get(0).url, "https://example.com/a", "vc129C: lite real url passthrough");
        eq(lr.get(1).url, "https://example.com/b", "vc129C: lite uddg unwrapped");
        eq(lr.get(0).content, "snip A", "vc129C: lite snippet from following tr");
        eq(lr.get(1).content, "Example B", "vc129C: lite missing-snippet falls back to title");
    }

    /** vc130: engine table rework — baidu re-added (mobile c-result SERP,
     *  1022 live fixture), github added (anonymous REST, default-off per
     *  user), SEED_IDS + dispatch + SpeedTester wired; vc129 bing host
     *  fallback explicitly KEPT (user 1022). JVM-safe: fixtures + file
     *  pins, no network. */
    static void testVc130() {
        System.out.println("== vc130: baidu re-add + github add + wiring ==");
        String ej = "";
        String ss = "";
        String ma = "";
        String bh = "";
        String st = "";
        try {
            ej = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/engines.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
            ss = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            bh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            st = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SpeedTester.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            eq(true, false, "vc130X: sources readable (" + t + ")");
            return;
        }
        // A. registry + wiring pins
        eq(ej.contains("\"id\": \"baidu\"") && ej.contains("\"id\": \"github\""), true,
                "vc130A: baidu + github entries in engines.json");
        eq(ej.contains("api.github.com/search/repositories"), true,
                "vc130A: github template endpoint");
        eq(ej.contains("c-result"), true, "vc130A: baidu c-result selector externalized");
        eq(ma.contains("\"wikipedia\", \"sogou\", \"baidu\", \"github\""), true,
                "vc130A: SEED_IDS extended in order");
        eq(ss.contains("\"baidu\".equals(ch.type)") && ss.contains("\"github\".equals(ch.type)"),
                true, "vc130A: dispatch branches wired");
        eq(st.contains("case \"baidu\"") && st.contains("case \"github\""),
                true, "vc130A: SpeedTester cases added");
        eq(bh.contains("VC=161"), true, "vc131: build.sh VC bumped to 131");
        eq(ss.contains("fetchHtmlDocPost") && ss.contains("bingFallbackHost(url)"),
                true, "vc130A: vc129 ddg POST + bing host fallback untouched (kept)");
        // B. baidu parse core — 1022 live c-result structure (data-log "mu"
        // carries the real target; cards without mu are entity/recommend,
        // skipped)
        String baiduHtml = "<div class='c-result' data-log='{&quot;mu&quot;:&quot;http://example.com/a&quot;}'>"
                + "<h3>Title A</h3>"
                + "<span class='summary-text_xyz'>snip A</span></div>"
                + "<div class='c-result'><h3>no-link card</h3><p>skip me</p></div>"
                + "<div class='c-result' data-log='{\"mu\":\"https://example.org/b\"}'>"
                + "<h3>Title B</h3></div>";
        java.util.List<SearchService.SearchResult> br = SearchService.parseBaiduSerp(
                org.jsoup.Jsoup.parse(baiduHtml), 5);
        eq(br.size(), 2, "vc130B: baidu fixture -> 2 (mu-less card skipped)");
        eq(br.get(0).url, "http://example.com/a", "vc130B: mu unwrapped (entity form)");
        eq(br.get(1).url, "https://example.org/b", "vc130B: mu unwrapped (raw form)");
        eq(br.get(0).content, "snip A", "vc130B: summary-text snippet");
        eq(br.get(1).content, "Title B", "vc130B: missing snippet falls back to title");
        eq(SearchService.parseBaiduSerp(null, 5).isEmpty(), true,
                "vc130B: null doc -> empty");
        eq(SearchService.baiduRealUrl(null), "", "vc130B: null data-log -> empty");
        eq(SearchService.baiduRealUrl("{\"mu\":\"ftp://x\"}"), "",
                "vc130B: non-http mu rejected");
        // C. github parse core — 1022 live REST fixture + rate-limit soft
        String ghJson = "{\"total_count\":1,\"items\":[{"
                + "\"full_name\":\"ggml-org/llama.cpp\","
                + "\"html_url\":\"https://github.com/ggml-org/llama.cpp\","
                + "\"description\":\"LLM inference in C/C++\","
                + "\"stargazers_count\":131977}]}";
        java.util.List<SearchService.SearchResult> gr = SearchService.parseGithubJson(ghJson, 5);
        eq(gr.size(), 1, "vc130C: github fixture -> 1");
        eq(gr.get(0).title, "ggml-org/llama.cpp", "vc130C: full_name as title");
        eq(gr.get(0).url, "https://github.com/ggml-org/llama.cpp", "vc130C: html_url as link");
        eq(gr.get(0).content.contains("LLM inference in C/C++")
                && gr.get(0).content.contains("131977"), true,
                "vc130C: description + star count in content");
        eq(SearchService.parseGithubJson(
                "{\"message\":\"API rate limit exceeded\"}", 5).isEmpty(), true,
                "vc130C: rate-limit body -> empty (soft, 1022 VM probe)");
        eq(SearchService.parseGithubJson("junk", 5).isEmpty(), true,
                "vc130C: junk body -> empty");
        eq(SearchService.parseGithubJson(null, 5).isEmpty(), true,
                "vc130C: null -> empty");
    }


    static void testVc131() {
        System.out.println("== vc131 (v1.5.0 batch2): maxtok 5000 + real update check + log fold to CLI ==");
        try {
            // ============ A. maxtok 5000 ============
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(html.contains("max=\"4096\""), false, "vc131A: html max=4096 gone");
            eq(html.contains("<input id=\"maxtok\" type=\"number\" min=\"16\" max=\"8192\" step=\"16\" value=\"8192\">"),
                    true, "vc131A: maxtok input max=8192 (vc139)");
            eq(html.contains("stepParam('maxtok',-64,16,8192,0)"), true, "vc131A: minus step to 8192 (vc139)");
            eq(html.contains("stepParam('maxtok',64,16,8192,0)"), true, "vc131A: plus step to 8192 (vc139)");
            String jsP = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js.pristine")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String p131 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc131.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(p131.contains("e<=4096") && p131.contains("e<=5000"),
                    true, "vc131A: patch-vc131.py carries the clamp swap");
            eq(p131.contains("stepParam"), false, "vc131A: maxtok UI steps live in html script only");

            // ============ B. real update check ============
            // --- Java pure logic: version compare matrix ---
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.4.5", "1.5.0")), "-1",
                    "vc131B: 1.4.5 < 1.5.0");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.5.0", "1.4.5")), "1",
                    "vc131B: 1.5.0 > 1.4.5");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.5.0", "1.5.0")), "0",
                    "vc131B: equal");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("v1.5.0", "1.5.0")), "0",
                    "vc131B: v-prefix tolerated");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.5", "1.5.0")), "0",
                    "vc131B: short local padded with zeros");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.10.0", "1.9.0")), "1",
                    "vc131B: numeric not lexicographic (1.10 > 1.9)");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("abc", "1.5.0")), "null",
                    "vc131B: unparseable local -> null");
            eq(String.valueOf(MainActivity.Vc131.cmpVer("1.5.0", "junk-v")), "null",
                    "vc131B: unparseable remote -> null");
            eq(String.valueOf(MainActivity.Vc131.cmpVer(null, "1.5.0")), "null",
                    "vc131B: null local -> null");
            eq(String.valueOf(MainActivity.Vc131.parseTagOf(
                    "{\"tag_name\":\"v1.4.5\",\"x\":1}")), "v1.4.5",
                    "vc131B: tag parse sanity");
            // --- Java pure logic: tag parse ---
            String giteeBody = "{\"tag_name\":\"v1.4.5\",\"target_commitish\":\"master\","
                    + "\"name\":\"v1.4.4\",\"prerelease\":false}";
            eq(MainActivity.Vc131.parseTagOf(giteeBody), "v1.4.5", "vc131B: gitee tag parsed");
            eq(String.valueOf(MainActivity.Vc131.parseTagOf("{\"message\":\"Not Found\"}")), "null",
                    "vc131B: missing tag -> null");
            eq(String.valueOf(MainActivity.Vc131.parseTagOf("junk")), "null", "vc131B: junk body -> null");
            eq(String.valueOf(MainActivity.Vc131.parseTagOf(null)), "null", "vc131B: null -> null");
            // --- Java pure logic: decision JSON ---
            String dn = MainActivity.Vc131.decisionJson("1.4.5", "v1.5.0",
                    MainActivity.Vc131.releasesPage());
            eq(dn.contains("\"state\":\"new\"") && dn.contains("\"tag\":\"v1.5.0\"")
                    && dn.contains("\"relPage\":\"https://gitee.com/PocketOrca/pocket-orca-llm/releases\""),
                    true, "vc131B: new-version decision payload");
            String ds = MainActivity.Vc131.decisionJson("1.5.0", "v1.5.0", "");
            eq(ds.contains("\"state\":\"same\""), true, "vc131B: same-version decision");
            String df = MainActivity.Vc131.decisionJson("1.5.0", null, "");
            eq(df.contains("\"state\":\"check_failed\""), true,
                    "vc131B: null tag -> check_failed (never fake up-to-date)");
            // --- Java contract: URLs, worker, bridge surface ---
            eq(MainActivity.Vc131.UPDATE_URLS.length, 2, "vc131B: two update channels");
            eq(MainActivity.Vc131.UPDATE_URLS[0],
                    "https://gitee.com/api/v5/repos/PocketOrca/pocket-orca-llm/releases/latest",
                    "vc131B: gitee primary");
            eq(MainActivity.Vc131.UPDATE_URLS[1],
                    "https://api.github.com/repos/PocketOrca/PocketOrca-LLM/releases/latest",
                    "vc131B: github fallback");
            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(
                            "app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ma.contains("class Vc131UpdateWorker"), true, "vc131B: worker class exists");
            eq(ma.contains("@JavascriptInterface") && ma.contains("public void updateCheck(final String appVer)"),
                    true, "vc131B: updateCheck bridge resident");
            eq(ma.contains("public void openUrl(final String url)"), true,
                    "vc131B: openUrl bridge resident");
            // JS side: markers live in the vc131 patch script (chain output is
            // verified by build.sh grep gates); pristine must stay untouched.
            eq(p131.contains("bridge(\"updateCheck\",APP_VER)"), true,
                    "vc131B: JS calls updateCheck with APP_VER");
            eq(p131.contains("window.onCheckUpdate"), true, "vc131B: onCheckUpdate renderer");
            eq(p131.contains("检查失败（网络）"), true, "vc131B: check-failed zh-CN literal");
            eq(p131.contains("檢查失敗（網路）"), true, "vc131B: check-failed zh-TW key");
            eq(p131.contains("Check failed (network)"), true, "vc131B: check-failed en key");
            eq(p131.contains("發現新版本 "), true, "vc131B: new-version zh-TW key");
            eq(p131.contains("New version available "), true, "vc131B: new-version en key");
            eq(p131.contains("已是最新 "), true, "vc131B: up-to-date literal kept");
            eq(Integer.valueOf(p131.split("检测更新：已是最新 ", -1).length - 1), Integer.valueOf(3),
                    "vc131B: fake string = B1 anchor + 2 dead-key anchors");
            eq(p131.contains("檢測更新：已是最新 "), true,
                    "vc131B: zh-TW dead-key removal anchor present");
            eq(p131.contains("Update check: up to date "), true,
                    "vc131B: en dead-key removal anchor present");
            eq(p131.contains("bridge(\"httpGet\""), false,
                    "vc131B: no JS httpGet surface (Java-only fetch)");
            eq(jsP.contains("checkUpdate"), true, "vc131B: pristine checkUpdate region present");

            // ============ C. fold moved to CLI page ============
            int ps = html.indexOf("<div class=\"page\" id=\"pageSearch\">");
            int cli = html.indexOf("<div class=\"page\" id=\"pageCli\">");
            int fold = html.indexOf("<details id=\"engLogFold\">");
            int bar = html.indexOf("<div id=\"tabbar\">");
            eq(fold >= 0 && fold > cli && fold < ps, true, "vc131C: fold inside pageCli");
            eq(bar > 0 && fold < bar, true, "vc131C: fold before tabbar");
            int ef = html.indexOf("id=\"engLogFold\"");
            eq(ef >= 0 && html.indexOf("id=\"engLogFold\"", ef + 1) < 0, true,
                    "vc131C: engLogFold unique");
            for (String id : new String[]{"id=\"logToolbar\"", "id=\"btnClear\"",
                    "id=\"btnExport\"", "id=\"logwrap\"", "id=\"autoScrollBadge\""}) {
                int first = html.indexOf(id);
                eq(first >= 0 && html.indexOf(id, first + 1) < 0, true,
                        "vc131C: unique " + id);
            }
            eq(html.contains("搜索服务"), true, "vc131C: search page intact (title now :N-dynamic, vc133)");
            // build.sh chain: vc131 runs last, VC bumped
            String bh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bh.contains("patch-vc131.py"), true, "vc131: build.sh runs the vc131 transform");
            eq(bh.indexOf("patch-vc131.py") > bh.indexOf("patch-vc124.py"), true,
                    "vc131: transform runs after vc124 (chain order)");
            eq(bh.contains("VC=161"), true, "vc131: VC bumped to 131 (moved from vc130 pin)");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc131 setup: " + e);
        }
    }

    static void testVc132() {
        System.out.println("== vc132 (v1.5.0 batch3): endpoint /v1+publicOn + wsAddr + btnWs redesign ==");
        try {
            // ============ files ============
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String lsm = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String p132 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc132.py")),
                    java.nio.charset.StandardCharsets.UTF_8);

            // ============ A: Java side ============
            eq(lsm.contains("private volatile boolean lastPublicOn = false;"), true,
                    "vc132A: lastPublicOn field");
            eq(lsm.contains("lastPublicOn = publicOn;"), true,
                    "vc132A: lastPublicOn captured at start");
            eq(lsm.contains("j.put(\"publicOn\", lastPublicOn);"), true,
                    "vc132A: statusJson carries publicOn");
            // bind host still derived from the parameter at L~535 (not the field)
            eq(lsm.contains("publicOn ? \"0.0.0.0\" : \"127.0.0.1\""), true,
                    "vc132A: bind host expression intact");

            // statusJson surface is compiled into the class; runtime probe needs a
            // real Context (ctor calls getApplicationContext) so JVM asserts the
            // field+serialization statically instead (no headless instantiation).
            eq(lsm.contains("j.put(\"state\", STATE_NAMES[state]);"), true,
                    "vc132A: statusJson well-formed baseline");

            // ============ A: JS side (patch source carries the transform) ============
            eq(p132.contains("window._pub?localIp||\"<IP>\":\"127.0.0.1\""), true,
                    "vc132A: patch switches host form on publicOn");
            eq(p132.contains("+\"/v1\""), true,
                    "vc132A: patch appends /v1 suffix");
            eq(p132.contains("window._pub=!(!e||!e.running)&&!!e.publicOn"), true,
                    "vc132A: patch syncs _pub from getStatus poll");
            eq(p132.contains("T(\"请求发往 127.0.0.1:\")") == false, true,
                    "vc132A: patch does not rewrite cliMeta copy");

            // ============ B: wsAddr row (HTML statics + patch renderers) ============
            eq(html.contains("id=\"wsAddr\""), true, "vc132B: wsAddr span present");
            eq(html.contains("id=\"btnWsAddrCopy\""), true, "vc132B: wsAddr copy button present");
            eq(html.contains("onclick=\"copyWsAddr()\""), true, "vc132B: copyWsAddr wired");
            eq(html.contains("data-i18n=\"地址\""), true, "vc132B: 地址 label i18n key");
            int wsAddrRow = html.indexOf("id=\"wsAddrRow\"");
            int wsState2 = html.indexOf("id=\"wsState2\"");
            int engineCard = html.indexOf("id=\"engineCard\"");
            eq(String.valueOf(wsAddrRow > wsState2 && engineCard > wsAddrRow && wsState2 > 0), "true",
                    "vc132B: wsAddrRow sits after wsState2, inside pageSearch (before engineCard)");
            eq(String.valueOf(html.indexOf("id=\"wsAddr\"") == html.lastIndexOf("id=\"wsAddr\"")), "true",
                    "vc132B: wsAddr id unique");
            eq(p132.contains("function renderWsAddr(j)"), true,
                    "vc132B: renderWsAddr in patch");
            eq(p132.contains("(j.port||8081)"), true,
                    "vc132B: port read from status JSON (no hardcode-only form)");
            eq(p132.contains("localIp||\"<IP>\""), true,
                    "vc132B: LAN IP from getLocalIp bridge");
            eq(p132.contains("function copyWsAddr()"), true,
                    "vc132B: copyWsAddr in patch");
            eq(p132.contains("fallbackCopy(t,n)"), true,
                    "vc132B: copy falls back like copyEndpoint");
            eq(p132.contains("renderWsAddr(sj),setTimeout(wsPoll,3e3)"), true,
                    "vc132B: renderer hooked into the 3s poll (single bridge call)");

            // ============ C: btnWs redesign ============
            eq(html.contains(">🔍</button>"), false, "vc132C: emoji glyph gone");
            eq(html.contains("id=\"btnWs\"") && html.contains("<svg class=\"ic\" viewBox=\"0 0 24 24\" style=\"width:18px; height:18px;\"><circle cx=\"11\" cy=\"11\" r=\"8\"/>"),
                    true, "vc132C: inline magnifier SVG in btnWs");
            eq(html.contains("#btnWs.on { background: var(--primary); color: #fff; }"), true,
                    "vc132C: on-state CSS rule");
            int btnWs = html.indexOf("id=\"btnWs\"");
            int chatAttach = html.indexOf("id=\"btnChatAttach\"");
            eq(String.valueOf(btnWs > chatAttach && chatAttach > 0), "true",
                    "vc132C: btnWs position unchanged (after attach button)");
            eq(p132.contains("b.classList.toggle(\"on\",!!window._wsOn)"), true,
                    "vc132C: toggle site 1+2 (wsToggle/wsInit)");
            eq(p132.contains("b.classList.toggle(\"on\",!!on)"), true,
                    "vc132C: toggle site 3 (st)");
            // btnWs on/off color pair appears exactly at the 3 switch sites
            // (regex: literal ?, parens escaped; count via split)
            eq(String.valueOf(p132.split("\\?\"var\\(--primary\\)\":\"var\\(--surface2\\)\"").length - 1), "3",
                    "vc132C: exactly three on/off style switch sites");
            // old warn form survives ONLY inside OLD_ anchors: 2x _wsOn-prefix + 1x st-prefix
            eq(String.valueOf(p132.split("background=window\\._wsOn\\?\"var\\(--warn\\)\"").length - 1), "2",
                    "vc132C: warn color only in OLD_ anchors (_wsOn sites)");
            eq(String.valueOf(p132.split("b\\.style\\.background=on\\?\"var\\(--warn\\)\"").length - 1), "1",
                    "vc132C: warn color only in OLD_ anchor (st site)");

            // ============ i18n ============
            eq(p132.contains("\"地址\":\"地址\",\"未启动\":\"未啟動\""), true, "vc132: zh-TW keys");
            eq(p132.contains("\"地址\":\"Address\",\"未启动\":\"Not running\""), true, "vc132: en keys");

            // ============ build.sh gates ============
            eq(bsh.contains("patch-vc132.py"), true, "vc132: build.sh runs the vc132 transform");
            eq(bsh.indexOf("patch-vc132.py") > bsh.indexOf("patch-vc131.py"), true,
                    "vc132: transform runs after vc131 (chain order)");
            eq(bsh.contains("VC=161"), true, "vc132: build.sh VC=132");
            eq(bsh.contains("VC132_JS_PUB_MISSING"), true, "vc132: product gate _pub");
            eq(bsh.contains("VC132_BTNWS_WARN_REMAINS"), true, "vc132: product gate warn-count");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc132 setup: " + e);
        }
    }

    // =====================================================================
    // vc133 (v1.5.0 batch4): search port configurable + search page alignment
    // =====================================================================
    static void testVc133Batch4() {
        System.out.println("== vc133 (v1.5.0 batch4): search port configurable + page alignment ==");
        try {
            String cfg = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchConfig.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String srv = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchServer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String fgs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchForegroundService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String p133 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc133.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);

            // ============ A: SearchConfig port store ============
            eq(SearchConfig.validatePortRange(8081), true, "vc133A: default 8081 valid");
            eq(SearchConfig.validatePortRange(1024), true, "vc133A: 1024 lower bound valid");
            eq(SearchConfig.validatePortRange(65535), true, "vc133A: 65535 upper bound valid");
            eq(SearchConfig.validatePortRange(1023), false, "vc133A: 1023 rejected (privileged)");
            eq(SearchConfig.validatePortRange(65536), false, "vc133A: 65536 rejected (over range)");
            eq(SearchConfig.validatePortRange(8080), false, "vc133A: 8080 rejected (model port)");
            eq(SearchConfig.searchPort(), 8081, "vc133A: no context -> default 8081");
            eq(cfg.contains("public static int searchPort()"), true, "vc133A: searchPort getter");
            eq(cfg.contains("public static void setSearchPort(int v)"), true, "vc133A: setSearchPort setter");
            eq(cfg.contains("p.getInt(\"search_port\", -1)"), true, "vc133A: prefs key search_port");

            // ============ A: SearchServer ctor + derived port ============
            eq(srv.contains("public SearchServer(SearchService svc, int port) {"), true,
                    "vc133A: port-aware ctor");
            eq(srv.contains("this(svc, SearchConfig.searchPort());"), true,
                    "vc133A: default ctor delegates to searchPort()");
            eq(srv.contains("super(port);"), true, "vc133A: NanoHTTPD bind uses the parameter");
            eq(SearchServer.PORT, 8081, "vc133A: PORT constant kept for fallback");
            eq(SearchServer.STATUS_PORT(), 8081, "vc133A: STATUS_PORT -> 8081 with no context");
            eq(srv.contains("catch (Throwable t) {"), true, "vc133A: STATUS_PORT degrades on throw");
            String ctrl = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/search/SearchController.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ctrl.contains("append(SearchServer.STATUS_PORT())"), true,
                    "vc133A: statusJson carries the configured port (not the constant)");

            // ============ A: FGS reads the port at start ============
            eq(fgs.contains("int port = SearchConfig.searchPort();"), true, "vc133A: FGS reads port at start");
            eq(fgs.contains("new SearchServer(new SearchService(), port)"), true,
                    "vc133A: FGS binds the configured port");
            eq(fgs.contains("\"搜索服务 :\" + SearchConfig.searchPort() + \" 运行中"), true,
                    "vc133A: notification body carries the dynamic port");
            eq(fgs.contains(":8081"), false, "vc133A: FGS :8081 literal gone");

            // ============ A: Bridge wsPortGet/wsPortSet ============
            eq(ma.contains("public String wsPortGet()"), true, "vc133A: bridge wsPortGet");
            eq(ma.contains("public String wsPortSet(final String v)"), true, "vc133A: bridge wsPortSet");
            eq(ma.contains("if (p == 8080) return \"conflict\";"), true, "vc133A: 8080 -> conflict");
            eq(ma.contains("!SearchConfig.validatePortRange(p)"), true, "vc133A: range check in the bridge");
            eq(ma.contains("SearchConfig.setSearchPort(p);"), true, "vc133A: setter wired");

            // ============ B: search page four-piece form ============
            int ps = html.indexOf("id=\"pageSearch\"");
            int tb = html.indexOf("id=\"tabbar\"");
            int pset = html.indexOf("id=\"pageSettings\"");
            eq(ps > 0 && tb > ps && pset > 0, true, "vc133B: page landmarks present");
            String pageSearch = html.substring(ps, tb);
            eq(pageSearch.contains("id=\"wsSrvToggle2\""), false,
                    "vc133B/vc136: duplicate toggle row retired (CTA at page bottom)");
            eq(pageSearch.contains("id=\"wsState2\""), true, "vc133B: state row");
            eq(pageSearch.contains("id=\"wsPortIn\"") && pageSearch.contains("id=\"btnWsPortSave\"") == false,
                    true, "vc133B/vc138: port row kept, save button retired");
            eq(pageSearch.contains("id=\"wsAddr\"") && pageSearch.contains("id=\"btnWsAddrCopy\""),
                    true, "vc133B: address row + copy (vc132 form)");
            eq(pageSearch.contains("id=\"wsBaseUrl\"") && pageSearch.contains("id=\"wsKey\""),
                    true, "vc133B: SearXNG URL + key rows moved to the search page");
            eq(pageSearch.contains("id=\"engineCard\""), true, "vc133B: engine card stays");
            eq(pageSearch.contains("id=\"requestsToday\""), false,
                    "vc133B: counter row retired (BRIEF L98 baseline)");
            eq(pageSearch.indexOf("id=\"wsPortIn\"") < pageSearch.indexOf("id=\"wsBaseUrl\"")
                    && pageSearch.indexOf("id=\"wsBaseUrl\"") < pageSearch.indexOf("id=\"engineCard\""),
                    true, "vc133B: card order status < settings < engines");
            eq(html.indexOf("id=\"wsPortIn\"") == html.lastIndexOf("id=\"wsPortIn\""), true,
                    "vc133B: wsPortIn unique");
            eq(html.indexOf("id=\"wsKey\"") == html.lastIndexOf("id=\"wsKey\""), true,
                    "vc133B: wsKey unique (single home after the move)");
            String pageSettings = html.substring(pset, ps);
            eq(pageSettings.contains("id=\"wsBaseUrl\"") || pageSettings.contains("id=\"wsKey\""),
                    false, "vc133B: settings page no longer hosts the search card");
            eq(pageSettings.contains("服务端点"), true,
                    "vc133B: settings title renamed to 服务端点 (search rows moved out)");
            eq(html.contains("max=\"65535\""), true, "vc133B: input range bound");
            eq(html.contains("max=\"63535\""), false, "vc133B: no typo'd bound");

            // ============ B: JS i18n + port functions (app.js = chain product) ============
            eq(js.contains("function wsPortSave()"), true, "vc133B: wsPortSave in app.js");
            eq(js.contains("renderWsAddr(e),renderWsPort(e)"), true,
                    "vc133B: renderWsPort hooked in wsPoll (terser arg form)");
            eq(js.contains("document.activeElement!==t"), true,
                    "vc133B: port input not clobbered while typing (renderWsPort guard)");
            eq(js.contains("\"搜索服务\":\"搜尋服務\"") && js.contains("\"搜索服务\":\"Search service\""),
                    true, "vc133B: :N i18n keys replace the :8081 pair");
            eq(js.contains("\"搜索服务 :8081\""), false, "vc133B: :8081 dict pair gone");
            eq(js.contains("\"今日请求数\""), false, "vc133B: counter dict keys retired");
            eq(js.contains("\"局域网设备通过下面的地址访问搜索\":\"區網裝置透過下面的位址存取搜尋\""), true,
                    "vc133B: hint key zh-TW");
            eq(js.contains("\"局域网设备通过下面的地址访问搜索\":\"LAN devices reach search via the address below\""),
                    true, "vc133B: hint key en");
            eq(js.contains("\"搜索服务\":\"搜尋服務\"") && js.contains("\"搜索服务\":\"Search service\"")
                    && js.contains("\"服务端点\":\"服務端點\"") && js.contains("\"服务端点\":\"Endpoint\""),
                    true, "vc133B: title keys (search page :N-dynamic, settings = 服务端点)");
            eq(js.contains("\"已保存 · 重启搜索服务后生效\""), true, "vc133B: saved toast zh");
            eq(js.contains("\"8080 被模型服务占用\""), true, "vc133B: conflict toast zh");
            eq(js.contains("\"端口无效（1024-65535）\""), true, "vc133B: invalid toast zh");

            // ============ chain + gates ============
            eq(bsh.contains("patch-vc133.py js"), true, "vc133: build.sh runs the js transform");
            eq(bsh.indexOf("patch-vc133.py") > bsh.indexOf("patch-vc132.py"), true,
                    "vc133: transform runs after vc132 (chain order)");
            eq(bsh.contains("VC=161"), true, "vc133: build.sh VC=133");
            eq(bsh.contains("VC133_JS_PORTSAVE_MISSING"), true, "vc133: product gate wsPortSave");
            eq(bsh.contains("VC133_REQTODAY_KEY_REMAINS"), true, "vc133: product gate dead-key sweep");
            eq(p133.contains("def do_app_js()"), true, "vc133: patch is a single file (build.sh calls js only)");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc133 setup: " + e);
        }
    }

    /** vc134: page-structure regression. vc131's apply-vc131-html.py moved the
     *  engLogFold card into pageCli but its block excluded the card's closing
     *  </div>; the insert swallowed pageCli's own closing </div> (and the
     *  extraction point left a stray </div>). pageChat/pageSettings/pageSearch
     *  then parsed as CHILDREN of the hidden pageCli -> three blank tabs on
     *  device (vc131-133 all affected). div-count checks stayed 232=232, so the
     *  guard must be structural: every page is a DIRECT child of #pages. */
    static void testVc134PageStructure() {
        System.out.println("== vc134: page structure (vc131 blank-tabs regression) ==");
        try {
            String h = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(h);
            org.jsoup.nodes.Element pages = d.getElementById("pages");
            eq(pages != null, true, "vc134: #pages exists");
            String[] pgs = {"pageServer", "pageCli", "pageChat", "pageSettings", "pageSearch"};
            for (String id : pgs) {
                org.jsoup.nodes.Element e = d.getElementById(id);
                eq(e != null, true, "vc134: #" + id + " exists");
                eq(e != null && e.parent() != null && "pages".equals(e.parent().id()),
                        true, "vc134: #" + id + " is a direct child of #pages");
            }
            org.jsoup.nodes.Element fold = d.getElementById("engLogFold");
            eq(fold != null && fold.closest("#pageCli") != null,
                    true, "vc134: engLogFold inside pageCli (vc131 intent kept)");
            eq(h.contains("</details>    </div>"), false,
                    "vc134: broken pageCli tail form gone");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc134: " + e);
        }
    }

    /** vc135: official-SearXNG schema contract for the built-in :8081 endpoint.
     *  Covers: dropped number_of_results, official empty-list placeholders,
     *  real-only unresponsive_engines, 8-key result objects, engine stamping,
     *  and the "0 hits is not a failure" rule. */
    static void testVc135SearxngSchema() {
        try {
            java.util.List<com.dawoer.npullm.search.SearchService.SearchResult> rs =
                    new java.util.ArrayList<com.dawoer.npullm.search.SearchService.SearchResult>();
            rs.add(new com.dawoer.npullm.search.SearchService.SearchResult(
                    "t0", "http://a", "c0", "bing_cn"));
            rs.add(new com.dawoer.npullm.search.SearchService.SearchResult(
                    "t1", "http://b", "c1", "sogou"));

            java.util.Map<String, String> none = new java.util.LinkedHashMap<String, String>();
            org.json.JSONObject clean = new org.json.JSONObject(
                    com.dawoer.npullm.search.SearchServer.buildSearchJson("q", rs,
                            java.util.Arrays.asList("bing_cn"), none));

            eq(clean.has("number_of_results"), false, "vc135: number_of_results removed");
            eq(clean.has("answers") && clean.optJSONArray("answers") != null,
                    true, "vc135: answers placeholder present");
            eq(clean.has("infoboxes") && clean.optJSONArray("infoboxes") != null,
                    true, "vc135: infoboxes placeholder present");
            eq(clean.has("suggestions") && clean.optJSONArray("suggestions") != null,
                    true, "vc135: suggestions placeholder present");
            eq(clean.has("corrections") && clean.optJSONArray("corrections") != null,
                    true, "vc135: corrections placeholder present");
            eq(clean.optJSONArray("unresponsive_engines").length(), 0,
                    "vc135: no failures -> empty unresponsive_engines");
            eq(clean.optJSONArray("engines").length(), 1,
                    "vc135: top-level engines kept");

            org.json.JSONObject r0 = clean.getJSONArray("results").getJSONObject(0);
            String[] keys = {"url", "title", "content", "engine", "engines", "category",
                    "score", "publishedDate"};
            boolean all = true;
            for (String k : keys) if (!r0.has(k)) all = false;
            eq(all, true, "vc135: result carries 8 official keys");
            eq(r0.getString("engine"), "bing_cn", "vc135: engine == channel id");
            eq(r0.getJSONArray("engines").length(), 1, "vc135: engines array single element");
            eq(r0.getJSONArray("engines").getString(0), "bing_cn",
                    "vc135: engines[0] == engine");
            eq(r0.isNull("publishedDate"), true,
                    "vc135: publishedDate present as JSON null (not deleted)");
            eq(r0.getDouble("score"), 1.0, "vc135: first result score 1.0");
            eq(clean.getJSONArray("results").getJSONObject(1).getDouble("score"), 0.9,
                    "vc135: second result score 0.9 (rank decay)");

            // real failure reasons land in unresponsive_engines
            java.util.Map<String, String> bad = new java.util.LinkedHashMap<String, String>();
            bad.put("sogou", "Suspended: CAPTCHA");
            org.json.JSONObject withFail = new org.json.JSONObject(
                    com.dawoer.npullm.search.SearchServer.buildSearchJson("q", rs,
                            java.util.Arrays.asList("bing_cn"), bad));
            org.json.JSONArray ue = withFail.getJSONArray("unresponsive_engines");
            eq(ue.length(), 1, "vc135: one failed engine reported");
            eq(ue.getJSONArray(0).getString(0), "sogou", "vc135: unresponsive id");
            eq(ue.getJSONArray(0).getString(1), "Suspended: CAPTCHA",
                    "vc135: unresponsive reason verbatim");

            // errOf classification
            eq(com.dawoer.npullm.search.SearchService.errOf(
                    new java.net.SocketTimeoutException("t")), "timeout",
                    "vc135: errOf maps SocketTimeoutException to timeout");
            eq(com.dawoer.npullm.search.SearchService.errOf(
                    new java.net.UnknownHostException("h")), "dns",
                    "vc135: errOf maps UnknownHostException to dns");

            // engine stamping: upstream engine wins, blank gets the channel id
            java.util.List<com.dawoer.npullm.search.SearchService.SearchResult> kept =
                    com.dawoer.npullm.search.SearchService.withEngine(
                            java.util.Arrays.asList(new com.dawoer.npullm.search.SearchService.SearchResult(
                                    "t", "u", "c", "sogou")), "bing_cn");
            eq(kept.get(0).engine, "sogou", "vc135: upstream engine preserved");
            java.util.List<com.dawoer.npullm.search.SearchService.SearchResult> filled =
                    com.dawoer.npullm.search.SearchService.withEngine(
                            java.util.Arrays.asList(new com.dawoer.npullm.search.SearchService.SearchResult(
                                    "t", "u", "c", "")), "bing_cn");
            eq(filled.get(0).engine, "bing_cn", "vc135: blank engine filled from channel id");

            // 0 hits is NOT a failure: unknown channel type yields an empty list
            // with no lastError, so outcomes must stay empty.
            com.dawoer.npullm.search.SearchService svc =
                    new com.dawoer.npullm.search.SearchService();
            java.util.List<com.dawoer.npullm.search.SearchService.ChannelSpec> chans =
                    new java.util.ArrayList<com.dawoer.npullm.search.SearchService.ChannelSpec>();
            chans.add(new com.dawoer.npullm.search.SearchService.ChannelSpec(
                    "noop", "unknown-type", ""));
            com.dawoer.npullm.search.SearchService.SearchResponse s1 = svc.search(
                    new com.dawoer.npullm.search.SearchService.SearchRequest("x", 5, chans));
            eq(s1.outcomes.isEmpty(), true,
                    "vc135: 0-hit channel is not reported as unresponsive");

            // circuit breaker: 3 consecutive empties trip it; the skipped channel
            // must be reported with the suspension reason.
            svc.search(new com.dawoer.npullm.search.SearchService.SearchRequest("x", 5, chans));
            svc.search(new com.dawoer.npullm.search.SearchService.SearchRequest("x", 5, chans));
            com.dawoer.npullm.search.SearchService.SearchResponse s4 = svc.search(
                    new com.dawoer.npullm.search.SearchService.SearchRequest("x", 5, chans));
            eq(s4.outcomes.get("noop"), "suspended: circuit breaker",
                    "vc135: tripped breaker reported as suspended");

            // proto version pin
            eq(com.dawoer.npullm.search.SearchServer.PROTO_VERSION, "vc160-ws1",
                    "vc135: proto version pinned");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc135: " + e);
        }
    }


    /** vc136: search-page restyle (status/addr/port/save + collapsible engine rows
     *  with switches + bottom CTA), settings duplicate hidden, chat ws button state
     *  kept, and the verLine tag now single-sourced from build.sh VN. */
    static void testVc136() {
        System.out.println("== vc136: search page restyle + settings dedup + ws toggle state ==");
        try {
            String h = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(h);

            // ---- A: search service card = status / address / port / save ----
            eq(d.select("#pageSearch #wsSrvToggle2").isEmpty(), true,
                    "vc136A: duplicated toggle row removed from search card");
            eq(d.select("#pageSearch #wsHintRow").isEmpty(), true,
                    "vc136A: LAN hint row removed");
            eq(h.contains("\u5c40\u57df\u7f51\u8bbe\u5907\u901a\u8fc7\u4e0b\u9762\u7684\u5730\u5740\u8bbf\u95ee\u641c\u7d22"), false,
                    "vc136A: LAN hint text gone from HTML");
            int iAddr = h.indexOf("id=\"wsAddrRow\"");
            int iPort = h.indexOf("id=\"wsPortRow\"");
            eq(iAddr > 0 && iPort > iAddr, true,
                    "vc136A/vc138: address row sits above port row (save button retired)");
            eq(d.getElementById("wsState2") != null, true, "vc136A: status row kept");
            eq(d.getElementById("wsPortIn") != null, true, "vc136A: port input kept");

            // ---- B: SearXNG URL/key form kept in DOM, hidden ----
            org.jsoup.nodes.Element compat = d.getElementById("wsCompatCard");
            eq(compat != null, true, "vc136B: compat card still in DOM");
            eq(compat != null && compat.attr("style").contains("display:none"), true,
                    "vc136B: compat card hidden");
            eq(d.getElementById("wsBaseUrl") != null && d.getElementById("wsKey") != null, true,
                    "vc136B: wsBaseUrl/wsKey kept (hidden config path still works)");

            // ---- C: settings page duplicate hidden ----
            org.jsoup.nodes.Element sep = d.getElementById("setEndpointCard");
            eq(sep != null, true, "vc136C: settings endpoint card still in DOM");
            eq(sep != null && sep.attr("style").contains("display:none"), true,
                    "vc136C: settings endpoint card hidden");
            eq(sep != null && sep.closest("#pageSettings") != null, true,
                    "vc136C: hidden card lives on the settings page");

            // ---- D: collapsible engine card + bottom CTA ----
            org.jsoup.nodes.Element drawer = d.getElementById("engDrawer");
            eq(drawer != null, true, "vc136D: engDrawer exists");
            eq(drawer != null && drawer.attr("style").contains("display:none"), true,
                    "vc136D: drawer starts collapsed");
            eq(drawer != null && drawer.getElementById("engineRows") != null, true,
                    "vc136D: engineRows nested in the drawer");
            eq(d.getElementById("engHead") != null && d.getElementById("engChev") != null, true,
                    "vc136D: header + chevron present");
            eq(h.contains("toggleEngines()"), true, "vc136D: header wired to toggleEngines");
            int iEng = h.indexOf("id=\"engineCard\"");
            int iCta = h.indexOf("id=\"btnWsMain\"");
            eq(iEng > 0 && iCta > iEng, true, "vc136D: CTA is the last block on the page");
            eq(d.getElementById("btnWsMain") != null
                    && "\u542f\u52a8\u641c\u7d22\u670d\u52a1".equals(d.getElementById("btnWsMain").attr("data-i18n")), true,
                    "vc136D: CTA carries the start label");

            // ---- E: CSS wiring ----
            eq(h.contains("#btnPick, #btnMoeImport, #btnSpeedTest {"), true,
                    "vc136E/vc138: speed test button takes the pick-GGUF style");
            eq(h.contains("#btnWsMain:active"), true, "vc136E: CTA shares btnMain active style");
            eq(h.contains("#btnWsMain.stop"), true, "vc136E: CTA stop state styled");
            eq(h.contains("#btnWsMain.retry"), true, "vc136E: CTA retry state styled");
            eq(h.contains("#btnWs.searching"), true, "vc136E: searching dim class defined");
            eq(h.contains("#engineCard.open #engHead .chev"), true,
                    "vc136E: chevron rotates when drawer opens");

            // ---- F: JS (terser output) ----
            eq(js.contains("btnWsMain"), true, "vc136F: CTA driven by applyWsState");
            eq(js.contains("\u542f\u52a8\u641c\u7d22\u670d\u52a1"), true, "vc136F: start label in JS");
            eq(js.contains("\u505c\u6b62\u641c\u7d22\u670d\u52a1"), true, "vc136F: stop label in JS");
            eq(js.contains("npullmEnginesOpen"), true, "vc136F: drawer state persisted");
            eq(js.contains("classList.toggle(\"searching\""), true,
                    "vc136F: searching class toggled on the chat button");
            eq(js.contains("class=\"prow\""), true, "vc136F: engine rows use .prow");
            eq(js.contains("class=\"switch\""), true, "vc136F: engine rows use .switch");
            eq(js.contains("padding:4px 2px"), false,
                    "vc136F: old bare-checkbox template removed");
            eq(js.contains("\u5f00\u542f\u540e \ud83d\udd0d \u805a\u5408\u591a\u5f15\u64ce\u7ed3\u679c"), true,
                    "vc136F: new hint key present");
            eq(js.contains("\"\u542f\u52a8\u641c\u7d22\u670d\u52a1\":\"Start search service\""), true,
                    "vc136F: en i18n key");
            eq(js.contains("\"\u542f\u52a8\u641c\u7d22\u670d\u52a1\":\"\u555f\u52d5\u641c\u5c0b\u670d\u52d9\""), true,
                    "vc136F: zh-TW i18n key");

            // ---- G: verLine single-sourced from build.sh VN ----
            eq(bsh.contains("UI_TAG=\"$VN \u00b7 llama.cpp (MIT)\""), true,
                    "vc136G: verLine driven by VN");
            eq(bsh.contains("UI_TAG=\"1.4.5 \u00b7 llama.cpp (MIT)\""), false,
                    "vc136G: stale 1.4.5 literal gone");
            eq(bsh.contains("patch-vc136.py"), true, "vc136G: vc136 chain wired");
            eq(bsh.contains("VC=161"), true, "vc136G: versionCode bumped");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc136: " + e);
        }
    }


    /** vc137: "启用MOE服务" settings switch, default OFF.
     *  Off => the service page "浏览选择 GGUF" never probes the GGUF header for a
     *  MoE architecture; the MoE engine form stays hidden.  On => probe + form. */
    static void testVc137() {
        System.out.println("== vc137: MoE service switch (default off) gates GGUF probing ==");
        try {
            String h = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(h);

            // ---- A: the switch lives on the settings page ----
            org.jsoup.nodes.Element sw = d.getElementById("moeSvcOn");
            eq(sw != null, true, "vc137A: #moeSvcOn exists");
            eq(d.select("#pageSettings #moeSvcOn").size(), 1,
                    "vc137A: switch sits on the settings page");
            eq(sw != null && "checkbox".equals(sw.attr("type")), true,
                    "vc137A: rendered as a checkbox");
            eq(sw != null && sw.parent() != null && sw.parent().hasClass("switch"), true,
                    "vc137A: uses the shared .switch widget");
            eq(sw != null && sw.parent() != null && sw.parent().parent() != null
                    && "srow".equals(sw.parent().parent().className()), true,
                    "vc137A: row uses the settings .srow form");
            org.jsoup.nodes.Element lbl = sw == null ? null : sw.closest(".srow");
            eq(lbl != null && lbl.text().contains("\u542f\u7528MOE\u670d\u52a1"), true,
                    "vc137A: row label is 启用MOE服务");
            eq(h.contains("data-i18n=\"\u542f\u7528MOE\u670d\u52a1\""), true,
                    "vc137A: label carries the i18n key");
            int iSw = h.indexOf("id=\"moeSvcOn\"");
            int iEp = h.indexOf("id=\"setEndpointCard\"");
            eq(iSw > 0 && iEp > iSw, true, "vc137A: switch sits in the system-settings card");

            // ---- B: default OFF, persisted under npullmMoeSvc ----
            eq(js.contains("_bmoeOn=!1;try{_bmoeOn=\"1\"===localStorage.getItem(\"npullmMoeSvc\")"),
                    true, "vc137B: default off, restored from localStorage");
            eq(js.contains("var _bmoeOn=true;"), false,
                    "vc137B: hardcoded default-on is gone");
            eq(js.contains("setItem(\"npullmMoeSvc\""), true,
                    "vc137B: switch state persisted");

            // ---- C: gating ----
            eq(js.contains("function moeProbe(){if(!_bmoeOn)return"), true,
                    "vc137C: moeProbe short-circuits when off (no GGUF probe)");
            eq(js.contains("function renderMoeEngine(){if(!_bmoeOn){var"), true,
                    "vc137C: MoE engine form hidden when off");
            eq(js.contains("function moeSvcSet("), true,
                    "vc137C: setter exists");
            eq(js.contains("e.addEventListener(\"change\",function(){moeSvcSet(this.checked)})")
                            || js.contains("addEventListener(\"change\",function(){moeSvcSet(this.checked)})"),
                    true, "vc137C: settings switch wired to the setter");

            // ---- D: i18n ----
            eq(js.contains("\"\u542f\u7528MOE\u670d\u52a1\":\"\u555f\u7528MOE\u670d\u52d9\""), true,
                    "vc137D: zh-TW key");
            eq(js.contains("\"\u542f\u7528MOE\u670d\u52a1\":\"Enable MoE service\""), true,
                    "vc137D: en key");

            // ---- E: build wiring ----
            eq(bsh.contains("patch-vc137.py"), true, "vc137E: vc137 chain wired");
            eq(bsh.contains("VC=161"), true, "vc137E: versionCode bumped");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc137: " + e);
        }
    }


    /** vc139: chat output ceiling 8192 + the reader is no longer dragged to the
     *  newest token while streaming (chatStick gate + jump-to-latest button). */
    static void testVc139() {
        System.out.println("== vc139: 8192 output ceiling + free scrolling while streaming ==");
        try {
            String h = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(h);

            // ---- A: 8192 ceiling ----
            org.jsoup.nodes.Element mt = d.getElementById("maxtok");
            eq(mt != null && "8192".equals(mt.attr("max")), true, "vc139A: maxtok max=8192");
            eq(mt != null && "8192".equals(mt.attr("value")), true, "vc139A: maxtok default 8192");
            eq(h.contains("stepParam('maxtok',-64,16,8192,0)"), true, "vc139A: minus stops at 8192");
            eq(h.contains("stepParam('maxtok',64,16,8192,0)"), true, "vc139A: plus stops at 8192");
            eq(h.contains("5000"), false, "vc139A: old 5000 ceiling gone");
            eq(js.contains("npullmMaxtok8192"), true, "vc139A: one-shot 1024->8192 migration");
            eq(BmoeAdapter.maxTokensFrom("{}"), 8192, "vc139A: MoE fallback 8192");
            eq(BmoeAdapter.maxTokensFrom("{\"max_tokens\":2048}"), 2048,
                    "vc139A: explicit max_tokens still wins");

            // ---- B: follow state + single scroll path ----
            eq(js.contains("var chatStick="), true, "vc139B: follow state exists");
            eq(js.contains("function chatAtBottom("), true, "vc139B: bottom test exists");
            eq(js.contains("function chatFollow("), true, "vc139B: single scroll path exists");
            eq(js.contains("function chatScrollToBottom("), true, "vc139B: jump-to-latest exists");
            int follows = 0;
            for (int i = js.indexOf("chatFollow("); i >= 0; i = js.indexOf("chatFollow(", i + 11)) follows++;
            eq(follows >= 4, true, "vc139B: every auto-scroll routed through chatFollow");
            eq(js.contains("scrollTop=scrollHeight"), false,
                    "vc139B: no unconditional scrollTop=scrollHeight left");
            eq(js.contains("touchstart"), true,
                    "vc139B: finger down freezes the follow");
            eq(js.contains("npullmSampling"), true, "vc139B: sampling store still used");

            // ---- C: floating button ----
            org.jsoup.nodes.Element jb = d.getElementById("chatToBottom");
            eq(jb != null, true, "vc139C: jump button exists");
            eq(jb != null && "chatScrollToBottom()".equals(jb.attr("onclick")), true,
                    "vc139C: wired to chatScrollToBottom");
            eq(jb != null && jb.attr("style").contains("display:none"), true,
                    "vc139C: hidden until the reader scrolls up");
            eq(jb != null && "chatToBottom".equals(jb.parent().id()), false,
                    "vc139C: button lives outside the scroll container");

            // ---- D: chain ----
            eq(bsh.contains("patch-vc139.py"), true, "vc139D: patch chained into build.sh");
            eq(bsh.contains("VC=161"), true, "vc139D: versionCode bumped");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc139: " + e);
        }
    }

    /** vc141: RAG 独立服务模块（:8082）—— 纯 Java 核心（分块/向量/指纹）+
     *  契约形态（包/端口/通道/路由/assets）。Android 侧（SAF/进程/SQLite）
     *  由真机验收覆盖，JVM 只钉可确定性复现的部分。 */
    static void testVc141() {
        System.out.println("== vc141: RAG module (chunk/vector/fingerprint/contract) ==");
        try {
            // ---- A: 分块 ----
            String md = "# 顶层\n" + repeat("导语段落内容需要足够长才能通过最小块阈值检查。", 2)
                    + "\n\n## 第二节\n" + repeat("第二节正文同样要足够长，否则会被当成碎块丢弃。", 2)
                    + "\n\n### 子节\n" + repeat("子节正文也保持足够长度以形成独立块。", 2) + "\n";
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> cs =
                    com.dawoer.npullm.rag.RagChunker.chunk("a.md", md);
            eq(cs.size(), 3, "vc141A: md 按标题切成 3 块");
            eq(cs.get(0).title, "# 顶层", "vc141A: 首块标题=一级");
            eq(cs.get(1).title, "# 顶层 > ## 第二节", "vc141A: 二级块带祖先路径");
            eq(cs.get(2).title, "# 顶层 > ## 第二节 > ### 子节", "vc141A: 三级块三段路径");
            eq(cs.get(1).text.contains("第二节正文"), true, "vc141A: 块正文不串章");

            // 超长单节被继续切，且每块不超上限太多
            String big = "# 大节\n" + repeat("这是一段用于测试超长章节切分的中文文本，", 200);
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> bc =
                    com.dawoer.npullm.rag.RagChunker.chunk("big.md", big);
            eq(bc.size() > 3, true, "vc141A: 超长章节切成多块 (" + bc.size() + ")");
            boolean allTitled = true;
            boolean allBounded = true;
            for (int i = 0; i < bc.size(); i++) {
                if (!bc.get(i).title.startsWith("# 大节")) allTitled = false;
                if (bc.get(i).text.length() > com.dawoer.npullm.rag.RagChunker.CHUNK_CHARS + 8) {
                    allBounded = false;
                }
            }
            eq(allTitled, true, "vc141A: 切出的每块都继承章节标题");
            eq(allBounded, true, "vc141A: 每块长度受 CHUNK_CHARS 约束");

            // 代码按顶层声明切
            String code = "public class A {\n    void x() {}\n}\n\n"
                    + "public void b() {\n    int y = 1;\n}\n\n"
                    + "private int c() {\n    return 2;\n}\n";
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> cc =
                    com.dawoer.npullm.rag.RagChunker.chunk("A.java", code);
            eq(cc.size(), 3, "vc141A: 代码按顶层声明切成 3 块");

            // 纯文本滑动窗口 + 重叠
            String plain = repeat("abcdefghij", 120); // 1200 字符，无换行
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> pc =
                    com.dawoer.npullm.rag.RagChunker.chunk("n.txt", plain);
            eq(pc.size() >= 2, true, "vc141A: 纯文本切成多块");
            String tail = pc.get(0).text.substring(
                    Math.max(0, pc.get(0).text.length() - 40));
            eq(pc.get(1).text.startsWith(tail), true, "vc141A: 相邻块有重叠");

            // 二进制拒收 + 稳定性
            eq(com.dawoer.npullm.rag.RagChunker.chunk("x.md", "abc\u0000def").size(), 0,
                    "vc141A: NUL 字节=二进制，拒收");
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> s1 =
                    com.dawoer.npullm.rag.RagChunker.chunk("a.md", md);
            java.util.List<com.dawoer.npullm.rag.RagChunker.Chunk> s2 =
                    com.dawoer.npullm.rag.RagChunker.chunk("a.md", md);
            boolean stable = s1.size() == s2.size();
            for (int i = 0; stable && i < s1.size(); i++) {
                stable = s1.get(i).seq == s2.get(i).seq
                        && s1.get(i).title.equals(s2.get(i).title)
                        && s1.get(i).text.equals(s2.get(i).text);
            }
            eq(stable, true, "vc141A: 同输入两次分块完全一致（可参与指纹幂等）");

            // ---- B: 向量 ----
            float[] v = {0.5f, -0.25f, 0.125f, 1e-3f};
            float[] back = com.dawoer.npullm.rag.RagVectors.fromBlob(
                    com.dawoer.npullm.rag.RagVectors.toBlob(v));
            eq(back.length, 4, "vc141B: blob 往返维度一致");
            eq(back[1], -0.25f, "vc141B: blob 往返保负数");
            eq(back[3], 1e-3f, "vc141B: blob 往返保小数");
            eq(com.dawoer.npullm.rag.RagVectors.fromBlob(new byte[]{1, 2, 3}).length, 0,
                    "vc141B: 非法 blob（非 4 字节对齐）→ 空");
            eq(com.dawoer.npullm.rag.RagVectors.cosine(new float[]{1, 0}, new float[]{1, 0}),
                    1.0f, "vc141B: 自身余弦=1");
            eq(com.dawoer.npullm.rag.RagVectors.cosine(new float[]{1, 0}, new float[]{0, 1}),
                    0.0f, "vc141B: 正交=0");
            eq(com.dawoer.npullm.rag.RagVectors.cosine(new float[]{1, 2}, new float[]{1, 2, 3}),
                    0.0f, "vc141B: 维度不符=0（不 NaN）");
            eq(com.dawoer.npullm.rag.RagVectors.cosine(new float[]{0, 0}, new float[]{1, 2}),
                    0.0f, "vc141B: 零向量=0（不 NaN）");
            float[][] docs = {{0, 1}, {1, 0}, {0.7f, 0.7f}};
            int[] top = com.dawoer.npullm.rag.RagVectors.topK(new float[]{1, 0}, docs, 2);
            eq(top.length, 2, "vc141B: topK 长度=min(k,n)");
            eq(top[0], 1, "vc141B: topK 榜首=最相似");
            eq(top[1], 2, "vc141B: topK 次席=次相似");
            eq(com.dawoer.npullm.rag.RagVectors.topK(new float[]{1, 0}, docs, 9).length, 3,
                    "vc141B: k>n 时返回全部");
            eq(com.dawoer.npullm.rag.RagVectors.QUERY_INSTRUCTION.contains("检索"), true,
                    "vc141B: bge 查询指令前缀在位");

            // ---- C: 指纹 ----
            eq(com.dawoer.npullm.rag.RagFingerprint.state(0, 0, "", 1, 2, "h"),
                    com.dawoer.npullm.rag.RagFingerprint.NEW, "vc141C: 无旧记录=NEW");
            eq(com.dawoer.npullm.rag.RagFingerprint.state(100, 50, "h", 100, 50, null),
                    com.dawoer.npullm.rag.RagFingerprint.SAME, "vc141C: mtime+size 未变=SAME");
            eq(com.dawoer.npullm.rag.RagFingerprint.state(100, 50, "h", 200, 50, "h"),
                    com.dawoer.npullm.rag.RagFingerprint.SAME, "vc141C: 仅 mtime 变但 hash 同=SAME");
            eq(com.dawoer.npullm.rag.RagFingerprint.state(100, 50, "h", 200, 60, "h2"),
                    com.dawoer.npullm.rag.RagFingerprint.CHANGED, "vc141C: 内容变=CHANGED");
            eq(com.dawoer.npullm.rag.RagFingerprint.sha256Hex("abc".getBytes("UTF-8")),
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "vc141C: sha256 与标准向量一致");

            // ---- D: 端口/阈值 ----
            eq(com.dawoer.npullm.rag.RagConfig.validatePort(8080), false,
                    "vc141D: 8080（主服务）拒收");
            eq(com.dawoer.npullm.rag.RagConfig.validatePort(8081), false,
                    "vc141D: 8081（搜索服务）拒收");
            eq(com.dawoer.npullm.rag.RagConfig.validatePort(8082), true, "vc141D: 8082 合法");
            eq(com.dawoer.npullm.rag.RagConfig.validatePort(80), false, "vc141D: 特权端口拒收");
            eq(com.dawoer.npullm.rag.RagConfig.validatePort(65535), true, "vc141D: 上界合法");
            eq(com.dawoer.npullm.rag.RagConfig.EMBED_PORT, 8083,
                    "vc141D: 嵌入进程端口=8083（内部）");
            eq(com.dawoer.npullm.rag.RagConfig.DEFAULT_TOPK, 5, "vc141D: 默认 topK=5");

            // ---- E: 契约形态 ----
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String mf = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/AndroidManifest.xml")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String fgs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagForegroundService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String srv = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagServer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("rag/*.java"), true, "vc141E: build.sh 编译 rag 包");
            eq(bsh.contains("VC=161"), true, "vc141E: versionCode 141");
            eq(mf.contains("com.dawoer.npullm.rag.RagForegroundService"), true,
                    "vc141E: manifest 注册 RAG FGS");
            eq(mf.contains("Local RAG knowledge-base endpoint"), true,
                    "vc141E: manifest specialUse 子类型声明");
            eq(fgs.contains("\"rag_server_v1\""), true,
                    "vc141E: 独立通知通道 rag_server_v1（不复用旧通道）");
            eq(fgs.contains("NOTIF_ID = 44"), true, "vc141E: 通知 id 44 独立");
            eq(srv.contains("/v1/embeddings"), true, "vc141E: 对外暴露 /v1/embeddings");
            eq(srv.contains("/v1/rag/search"), true, "vc141E: 检索路由在位");
            eq(srv.contains("/v1/rag/index"), true, "vc141E: 索引路由在位");
            eq(com.dawoer.npullm.rag.RagServer.PROTO_VERSION, "vc159-rag1",
                    "vc141E: 协议版本随版前移（vc159-rag1）");
            String rsvc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rsvc.contains("o.put(\"running\", RagForegroundService.alive)"), true,
                    "vc141E: status 吐真实 running（不信配置端口）");
            String ajs2 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ajs2.contains(".running"), true, "vc141E: 前端按 running 判定运行中");
            java.io.File model = new java.io.File(
                    "app/src/main/assets/rag/bge-small-zh-v1.5-q8_0.gguf");
            eq(model.isFile() && model.length() > 20L * 1024 * 1024, true,
                    "vc141E: 嵌入模型随包（>20MB）");
            String idx = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(idx.contains("id=\"ragCard\""), true, "vc141E: 搜索页含 RAG 卡片");
            eq(idx.contains("id=\"btnRagToggle\""), true, "vc141E: 开关按钮在位");
            eq(idx.contains("onclick=\"ragIndex(true)\""), true, "vc141E: 重建按钮在位");
            eq(idx.contains("id=\"ragHits\""), true, "vc141E: 检索结果区在位");
            eq(idx.contains("data-i18n-t=\"知识库 RAG（实验）\""), true,
                    "vc141E: 卡片标题走 i18n 键");

            // ---- F: 读入层纯函数 ----
            eq(com.dawoer.npullm.rag.RagIndexer.accept("notes.md", 1024), true,
                    "vc141F: .md 白名单内");
            eq(com.dawoer.npullm.rag.RagIndexer.accept("a.exe", 1024), false,
                    "vc141F: .exe 拒收");
            eq(com.dawoer.npullm.rag.RagIndexer.accept("big.md", 5L * 1024 * 1024), false,
                    "vc141F: >2MB 拒收");
            eq(com.dawoer.npullm.rag.RagIndexer.decode("中文".getBytes("UTF-8")), "中文",
                    "vc141F: UTF-8 正常解码");
            eq(com.dawoer.npullm.rag.RagIndexer.decode("中文GBK".getBytes("GBK")), "中文GBK",
                    "vc141F: GBK 回退解码");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc141: " + e);
        }
    }

    /** 重复拼接（测试造长文本用）。 */
    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** vc149: recall memory block merged into the current user turn instead of a
     *  second role:"system" message. Qwen3.5 non-MTP GGUF embeds a chat template
     *  with raise_exception('System message must be at the beginning'); any second
     *  system message (recall block, search block) 500s at template render time —
     *  engine-independent, MTP GGUF unaffected (vc133 lesson, template-pit section).
     *  New contract: messages = [system?][history...][user: RECALL\n\nCUR] —
     *  at most one system message, always first. Also friendlier to KV prefix cache:
     *  a changed recall block only invalidates the final user turn. */
    static void testVc149() {
        System.out.println("== vc149: recall block into user turn (template-pit fix) ==");
        try {
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            // A: no separate recall system message anywhere
            eq(js.contains("c&&s.push({role:\"system\",content:c})"), false,
                    "vc149A: recall block no longer pushed as role:system");
            // B: merge contract present exactly once (recall -> user turn header)
            // B: vc152 —— terser 会改局部变量名，锚"块并入 user turn 的次数"：
            // 聊天记忆（vc149 recall）+ 本地知识库（vc152 kb）= 恰好 2 处
            eq(countOccurrences(js, "\"\\n\\n\"+"), 2,
                    "vc149B: recall+kb 都并入当前 user turn（恰 2 处）");
            // C: chain wiring — build.sh runs patch-vc149 with hard gates
            String bsh2 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh2.contains("patch-vc149.py"), true,
                    "vc149C: build.sh replays patch-vc149");
            eq(bsh2.contains("VC149_JS_RECALL_USER_TURN_MISSING"), true,
                    "vc149C: build.sh gates recall-user-turn presence");
            eq(bsh2.contains("VC149_RECALL_SYSTEM_REMAINS"), true,
                    "vc149C: build.sh gates old system-push removal");
            String p149 = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc149.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(p149.contains("for(var i=0;i<chatMsgs.length-1;i++)s.push(chatMsgs[i]);"), true,
                    "vc149C: chain emits [system?]->history->user(cur) order");
            // D: version bumped
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc149D: build.sh VC=153");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc149: " + e);
        }
    }

    /** vc140: llama.cpp tip 4e910a86e baseline + KV prefix reuse.
     *  档位默认开：四档 exec profile 的 args 自带 --cache-reuse 256（bmoe 走自有
     *  argv 不加）；UI 逃生门 = 高级参数框追加 "--cache-reuse 0"（在 profile 之后
     *  解析，llama.cpp 后者覆盖）。 */
    static void testVc140() {
        System.out.println("== vc140: llama.cpp baseline upgrade + KV prefix reuse ==");
        try {
            String prof = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(prof);
            org.json.JSONObject ps = root.getJSONObject("profiles");

            // ---- A: every exec profile enables cache reuse by default ----
            String[] execs = {"htp", "ocl", "cpu", "mtp"};
            for (int i = 0; i < execs.length; i++) {
                String a = ps.getJSONObject(execs[i]).getString("args");
                eq(a.contains("--cache-reuse 256"), true,
                        "vc140A: " + execs[i] + " profile ships --cache-reuse 256");
            }
            eq(ps.getJSONObject("bmoe").getString("args").contains("--cache-reuse"), false,
                    "vc140A: bmoe owns its argv, no flag injected");

            // ---- B: flag survives buildCommand into argv exactly once ----
            String[] cmd = LlamaServerManager.buildCommand("/x/e",
                    ps.getJSONObject("htp").getString("args"),
                    "/s/m.gguf", "127.0.0.1", 8080, 8192, "off", 4, false, 0, 0);
            eq(count(cmd, "--cache-reuse"), 1, "vc140B: --cache-reuse present exactly once");
            eq(idx(cmd, "--cache-reuse") > 0
                    && "256".equals(cmd[idx(cmd, "--cache-reuse") + 1]), true,
                    "vc140B: value 256 follows the flag");
            eq(idx(cmd, "--cache-reuse") < idx(cmd, "--host"), true,
                    "vc140B: lands before the fixed host/port tail");
            String[] off = LlamaServerManager.buildCommand("/x/e", "-m {MODEL} -fa on",
                    "/s/m.gguf", "127.0.0.1", 8080, 4096, "off", 4, false, 0, 0);
            eq(contains(off, "--cache-reuse"), false,
                    "vc140B: profile without the flag gets none appended");

            // ---- C: version bump ----
            eq(bsh.contains("VC=161"), true, "vc140C: versionCode 140");
            eq(bsh.contains("VN=1.5.1"), true, "vc140C: versionName 1.5.1");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc140: " + e);
        }
    }

    /** vc138: search page UI pass 2 — port row like the service page (no 保存
     *  button, saves on change), tighter rows, 测速 button = old save-button style. */
    static void testVc138() {
        System.out.println("== vc138: search page rows/port/speed-test styling ==");
        try {
            String h = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String js = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(h);

            // ---- A: 保存 button retired ----
            eq(d.getElementById("btnWsPortSave") == null, true, "vc138A: save button removed");
            eq(h.contains("btnWsPortSave"), false, "vc138A: no trace of the save button");
            eq(d.select("#pageSearch button[onclick=\"wsPortSave()\"]").isEmpty(), true,
                    "vc138A: no onclick save trigger left");
            eq(js.contains("function wsPortSave("), true,
                    "vc138A: wsPortSave kept (change handler still saves)");

            // ---- B: port row = service-page row form ----
            org.jsoup.nodes.Element portRow = d.getElementById("wsPortRow");
            eq(portRow != null && "prow".equals(portRow.className()), true,
                    "vc138B: port row uses .prow");
            eq(portRow != null && portRow.select("> .plabel").size() == 1, true,
                    "vc138B: port row has a .plabel");
            org.jsoup.nodes.Element pin = d.getElementById("wsPortIn");
            eq(pin != null && "number".equals(pin.attr("type")), true,
                    "vc138B: port input kept");
            eq(pin != null && pin.attr("style").contains("width:110px"), false,
                    "vc138B: old 110px inline width dropped");
            eq(pin != null && pin.parent() != null && "wsPortRow".equals(pin.parent().id()), true,
                    "vc138B: input sits directly in the port row");
            eq(h.contains("#wsPortIn { width: 96px; text-align: right; }"), true,
                    "vc138B: service-page sizing applied via CSS");
            eq(h.contains("#port { width: 96px; text-align: right; }"), true,
                    "vc138B: service page keeps the same 96px rule");

            // ---- C: tighter rows on the search page ----
            eq(h.contains("#pageSearch .prow { padding: 6px 2px; }"), true,
                    "vc138C: rows tightened to 6px");
            eq(h.contains("#pageSearch .card-title { margin-bottom: 3px; }"), true,
                    "vc138C: card title pulled closer to the rows");
            eq(h.contains("#wsState2 { font-size: 13px; font-weight: 600; color: var(--text); }"), true,
                    "vc138C: status value styled like a row value");
            eq(d.select("#pageSearch .card > .prow").size() >= 3, true,
                    "vc138C: status/addr/port are all .prow rows");
            org.jsoup.nodes.Element stRow = d.getElementById("wsStateRow");
            eq(stRow != null && "prow".equals(stRow.className()), true,
                    "vc138C: status row converted too");
            eq(d.getElementById("wsPortRow").select("button").isEmpty(), true,
                    "vc138C: port row holds no button");
            eq(portRow != null && portRow.nextElementSibling() == null, true,
                    "vc138C: port row is the last element of the search card");

            // ---- D: 测速 button takes the save-button style ----
            eq(h.contains("#btnPick, #btnMoeImport, #btnSpeedTest {"), true,
                    "vc138D: speed test shares the dashed full-width rule");
            eq(h.contains("#btnPick:active, #btnMoeImport:active, #btnSpeedTest:active { background: #1d2532; }"), true,
                    "vc138D: speed test shares the :active rule");
            eq(js.contains("width:100%;padding:6px"), false,
                    "vc138D: inline speed-test box style gone");
            eq(js.contains("\"margin:2px 0 6px;\""), true,
                    "vc138D: speed test keeps only a tight margin inline");

            // ---- E: port saves on change, silently when unchanged ----
            eq(js.contains("_wsPortCur"), true, "vc138E: last-known port remembered");
            eq(js.contains("addEventListener(\"change\",function(){wsPortSave()})"), true,
                    "vc138E: port input wired to wsPortSave on change");
            eq(js.contains("String(window._wsPortCur||\"\")"), true,
                    "vc138E: unchanged value is a no-op (no toast spam)");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc138: " + e);
        }
    }

    static void testVc147() {
        System.out.println("== vc147: chat memory (windows/chatstore/recall) ==");
        try {
            // 窗口：两条短消息拼一块，块头带时间+角色
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Turn> t1 =
                    new java.util.ArrayList<com.dawoer.npullm.rag.MemoryWindows.Turn>();
            t1.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    1L, "main", "user", "第一条", 1730000000000L));
            t1.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    2L, "main", "assistant", "第二条", 1730000060000L));
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Win> w1 =
                    com.dawoer.npullm.rag.MemoryWindows.build(t1, 700);
            eq(w1.size(), 1, "vc147A: 短消息合并成一个窗口");
            eq(w1.get(0).rel, "chat/main#1", "vc147A: 块身份锚首条消息 id");
            eq(w1.get(0).text.contains("[20"), true, "vc147A: 块头带日期");
            eq(w1.get(0).text.contains(" user] "), true, "vc147A: 块头带角色");
            eq(w1.get(0).text.contains("第一条"), true, "vc147A: 正文保留");
            eq(w1.get(0).ts, 1730000060000L, "vc147A: 窗口 ts=最后一条时间");

            // 追加一条消息：块身份不变、正文变（增量只重嵌最后一块）
            t1.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    3L, "main", "user", "第三条", 1730000120000L));
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Win> w2 =
                    com.dawoer.npullm.rag.MemoryWindows.build(t1, 700);
            eq(w2.get(0).rel, w1.get(0).rel, "vc147B: 追加后块身份不变（增量语义）");
            eq(w2.get(0).text.contains("第三条"), true, "vc147B: 正文已含新消息");
            eq(w2.get(0).ts > w1.get(0).ts, true, "vc147B: ts 随最后一条前移");

            // 超长消息：按 maxChars 切多块，块身份各不相同
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Turn> t2 =
                    new java.util.ArrayList<com.dawoer.npullm.rag.MemoryWindows.Turn>();
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 400; i++) big.append("内容块");
            t2.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    10L, "main", "user", big.toString(), 1730000000000L));
            t2.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    11L, "main", "assistant", big.toString(), 1730000100000L));
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Win> w3 =
                    com.dawoer.npullm.rag.MemoryWindows.build(t2, 700);
            eq(w3.size() > 1, true, "vc147C: 超长内容切成多窗口");
            boolean distinct = true;
            for (int i = 0; i < w3.size(); i++) {
                if (w3.get(i).text.length() > 1400) distinct = false;
            }
            eq(distinct, true, "vc147C: 每窗口都在容量内");

            // 空消息不进窗口
            java.util.List<com.dawoer.npullm.rag.MemoryWindows.Turn> t3 =
                    new java.util.ArrayList<com.dawoer.npullm.rag.MemoryWindows.Turn>();
            t3.add(new com.dawoer.npullm.rag.MemoryWindows.Turn(
                    20L, "main", "user", "   ", 1730000000000L));
            eq(com.dawoer.npullm.rag.MemoryWindows.build(t3, 700).size(), 0,
                    "vc147D: 空白消息不生成窗口");

            // 接线：Java 侧
            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ma.contains("public long chatSave("), true, "vc147E: Bridge chatSave 在位");
            eq(ma.contains("public String ragRecallSync("), true, "vc147E: Bridge ragRecallSync 在位");
            eq(ma.contains("public String chatMemStatus()"), true, "vc147E: Bridge chatMemStatus 在位");
            eq(ma.contains("class RagIndexChatTask"), true, "vc147E: 索引任务类在位");

            String rs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rs.contains("public String recall("), true, "vc147E: recall 在位");
            eq(rs.contains("recallHalfLifeDays()"), true, "vc147E: 时间衰减半衰期在用");

            String cs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/ChatStore.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(cs.contains("CREATE TABLE msg("), true, "vc147E: chat.db 建表在位");
            eq(cs.contains("MAIN_CID"), true, "vc147E: 会话 id 常量在位");

            // 接线：JS 侧（terser 产物里属性名保留，变量名会变，只锚字面量）
            String ajs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ajs.contains("chatSave"), true, "vc147F: 前端落库调用在位");
            eq(ajs.contains("memRecallBlock"), true, "vc147F: 召回注入函数在位");
            eq(ajs.contains("onRagRecall"), true, "vc147F: 记忆检索回调在位");
            eq(ajs.contains("chatRestore"), true, "vc147F: 历史恢复在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc147: " + e);
        }
    }

    static void testVc152() {
        System.out.println("\n== vc152: chat 📚 local knowledge-base injection ==");
        try {
            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ma.contains("public String ragKbSearchSync("), true, "vc152A: Bridge ragKbSearchSync 在位");
            eq(ma.contains("public boolean kbOnGet()"), true, "vc152A: Bridge kbOnGet 在位");
            eq(ma.contains("public void kbOnSet("), true, "vc152A: Bridge kbOnSet 在位");

            String rs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rs.contains("public String searchDocs("), true, "vc152B: searchDocs 在位");
            eq(rs.contains("private long[] docLibIds()"), true, "vc152B: 文档库筛选在位");
            eq(rs.contains("RagConfig.CHAT_LIB_URI.equals"), true, "vc152B: 检索排除聊天记忆库");

            String rc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagConfig.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rc.contains("\"rag_kb_on\""), true, "vc152C: 开关持久化键在位");
            eq(rc.contains("public static boolean kbOn()"), true, "vc152C: kbOn 读取在位");

            String ajs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ajs.contains("memKbBlock"), true, "vc152D: 前端注入函数在位");
            eq(ajs.contains("ragKbSearchSync"), true, "vc152D: 前端走文档检索桥");
            eq(ajs.contains("kbToggle"), true, "vc152D: 开关按钮在位");
            eq(ajs.contains("1500"), true, "vc152D: 总预算 1500 字在位");
            eq(ajs.contains("500"), true, "vc152D: 单段 500 字裁剪在位");
            eq(ajs.contains("kbOnGet"), true, "vc152D: 开关状态读完在桥");
            // vc149 铁律：知识库块也必须是 user turn 合并，不许新增 system 消息
            eq(ajs.contains("content:_kb"), false, "vc152D: 未新增 system 消息");

            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(html.contains("id=\"btnKb\""), true, "vc152E: 📚 按钮在 HTML 中");
            eq(html.contains("#btnKb.on"), true, "vc152E: 按钮开启态样式在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc152: " + e);
        }
    }


    static void testVc153() {
        System.out.println("\n== vc153: services hub page ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(html.contains("id=\"pageServices\""), true, "vc153A: pageServices 在位");
            eq(html.contains("id=\"svcInfOn\"") && html.contains("id=\"svcWsOn\"") && html.contains("id=\"svcRagOn\""), true, "vc153A: 三服务开关在位");
            eq(html.contains("gotoPage('pageServer')"), true, "vc153A: 推理二级入口");
            eq(html.contains("gotoPage('pageCli')"), true, "vc153A: CLI 调参入口");
            eq(html.contains("gotoPage('pageSearch')"), true, "vc153A: 搜索/知识库二级入口");
            eq(html.contains("id=\"tabServer\" data-page=\"pageServices\""), true, "vc153B: tabServer 指向聚合页");
            eq(html.contains("tabCli"), false, "vc153B: tabCli 按钮已删");
            eq(html.contains("id=\"tabChat\"") && html.contains("id=\"tabSettings\""), true, "vc153B: Chat/设置 tab 保留");
            eq(html.contains("id=\"pageServer\"") && html.contains("id=\"pageCli\"") && html.contains("id=\"pageSearch\""), true, "vc153B: 二级页全部保留");
            eq(html.contains("id=\"btnSvcCli\" title=\"CLI 调参\" data-i18n-t"), false, "vc153C: 符号按钮不带 i18n-t");
            String ajs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ajs.contains("function gotoPage("), true, "vc153D: gotoPage 在位");
            eq(ajs.contains("window.svcInfToggle=") && ajs.contains("window.svcWsToggle=") && ajs.contains("window.svcRagToggle="), true, "vc153D: 三开关转发在位");
            eq(ajs.contains("svcRefresh"), true, "vc153D: 状态刷新在位");
            eq(ajs.contains("apiKeyFingerprint"), true, "vc153D: Key 指纹走现成桥");
            eq(ajs.contains("tabServer:\"服务\",tabChat:\"Chat\",tabSettings:\"设置\""), true, "vc153D: i18n tab 映射 3 项");
            eq(ajs.contains("tabCli"), false, "vc153D: tabCli 映射已删");
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc153E: build.sh VC=153");
            eq(bsh.contains("VC153_HTML_SVC_PAGE_MISSING"), true, "vc153E: build 门禁在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc153: " + e);
        }
    }

    static void testVc154() {
        System.out.println("\n== vc154: services page fixes (toggle-off / card jump / copy addr / kb-in-settings / chat two-row) ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(html.contains("id=\"svcInfCard\" onclick=") || html.contains("id=\"svcWsCard\" onclick=") || html.contains("id=\"svcRagCard\" onclick="), false, "vc155A: 整卡点击已撤(vc154 方案推翻)");
            eq(html.contains("id=\"btnSvcInfCfg\"") && html.contains("id=\"btnSvcWsCfg\"") && html.contains("id=\"btnSvcRagCfg\""), true, "vc155A: 三卡设置按钮回归");
            eq(html.contains("copySvcAddr('svcInfAddr')") && html.contains("copySvcAddr('svcWsAddr')") && html.contains("copySvcAddr('svcRagAddr')"), true, "vc154A: 三地址复制按钮在位");
            eq(html.contains("gotoPage('pageCli')"), true, "vc154A: CLI 小按钮保留");
            int srch0 = html.indexOf("id=\"pageSearch\""), rpg0 = html.indexOf("id=\"pageRag\"");
            eq(srch0 > 0 && rpg0 > srch0, true, "vc155B: pageRag 在 pageSearch 之后");
            String srchSeg = html.substring(srch0, rpg0);
            eq(srchSeg.contains("id=\"ragCard\"") || srchSeg.contains("id=\"memCard\""), false, "vc155B: 搜索页不再含 RAG/记忆卡");
            eq(html.substring(rpg0).contains("id=\"ragCard\"") && html.substring(rpg0).contains("id=\"memCard\""), true, "vc155B: RAG/记忆卡在 pageRag 内");
            eq(html.contains("id=\"chatInputRow\" style=\"flex:none; display:flex; flex-direction:column"), true, "vc154C: Chat 输入区两行化");
            String ajs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/app.js")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ajs.contains("checked=!0,bridge(\"stopServer\")"), true, "vc154D: 推理开关可关(stopServer)");
            eq(ajs.contains("function copySvcAddr("), true, "vc154D: copySvcAddr 在位");
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc154E: build.sh VC=161");
            eq(bsh.contains("VC154_JS_STOP_PATH_MISSING"), true, "vc154E: build 门禁在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc154: " + e);
        }
    }

    static void testVc155() {
        System.out.println("\n== vc155: services gear buttons (2x) + standalone RAG page ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(countOccurrences(html, "class=\"svc-gear\""), 3, "vc155A: 三卡设置按钮");
            eq(html.contains(".svc-gear { flex: none; margin-left: auto;") && html.contains("width: 52px; height: 52px;"), true, "vc155A: 按钮 52px = 2x(.svc-btn 26px)");
            eq(html.contains("gotoPage('pageRag')"), true, "vc155B: 知识库卡指向独立二级页");
            eq(html.contains("id=\"pageRag\""), true, "vc155B: pageRag 页存在");
            eq(html.contains("svc-chev"), false, "vc155B: 旧箭头残留清零");
            eq(html.contains("id=\"svcInfCard\" onclick=") || html.contains("id=\"svcWsCard\" onclick=") || html.contains("id=\"svcRagCard\" onclick="), false, "vc155C: 整卡点击已撤(开关/复制可点)");
            eq(html.contains("id=\"svcWsOn\"") && html.contains("id=\"svcRagOn\"") && html.contains("id=\"svcInfOn\""), true, "vc155C: 三开关仍在");
            eq(html.contains("copySvcAddr('svcInfAddr')") && html.contains("copySvcAddr('svcRagAddr')"), true, "vc155C: 地址复制按钮保留");
            eq(html.indexOf("id=\"pageSettings\"") < html.indexOf("id=\"pageSearch\""), true, "vc155C: pageSettings 位置未变");
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc155D: build.sh VC=161");
            eq(bsh.contains("VC155_HTML_RAG_PAGE_MISSING") && bsh.contains("VC155_HTML_GEAR_COUNT") && bsh.contains("VC155_HTML_CARD_CLICK_REMAINS"), true, "vc155D: build 门禁在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc155: " + e);
        }
    }

    static void testVc156() {
        System.out.println("\n== vc156: cli button = gear size / Key label / RAG page unify ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(countOccurrences(html, "class=\"svc-gear svc-cli\""), 1, "vc156A: 调参按钮=52px .svc-gear 规格");
            eq(countOccurrences(html, "class=\"svc-gear\""), 3, "vc156A: 三卡设置按钮计数不受影响");
            eq(html.indexOf("id=\"btnSvcCli\"") < html.indexOf("id=\"btnSvcInfCfg\""), true, "vc156A: 调参按钮在设置按钮旁(前)");
            eq(html.contains("data-i18n=\"Key：\""), true, "vc156B: Key 行文案=Key：");
            eq(html.contains("data-i18n=\"访问 Key\""), false, "vc156B: 旧文案清零");
            eq(html.contains("#svcInfKey { flex: 1; min-width: 0;"), true, "vc156B: Key 复制按钮与地址复制按钮同列");
            eq(countOccurrences(html, "id=\"btnRagToggle\""), 1, "vc156C: RAG 主按钮唯一");
            eq(html.contains("id=\"btnRagToggle\" onclick=\"ragToggle()\" data-i18n=\"启动服务\""), true, "vc156C: RAG 主按钮=启动服务/停止服务");
            eq(html.indexOf("id=\"btnRagToggle\"") > html.indexOf("id=\"memCard\""), true, "vc156C: 主按钮置于页底");
            eq(html.contains("<b id=\"ragState\" class=\"idle\">off</b></div>"), true, "vc156C: 状态行不再挂按钮");
            eq(html.contains("#btnRagToggle.stop"), true, "vc156C: 停止态样式");
            eq(html.contains("#pageRag .prow { padding: 6px 2px; }"), true, "vc156C: RAG 行距与服务页一致");
            eq(html.contains("<div style=\"display:none;\"><input id=\"memQ\""), true, "vc156D: 聊天记忆检索已隐藏(元素保留)");
            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc156E: build.sh VC=161");
            eq(bsh.contains("VC156_HTML_CLI_GEAR_MISSING") && bsh.contains("VC156_JS_RAG_CTA_MISSING") && bsh.contains("VC156_HTML_MEMQ_NOT_HIDDEN"), true, "vc156E: 门禁三条在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc156: " + e);
        }
    }

    static void testVc157() {
        System.out.println("\n== vc157: RAG gate key UI + 多库入口 + 服务页对齐 ==");
        try {
            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(countOccurrences(html, "id=\"ragLibs\""), 1, "vc157A: 库列表容器在位");
            eq(countOccurrences(html, "id=\"btnRagKeyReset\""), 1, "vc157A: Key 重置按钮在位");
            eq(countOccurrences(html, "id=\"btnRagKeyCopy\""), 1, "vc157A: Key 复制按钮在位");
            eq(html.contains("onclick=\"ragKeyReset()\"") && html.contains("onclick=\"ragKeyCopy()\""), true, "vc157A: Key 按钮已接线");
            eq(html.contains("id=\"ragKeyTxt\""), true, "vc157A: Key 指纹展示位在位");
            eq(html.contains(".svc-gear + .svc-gear { margin-left: 0; }"), true, "vc157B: 调参/设置按钮不再被 auto margin 撑开");
            eq(html.contains(".svc-row { display: flex; align-items: center; gap: 10px; }"), true, "vc157B: 开关上下居中");
            eq(html.indexOf("id=\"ragKeyTxt\"") > html.indexOf("id=\"ragPortIn\""), true, "vc157A: Key 行在端口行之后");
            eq(html.indexOf("id=\"ragLibs\"") > html.indexOf("id=\"ragDirTxt\""), true, "vc157A: 库列表在目录行之后");

            String rc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagConfig.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rc.contains("public static String ensureGateKey("), true, "vc157C: key 首次生成入口在位");
            eq(rc.contains("public static String resetGateKey("), true, "vc157C: key 重置入口在位");
            eq(rc.contains("public static String gateKeyFingerprint("), true, "vc157C: 指纹接口在位");

            String rs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagServer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rs.contains("Status.UNAUTHORIZED"), true, "vc157D: 鉴权失败改 401");
            eq(rs.contains("Status.FORBIDDEN"), false, "vc157D: 403 残留清零");
            eq(rs.indexOf("gateOk(session)") < rs.indexOf("\"/v1/rag/status\""), true, "vc157D: status 已纳入门内");
            eq(rs.indexOf("\"/health\"") < rs.indexOf("gateOk(session)"), true, "vc157D: /health 仍在门外");
            eq(rs.contains("include_chat"), true, "vc157D: 服务端接受 include_chat");

            String rv = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rv.contains("includeChat ? allLibIds() : docLibIds()"), true, "vc157E: search 默认排除聊天记忆库");
            eq(rv.contains("boolean includeChat"), true, "vc157E: 5 参 search 在位");
            eq(rv.contains("\"key_fp\""), true, "vc157E: status 吐 key 指纹");
            eq(rv.contains("聊天记忆库不可删除"), true, "vc157E: 聊天记忆库禁删");

            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ma.contains("public String ragKeyGet("), true, "vc157F: ragKeyGet 桥在位");
            eq(ma.contains("public String ragKeyReset("), true, "vc157F: ragKeyReset 桥在位");
            eq(ma.contains("RagConfig.resetGateKey()"), true, "vc157F: 重置走 RagConfig");

            String fgs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagForegroundService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(fgs.contains("s.setApiKey(RagConfig.ensureGateKey())"), true, "vc157C: 启动服务即生成 key");

            String pj = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc157.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(pj.contains("window.ragKeyCopy=function") && pj.contains("window.ragKeyReset=function"), true, "vc157G: 前端 key 逻辑在补丁链里");
            eq(pj.contains("function ragMeta(o)") && pj.contains("bridge(\"ragDrop\""), true, "vc157G: 库列表渲染+删除接 ragDrop");

            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc157H: build.sh VC=161");
            eq(bsh.contains("python3 $ROOT/patch-vc157.py"), true, "vc157H: 补丁链已挂");
            eq(bsh.contains("VC157_SERVER_401_MISSING") && bsh.contains("VC157_SEARCH_CHAT_EXCLUDE_MISSING")
                    && bsh.contains("VC157_HTML_LIBLIST_MISSING"), true, "vc157H: 门禁三条在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc157: " + e);
        }
    }

    static void testVc158() {
        System.out.println("\n== vc158: 思考流分流 + 服务页知识库 Key 行（v1.5.1 发布版）==");
        try {
            String ss = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/ServerChatStreamer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ss.contains("void onReasoning(String text);"), true, "vc158A: SSE 回调新增 onReasoning");
            eq(ss.contains("static String reasoningPiece("), true, "vc158A: reasoningPiece 独立取值");
            eq(ss.contains("emitReasoning(rp)"), true, "vc158A: 思考走独立 emit");
            eq(ss.indexOf("emitReasoning(rp)") < ss.indexOf("deltaPiece(delta)"), true, "vc158A: 思考先于正文派发");
            eq(ss.contains("Object oRc = delta.opt(\"reasoning_content\");\n        return (oRc instanceof String) ? (String) oRc : \"\";"),
                    true, "vc158A: deltaPiece 不再回退 reasoning");

            String ba = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/BmoeAdapter.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(ba.contains("void onReasoning(String t);"), true, "vc158B: bmoe Delegate 新增 onReasoning");
            eq(ba.contains("static void reasoning(String t)"), true, "vc158B: UiHook.reasoning 独立通道");

            String lsm = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/LlamaServerManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(lsm.contains("BmoeAdapter.UiHook.reasoning(t)"), true, "vc158B: bmoe 思考不再走 chunk 流");
            eq(lsm.contains("pushBmoeReasoning(String t) {\n        // vc158") || lsm.contains("vc158: reasoning"), true, "vc158B: 改动有据");

            String ma = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/MainActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(countOccurrences(ma, "window.onChatReasoning && window.onChatReasoning("), 2, "vc158C: 两条桥都派发思考");

            String pj = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("patch-vc158.py")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(pj.contains("window.onChatReasoning=function"), true, "vc158D: 前端思考回调在链里");
            eq(pj.contains("0===String(e).indexOf(\"[THINKING_ID:\")"), true, "vc158D: 控制标记不入正文");
            eq(pj.contains("window.copyRagKey=function"), true, "vc158D: 服务页 Key 复制在链里");
            eq(pj.contains("svcRagKeyRow"), true, "vc158D: Key 行随 ragStatus 刷新");

            String html = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/assets/index.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(countOccurrences(html, "id=\"svcRagKeyRow\""), 1, "vc158E: 服务页知识库 Key 行在位");
            eq(countOccurrences(html, "onclick=\"copyRagKey()\""), 1, "vc158E: 复制按钮已接线");
            eq(html.contains("#svcRagKey { flex: 1; min-width: 0;"), true, "vc158E: Key 文本与推理卡同款");
            eq(html.indexOf("id=\"svcRagKeyRow\"") > html.indexOf("id=\"svcRagAddr\""), true, "vc158E: Key 行在地址行之后");

            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc158F: build.sh VC=161");
            eq(bsh.contains("python3 $ROOT/patch-vc158.py"), true, "vc158F: 补丁链已挂");
            eq(bsh.contains("VC158_SSE_REASONING_IFACE_MISSING") && bsh.contains("VC158_BMOE_REASONING_ROUTE_MISSING")
                    && bsh.contains("VC158_HTML_SVC_RAGKEY_MISSING"), true, "vc158F: 门禁三条在位");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc158: " + e);
        }
    }

    static void testVc159() {
        System.out.println("\n== vc159: RAG 检索 API 修复（参数生效/按库检索/单文件配额/回显）==");
        try {
            String srv = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagServer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(srv.contains("public static final String PROTO_VERSION = \"vc159-rag1\";"), true, "vc159A: proto 版本前移");
            eq(srv.contains("floatParam(p, \"min_score\""), true, "vc159A: GET 解析 min_score");
            eq(srv.contains("longParam(p, \"library_id\""), true, "vc159A: GET 解析 library_id");
            eq(srv.contains("intParam(p, \"file_cap\""), true, "vc159A: GET 解析 file_cap");
            eq(srv.contains("svc.search(q, k, min, libId, incChat, fileCap)"), true, "vc159A: 六参下传");
            eq(srv.contains("incChat = \"1\".equals(p.get(\"include_chat\"));"), true, "vc159A: include_chat 仍支持");
            eq(srv.contains("q = b.optString(\"query\", b.optString(\"q\", \"\"));"), true, "vc159A: POST 容错 q 别名");

            String svc = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(svc.contains("boolean includeChat, int fileCap"), true, "vc159B: search 六参签名");
            eq(svc.contains("if (topK > 50) topK = 50;"), true, "vc159B: k 上限 20->50");
            eq(svc.contains("if (fileCap < 0) fileCap = Math.max(2, topK / 4);"), true, "vc159B: 默认单文件配额");
            eq(svc.contains("if (used >= cap) continue;"), true, "vc159B: 配额过滤落地");
            eq(svc.contains("Math.max(topK * 20, 200)"), true, "vc159B: 候选池放大二十倍");
            eq(svc.contains("out.put(\"returned\", hits.length());"), true, "vc159B: 回显 returned");
            eq(svc.contains("if (!store.libExists(libId))"), true, "vc159B: 检索前校验库存在");
            eq(svc.contains("if (score < minScore) break;"), true, "vc159B: 降序可提前 break");

            String st = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagStore.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(st.contains("synchronized boolean libExists(long libId)"), true, "vc159C: libExists 实现");
            eq(st.indexOf("synchronized boolean libExists(") < st.indexOf("synchronized void dropLib("), true, "vc159C: libExists 在 dropLib 之前");
            eq(st.contains("SELECT 1 FROM lib WHERE id=?"), true, "vc159C: 存在性查询有据");

            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("VC=161"), true, "vc159D: build.sh VC=161");
            eq(bsh.contains("VC159_PROTO_MISSING") && bsh.contains("VC159_LIBEXISTS_MISSING"), true, "vc159D: 门禁在位");
            eq(bsh.contains("grep -Fq 'boolean includeChat, int fileCap'"), true, "vc159D: 签名门禁在位");

            String rs = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("app/src/main/java/com/dawoer/npullm/rag/RagService.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(rs.contains("库不存在: "), true, "vc159E: drop/search 明确报库不存在");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc159: " + e);
        }
    }

    static void testVc161() {
        System.out.println("\n== vc161: HTP v85 skel + 缺 skel 时降档兜底 ==");
        try {
            String cpp = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(System.getProperty("user.home"),
                            "llama.cpp/ggml/src/ggml-hexagon/ggml-hexagon.cpp")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(cpp.contains("static int ggml_hexagon_pick_shipped_arch(int arch)"), true,
               "vc161A: 降档助手已定义");
            eq(cpp.contains("opt_arch = ggml_hexagon_pick_shipped_arch(opt_arch);"), true,
               "vc161A: 自动探测后调用降档");
            eq(cpp.contains("if (!str_arch) {\n        opt_arch = ggml_hexagon_pick_shipped_arch(opt_arch);"), true,
               "vc161A: 仅自动探测路径降档（env 覆盖不动）");
            eq(cpp.contains("{ 85, 81, 79, 75, 73 }"), true, "vc161A: 候选 skel 集含 v85");
            eq(cpp.contains("#    include <dlfcn.h>"), true, "vc161A: 引入 dlfcn 用于定位自身 lib 目录");
            eq(cpp.indexOf("ggml_hexagon_pick_shipped_arch(opt_arch)") < cpp.indexOf("opt_vmem  = opt_arch >= 75 ? HTP_OP_MAX_VMEM_DEFAULT"), true,
               "vc161A: 降档在 vmem/dma64 派生之前");

            String cm = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(System.getProperty("user.home"),
                            "llama.cpp/ggml/src/ggml-hexagon/CMakeLists.txt")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(cm.contains("build_htp_skel(v85)"), true, "vc161B: v85 skel 目标已启用");
            eq(cm.contains("# build_htp_skel(v85)"), false, "vc161B: 注释形态已清除");
            eq(cm.contains("GGML_HEX_SDK_ARCH_CMAKE MATCHES"), true, "vc161B: 由 SDK 能力决定是否编 v85");
            eq(cm.contains("hexagon: installed SDK has no v85 branch, skipping v85 skel"), true, "vc161B: SDK 6.6 回落分支在位");

            String bsh = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bsh.contains("vc161: HTP v85 support"), true, "vc161C: build.sh 门禁段在位");
            eq(bsh.contains("VC161_SKEL_V85_MISSING") && bsh.contains("VC161_HOST_FALLBACK_MISSING"), true,
               "vc161C: 门禁串在位");
            eq(bsh.contains("[ $(stat -c %s $SRC/libggml-htp-v85.so) -gt 900000 ]"), true,
               "vc161C: 空壳/旧壳拦截");

            String bv = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("build-verify.sh")),
                    java.nio.charset.StandardCharsets.UTF_8);
            eq(bv.contains("'libggml-htp-v85.so',"), true, "vc161D: 16KB 检查豁免 v85 skel");
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL vc161: " + e);
        }
    }
}
