package com.dawoer.npullm.search;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * SearXNG-minimum-compatible LAN endpoint (default :8081, port configurable
 * since vc133; BRIEF §0 protocol).
 *
 * GET /search?q=<query>&format=json[&count=N][&key=K]
 *   200 -> {"query":..,"results":[{url,title,content,engine,engines,category,score,publishedDate}],
 *           "engines":[..],"answers":[],"infoboxes":[],"suggestions":[],"corrections":[],
 *           "unresponsive_engines":[[engineId,reason],..]}
 *   format present and != json        -> 406
 *   key gate enabled and key mismatch -> 403
 *   missing/empty q                   -> 400
 * Unknown parameters are ignored. LAN-only; never exposed publicly.
 *
 * vc135: schema aligned with upstream SearXNG — number_of_results dropped
 * (the official API never had it), official empty-list keys added, and
 * unresponsive_engines carries REAL failure reasons only (a normal empty
 * SERP is "0 hits", not an engine failure — it must never appear there).
 *
 * Pure Java (no android.* imports) so the JVM loopback test can run it.
 * Zero imports from engine-side packages.
 */
public class SearchServer extends NanoHTTPD {

    /** Protocol version reported by wsVersion / status JSON. */
    public static final String PROTO_VERSION = "vc135-ws1";
    public static final int PORT = 8081;

    /** vc133: effective port — SearchConfig.searchPort() when context is up
     *  (junk prefs -> 8081), plain default before SearchConfig.init. */
    public static int STATUS_PORT() {
        try {
            return SearchConfig.searchPort();
        } catch (Throwable t) {
            return PORT;
        }
    }

    private volatile SearchService service;
    private volatile String apiKey = "";

    public SearchServer(SearchService svc) {
        this(svc, SearchConfig.searchPort());
    }

    /** vc133: port-aware ctor — the FGS reads the configured port at start. */
    public SearchServer(SearchService svc, int port) {
        super(port);
        this.service = svc == null ? new SearchService() : svc;
    }

    /** Optional key gate; empty string = gate disabled (BRIEF: 默认不启用). */
    public void setApiKey(String key) {
        this.apiKey = key == null ? "" : key;
    }

    public String getApiKey() {
        return this.apiKey;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) uri = "";
        if ("/speed".equals(uri)) {
            return serveSpeed(session);
        }
        if (!"/search".equals(uri) && !"/health".equals(uri)) {
            return plain(Response.Status.NOT_FOUND, "{\"error\":\"not found\"}");
        }
        if ("/health".equals(uri)) {
            try {
                JSONObject h = new JSONObject();
                h.put("status", "ok");
                h.put("proto", PROTO_VERSION);
                return plain(Response.Status.OK, h.toString());
            } catch (Throwable je) {
                return plain(Response.Status.INTERNAL_ERROR, "{\"error\":\"json\"}");
            }
        }

        // format gate: default json when absent; any explicit non-json -> 406
        Map<String, String> parms = session.getParms();
        String format = val(parms, "format");
        if (format.length() > 0 && !"json".equals(format)) {
            return plain(Response.Status.NOT_ACCEPTABLE,
                    "{\"error\":\"format not supported: only format=json\"}");
        }

        // key gate: only when enabled; missing/wrong key -> 403 (BRIEF §5 ⑩)
        String key = apiKey;
        if (key.length() > 0) {
            String given = val(parms, "key");
            if (!key.equals(given)) {
                return plain(Response.Status.FORBIDDEN, "{\"error\":\"invalid key\"}");
            }
        }

        String q = val(parms, "q");
        if (q.trim().length() == 0) {
            return plain(Response.Status.BAD_REQUEST, "{\"error\":\"missing q\"}");
        }

        // vc127: structured data source first (quote > weather). Any miss or
        // failure yields null -> silent fallback to the plain engine path.
        String structured = null;
        try {
            structured = DataSources.structuredLine(q);
        } catch (Throwable dt) {
            structured = null; // never break the search path
        }
        if (structured != null) {
            try {
                JSONObject root = new JSONObject();
                root.put("query", q);
                JSONArray ea = new JSONArray();
                ea.put("structured");
                root.put("engines", ea);
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("url", "");
                o.put("title", structured);
                o.put("content", structured);
                o.put("engine", "structured");
                JSONArray es = new JSONArray();
                es.put("structured");
                o.put("engines", es);
                o.put("category", "general");
                o.put("score", 1.0);
                o.put("publishedDate", JSONObject.NULL);
                arr.put(o);
                root.put("results", arr);
                putPlaceholders(root);
                putUnresponsive(root, null);
                Response rr = plain(Response.Status.OK, root.toString());
                SearchConfig.bumpDailyCount();
                return rr;
            } catch (Throwable je) {
                // fall through to plain search on any JSON failure
            }
        }
        int count = SearchService.COUNT_DEFAULT;
        String cs = val(parms, "count");
        if (cs.length() > 0) {
            try { count = Integer.parseInt(cs.trim()); } catch (Throwable bad) { count = -1; }
        }
        int want = SearchService.clampCount(count);

        // vc124: speed=1 → include per-engine speed test in response
        boolean speedTest = "1".equals(val(parms, "speed"));

        // vc123: channels come from the engine registry (enabled engines only,
        // whitelist enforced); empty registry degrades to the vc122 behavior.
        List<SearchService.ChannelSpec> chans = registryChannels();
        SearchService.SearchResponse resp =
                service.search(new SearchService.SearchRequest(q, want, chans));
        try {
            String body;
            if (speedTest) {
                body = buildSearchJsonWithSpeed(q, resp.results, resp.sources, resp.outcomes);
            } else {
                body = buildSearchJson(q, resp.results, resp.sources, resp.outcomes);
            }
            Response r = plain(Response.Status.OK, body);
            SearchConfig.bumpDailyCount(); // vc122 挂账: successful-request counter
            return r;
        } catch (Throwable je) {
            return plain(Response.Status.INTERNAL_ERROR, "{\"error\":\"json build failed\"}");
        }
    }

    /** vc123 channel builder: assets/engines.json + per-engine prefs flags.
     *  Any failure degrades to the single searxng channel (vc122 behavior). */
    static List<SearchService.ChannelSpec> registryChannels() {
        try {
            List<EngineRegistry.EngineSpec> engines =
                    EngineRegistry.parse(SearchConfig.assetText("engines.json"));
            List<Boolean> flags = new java.util.ArrayList<Boolean>();
            for (int i = 0; i < engines.size(); i++) {
                EngineRegistry.EngineSpec e = engines.get(i);
                flags.add(Boolean.valueOf(
                        SearchConfig.engineEnabled(e.id, e.defaultOn)));
            }
            List<SearchService.ChannelSpec> chans = EngineRegistry.channelsFor(
                    SearchConfig.baseUrl(), engines, flags);
            if (!chans.isEmpty()) return chans;
        } catch (Throwable ignore) {
            // fall through to the legacy single channel
        }
        return defaultChannel();
    }

    // ===== vc135: official SearXNG schema helpers =====

    /** Official keys that are legitimately empty for this endpoint; present so a
     *  consumer never branches on a missing key. */
    private static void putPlaceholders(JSONObject root) throws org.json.JSONException {
        root.put("answers", new JSONArray());
        root.put("infoboxes", new JSONArray());
        root.put("suggestions", new JSONArray());
        root.put("corrections", new JSONArray());
    }

    /** [[engineId, reason], ..] — REAL failures only. A normal empty SERP is a
     *  successful "0 hits" and must never land here. */
    private static void putUnresponsive(JSONObject root, Map<String, String> outcomes)
            throws org.json.JSONException {
        JSONArray ue = new JSONArray();
        if (outcomes != null) {
            for (Map.Entry<String, String> e : outcomes.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || k.length() == 0 || v == null || v.length() == 0) continue;
                JSONArray pair = new JSONArray();
                pair.put(k);
                pair.put(v);
                ue.put(pair);
            }
        }
        root.put("unresponsive_engines", ue);
    }

    /** One result object, 8 keys (official subset; rank order gives the score).
     *  JSONObject.NULL is required — put(key, null) would DELETE the key. */
    private static void putResult(JSONArray arr, SearchService.SearchResult r, int i)
            throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("url", r.url);
        o.put("title", r.title);
        o.put("content", r.content);
        o.put("engine", r.engine);
        JSONArray es = new JSONArray();
        if (r.engine != null && r.engine.length() > 0) es.put(r.engine);
        o.put("engines", es);
        o.put("category", "general");
        double score = Math.round((1.0 - i * 0.1) * 100.0) / 100.0;
        o.put("score", Math.max(0.1, score));
        o.put("publishedDate", JSONObject.NULL);
        arr.put(o);
    }

    public static String buildSearchJsonWithSpeed(String q, List<SearchService.SearchResult> rs,
                                           List<String> engineIds, Map<String, String> outcomes)
            throws org.json.JSONException {
        JSONObject root = new JSONObject();
        root.put("query", q);
        JSONArray ea = new JSONArray(); // vc123: engines that participated
        if (engineIds != null) for (int i = 0; i < engineIds.size(); i++) ea.put(engineIds.get(i));
        root.put("engines", ea);
        JSONArray arr = new JSONArray();
        int i = 0;
        for (SearchService.SearchResult r : rs) putResult(arr, r, i++);
        root.put("results", arr);
        putPlaceholders(root);
        putUnresponsive(root, outcomes);
        // vc124: speed results — use placeholder (actual data via /speed endpoint)
        root.put("speed_results", new JSONArray());
        return root.toString();
    }

    public static String buildSearchJson(String q, List<SearchService.SearchResult> rs,
                                          List<String> engineIds, Map<String, String> outcomes)
            throws org.json.JSONException {
        JSONObject root = new JSONObject();
        root.put("query", q);
        JSONArray ea = new JSONArray(); // vc123: engines that participated
        if (engineIds != null) for (int i = 0; i < engineIds.size(); i++) ea.put(engineIds.get(i));
        root.put("engines", ea);
        JSONArray arr = new JSONArray();
        int i = 0;
        for (SearchService.SearchResult r : rs) putResult(arr, r, i++);
        root.put("results", arr);
        putPlaceholders(root);
        putUnresponsive(root, outcomes);
        return root.toString();
    }

    /** Stage-1 single channel: the searxng channel pointing at this device's upstream. */
    static List<SearchService.ChannelSpec> defaultChannel() {
        List<SearchService.ChannelSpec> l = new java.util.ArrayList<SearchService.ChannelSpec>();
        l.add(new SearchService.ChannelSpec("searxng", "searxng", SearchConfig.baseUrl()));
        return l;
    }

    private static String val(Map<String, String> m, String k) {
        if (m == null) return "";
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static Response plain(Response.Status st, String body) {
        return NanoHTTPD.newFixedLengthResponse(st, "application/json", body);
    }

    /** vc124: /speed endpoint — run SpeedTester.measure on all enabled engines. */
    private Response serveSpeed(IHTTPSession session) {
        try {
            List<SearchService.ChannelSpec> chans = registryChannels();
            String probe = "test"; // lightweight probe query
            List<SpeedTester.EngineSpeedResult> speeds =
                    SpeedTester.measure(service, chans, probe);
            JSONObject root = new JSONObject();
            root.put("proto", PROTO_VERSION);
            JSONArray arr = new JSONArray();
            for (SpeedTester.EngineSpeedResult s : speeds) {
                JSONObject o = new JSONObject();
                o.put("engineId", s.engineId);
                o.put("engineLabel", s.engineLabel);
                o.put("latency", s.latencyMs);
                o.put("hasResults", s.hasResults);
                o.put("status", s.status);
                arr.put(o);
            }
            root.put("speed_results", arr);
            return plain(Response.Status.OK, root.toString());
        } catch (Throwable t) {
            return plain(Response.Status.INTERNAL_ERROR,
                    "{\"error\":\"speed test failed\"}");
        }
    }

    /** vc124: GET /region — return current region config. */
    
    /** vc124: POST /region — set region ("cn" | "intl"). */
    }
