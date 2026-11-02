package com.dawoer.npullm.search;

import java.util.ArrayList;
import java.util.List;

/**
 * vc124: per-engine latency measurement (BRIEF-vc124-engines-2ndbatch §1.3).
 * Lives in the search package (zero engine-side imports); concrete fetchers
 * ride on the supplied SearchService instance. JVM-testable, no anonymous
 * classes, never blocks the caller beyond per-engine timeouts.
 */
public final class SpeedTester {

    /** One engine's probe result. */
    public static class EngineSpeedResult {
        public final String engineId;
        public final String engineLabel;
        public final int latencyMs;
        public final boolean hasResults;
        public final String status; // "fast" | "medium" | "slow" | "timeout"

        EngineSpeedResult(String engineId, String engineLabel, int latencyMs,
                          boolean hasResults, String status) {
            this.engineId = engineId;
            this.engineLabel = engineLabel;
            this.latencyMs = latencyMs;
            this.hasResults = hasResults;
            this.status = status;
        }
    }

    private SpeedTester() {}

    /** Measure latency of all enabled engines with a lightweight probe query.
     *  Returns results sorted by latency for UI ranking. Enabled filtering
     *  happens upstream (registry channelsFor only returns enabled channels),
     *  so no per-channel flag is needed here. */
    public static List<EngineSpeedResult> measure(SearchService svc,
            List<SearchService.ChannelSpec> engines, String probeQuery) {
        List<EngineSpeedResult> out = new ArrayList<>();
        if (svc == null || engines == null) return out;
        for (SearchService.ChannelSpec ch : engines) {
            long t0 = System.currentTimeMillis();
            try {
                List<SearchService.SearchResult> results;
                String type = ch.type == null ? "" : ch.type;
                switch (type) {
                    case "searxng":
                        results = svc.fetchSearxng(ch, probeQuery, 3);
                        break;
                    case "bing": case "bing_cn":
                        results = svc.fetchBingHtml(ch, probeQuery, 3);
                        break;
                    case "sogou":
                        results = svc.fetchSogouHtml(ch, probeQuery, 3);
                        break;
                    case "ddg":
                        results = svc.fetchDdgHtml(ch, probeQuery, 3);
                        break;
                    case "wikipedia":
                        results = svc.fetchWikipediaApi(ch, probeQuery, 3);
                        break;
                    case "baidu":
                        results = svc.fetchBaiduHtml(ch, probeQuery, 3);
                        break;
                    case "github":
                        results = svc.fetchGithubApi(ch, probeQuery, 3);
                        break;
                    default:
                        results = svc.fetchSearxng(ch, probeQuery, 3);
                        break;
                }
                long elapsed = System.currentTimeMillis() - t0;
                int lat = (int) elapsed;
                String status;
                if (lat < 500) status = "fast";
                else if (lat < 1500) status = "medium";
                else if (lat < 3000) status = "slow";
                else status = "timeout";
                boolean hasR = (results != null && results.size() > 0);
                out.add(new EngineSpeedResult(ch.id, spLabel(ch), lat, hasR, status));
            } catch (Throwable t) {
                long elapsed = System.currentTimeMillis() - t0;
                out.add(new EngineSpeedResult(ch.id, spLabel(ch),
                        (int) elapsed, false, "timeout"));
            }
        }
        out.sort((a, b) -> Integer.compare(a.latencyMs, b.latencyMs));
        return out;
    }

    /** Display label for a channel — registry spec label when present,
     *  otherwise the channel id (never null for JSON build). */
    private static String spLabel(SearchService.ChannelSpec ch) {
        if (ch == null) return "";
        return (ch.spec != null && ch.spec.label != null
                && ch.spec.label.length() > 0) ? ch.spec.label : ch.id;
    }
}
