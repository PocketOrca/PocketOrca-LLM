package com.dawoer.npullm.search;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Search-package-local config store (BRIEF-vc122 §0 isolation: the search
 * package imports NOTHING from com.dawoer.npullm engine classes, so it keeps
 * its own context holder + prefs file instead of AppCtx / npullm prefs).
 *
 * Keys:
 *   search_base_url  — upstream SearXNG-compatible base URL (default example:
 *                      the LAN box; never published in docs).
 *   search_gate_key  — search endpoint key gate; empty = disabled (user decision 1020 #1).
 *   search_count     — default result count for search callers (clamped 1..8).
 *   search_port      — LAN search endpoint port (vc133: default 8081, valid
 *                      1024..65535, must not equal the model service port 8080).
 */
public final class SearchConfig {

    private static final String PREFS = "npullm_search";
    private static Context app;

    private SearchConfig() {}

    /** Idempotent; called from SearchForegroundService.onCreate and Bridge init. */
    public static void init(Context c) {
        if (app == null && c != null) app = c.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        Context c = app;
        if (c == null) return null;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String baseUrl() {
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString("search_base_url", "");
        return v == null ? "" : v;
    }

    public static void setBaseUrl(String v) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString("search_base_url",
                SearchService.normalizeBaseUrl(v == null ? "" : v)).apply();
    }

    public static String gateKey() {
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString("search_gate_key", "");
        return v == null ? "" : v;
    }

    public static void setGateKey(String v) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString("search_gate_key", v == null ? "" : v.trim()).apply();
    }

    /** vc133: valid search-port range — privileged ports rejected, the model
     *  port 8080 rejected (the two services must never collide). */
    public static boolean validatePortRange(int v) {
        return v >= 1024 && v <= 65535 && v != 8080;
    }

    /** vc133: stored search port; junk/missing/out-of-range -> default 8081. */
    public static int searchPort() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("search_port", -1);
        if (!validatePortRange(v)) return 8081;
        return v;
    }

    /** vc133: persist the search port (caller validates; junk is stored raw
     *  and re-validated to the default on every read). */
    public static void setSearchPort(int v) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putInt("search_port", v).apply();
    }

    public static int defaultCount() {
        SharedPreferences p = prefs();
        int v = p == null ? -1 : p.getInt("search_count", -1);
        return SearchService.clampCount(v);
    }

    // ===== vc123: engine toggles + assets + daily counter =====

    /** Stored per-engine flag by prefs key ("1"/"0"); junk/missing -> def.
     *  Goes through EngineRegistry.readEnabled so seeding semantics live in
     *  one place (JVM-tested without Android). */
    public static boolean enabledFlag(String key, boolean def) {
        SharedPreferences p = prefs();
        String v = p == null ? null : p.getString(key, null);
        return EngineRegistry.readEnabled(v, def);
    }

    /** Effective enabled state of one engine (prefs override, else defaultOn). */
    public static boolean engineEnabled(String id, boolean def) {
        return enabledFlag(EngineRegistry.enabledKey(id), def);
    }

    public static void setEngineEnabled(String id, boolean on) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString(EngineRegistry.enabledKey(id), on ? "1" : "0").apply();
    }

    /** Raw asset text ("" when context missing / asset unreadable — callers
     *  degrade to the vc122 searxng-only behavior). */
    public static String assetText(String name) {
        Context c = app;
        if (c == null || name == null || name.length() == 0) return "";
        InputStream is = null;
        try {
            is = c.getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignore) {}
        }
    }

    /** Prefs key for the daily request counter (vc122 挂账). */
    public static String dailyKey() {
        return "requests_" + new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date());
    }

    /** Today's search request count (0 when none/unavailable). */
    public static int requestsToday() {
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(dailyKey(), 0);
    }

    /** Count one successful /search request (per-date key = auto reset). */
    public static void bumpDailyCount() {
        SharedPreferences p = prefs();
        if (p == null) return;
        String k = dailyKey();
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply();
    }
}
