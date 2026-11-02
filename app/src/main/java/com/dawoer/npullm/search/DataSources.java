package com.dawoer.npullm.search;

/**
 * vc126: structured-data intent router (BRIEF-v150 §B). Decides whether a
 * chat query gets a structured line (quote/weather) and renders it; all
 * miss/failure paths yield null so the caller silently falls back to the
 * plain search path. Zero network here — providers own their fetches.
 *
 * Priority: quote > weather (a query matching both is treated as quote).
 */
public class DataSources {

    /** Route + fetch + render. Returns one structured line (≤200 chars)
     *  or null (= no structured result, plain search proceeds). */
    public static String structuredLine(String query) {
        if (query == null) return null;
        String q = query.trim();
        if (q.length() == 0) return null;
        QuoteProvider qp = new QuoteProvider();
        if (QuoteProvider.isQuoteIntent(q)) {
            try {
                return QuoteProvider.render(qp.lookup(q));
            } catch (Throwable t) {
                return null; // never break the search path
            }
        }
        if (OpenMeteoProvider.isWeatherIntent(q)) {
            try {
                return OpenMeteoProvider.render(
                        new OpenMeteoProvider().lookup(q));
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
