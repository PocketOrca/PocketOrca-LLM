package com.dawoer.npullm.search;

import org.json.JSONArray;
import org.json.JSONObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * vc122 web-search service (BRIEF-vc122-websearch-1020 §2.1).
 *
 * Pure-Java, JVM-testable. Zero imports from the engine-side packages
 * (llama / bmoe / sampler) — architectural isolation asserted by tests.
 *
 * Stage 1 ships a single channel: SearXNG-compatible JSON endpoint
 * (base URL user-configured, default example points at the LAN box).
 * The aggregation structure (channels[] -> merge -> dedupe) is in place
 * so later engine stages slot in without signature churn.
 */
public class SearchService {

    // ===== data shapes (named static nested classes — d8 zero-anonymous rule) =====

    /** One upstream channel spec. type="searxng" uses {base}/search?q=&format=json.
     *  vc123: spec carries the engines.json EngineSpec so HTML/API fetchers can
     *  run their externalized selectors; base is used by searxng only. */
    public static class ChannelSpec {
        public final String id;
        public final String type;
        public final String base;
        public final EngineRegistry.EngineSpec spec;
        /** vc135: 最近一次失败原因；"" = 无失败（含真 0 命中）。 */
        public volatile String lastError = "";
        public ChannelSpec(String id, String type, String base) {
            this(id, type, base, null);
        }
        public ChannelSpec(String id, String type, String base, EngineRegistry.EngineSpec spec) {
            this.id = id == null ? "" : id;
            this.type = type == null ? "" : type;
            this.base = base == null ? "" : base;
            this.spec = spec;
        }
    }

    public static class SearchRequest {
        public final String query;
        public final int count;
        public final List<ChannelSpec> channels;
        public SearchRequest(String query, int count, List<ChannelSpec> channels) {
            this.query = query == null ? "" : query;
            this.count = clampCount(count);
            this.channels = channels == null ? new ArrayList<ChannelSpec>() : channels;
        }
    }

    public static class SearchResult {
        public final String title;
        public final String url;
        public final String content;
        public final String engine;
        public SearchResult(String title, String url, String content, String engine) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.content = content == null ? "" : content;
            this.engine = engine == null ? "" : engine;
        }
    }

    public static class SearchResponse {
        public final List<SearchResult> results;
        public final long elapsedMs;
        public final List<String> sources; // channel ids that answered
        public final java.util.Map<String, String> outcomes; // channel id -> 失败原因（仅失败项）
        /** vc135: 5-arg constructor with outcomes. */
        public SearchResponse(List<SearchResult> results, long elapsedMs, List<String> sources, java.util.Map<String, String> outcomes) {
            this.results = results;
            this.elapsedMs = elapsedMs;
            this.sources = sources;
            this.outcomes = outcomes != null ? outcomes : new java.util.LinkedHashMap<String, String>();
        }
        /** vc135: backward-compatible 4-arg, delegates to 5-arg with empty outcomes. */
        public SearchResponse(List<SearchResult> results, long elapsedMs, List<String> sources) {
            this(results, elapsedMs, sources, new java.util.LinkedHashMap<String, String>());
        }
    }

    // ===== tunables =====
    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 8000;
    static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    public static final int COUNT_MIN = 1;
    public static final int COUNT_MAX = 20;
    public static final int COUNT_DEFAULT = 5;
    public static final int BLOCK_MAX_CHARS = 4000;
    /** vc123: hard cap on the merged result list (aggregation over engines). */
    public static final int MERGE_MAX = 8;

    // vc135: stamp the channel id onto results created by pure parse helpers
    // (they have no ChannelSpec). An engine already carried by the upstream
    // payload (searxng JSON) wins over the channel id.
    public static List<SearchResult> withEngine(List<SearchResult> src, String id) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (src == null) return out;
        for (SearchResult r : src) {
            out.add(new SearchResult(r.title, r.url, r.content,
                    r.engine.length() > 0 ? r.engine : (id == null ? "" : id)));
        }
        return out;
    }

    // vc135: translate Throwable to a short reason string
    public static String errOf(Throwable t) {
        if (t == null) return "error";
        if (t instanceof java.net.SocketTimeoutException) return "timeout";
        if (t instanceof java.net.UnknownHostException) return "dns";
        String n = t.getClass().getSimpleName();
        return n.length() == 0 ? "error" : "error: " + n;
    }

    // ===== public API =====

    /**
     * Aggregation skeleton: query each channel, merge with URL-normalization
     * dedupe (insertion order preserved). vc123 = built-in engines: dispatch
     * by channel type, each enabled engine contributes up to req.count
     * results, merge capped at the requested count. Sequential execution (no thread
     * pool — d8 zero-anonymous rule).
     */

    /** vc124: per-instance circuit breaker state (BRIEF-vc124 §0.4). */
    private final CircuitBreakerTracker breaker = new CircuitBreakerTracker();

    public SearchResponse search(SearchRequest req) {
        long t0 = System.currentTimeMillis();
        List<SearchResult> merged = new ArrayList<SearchResult>();
        List<String> sources = new ArrayList<String>();
        java.util.Map<String, String> outcomes = new java.util.LinkedHashMap<String, String>();
        if (req != null && req.query.trim().length() > 0) {
            Map<String, SearchResult> seen = new LinkedHashMap<String, SearchResult>();
            for (ChannelSpec ch : req.channels) {
                ch.lastError = "";
                // vc124: circuit breaker — a tripped engine in cooldown is
                // skipped entirely (BRIEF-vc124 §0.4/§1.2).
                if (!breaker.canRetry(ch.id)) {
                    ch.lastError = "suspended: circuit breaker";
                    outcomes.put(ch.id, ch.lastError);
                    continue;
                }
                List<SearchResult> rs;
                if ("searxng".equals(ch.type)) {
                    rs = fetchSearxng(ch, req.query, req.count);
                } else if ("bing".equals(ch.type) || "bing_cn".equals(ch.type)) {
                    rs = fetchBingHtml(ch, req.query, req.count);
                } else if ("sogou".equals(ch.type)) {
                    rs = fetchSogouHtml(ch, req.query, req.count);
                } else if ("ddg".equals(ch.type)) {
                    rs = fetchDdgHtml(ch, req.query, req.count);
                } else if ("wikipedia".equals(ch.type)) {
                    rs = fetchWikipediaApi(ch, req.query, req.count);
                } else if ("baidu".equals(ch.type)) {
                    rs = fetchBaiduHtml(ch, req.query, req.count);
                } else if ("github".equals(ch.type)) {
                    rs = fetchGithubApi(ch, req.query, req.count);
                } else {
                    rs = new ArrayList<SearchResult>(); // unknown type: skip, never throw
                }
                if (!rs.isEmpty()) {
                    sources.add(ch.id);
                    breaker.recordSuccess(ch.id);
                } else {
                    // vc124: empty SERP counts as one consecutive failure
                    // (3 in a row -> 30s cooldown, checked via canRetry above).
                    breaker.recordFailure(ch.id);
                }
                rs = withEngine(rs, ch.id); // vc135: stamp engine for the JSON contract
                for (SearchResult r : rs) {
                    String key = normalizeUrl(r.url);
                    if (key.length() == 0) continue;
                    SearchResult dup = seen.get(key);
                    if (dup == null) {
                        seen.put(key, r);
                    } else if (dup.content.length() < r.content.length()) {
                        seen.put(key, r); // keep the richer copy
                    }
                }
            }
            // vc135: collect all non-empty lastError into outcomes
            for (ChannelSpec ch : req.channels) {
                if (ch.lastError.length() > 0) outcomes.put(ch.id, ch.lastError);
            }
            merged.addAll(seen.values());
            int cap = req.count;
            if (merged.size() > cap) {
                merged = merged.subList(0, cap);
            }
        }
        return new SearchResponse(merged, System.currentTimeMillis() - t0, sources, outcomes);
    }

    /**
     * SearXNG-compatible JSON channel:
     * GET {base}/search?q=<URLEncode>&format=json  (timeout 8s x2, browser UA).
     * Missing/soft-failing upstream yields an empty list — never throws.
     */
    public List<SearchResult> fetchSearxng(ChannelSpec ch, String query, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        String base = normalizeBaseUrl(ch.base);
        if (base.length() == 0 || query.trim().length() == 0) return out;
        HttpURLConnection c = null;
        try {
            String url = base + "/search?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&format=json";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", BROWSER_UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { ch.lastError = "http " + code; return out; }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("results");
            if (arr == null) { ch.lastError = "parse"; return out; }
            int want = clampCount(count);
            for (int i = 0; i < arr.length() && out.size() < want; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // org.json pitfall #7: optString(key,null) returns literal "null"
                // for BOTH missing key and JSON null — always opt()+instanceof.
                String title = str(o, "title");
                String u = str(o, "url");
                String content = str(o, "content");
                if (u.length() == 0) continue;
                out.add(new SearchResult(title, u, content,
                        o.optString("engine", ch.id)));
            }
        } catch (Throwable t) {
            ch.lastError = errOf(t);
            return new ArrayList<SearchResult>(); // soft-fail: upstream down/parse error
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
        return out;
    }

    // ===== vc123 built-in engine fetchers =====

    /** Fetch a SERP page as a jsoup Document; null on any failure (soft-fail). */
    private Document fetchHtmlDoc(String url) {
        try {
            return Jsoup.connect(url).userAgent(BROWSER_UA)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * vc129: POST variant for the DDG lite endpoint. GET on any
     * duckduckgo host returns a 202 anomaly challenge on residential
     * egress; POST with a form body returns the plain SERP (probe D/E
     * 1022: 4/4 stable, EN and CJK queries). Jsoup encodes the form body
     * and follows redirects.
     */
    private Document fetchHtmlDocPost(String url, String q) {
        try {
            return Jsoup.connect(url).data("q", q).data("b", "")
                    .userAgent(BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(CONNECT_TIMEOUT_MS)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .post();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Substitute {query}/{count} in an engines.json URL template. */
    public static String expandTemplate(String tpl, String query, int count) {
        if (tpl == null) return "";
        String enc;
        try { enc = URLEncoder.encode(query == null ? "" : query, "UTF-8"); }
        catch (Throwable e) { enc = query == null ? "" : query; }
        return tpl.replace("{query}", enc).replace("{count}", String.valueOf(count));
    }

    /**
     * Bing HTML SERP (vc123, selectors externalized in engines.json).
     * Verified live structure: li.b_algo blocks, first absolute http anchor =
     * target link, h2 = title, .b_caption = snippet. Defensive: bing.com/ck/
     * redirect hrefs are kept as-is (results still usable). Soft-fails to an
     * empty list on challenge pages / structure changes / network errors.
     */
    public List<SearchResult> fetchBingHtml(ChannelSpec ch, String query, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        String items = "li.b_algo", titleSel = "h2", snipSel = ".b_caption";
        String tpl = "";
        if (ch != null && ch.spec != null) {
            if (ch.spec.items.length() > 0) items = ch.spec.items;
            if (ch.spec.title.length() > 0) titleSel = ch.spec.title;
            if (ch.spec.snippet.length() > 0) snipSel = ch.spec.snippet;
            tpl = ch.spec.url;
        }
        // vc129: SERP quality gate — Bing serves degraded buckets whose
        // li.b_algo SERPs are well-formed but share zero query terms with
        // the query (cn host on EN brand queries, www host on CJK queries;
        // 1022 probe A/B on VM egress). Detect via serpMatchesQuery and
        // retry once on the complementary host (cn <-> www). If both are
        // junk, return empty so the aggregation cap is not crowded out and
        // the circuit breaker sees a failure. Negative outcomes are cached
        // per query (FIFO cap, d8 zero-anonymous: no lambdas, no anonymous
        // classes, no iterator remove).
        String url = (tpl.length() > 0)
                ? expandTemplate(tpl, query, count)
                : "https://cn.bing.com/search?q="
                    + safeEnc(query) + "&count=" + count;
        Document doc = fetchHtmlDoc(url);
        if (doc == null) { ch.lastError = "parse"; }
        List<SearchResult> first = doc == null
                ? out : parseBingSerp(doc, items, titleSel, snipSel, count);
        if (!first.isEmpty() && !serpMatchesQuery(query, titlesOf(first))
                && !RETRY_BING_QUERIES.contains(query)) {
            RETRY_BING_QUERIES.add(query);
            if (RETRY_BING_QUERIES.size() > RETRY_BING_CAP) {
                RETRY_BING_QUERIES.remove(0);
            }
            String alt = bingFallbackHost(url);
            if (alt.length() > 0) {
                Document altDoc = fetchHtmlDoc(alt);
                if (altDoc == null) { ch.lastError = "parse"; }
                List<SearchResult> second = altDoc == null
                        ? new ArrayList<SearchResult>()
                        : parseBingSerp(altDoc, items, titleSel, snipSel, count);
                if (!second.isEmpty() && serpMatchesQuery(query, titlesOf(second))) {
                    return second;
                }
            }
            return new ArrayList<SearchResult>();
        }
        return first;
    }

    /** vc129: queries already given the one-shot bing host fallback. */
    static final java.util.List<String> RETRY_BING_QUERIES = new ArrayList<String>();
    /** vc129: FIFO cap for the negative-outcome cache. */
    static final int RETRY_BING_CAP = 128;

    /** vc129: swap cn<->www bing host for the quality-gate retry. Empty for
     * custom templates (host swap unsupported, no retry). */
    static String bingFallbackHost(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("https://cn.bing.com/")) {
            return "https://www.bing.com/" + u.substring("https://cn.bing.com/".length());
        }
        if (u.startsWith("https://www.bing.com/")) {
            return "https://cn.bing.com/" + u.substring("https://www.bing.com/".length());
        }
        return "";
    }

    /** vc129: join result titles (space-separated) for the quality gate. */
    static String titlesOf(List<SearchResult> rs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rs.size(); i++) {
            if (i > 0) b.append(' ');
            b.append(rs.get(i).title);
        }
        return b.toString();
    }

    /**
     * vc129: SERP quality gate. True when at least one substantive query
     * token (len >= 4, or containing a CJK ideograph) appears in the SERP
     * titles. Bing's degraded buckets (1022 probe A/B: cn host EN-brand ->
     * DeepSeek Zhihu digest, www host CJK -> On-This-Day history items)
     * share zero such terms, while genuine SERPs echo the query. Queries
     * with more than 8 tokens (natural-language sentences) are never gated
     * to avoid false rejections; short/pure-digit tokens never gate.
     */
    public static boolean serpMatchesQuery(String query, String titles) {
        String q = query == null ? "" : query.trim().toLowerCase();
        String t = titles == null ? "" : titles.toLowerCase();
        if (q.length() == 0) return true;
        if (t.length() == 0) return false;
        String[] toks = q.split("[^0-9a-zA-Z\u4e00-\u9fff]+");
        int total = 0;
        for (int i = 0; i < toks.length; i++) {
            String w = toks[i];
            if (w.length() == 0) continue;
            total++;
            if (!isQueryToken(w)) continue;
            if (t.contains(w)) return true;
        }
        return total > 8;
    }

    /** vc129: a token gates only if it is substantive (len >= 4, or CJK). */
    static boolean isQueryToken(String t) {
        if (t == null || t.length() < 4) return false;
        boolean hasCjk = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) { hasCjk = true; continue; }
            if (!Character.isLetterOrDigit(c)) return false;
        }
        return hasCjk || t.length() >= 4;
    }

    /**
     * vc129: lite.duckduckgo.com POST SERP. Structure is plain table rows:
     * a link row (a.result-link, real URL in href) followed by an optional
     * snippet row (td.result-snippet). Legacy uddg redirect hrefs are
     * unwrapped via unwrapDdgLink for safety. Pure JVM-testable.
     */
    public static List<SearchResult> parseDdgLite(Document doc, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (doc == null) return out;
        Elements anchors = doc.select("a.result-link");
        int want = clampCount(count);
        for (int i = 0; i < anchors.size() && out.size() < want; i++) {
            Element a = anchors.get(i);
            String link = unwrapDdgLink(a.attr("href").trim());
            if (link.length() == 0 || !link.startsWith("http")) continue;
            String title = a.text().trim();
            String snip = "";
            Element tr = a.closest("tr");
            if (tr != null) {
                Element next = tr.nextElementSibling();
                if (next != null) {
                    Element sn = next.selectFirst("td.result-snippet");
                    if (sn != null) snip = sn.text().trim();
                }
            }
            if (snip.length() == 0) snip = title;
            out.add(new SearchResult(title, link, snip, ""));
        }
        return out;
    }

    /** Pure Bing SERP parse core (JVM-testable): fixture in, results out. */
    public static List<SearchResult> parseBingSerp(Document doc, String items,
            String titleSel, String snipSel, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (doc == null) return out;
        Elements blocks = doc.select(items);
        int want = clampCount(count);
        for (int i = 0; i < blocks.size() && out.size() < want; i++) {
            Element blk = blocks.get(i);
            String title = "";
            Elements hs = blk.select(titleSel);
            if (!hs.isEmpty()) title = hs.get(0).text().trim();
            String link = "";
            Element a = blk.selectFirst("a[href^=http]");
            if (a != null) link = a.attr("href").trim();
            if (link.length() == 0) continue;
            String snip = "";
            Element cap = blk.selectFirst(snipSel);
            if (cap != null) snip = cap.text().trim();
            if (snip.length() == 0) snip = title;
            out.add(new SearchResult(title, link, snip, ""));
        }
        return out;
    }

    /**
     * DuckDuckGo html.duckduckgo.com SERP. Links arrive as
     * //duckduckgo.com/l/?uddg=<urlencoded>&rut=... redirects — the uddg
     * parameter is unwrapped to the real target. NOTE (1021): DDG serves an
     * anomaly/challenge page (HTTP 202) to some residential exits on GET.
     * vc129: this fetcher now POSTs to the lite endpoint (4/4 stable on
     * VM+S25 egress 1022) and default_on is true in engines.json.
     */
    public List<SearchResult> fetchDdgHtml(ChannelSpec ch, String query, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        String tpl = (ch != null && ch.spec != null && ch.spec.url.length() > 0)
                ? ch.spec.url : "https://lite.duckduckgo.com/lite/";
        Document doc = fetchHtmlDocPost(tpl, query);
        if (doc == null) { ch.lastError = "parse"; return out; }
        return parseDdgLite(doc, count);
    }

    /** Pure DDG SERP parse core (JVM-testable). */
    public static List<SearchResult> parseDdgSerp(Document doc, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (doc == null) return out;
        Elements anchors = doc.select("a.result__a");
        int want = clampCount(count);
        for (int i = 0; i < anchors.size() && out.size() < want; i++) {
            Element a = anchors.get(i);
            String link = unwrapDdgLink(a.attr("href").trim());
            if (link.length() == 0 || !link.startsWith("http")) continue;
            String title = a.text().trim();
            String snip = "";
            Element par = a.closest(".result");
            if (par != null) {
                Element sn = par.selectFirst(".result__snippet");
                if (sn != null) snip = sn.text().trim();
            }
            if (snip.length() == 0) snip = title;
            out.add(new SearchResult(title, link, snip, ""));
        }
        return out;
    }

    /** DDG redirect -> real URL ("//duckduckgo.com/l/?uddg=<enc>"; passthrough otherwise). */
    public static String unwrapDdgLink(String href) {
        if (href == null) return "";
        String s = href.trim();
        if (s.startsWith("//")) s = "https:" + s;
        int m = s.indexOf("duckduckgo.com/l/");
        if (m < 0) return s;
        int u = s.indexOf("uddg=");
        if (u < 0) return s;
        String rest = s.substring(u + 5);
        int amp = rest.indexOf('&');
        if (amp >= 0) rest = rest.substring(0, amp);
        try { return URLDecoder.decode(rest, "UTF-8"); }
        catch (Throwable e) { return s; }
    }

    /**
     * Wikipedia API channel: action=query&list=search returns JSON with
     * title + HTML-bearing snippet; snippet is stripped via jsoup and the
     * result URL is the article page.
     */
    public List<SearchResult> fetchWikipediaApi(ChannelSpec ch, String query, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        String tpl = (ch != null && ch.spec != null && ch.spec.url.length() > 0)
                ? ch.spec.url
                : "https://zh.wikipedia.org/w/api.php?action=query&list=search"
                    + "&srsearch={query}&format=json&srlimit={count}";
        String url = expandTemplate(tpl, query, count);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", BROWSER_UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { ch.lastError = "http " + code; return out; }
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            return parseWikipediaJson(sb.toString(), count);
        } catch (Throwable t) {
            ch.lastError = errOf(t);
            return new ArrayList<SearchResult>();
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** Pure Wikipedia API parse core (JVM-testable): JSON text in, results out. */
    public static List<SearchResult> parseWikipediaJson(String json, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (json == null || json.trim().length() == 0) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject qj = root.optJSONObject("query");
            if (qj == null) return out;
            JSONArray arr = qj.optJSONArray("search");
            if (arr == null) return out;
            int want = clampCount(count);
            for (int i = 0; i < arr.length() && out.size() < want; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = str(o, "title");
                if (title.length() == 0) continue;
                String raw = str(o, "snippet");
                String snip = Jsoup.parse(raw).text().trim();
                String link = "https://zh.wikipedia.org/wiki/"
                        + safeEnc(title).replace("+", "%20");
                out.add(new SearchResult(title, link, snip, ""));
            }
        } catch (Throwable t) {
            return new ArrayList<SearchResult>();
        }
        return out;
    }

    /**
     * Selector-driven fetcher for Mojeek/Startpage (and future HTML engines):
     * every selector comes from the engines.json spec — this code has zero
     * engine-specific knowledge. NOTE (1021): selectors are NOT yet
     * live-verified from a clean exit (both engines serve shell/challenge
     * pages to this VM) — engines default OFF; calibrate in vc124.
     */
    
    /** Pure selector-driven parse core (JVM-testable, spec selectors only). */
    
    /** URLEncoder without checked exception (query text only). */
    private static String safeEnc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable e) { return s == null ? "" : s; }
    }

    // ===== pure helpers (JVM-matrix-tested) =====

    /** Clamp count into [1..8]; default when out of range (0/negative/NaN-safe). */
    public static int clampCount(int count) {
        if (count < COUNT_MIN || count > COUNT_MAX) return COUNT_DEFAULT;
        return count;
    }

    /** org.json safe string: real string value or "" (never the literal "null"). */
    static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return (v instanceof String) ? (String) v : "";
    }

    /**
     * Normalization for dedupe keys: lowercase scheme+host, strip fragment,
     * strip trailing slash on empty path. Empty result for junk input.
     */
    public static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int scheme = s.indexOf("://");
        if (scheme > 0) {
            String sch = s.substring(0, scheme).toLowerCase();
            String rest = s.substring(scheme + 3);
            int slash = rest.indexOf('/');
            String host = slash >= 0 ? rest.substring(0, slash) : rest;
            String path = slash >= 0 ? rest.substring(slash) : "";
            host = host.toLowerCase();
            if (path.equals("/")) path = "";
            s = sch + "://" + host + path;
        }
        return s;
    }

    /** Base-URL normalization: trim, strip trailing slashes, add http:// when scheme missing. */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.contains("://")) s = "http://" + s;
        return s;
    }

    /** Host portion of a URL (for the 来源 label); "" when unparseable. */
    public static String hostOf(String url) {
        String s = normalizeUrl(url);
        int scheme = s.indexOf("://");
        if (scheme <= 0) return "";
        String rest = s.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return (slash >= 0 ? rest.substring(0, slash) : rest);
    }

    /**
     * Build the untrusted-content injection block (BRIEF §3 contract, single
     * implementation lives here; JS never assembles the block).
     * Hard budget: BLOCK_MAX_CHARS — when exceeded, stop appending entries and
     * close the block (never emit a partial entry).
     */
    public static String buildSearchBlock(String channelLabel, List<SearchResult> results,
                                          int maxChars) {
        if (maxChars <= 0) maxChars = BLOCK_MAX_CHARS;
        StringBuilder sb = new StringBuilder();
        int n = results == null ? 0 : results.size();
        String host = channelLabel == null ? "" : channelLabel.trim();
        if (host.length() == 0 && n > 0) host = hostOf(results.get(0).url);
        sb.append("〔联网搜索结果 | 来源: ").append(host)
                .append(" | ").append(n).append("条 | 不可信外部内容，仅供参考〕\n");
        String tail = "\n〔/搜索结果〕\n"
                + "回答要求：优先依据上述结果；引用处标 [n]；结果可能过时或有误，须甄别；\n"
                + "与结果无关的内容按既有知识回答。";
        // budget counts EVERYTHING emitted: header (already in sb) + entries + tail
        int budget = maxChars - sb.length() - tail.length();
        int added = 0;
        for (int i = 0; i < n; i++) {
            SearchResult r = results.get(i);
            StringBuilder entry = new StringBuilder();
            entry.append("[").append(i + 1).append("] ").append(r.title).append("\n");
            entry.append(r.content).append("\n");
            entry.append(r.url).append("\n"); // trailing newline: entries never glue together
            if (entry.length() > budget) break; // hard budget: no partial entries
            sb.append(entry);
            budget -= entry.length();
            added++;
        }
        if (added == 0) return ""; // nothing fit: no block at all
        sb.append(tail);
        return sb.toString();
    }

    // ===== vc124: circuit breaker =====

    /** Consecutive-failure tracker for per-engine circuit breaking. */


    public static class CircuitBreakerTracker {
        private static final int FAILURE_THRESHOLD = 3;
        private static final long COOLDOWN_MS = 30_000L;

        private final Map<String, FailureState> state = new LinkedHashMap<>();

        private static class FailureState {
            int failures;
            long lastFailureMs;
        }

        /** Record a failure for engineId. Returns true if circuit is now open. */
        public synchronized boolean recordFailure(String engineId) {
            FailureState fs = state.get(engineId);
            if (fs == null) { fs = new FailureState(); state.put(engineId, fs); }
            fs.failures++;
            fs.lastFailureMs = System.currentTimeMillis();
            if (fs.failures >= FAILURE_THRESHOLD) return true; // circuit open
            return false;
        }

        /** Record a success: reset failure counter. */
        public synchronized void recordSuccess(String engineId) {
            FailureState fs = state.get(engineId);
            if (fs != null) { fs.failures = 0; }
        }

        /** Check if circuit is open AND cooldown expired. Returns true if OK to retry. */
        public synchronized boolean canRetry(String engineId) {
            FailureState fs = state.get(engineId);
            if (fs == null) return true; // never failed → ok
            if (fs.failures < FAILURE_THRESHOLD) return true; // not yet tripped → ok
            long elapsed = System.currentTimeMillis() - fs.lastFailureMs;
            return elapsed >= COOLDOWN_MS; // cooldown expired → ok
        }

        /** Get remaining cooldown ms; 0 if not tripped. */
        public synchronized long remainingCooldown(String engineId) {
            FailureState fs = state.get(engineId);
            if (fs == null || fs.failures < FAILURE_THRESHOLD) return 0;
            long remaining = COOLDOWN_MS - (System.currentTimeMillis() - fs.lastFailureMs);
            return Math.max(0, remaining);
        }

        /** Reset all states (new session). */
        public synchronized void reset() { state.clear(); }
    }


    // ===== vc124: 2nd-batch engine fetchers =====

    /** Baidu SERP fetcher — mobile UA required. */
    
    /** vc124 parse core (JVM-testable): baidu SERP structure per 1021 sample
     *  — div.s_result / div.c-container blocks, title h3.t or
     *  a[data-log-title], link a[href^=http] (baidu /link redirects kept),
     *  snippet p.content-abstract / div.c-span-last. Soft-empty on the
     *  "请输入关键词" placeholder page. */
    
    /** Sogou SERP fetcher — mobile UA preferred. */
    public List<SearchResult> fetchSogouHtml(ChannelSpec ch, String query, int count) {
        String url = "https://www.sogou.com/web?query=" + safeEnc(query);
        Document doc = fetchHtmlDoc(url);
        if (doc == null) { ch.lastError = "parse"; return new ArrayList<SearchResult>(); }
        return parseSogouSerp(doc, count);
    }

    /** vc124 parse core (JVM-testable): sogou MOBILE SERP (www.sogou.com 302s
     *  to m.sogou.com and jsoup follows it) — div.vrResult blocks, title
     *  h3.vr-tit a, snippet .text-summary, real target decoded from the url=
     *  parameter of sogou jump links. Blocks without h3 anchor (recommend
     *  cards) are skipped; soft-empty on null/empty pages. */
    public static List<SearchResult> parseSogouSerp(Document doc, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (doc == null) return out;
        Elements blocks = doc.select("div.vrResult");
        int want = clampCount(count);
        for (int i = 0; i < blocks.size() && out.size() < want; i++) {
            Element blk = blocks.get(i);
            Element a = blk.selectFirst("h3.vr-tit a[href]");
            if (a == null) continue;
            String title = a.text().trim();
            String link = unwrapSogouJump(a.attr("href"));
            if (link.length() == 0) continue;
            String snip = "";
            Element p = blk.selectFirst(".text-summary, .clamp3, p");
            if (p != null) snip = p.text().trim();
            if (snip.length() == 0) snip = title;
            out.add(new SearchResult(title, link, snip, ""));
        }
        return out;
    }

    /** vc124: sogou jump links carry the real target as url=<percent-encoded>
     *  (e.g. .../tc?rcer=1&url=https%3A%2F%2Fbaike...&vrid=...). Decode it;
     *  direct http(s) links pass through; relative/keyword links (no real
     *  target) return "" so the block is skipped. Public: JVM-tested. */
    public static String unwrapSogouJump(String href) {
        if (href == null) return "";
        String h = href.trim();
        int k = h.indexOf("url=http");
        if (k >= 0) {
            String tail = h.substring(k + 4);
            int amp = tail.indexOf('&');
            String enc = amp >= 0 ? tail.substring(0, amp) : tail;
            try {
                return java.net.URLDecoder.decode(enc, "UTF-8");
            } catch (Throwable t) {
                return enc;
            }
        }
        if (h.startsWith("http")) return h;
        return "";
    }

    // ===== vc130: baidu (mobile-UA SERP) + github (anonymous REST) =====

    /** vc130: Baidu SERP. Desktop UA is wappass-captcha'd (1022 probe); the
     *  app's mobile BROWSER_UA gets a real c-result SERP — fetchHtmlDoc
     *  already sends it. URL template from engines.json (rn = page size);
     *  the anchor href is a m.baidu.com jump, the real target lives in the
     *  data-log attribute's "mu" JSON field. Soft-fails to empty. */
    public List<SearchResult> fetchBaiduHtml(ChannelSpec ch, String query, int count) {
        String tpl = (ch != null && ch.spec != null) ? ch.spec.url : "";
        String url = (tpl.length() > 0)
                ? expandTemplate(tpl, query, count)
                : "https://www.baidu.com/s?wd=" + safeEnc(query)
                    + "&rn=" + clampCount(count);
        Document doc = fetchHtmlDoc(url);
        if (doc == null) { ch.lastError = "parse"; return new ArrayList<SearchResult>(); }
        return parseBaiduSerp(doc, count);
    }

    /** vc130 parse core (JVM-testable): div.c-result blocks, title h3, real
     *  target from data-log "mu" (blocks without it = recommend/entity cards,
     *  skipped), snippet [class*=summary-text] with title fallback.
     *  Soft-empty on null/challenge pages. */
    public static List<SearchResult> parseBaiduSerp(Document doc, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (doc == null) return out;
        Elements blocks = doc.select("div.c-result");
        int want = clampCount(count);
        for (int i = 0; i < blocks.size() && out.size() < want; i++) {
            Element blk = blocks.get(i);
            Element h3 = blk.selectFirst("h3");
            if (h3 == null) continue;
            String title = h3.text().trim();
            String link = baiduRealUrl(blk.attr("data-log"));
            if (link.length() == 0) continue;
            String snip = "";
            Element p = blk.selectFirst("[class*=summary-text]");
            if (p != null) snip = p.text().trim();
            if (snip.length() == 0) snip = title;
            out.add(new SearchResult(title, link, snip, ""));
        }
        return out;
    }

    /** vc130: extract "mu":"<url>" from the data-log attribute (raw or
     *  jsoup-decoded entity form). Returns "" when absent/not http. */
    public static String baiduRealUrl(String dataLog) {
        if (dataLog == null) return "";
        String s = dataLog.replace("\\", "").replace("&quot;", "\"");
        int k = s.indexOf("\"mu\":\"");
        if (k < 0) return "";
        int s0 = k + 6;
        int e = s.indexOf('"', s0);
        if (e < 0) return "";
        String u = s.substring(s0, e).trim();
        return u.startsWith("http") ? u : "";
    }

    /** vc130: GitHub repository search, anonymous REST API (user decision
     *  1022: no token ever ships in the APK — credential red line). Anonymous
     *  callers get rate-limited (403 on the 1022 VM probe); that is an
     *  accepted soft failure — aggregation moves on, engine is default-off. */
    public List<SearchResult> fetchGithubApi(ChannelSpec ch, String query, int count) {
        String tpl = (ch != null && ch.spec != null) ? ch.spec.url : "";
        String url = (tpl.length() > 0)
                ? expandTemplate(tpl, query, count)
                : "https://api.github.com/search/repositories?q=" + safeEnc(query)
                    + "&per_page=" + clampCount(count);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", BROWSER_UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { ch.lastError = "http " + code; return new ArrayList<SearchResult>(); }
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            return parseGithubJson(sb.toString(), count);
        } catch (Throwable t) {
            ch.lastError = errOf(t);
            return new ArrayList<SearchResult>(); // soft-fail (rate limit / net)
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** vc130 parse core (JVM-testable): items[] -> full_name + html_url,
     *  content = description + star count. Soft-empty on junk/rate-limit
     *  bodies. */
    public static List<SearchResult> parseGithubJson(String json, int count) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (json == null || json.trim().length() == 0) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return out;
            int want = clampCount(count);
            for (int i = 0; i < arr.length() && out.size() < want; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = str(o, "full_name");
                String u = str(o, "html_url");
                if (name.length() == 0 || u.length() == 0) continue;
                String desc = str(o, "description");
                int stars = o.optInt("stargazers_count", -1);
                StringBuilder sbc = new StringBuilder();
                if (desc.length() > 0) sbc.append(desc);
                if (stars >= 0) {
                    if (sbc.length() > 0) sbc.append(" \u00b7 ");
                    sbc.append('\u2605').append(stars);
                }
                out.add(new SearchResult(name, u, sbc.toString(), ""));
            }
        } catch (Throwable t) {
            return new ArrayList<SearchResult>(); // soft-fail
        }
        return out;
    }

    /** 360 (so.com) SERP fetcher. */
    
    /** vc124 parse core (JVM-testable): so.com SERP structure per 1021 sample
     *  — div[class*=result]/div[class*=list-result] blocks, a[data-rank] or
     *  first a[href] (http only), snippet p[class*=desc]. Soft-empty on the
     *  "请输入搜索关键词" placeholder page. */

    /** vc126: insert a structured line (quote/weather) after the block header;
     *  empty block gets a minimal wrapped block. Total cap preserved. */
    public static String insertStructuredLine(String block, String line,
                                              int maxChars) {
        if (line == null || line.trim().length() == 0) return block;
        String tail = "\n\u3014/\u641c\u7d22\u7ed3\u679c\u3015\n";
        if (block == null || block.length() == 0) {
            StringBuilder nb = new StringBuilder();
            nb.append("\u3014\u8054\u7f51\u641c\u7d22\u7ed3\u679c | \u6765\u6e90: \u7ed3\u6784\u5316\u6570\u636e | 1\u6761 | \u4e0d\u53ef\u4fe1\u5916\u90e8\u5185\u5bb9\uff0c\u4ec5\u4f9b\u53c2\u8003\u3015\n");
            nb.append(line).append("\n");
            nb.append(tail);
            nb.append("\u56de\u7b54\u8981\u6c42\uff1a\u4f18\u5148\u4f9d\u636e\u4e0a\u8ff0\u7ed3\u679c\uff1b\u4e0e\u7ed3\u679c\u65e0\u5173\u7684\u5185\u5bb9\u6309\u65e2\u6709\u77e5\u8bc6\u56de\u7b54\u3002");
            return nb.length() > maxChars ? nb.substring(0, maxChars) : nb.toString();
        }
        int cut = block.indexOf("\n");
        if (cut < 0) return block;
        String out = block.substring(0, cut + 1) + line + "\n"
                + block.substring(cut + 1);
        return out.length() > maxChars ? out.substring(0, maxChars) : out;
    }
}
