package com.dawoer.npullm.mcp.diag;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * vc186：trace 落盘（JSONL，滚动 1 份）。
 *
 * 为什么之前只有 logcat 不行：logcat 在本 app 里不稳定（release 包看不到 tag）、
 * 会被系统环形缓冲挤掉，且「静默失败率=0」这条承诺没有可量化的事实来源。
 *
 * 硬约束（与 v3 设计一致）：
 *   - 只存参数 **hash**，不存原始参数/正文/号码（trace 可能被整份导出给第三方排查）。
 *   - 每行一个 JSON 对象，≤ 512 字节；错误文本按 redact 规则处理（>=7 位数字打码）。
 *   - 写失败一律吞掉：诊断日志不能反过来弄坏主流程。
 */
public final class McpTraceFile {

    /** 单行上限（超出截断错误文本，不截断 JSON 结构）。 */
    public static final int MAX_LINE = 512;
    /** 滚动阈值：当前文件到这个大小就改名为 .1。 */
    public static final long DEFAULT_MAX_BYTES = 200L * 1024L;

    private final File dir;
    private final String name;
    private final long maxBytes;

    public McpTraceFile(File dir, String name, long maxBytes) {
        this.dir = dir;
        this.name = name == null ? "trace.jsonl" : name;
        this.maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    public File file() {
        return new File(dir, name);
    }

    /** 追加一行（调用方传完整 JSON 行；仍会做长度兜底）。线程安全，串行化。 */
    public synchronized void append(String jsonLine) {
        if (jsonLine == null || jsonLine.length() == 0) return;
        try {
            if (dir != null && !dir.exists()) dir.mkdirs();
            File f = file();
            rotateIfNeeded(f);
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                out.write(jsonLine.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) { }
            }
        } catch (Throwable ignored) {
            // 诊断日志不能反过来弄坏主流程
        }
    }

    private void rotateIfNeeded(File f) {
        try {
            if (!f.exists() || f.length() < maxBytes) return;
            File old = new File(dir, name + ".1");
            try { if (old.exists()) old.delete(); } catch (Throwable ignored) { }
            if (!f.renameTo(old)) {
                // 改名失败（占用）：直接清空，宁可丢历史也不让文件无限长
                try { new FileOutputStream(f, false).close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** 读最后 n 行（n<=0 → 空）。文件 ≤ 200KB，整读即可。 */
    public static List<String> tail(File dir, String name, int n) {
        List<String> out = new ArrayList<>();
        if (n <= 0) return out;
        File f = new File(dir, name);
        if (!f.exists()) return out;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            String all = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = all.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.length() > 0) out.add(s);
            }
            if (out.size() > n) return new ArrayList<>(out.subList(out.size() - n, out.size()));
            return out;
        } catch (Throwable t) {
            return out;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /** 一站式：拼一行并追加。 */
    public void tool(String tool, boolean ok, long ms, String code, String argsHash, String err) {
        append(oneLine(System.currentTimeMillis(), "tool", tool, ok, ms, code, argsHash, err));
    }

    public void proto(String event, String detail) {
        append(oneLine(System.currentTimeMillis(), "proto", event, false, 0L, "", "", detail));
    }

    /** 拼一行 JSON。字段名短、顺序固定，便于人眼扫和 awk 处理。 */
    public static String oneLine(long ts, String kind, String tool, boolean ok, long ms,
                                 String code, String argsHash, String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", ts);
            o.put("k", kind == null ? "" : kind);
            o.put("n", tool == null ? "" : tool);
            o.put("ok", ok);
            if (ms > 0) o.put("ms", ms);
            if (code != null && code.length() > 0) o.put("code", code);
            if (argsHash != null && argsHash.length() > 0) o.put("a", argsHash);
            if (err != null && err.length() > 0) o.put("err", redact(err));
        } catch (Exception ignored) { }
        String s = o.toString();
        if (s.length() > MAX_LINE) s = s.substring(0, MAX_LINE);
        return s;
    }

    /**
     * 脱敏 + 压成单行：控制字符换空格；连续 >=7 位数字（手机号/验证码/账号）→ ****；截断 200 字。
     * 规则故意简单且可测：复杂正则在这类场景里只会漏。
     */
    public static String redact(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        String flat = sb.toString().replaceAll("\\d{7,}", "****");
        if (flat.length() > 200) flat = flat.substring(0, 200);
        return flat;
    }
}
