package com.dawoer.npullm.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块化分块（纯 Java，无 android.* —— JVM 可测）。
 *
 * 三种形态：
 *   md/doc   → 按标题层级切，块标题 = 祖先标题路径（"H1 > H2"），不跨章节
 *   code     → 按顶层声明边界切（function/class/def/public/export/impl/fn…）
 *   text     → 滑动窗口，CHUNK_CHARS 窗口 + OVERLAP_CHARS 重叠
 *
 * 尺寸口径：CHUNK_CHARS=700 字符。中文场景 1 token ≈ 1.3~1.5 字符，即
 * 约 500 token —— 与任务书的「500tok+50 重叠」对齐（用字符做单位是为了
 * 不引入分词器依赖，代价是英文块实际 token 更少，无害）。
 *
 * 纯函数：同一 (path, text) 必得同一结果，分块结果参与指纹幂等，必须稳定。
 */
public final class RagChunker {

    public static final int CHUNK_CHARS = 700;
    public static final int OVERLAP_CHARS = 70;
    /** 小于这个长度的块丢弃（通常是只剩标题的碎块）。 */
    public static final int MIN_CHUNK_CHARS = 24;

    public static final class Chunk {
        public final int seq;
        public final String title;
        public final String text;
        Chunk(int seq, String title, String text) {
            this.seq = seq;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }
    }

    private RagChunker() {}

    /** 按文件路径后缀分派。二进制（含 NUL）→ 空列表。 */
    public static List<Chunk> chunk(String path, String text) {
        List<Chunk> out = new ArrayList<Chunk>();
        if (text == null || text.length() == 0) return out;
        if (isBinary(text)) return out;
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".mdx") || lower.endsWith(".rst")
                || lower.endsWith(".adoc")) {
            return chunkMarkdown(text);
        }
        if (isCode(lower)) return chunkCode(text);
        return chunkPlain(text);
    }

    public static boolean isBinary(String text) {
        if (text == null) return true;
        int n = Math.min(text.length(), 4096);
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == 0) return true;
        }
        return false;
    }

    static boolean isCode(String lowerPath) {
        String[] exts = {".java", ".kt", ".py", ".js", ".ts", ".tsx", ".jsx", ".c", ".h",
                ".cpp", ".cc", ".hpp", ".rs", ".go", ".sh", ".bash", ".sql", ".gradle",
                ".toml", ".yaml", ".yml", ".json", ".xml", ".css", ".scss"};
        for (int i = 0; i < exts.length; i++) {
            if (lowerPath.endsWith(exts[i])) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ md

    static List<Chunk> chunkMarkdown(String text) {
        List<Chunk> out = new ArrayList<Chunk>();
        String[] lines = text.split("\n", -1);
        String[] stack = new String[6];
        StringBuilder buf = new StringBuilder();
        int seq = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lvl = headingLevel(line);
            if (lvl > 0) {
                seq = flushMarkdown(out, buf, stack, seq);
                buf.setLength(0);
                stack[lvl - 1] = line.trim();
                for (int k = lvl; k < 6; k++) stack[k] = null;
            }
            buf.append(line).append('\n');
            while (buf.length() > CHUNK_CHARS) {
                int cut = lastBreak(buf, CHUNK_CHARS);
                if (cut <= 0) cut = CHUNK_CHARS;
                String piece = buf.substring(0, cut);
                seq = emit(out, seq, pathOf(stack), piece);
                buf.delete(0, cut);
            }
        }
        flushMarkdown(out, buf, stack, seq);
        return out;
    }

    static int flushMarkdown(List<Chunk> out, StringBuilder buf, String[] stack, int seq) {
        if (buf.length() == 0) return seq;
        String piece = buf.toString();
        buf.setLength(0);
        return emit(out, seq, pathOf(stack), piece);
    }

    static String pathOf(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.length; i++) {
            if (stack[i] == null || stack[i].length() == 0) continue;
            if (sb.length() > 0) sb.append(" > ");
            sb.append(stack[i]);
        }
        return sb.toString();
    }

    /** `#`~`######` + 空格 → 级别 1..6；否则 0。 */
    static int headingLevel(String line) {
        if (line == null || line.length() < 2 || line.charAt(0) != '#') return 0;
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        if (n > 6) return 0;
        if (n >= line.length() || line.charAt(n) != ' ') return 0;
        // 排除 "#######" 这类超长（n>6 已挡）与 "#hashtag"（无空格已挡）
        return n;
    }

    // ---------------------------------------------------------------- code

    static List<Chunk> chunkCode(String text) {
        List<Chunk> out = new ArrayList<Chunk>();
        String[] lines = text.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        String title = "";
        int seq = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isTopLevelDecl(line) && buf.length() > MIN_CHUNK_CHARS) {
                seq = emit(out, seq, title, buf.toString());
                buf.setLength(0);
            }
            if (title.length() == 0 && isTopLevelDecl(line)) title = line.trim();
            buf.append(line).append('\n');
            while (buf.length() > CHUNK_CHARS) {
                int cut = lastBreak(buf, CHUNK_CHARS);
                if (cut <= 0) cut = CHUNK_CHARS;
                seq = emit(out, seq, title, buf.substring(0, cut));
                buf.delete(0, cut);
            }
        }
        if (buf.length() > 0) seq = emit(out, seq, title, buf.toString());
        return out;
    }

    /** 缩进 ≤2、以常见声明关键字开头 → 视为一个顶层单元的开头。 */
    static boolean isTopLevelDecl(String line) {
        if (line == null) return false;
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        if (i > 2) return false;
        String s = line.substring(i);
        String[] kw = {"public ", "private ", "protected ", "static ", "final ",
                "class ", "interface ", "enum ", "struct ", "impl ", "function ",
                "def ", "fn ", "func ", "export ", "async ", "const ", "let ", "var ",
                "package ", "import ", "using ", "module ", "namespace ", "template",
                "@", "#include", "#define", "---"};
        for (int k = 0; k < kw.length; k++) {
            if (s.startsWith(kw[k])) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- plain

    static List<Chunk> chunkPlain(String text) {
        List<Chunk> out = new ArrayList<Chunk>();
        int n = text.length();
        int seq = 0;
        int start = 0;
        while (start < n) {
            int end = Math.min(n, start + CHUNK_CHARS);
            if (end < n) {
                int cut = lastBreakFrom(text, start, end);
                if (cut > start + MIN_CHUNK_CHARS) end = cut;
            }
            seq = emit(out, seq, "", text.substring(start, end));
            if (end >= n) break;
            int next = end - OVERLAP_CHARS;
            start = next > start ? next : end;
        }
        return out;
    }

    // --------------------------------------------------------------- utils

    /** 在 [start, limit) 内找最后一个换行/句末，返回切点（含标点后一位）。 */
    static int lastBreakFrom(String text, int start, int limit) {
        for (int i = limit - 1; i > start + 40; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == ';') {
                return i + 1;
            }
        }
        return limit;
    }

    static int lastBreak(CharSequence buf, int limit) {
        int lim = Math.min(limit, buf.length());
        for (int i = lim - 1; i > 40; i--) {
            char c = buf.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？') return i + 1;
        }
        return lim;
    }

    static int emit(List<Chunk> out, int seq, String title, String piece) {
        if (piece == null) return seq;
        String t = piece.trim();
        if (t.length() < MIN_CHUNK_CHARS) return seq;
        out.add(new Chunk(seq, title, t));
        return seq + 1;
    }
}
