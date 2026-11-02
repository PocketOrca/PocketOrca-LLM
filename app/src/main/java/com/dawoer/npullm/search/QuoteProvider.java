package com.dawoer.npullm.search;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * vc126 structured data source: stock quotes via Tencent qt.gtimg.cn
 * (zero-auth, GBK response). Name->code resolution via the eastmoney
 * suggest API (zero-auth, UTF-8 JSON) so plain-Chinese queries like
 * "海康威视股价" resolve without a typed ticker.
 *
 * vc128: smartbox.gtimg.cn is DEAD in the wild (returns v_hint="N" for
 * every query from both home-LAN exits, GBK and UTF-8 alike, verified
 * 1022) -> replaced by searchapi.eastmoney.com/api/suggest/get (live
 * from both exits, no token, UTF-8). QuoteID prefix maps to market:
 * 0=sz, 1=sh, 116=hk, 105=us.
 *
 * Architecture (BRIEF-v150 §B): adapter layer — intent detection here,
 * direct API lookup, one rendered line spliced into the search block.
 * Every failure path returns null (silent fallback to plain search);
 * this class NEVER throws and NEVER blocks the chat path.
 *
 * Pure logic (intent/code-extract/parse/render) is JVM-testable against
 * live-captured fixtures; only fetch()/lookup() touch the network
 * (worker thread only).
 */
public class QuoteProvider {

    /** qt.gtimg.cn field indexes (verified on live samples 1021:
     *  v_sz002415 / v_hk00700 / v_usAAPL). */
    static final int F_NAME = 1;
    static final int F_CODE = 2;
    static final int F_PRICE = 3;
    static final int F_PREV = 4;
    static final int F_TIME = 30;
    static final int F_PCT = 32;

    static final int HTTP_TIMEOUT_MS = 5000;
    static final int MAX_LINE_CHARS = 200;

    /** Parsed quote (named static nested class — d8 zero-anonymous rule). */
    public static class Quote {
        public final String name;
        public final String code;
        public final String price;
        public final String prevClose;
        public final String changePct;
        public final String time;
        public Quote(String name, String code, String price, String prevClose,
                     String changePct, String time) {
            this.name = name;
            this.code = code;
            this.price = price;
            this.prevClose = prevClose;
            this.changePct = changePct;
            this.time = time;
        }
    }

    // ===== intent detection (pure) =====

    /** Quote intent: any market keyword. Checked BEFORE weather. */
    public static boolean isQuoteIntent(String q) {
        if (q == null) return false;
        return q.contains("股价") || q.contains("行情")
                || q.contains("大盘") || q.contains("指数");
    }

    // ===== code extraction (pure) =====

    /** Extract a market-prefixed ticker from free text; null if none.
     *  Forms: sz/sh/hk/us + digits or letters ("sz002415","hk00700",
     *  "usAAPL"), or a bare 6-digit A-share number (6xxxxx -> sh, else sz). */
    public static String extractCode(String query) {
        if (query == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(^|[^a-z0-9])(sz|sh|hk|us)\\s*([0-9]{4,6}|[a-z.]{1,6})")
                .matcher(query);
        if (m.find()) {
            String pre = m.group(2).toLowerCase();
            String body = m.group(3);
            if (body.matches("[0-9]+") && body.length() < 4) return null;
            return pre + body;
        }
        m = java.util.regex.Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])")
                .matcher(query);
        if (m.find()) {
            String d = m.group(1);
            return (d.charAt(0) == '6' ? "sh" : "sz") + d;
        }
        return null;
    }

    /** Strip intent/filler words for name resolution ("海康威视股价" ->
     *  "海康威视"). Null/empty when nothing remains (no lookup). */
    public static String stripIntentWords(String q) {
        if (q == null) return null;
        String s = q;
        String[] fillers = {"今天", "明天", "目前", "现在", "最新", "多少",
                "股价", "行情", "大盘", "指数", "怎么样", "怎样", "如何"};
        for (int i = 0; i < fillers.length; i++) s = s.replace(fillers[i], "");
        s = s.trim();
        if (s.length() == 0 || s.length() > 12) return null;
        return s;
    }

    // ===== eastmoney name->code (pure parse + thin HTTP) =====

    /** Parse an eastmoney suggest JSON body; first usable equity candidate.
     *  Wire shape (live 1022): {"QuotationCodeTable":{"Data":[
     *  {"Code":"002415","Name":"海康威视","QuoteID":"0.002415",..},..]}}.
     *  QuoteID prefix -> market: 0=sz, 1=sh, 116=hk, 105=us; other
     *  prefixes (ETF/fund/JPX rows) are skipped. Returns market+code
     *  ("sz002415") or null on any miss. */
    public static String parseSuggest(String body) {
        if (body == null) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject qt = root.optJSONObject("QuotationCodeTable");
            if (qt == null) return null;
            org.json.JSONArray data = qt.optJSONArray("Data");
            if (data == null) return null;
            for (int i = 0; i < data.length(); i++) {
                org.json.JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                String qid = o.optString("QuoteID", "");
                int dot = qid.indexOf('.');
                if (dot <= 0) continue;
                String mkt = qid.substring(0, dot);
                String code = qid.substring(dot + 1);
                String pre;
                if ("0".equals(mkt)) pre = "sz";
                else if ("1".equals(mkt)) pre = "sh";
                else if ("116".equals(mkt)) pre = "hk";
                else if ("105".equals(mkt)) pre = "us";
                else pre = null;
                if (pre == null) continue;
                if (code.length() == 0 || code.length() > 6) continue;
                return pre + code;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== response parsing (pure) =====

    /** Parse a GBK-decoded qt.gtimg.cn body ("v_xxx=\"...\";").
     *  Returns null on any malformed/short input (soft). */
    public static Quote parse(String body) {
        if (body == null) return null;
        int q1 = body.indexOf('"');
        int q2 = body.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) return null;
        String[] f = body.substring(q1 + 1, q2).split("~");
        if (f.length <= F_PCT + 1) return null;
        String name = f[F_NAME].trim();
        String code = f[F_CODE].trim();
        String price = f[F_PRICE].trim();
        String prev = f[F_PREV].trim();
        String pct = f[F_PCT].trim();
        String time = f[F_TIME].trim();
        if (name.length() == 0 || code.length() == 0) return null;
        if (!price.matches("[0-9]+(\\.[0-9]+)?")) return null;
        if (!prev.matches("[0-9]+(\\.[0-9]+)?")) return null;
        if (!pct.matches("-?[0-9]+(\\.[0-9]+)?")) return null;
        // A-share wire time = yyyyMMddHHmmss -> MM-dd HH:mm; other markets
        // keep their raw string (already human-readable).
        if (time.matches("[0-9]{14}")) {
            time = time.substring(4, 6) + "-" + time.substring(6, 8)
                    + " " + time.substring(8, 10) + ":"
                    + time.substring(10, 12);
        }
        return new Quote(name, code, price, prev, pct, time);
    }

    // ===== rendering (pure) =====

    /** One structured line, <=200 chars:
     *  〔行情〕海康威视 002415 现价 32.34 昨收 32.82 -1.46%（腾讯行情 10-21 16:14） */
    public static String render(Quote q) {
        if (q == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("\u3014\u884c\u60c5\u3015").append(q.name).append(" ")
                .append(q.code).append(" \u73b0\u4ef7 ").append(q.price)
                .append(" \u6628\u6536 ").append(q.prevClose).append(" ")
                .append(q.changePct).append("%\uff08\u817e\u8baf\u884c\u60c5 ")
                .append(q.time == null ? "" : q.time).append("\uff09");
        String s = sb.toString();
        return s.length() > MAX_LINE_CHARS ? s.substring(0, MAX_LINE_CHARS) : s;
    }

    // ===== HTTP (thin; worker thread only) =====

    /** GET a URL body decoded with the given charset; null on any failure.
     *  Never throws. qt.gtimg.cn is GBK, eastmoney suggest is UTF-8. */
    static String httpGet(String url, String charset) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", SearchService.BROWSER_UA);
            int rc = c.getResponseCode();
            if (rc != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), charset);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Suggestion lookup: Chinese name -> market+code. Null on any miss. */
    public String resolveByName(String name) {
        if (name == null) return null;
        try {
            String u = "https://searchapi.eastmoney.com/api/suggest/get?input="
                    + URLEncoder.encode(name, "UTF-8")
                    + "&type=14&count=5";
            return parseSuggest(httpGet(u, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fetch + parse one quote by code. Null on any failure. */
    public Quote fetch(String code) {
        if (code == null || code.length() == 0) return null;
        return parse(httpGet("https://qt.gtimg.cn/q=" + code, "GBK"));
    }

    /** Full lookup: explicit code first, then name resolution (intent
     *  keywords stripped). Null = silent fallback to plain search. */
    public Quote lookup(String query) {
        if (!isQuoteIntent(query)) return null;
        String code = extractCode(query);
        if (code == null) code = resolveByName(stripIntentWords(query));
        return fetch(code);
    }
}
