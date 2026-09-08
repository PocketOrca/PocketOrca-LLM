package com.dawoer.npullm;

/** Logic tests (run on PC JVM, no device). Same package to reach package-private classes. */
public final class LlamaLogicTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testBuildCommand();
        testUnifiedParams();
        testCpuProfileExec();
        testExecEnvPolicy();
        testSplitArgs();
        testQuantWhitelist();
        testRingBuffer();
        testChatStreamerDelta();
        testChatStreamerStats();
        testRamLabel();
        testRebornAction();
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
                "/storage/emulated/0/Download/Qwen2.5-7B-Instruct-Q4_0.gguf", "0.0.0.0", 8080, 4096, "off", 4, false);
        eq(cmd[0].endsWith("libllamaserver.so"), true, "argv0 = jniLibs exe");
        eq(idx(cmd, "--ctx-size"), idx(cmd, "4096") - 1, "ctx replaced in place (4096)");
        eq(contains(cmd, "8192"), false, "profile ctx 8192 gone");
        int ro = idx(cmd, "--reasoning");
        eq(ro > 0 && "off".equals(cmd[ro + 1]), true, "reasoning off = --reasoning off explicit (0906 finding)");
        eq(idx(cmd, "--host"), cmd.length - 4, "host flag appended");
        eq(cmd[cmd.length - 1], "8080", "port appended last");
        eq(idx(cmd, "--device"), cmd.length - 10, "HTP0 before host/port (v0.5: threads+reasoning off)");

        // reasoning on appends flag
        String[] cmdR = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "on", 4, false);
        int r = idx(cmdR, "--reasoning");
        eq(r > 0 && "on".equals(cmdR[r + 1]), true, "reasoning on = --reasoning on appended");
        eq(idx(cmdR, "--ctx-size"), idx(cmdR, "8192") - 1, "ctx 8192 kept when UI=8192");

        // profile without ctx flag gets it appended
        String[] cmdA = LlamaServerManager.buildCommand("/x/libllamaserver.so",
                "--no-mmap -m {MODEL} --threads 4", "/s/m.gguf", "0.0.0.0", 8080, 65536, "off", 2, false);
        eq(idx(cmdA, "--ctx-size") > 0 && "65536".equals(cmdA[idx(cmdA, "--ctx-size") + 1]), true, "ctx appended when profile lacks it");

        // profile with pre-existing --reasoning gets value replaced
        String[] cmdP = LlamaServerManager.buildCommand("/x/libllamaserver.so",
                "-m {MODEL} --reasoning auto --ctx-size 4096", "/s/m.gguf", "0.0.0.0", 8080, 16384, "on", 4, false);
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
                "/s/m.gguf", "0.0.0.0", 8080, 0, "off", 0, false);
        eq(idx(cmd0, "--ctx-size"), idx(cmd0, "8192") - 1, "ctxSize<=0 keeps profile 8192");

        // == v1.2.1: --ubatch-size UI override (mirrors ctx-size) ==
        System.out.println("== buildCommand ubatch override (v1.2.1) ==");
        String OCL2 = "--no-mmap -m {MODEL} --ctx-size 8192 -fa on -ngl 99";
        // 10-arg: UI 256 replaces profile 1024 in place
        String[] ub = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 256);
        eq(idx(ub, "--ubatch-size"), idx(ub, "256") - 1, "ubatch replaced in place (256)");
        eq(contains(ub, "1024"), false, "profile ubatch 1024 gone");
        // UI ubatch == profile value: kept, single occurrence
        String[] ubKeep = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 1024);
        eq(count(ubKeep, "--ubatch-size"), 1, "ubatch 1024 kept exactly once");
        eq(count(ubKeep, "1024"), 1, "ubatch value not duplicated");
        // v1.2.5: ubatch <= 0 = strip the flag entirely (0 = off/auto), even if profile carries it
        String[] ub0 = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0);
        eq(contains(ub0, "--ubatch-size"), false, "ubatch<=0 strips profile flag (v1.2.5)");
        eq(contains(ub0, "1024"), false, "profile ubatch 1024 gone when UI=0");
        // v1.2.5: ubatch 0 on profile WITHOUT the flag -> still nothing appended
        String[] ub0b = LlamaServerManager.buildCommand("/x/libllamaserver.so", OCL2,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 0);
        eq(contains(ub0b, "--ubatch-size"), false, "ubatch 0 appends nothing (no profile flag)");
        // profile WITHOUT --ubatch-size: UI value appended
        String[] ubAdd = LlamaServerManager.buildCommand("/x/libllamaserver.so", OCL2,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false, 512);
        eq(idx(ubAdd, "--ubatch-size") > 0 && "512".equals(ubAdd[idx(ubAdd, "--ubatch-size") + 1]),
           true, "ubatch appended when profile lacks it");
        // 9-arg legacy overload passes ubatch 0 -> v1.2.5 strips profile flag
        String[] ub9 = LlamaServerManager.buildCommand("/x/libllamaserver.so", HTP,
                "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false);
        eq(contains(ub9, "--ubatch-size"), false, "9-arg overload (ubatch 0) strips flag (v1.2.5)");
    }

    /** Spec §4.1A: threads for all profiles, kv-offload default off, verified arg names. */
    static void testUnifiedParams() {
        System.out.println("== buildCommand unified params (spec §4, v0.5.0) ==");
        String HTP = "--no-mmap -m {MODEL} --ctx-size 8192 --ubatch-size 1024 -fa on -ngl 99 --device HTP0";
        String OCL = "--no-mmap -m {MODEL} --ctx-size 8192 -fa on -ngl 99";
        String CPU = "--no-mmap -m {MODEL} --ctx-size 4096 --threads 4";

        // §4.1A rule 1: --threads passed on ALL profiles, incl. htp
        String[] h = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 6, false);
        eq(idx(h, "--threads") > 0 && "6".equals(h[idx(h, "--threads") + 1]), true, "htp: --threads 6 appended");
        eq(idx(h, "--threads"), h.length - 8, "htp: --threads lands right before host/port");
        eq(idx(h, "--device"), h.length - 10, "htp: HTP0 before the host/port block (incl reasoning off)");
        eq(contains(h, "--no-kv-offload"), false, "htp: kv-offload default on = no flag");

        String[] o = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 8, true);
        eq(idx(o, "--threads") > 0 && "8".equals(o[idx(o, "--threads") + 1]), true, "ocl: --threads 8 appended");
        eq(contains(o, "--no-kv-offload"), true, "ocl: noKvOffload=true appends flag");
        eq(contains(o, "--device"), false, "ocl: no HTP device flag");

        // cpu profile carries --threads in profile args: UI value replaces in place
        String[] c = LlamaServerManager.buildCommand("/x/e", CPU, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 2, false);
        eq(idx(c, "--threads") > 0 && "2".equals(c[idx(c, "--threads") + 1]), true, "cpu: profile threads 4 -> UI 2");
        eq(contains(c, "4"), false, "cpu: old threads value gone");
        // §4.2 baseline default: threads 4 + kv offload off = --threads 4 present, no --no-kv-offload
        String[] b = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 8192, "off", 4, false);
        eq(contains(b, "--threads") && contains(b, "4"), true, "bench default: threads 4 present");
        eq(contains(b, "--no-kv-offload"), false, "bench default: kv-offload stays enabled");

        // v1.2.5: threads <= 0 = strip flag (0 = hardware auto-detect), even if profile carries it
        String[] p0 = LlamaServerManager.buildCommand("/x/e", CPU, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 0, false);
        eq(contains(p0, "--threads"), false, "threads 0 strips profile flag (v1.2.5)");
        // v1.2.5: threads 0 on profile without the flag -> nothing appended
        String[] p0b = LlamaServerManager.buildCommand("/x/e", OCL, "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 0, false);
        eq(contains(p0b, "--threads"), false, "threads 0 appends nothing (no profile flag)");
        // v1.2.5: threads 0 + ubatch 0 combined on HTP -> both stripped, ctx still replaced
        String[] both0 = LlamaServerManager.buildCommand("/x/e", HTP, "/s/m.gguf", "0.0.0.0", 8080, 16384, "off", 0, false, 0);
        eq(contains(both0, "--threads"), false, "combined 0: no --threads");
        eq(contains(both0, "--ubatch-size"), false, "combined 0: no --ubatch-size");
        eq(idx(both0, "--ctx-size"), idx(both0, "16384") - 1, "combined 0: ctx still replaced");

        // profile already carrying --no-kv-offload is not duplicated
        String[] dup = LlamaServerManager.buildCommand("/x/e", "-m {MODEL} --no-kv-offload",
                "/s/m.gguf", "0.0.0.0", 8080, 4096, "off", 4, true);
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
        eq(cpuEnv.get("GGML_HEXAGON_NDEV"), "0", "cpu: GGML_HEXAGON_NDEV=0 injected");
        java.util.Map<String, String> htpEnv = new java.util.HashMap<>();
        LlamaServerManager.applyExecEnvPolicy(htpEnv, true);
        eq(htpEnv.containsKey("GGML_HEXAGON_ARCH"), false, "htp: ARCH untouched");
        eq(htpEnv.containsKey("GGML_HEXAGON_NDEV"), false, "htp: NDEV untouched");
        // engine identity guard: htp must remain the only exec profile allowed
        // to touch hexagon; engine set unchanged (htp/ocl/cpu)
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/main/assets/profiles.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject profs = new org.json.JSONObject(raw).getJSONObject("profiles");
        eq(profs.length(), 3, "profiles count unchanged");
        eq(profs.has("htp") && profs.has("cpu") && profs.has("ocl"), true,
                "engine ids htp/ocl/cpu unchanged");
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
        // reasoning chunk (no content)
        eq(ServerChatStreamer.deltaPiece(jobj("{\"reasoning_content\":\"think\"}")), "think", "reasoning piece");
        // content wins over reasoning when both present
        eq(ServerChatStreamer.deltaPiece(jobj("{\"content\":\"a\",\"reasoning_content\":\"b\"}")), "a", "content precedence");
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
    }
}
