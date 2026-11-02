package com.dawoer.npullm.search;

import org.json.JSONObject;

/**
 * vc126 structured data source: weather via Open-Meteo (zero-auth, two-hop:
 * geocoding -> forecast). BRIEF-v150 §B architecture — adapter layer, all
 * failures return null (silent fallback to plain search), never throws.
 *
 * Pure logic (city extraction / two-stage parse / render) is JVM-testable
 * against 1021 live-captured Shanghai fixtures; fetch() touches the network
 * (worker thread only).
 */
public class OpenMeteoProvider {

    static final int HTTP_TIMEOUT_MS = 5000;
    static final int MAX_LINE_CHARS = 200;

    /** Parsed weather snapshot (named static nested class — d8 rule). */
    public static class Weather {
        public final String city;
        public final String temp;
        public final String code;
        public final String max;
        public final String min;
        public Weather(String city, String temp, String code,
                       String max, String min) {
            this.city = city;
            this.temp = temp;
            this.code = code;
            this.max = max;
            this.min = min;
        }
    }

    // ===== intent + city extraction (pure) =====

    /** Weather intent keywords (checked AFTER quote intent). */
    public static boolean isWeatherIntent(String q) {
        if (q == null) return false;
        return q.contains("天气") || q.contains("气温")
                || q.contains("降雨");
    }

    /** Strip weather/intent/filler words; remaining string = city.
     *  Empty result ("天气怎么样" with no city) -> null (no lookup). */
    public static String extractCity(String q) {
        if (q == null) return null;
        String s = q;
        String[] fillers = {"今天", "明天", "现在", "目前", "最新", "多少",
                "呢", "吗", "的", "天气", "气温", "降雨", "怎么样", "怎样",
                "如何", "查"};
        for (int i = 0; i < fillers.length; i++) s = s.replace(fillers[i], "");
        s = s.trim();
        if (s.length() == 0 || s.length() > 12) return null;
        return s;
    }

    // ===== parsing (pure) =====

    /** WMO weather_code -> short Chinese phrase; unknown -> "-". */
    public static String weatherPhrase(String code) {
        if (code == null) return "-";
        try {
            int c = (int) Double.parseDouble(code.trim());
            if (c == 0) return "晴";
            if (c <= 3) return "多云";
            if (c >= 45 && c <= 48) return "雾";
            if (c >= 51 && c <= 67) return "雨";
            if (c >= 71 && c <= 77) return "雪";
            if (c >= 80 && c <= 82) return "阵雨";
            if (c >= 95 && c <= 99) return "雷雨";
            return "-";
        } catch (Throwable t) {
            return "-";
        }
    }

    /** Parse geocoding response; first result's lat,lon + name.
     *  Null on no-results/malformed (soft). */
    public static String[] parseGeo(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            org.json.JSONArray arr = root.optJSONArray("results");
            if (arr == null || arr.length() == 0) return null;
            JSONObject r = arr.optJSONObject(0);
            if (r == null) return null;
            if (!r.has("latitude") || !r.has("longitude")) return null;
            return new String[]{String.valueOf(r.getDouble("latitude")),
                    String.valueOf(r.getDouble("longitude")),
                    r.optString("name", "")};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parse forecast response (current temp/code + today's max/min).
     *  Null on malformed/missing fields (soft). */
    public static Weather parseWeather(String city, String body) {
        if (body == null || city == null || city.length() == 0) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject cur = root.optJSONObject("current");
            JSONObject daily = root.optJSONObject("daily");
            if (cur == null || daily == null) return null;
            if (!cur.has("temperature_2m") || !cur.has("weather_code")) {
                return null;
            }
            org.json.JSONArray mx = daily.optJSONArray("temperature_2m_max");
            org.json.JSONArray mn = daily.optJSONArray("temperature_2m_min");
            if (mx == null || mn == null || mx.length() == 0
                    || mn.length() == 0) return null;
            return new Weather(city,
                    String.valueOf(cur.getDouble("temperature_2m")),
                    String.valueOf(cur.getLong("weather_code")),
                    String.valueOf(mx.getDouble(0)),
                    String.valueOf(mn.getDouble(0)));
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== rendering (pure) =====

    /** One line, <=200 chars: 〔天气〕上海 现在 17.2°C 晴 今天 14.9~21.1°C */
    public static String render(Weather w) {
        if (w == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("\u3014\u5929\u6c14\u3015").append(w.city).append(" \u73b0\u5728 ")
                .append(w.temp).append("\u00b0C ")
                .append(weatherPhrase(w.code)).append(" \u4eca\u5929 ")
                .append(w.min).append("~").append(w.max).append("\u00b0C");
        String s = sb.toString();
        return s.length() > MAX_LINE_CHARS ? s.substring(0, MAX_LINE_CHARS) : s;
    }

    // ===== HTTP (thin; worker thread only) =====

    /** GET a UTF-8 JSON body; null on any failure. Never throws. */
    static String httpGetJson(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url)
                    .openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", SearchService.BROWSER_UA);
            int rc = c.getResponseCode();
            if (rc != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Full lookup: city -> lat/lon -> weather line. Null = silent fallback. */
    public Weather lookup(String query) {
        if (!isWeatherIntent(query)) return null;
        String city = extractCity(query);
        if (city == null) return null;
        String[] geo;
        try {
            String u = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + java.net.URLEncoder.encode(city, "UTF-8")
                    + "&count=1&language=zh";
            geo = parseGeo(httpGetJson(u));
        } catch (Throwable t) {
            return null;
        }
        if (geo == null) return null;
        Weather w;
        try {
            String u = "https://api.open-meteo.com/v1/forecast?latitude="
                    + geo[0] + "&longitude=" + geo[1]
                    + "&current=temperature_2m,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=2";
            w = parseWeather(geo[2].length() > 0 ? geo[2] : city,
                    httpGetJson(u));
        } catch (Throwable t) {
            return null;
        }
        return w;
    }
}
