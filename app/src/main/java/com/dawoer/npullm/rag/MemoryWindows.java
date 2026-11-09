package com.dawoer.npullm.rag;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 聊天记忆窗口切分（纯逻辑，无 Android 依赖，JVM 可测）。
 *
 * 语义：把一个会话里连续的消息拼成 <=maxChars 的窗口，块头带 [时间 角色] 前缀。
 * 前缀不是装饰——它让「谁说的 / 什么时候」变成可检索的语义信号；没有前缀，
 * 检索只能命中内容，时序信息全丢。
 *
 * 块身份 = chat/&lt;cid&gt;#&lt;首条消息 id&gt;：追加新消息时只有最后一块正文变化，
 * 前面的块 hash 不变 → 增量索引只重嵌最后一块，不重扫历史。
 */
public final class MemoryWindows {

    public static final int MAX_CHARS = 700;

    /** 一条消息（ChatStore 读出来的纯数据）。 */
    public static final class Turn {
        public final long id;
        public final String cid;
        public final String role;
        public final String content;
        public final long ts;

        public Turn(long id, String cid, String role, String content, long ts) {
            this.id = id;
            this.cid = cid == null || cid.length() == 0 ? ChatStore.MAIN_CID : cid;
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
            this.ts = ts;
        }
    }

    /** 一个记忆窗口。 */
    public static final class Win {
        public final String rel;
        public final String text;
        public final long ts;

        public Win(String rel, String text, long ts) {
            this.rel = rel;
            this.text = text;
            this.ts = ts;
        }
    }

    private MemoryWindows() {}

    /** 块头：[2026-11-02 21:13 user] */
    public static String header(long ts, String role) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        long t = ts > 0 ? ts : System.currentTimeMillis();
        return "[" + f.format(new Date(t)) + " " + role + "] ";
    }

    public static List<Win> build(List<Turn> turns, int maxChars) {
        List<Win> out = new ArrayList<Win>();
        if (turns == null || turns.isEmpty()) return out;
        if (maxChars < 200) maxChars = MAX_CHARS;
        Map<String, List<Turn>> byCid = new LinkedHashMap<String, List<Turn>>();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.content.trim().length() == 0) continue;
            List<Turn> l = byCid.get(t.cid);
            if (l == null) {
                l = new ArrayList<Turn>();
                byCid.put(t.cid, l);
            }
            l.add(t);
        }
        for (Map.Entry<String, List<Turn>> e : byCid.entrySet()) {
            String cid = e.getKey();
            List<Turn> list = e.getValue();
            StringBuilder buf = new StringBuilder();
            long firstId = 0;
            long lastTs = 0;
            for (int i = 0; i < list.size(); i++) {
                Turn t = list.get(i);
                String line = header(t.ts, t.role) + t.content.trim() + "\n";
                if (buf.length() > 0 && buf.length() + line.length() > maxChars) {
                    out.add(new Win("chat/" + cid + "#" + firstId, buf.toString(), lastTs));
                    buf.setLength(0);
                }
                if (buf.length() == 0) firstId = t.id;
                buf.append(line);
                lastTs = t.ts;
            }
            if (buf.length() > 0) {
                out.add(new Win("chat/" + cid + "#" + firstId, buf.toString(), lastTs));
            }
        }
        return out;
    }
}
