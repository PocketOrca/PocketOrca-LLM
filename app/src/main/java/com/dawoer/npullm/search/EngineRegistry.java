package com.dawoer.npullm.search;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * vc123 built-in search engine registry (BRIEF-vc123-engines-1021 §2).
 *
 * Parses assets/engines.json (selector rules externalized) into EngineSpec
 * records, keeps per-engine enabled flags in the search prefs, and builds the
 * channel list for SearchService. Only whitelisted types ever reach a
 * channel — unknown types are dropped at parse time (structure fingerprint
 * asserts this and the runtime filters again).
 *
 * Pure logic (no android.* imports): prefs access goes through the
 * BoolLookup indirection so the JVM tests everything without a device.
 * Zero imports from engine-side packages. No anonymous classes.
 */
public final class EngineRegistry {

    /** One engine spec from engines.json. */
    public static class EngineSpec {
        public final String id;
        public final String label;
        public final String type;
        public final String url;      // template with {query}/{count}, "" for searxng
        public final String items;    // jsoup selector for result blocks ("" = api/json)
        public final String title;    // jsoup selector within a block
        public final String link;     // jsoup selector for the anchor (".href" suffix = attr)
        public final String snippet;  // jsoup selector for the description
        public final boolean defaultOn;
        public final boolean builtin; // searxng: toggle row not rendered; base = UI base URL
        EngineSpec(String id, String label, String type, String url, String items,
                   String title, String link, String snippet,
                   boolean defaultOn, boolean builtin) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.type = type == null ? "" : type;
            this.url = url == null ? "" : url;
            this.items = items == null ? "" : items;
            this.title = title == null ? "" : title;
            this.link = link == null ? "" : link;
            this.snippet = snippet == null ? "" : snippet;
            this.defaultOn = defaultOn;
            this.builtin = builtin;
        }
    }

    public static EngineSpec makeSpec(String id, String type, String url, String items,
            String title, String link, String snippet, boolean defOn, boolean builtin) {
        return new EngineSpec(id, id, type, url, items, title, link, snippet, defOn, builtin);
    }

    /** Whitelist of channel types SearchService knows how to execute. */
    public static final String[] KNOWN_TYPES =
            {"searxng", "bing", "ddg", "wikipedia", "sogou", "baidu", "github"};

    public static boolean isKnownType(String t) {
        for (int i = 0; i < KNOWN_TYPES.length; i++) {
            if (KNOWN_TYPES[i].equals(t)) return true;
        }
        return false;
    }

    private EngineRegistry() {}

    // ===== parsing =====

    /**
     * Parse the engines.json text. Unknown-typed entries are dropped here
     * (defence in depth: channelsFor filters again). Malformed JSON -> empty
     * list (degrades to the vc122 searxng-only behavior upstream).
     */
    public static List<EngineSpec> parse(String json) {
        List<EngineSpec> out = new ArrayList<EngineSpec>();
        if (json == null || json.trim().length() == 0) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("engines");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String type = str(o, "type");
                if (!isKnownType(type)) continue; // whitelist, never executes
                out.add(new EngineSpec(str(o, "id"), str(o, "label"), type,
                        str(o, "url"), str(o, "items"), str(o, "title"),
                        str(o, "link"), str(o, "snippet"),
                        o.optBoolean("default_on", false),
                        o.optBoolean("builtin", false)));
            }
        } catch (Throwable t) {
            return new ArrayList<EngineSpec>(); // soft-fail, never throws
        }
        return out;
    }

    /** org.json safe string (SearchService.str is package-visible; same trap). */
    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return (v instanceof String) ? (String) v : "";
    }

    // ===== enabled flags =====

    /** Prefs keys: engine_enabled_<id> = "1"/"0". First read of an engine with
     *  no stored key seeds the stored value from default_on (sticky default). */
    public static boolean readEnabled(String json, boolean defaultOn) {
        if (json == null) return defaultOn;
        if ("1".equals(json)) return true;
        if ("0".equals(json)) return false;
        return defaultOn; // junk value -> default
    }

    public static String enabledKey(String engineId) {
        String s = engineId == null ? "" : engineId.trim().toLowerCase();
        StringBuilder sb = new StringBuilder("engine_enabled_");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t') sb.append(c); // ids are plain ascii words
        }
        return sb.toString();
    }

    /** Minimal getter seam so the JVM tests the seeding logic without Android. */
    public interface BoolLookup {
        boolean get(String key, boolean def);
    }

    /** Resolve the effective enabled set for a parsed registry. */
    public static List<Boolean> effectiveEnabled(List<EngineSpec> engines, BoolLookup lookup) {
        List<Boolean> out = new ArrayList<Boolean>();
        if (engines == null) return out;
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec e = engines.get(i);
            boolean stored = lookup == null
                    ? e.defaultOn
                    : lookup.get(enabledKey(e.id), e.defaultOn);
            out.add(Boolean.valueOf(readEnabled(stored ? "1" : "0", e.defaultOn)));
        }
        return out;
    }

    // ===== channel construction =====

    /**
     * Build the channel list for search(): enabled engines only, whitelist
     * enforced again. The builtin searxng engine takes its base URL from the
     * UI setting (empty = skipped, mirroring vc122 behavior); every other
     * engine carries its own URL template (base left empty).
     */
    public static List<SearchService.ChannelSpec> channelsFor(
            String uiBaseUrl, List<EngineSpec> engines, List<Boolean> enabled) {
        List<SearchService.ChannelSpec> out = new ArrayList<SearchService.ChannelSpec>();
        if (engines == null) return out;
        int n = engines.size();
        for (int i = 0; i < n; i++) {
            EngineSpec e = engines.get(i);
            boolean on = (enabled != null && i < enabled.size())
                    ? enabled.get(i).booleanValue() : e.defaultOn;
            if (!on) continue;
            if (!isKnownType(e.type)) continue;
            if (e.builtin) {
                String base = SearchService.normalizeBaseUrl(uiBaseUrl == null ? "" : uiBaseUrl);
                if (base.length() > 0) {
                    out.add(new SearchService.ChannelSpec(e.id, e.type, base, e));
                }
            } else {
                out.add(new SearchService.ChannelSpec(e.id, e.type, "", e));
            }
        }
        return out;
    }

    // ===== structure fingerprint (JVM drift guard) =====

    /**
     * Normalized structure fingerprint of the engines JSON: keys + types in
     * document order, all values stripped. Adding/removing an engine, adding
     * or renaming a field, or changing a value's type all change the hash —
     * the JVM test pins the expected fingerprint so silent structural drift
     * of assets/engines.json fails the build instead of shipping.
     */
    public static String structureFingerprint(String json) {
        if (json == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject root = new JSONObject(json);
            fpObject(root, sb);
        } catch (Throwable t) {
            return "ERR"; // malformed JSON never fingerprints as valid
        }
        return sb.toString();
    }

    private static void fpObject(JSONObject o, StringBuilder sb) {
        JSONArray names = o.names();
        sb.append('{');
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                Object v = o.opt(k);
                sb.append(k).append(':');
                fpValue(v, sb);
                sb.append(';');
            }
        }
        sb.append('}');
    }

    private static void fpArray(JSONArray a, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < a.length(); i++) {
            fpValue(a.opt(i), sb);
            sb.append(',');
        }
        sb.append(']');
    }

    private static void fpValue(Object v, StringBuilder sb) {
        if (v == null || v == JSONObject.NULL) { sb.append('~'); return; }
        if (v instanceof JSONObject) { fpObject((JSONObject) v, sb); return; }
        if (v instanceof JSONArray) { fpArray((JSONArray) v, sb); return; }
        if (v instanceof Boolean) { sb.append('B'); return; }
        if (v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte) { sb.append('N'); return; }
        if (v instanceof Double || v instanceof Float) { sb.append('F'); return; }
        sb.append('S'); // any string value, content stripped
    }
}
