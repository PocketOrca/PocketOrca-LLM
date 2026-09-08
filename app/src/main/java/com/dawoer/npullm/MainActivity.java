package com.dawoer.npullm;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * v0.3.0: model access core replaced with the llama-npu.apk (v1.1, verified
 * on-device) implementation — MANAGE_EXTERNAL_STORAGE + native directory
 * browser returning real file paths. The SAF/content-URI resolution chain
 * was the cause of the v0.2.0 model-load failure. WebView shell (log ring,
 * state machine, quant confirm dialog) is kept.
 */
public class MainActivity extends Activity {
    private WebView web;               // package-private: accessed by static nested Bridge
    private LlamaServerManager server; // package-private likewise
    private final ServerChatStreamer chat = new ServerChatStreamer();
    private Bridge bridge;
    private SharedPreferences prefs;
    /** v1.2.19: ask for the battery exemption at most once per session. */
    private boolean batteryAsked;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getPreferences(MODE_PRIVATE);
        instanceRef = new java.lang.ref.WeakReference<>(this);
        web = new WebView(this);
        // Wrap the WebView in a FrameLayout and pad THAT layer: Chromium's
        // compositor can ignore padding set directly on the WebView, but a
        // plain ViewGroup padding always applies. Combined with the
        // windowOptOutEdgeToEdgeEnforcement style (API 35) this keeps the
        // app content below the status bar on Android 15 (v0.5.1 fix).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(0, bars.top, 0, 0);
                return android.view.WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient());
        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");

        server = LlamaServerManager.get(getApplicationContext(), new UiForwarder(this));
        // v1.3.1: the singleton may predate this Activity (reborn while the app
        // was dead) — make sure state pushes reach THIS WebView from now on.
        server.setUi(new UiForwarder(this));
        // Chat page SSE callbacks: push deltas into the WebView (spec §3.5)
        chat.setCallbacks(new ChatUiBridge(this));
        requestNotifPermission();

        // All-files access — the llama-npu.apk verified flow. Models are read
        // from real paths under /storage/emulated/0; the toggle only exists
        // in system settings when MANAGE_EXTERNAL_STORAGE is declared.
        if (!Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            // v1.2.19: storage already granted (any launch after the first) —
            // ask for the battery-optimization exemption now.
            requestBatteryExemption();
        }
    }

    /**
     * v1.2.17: the PARTIAL_WAKE_LOCK moved to ServerService (held only while
     * the llama-server is up, released on stop — Termux "wakelock" model).
     * Only the POST_NOTIFICATIONS runtime request (API 33+) stays here so the
     * FGS notification is visible. Denied = degraded (spec §3.7).
     */
    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    /**
     * v1.2.18: Samsung/OneUI (and other vendors) kill or cut the network of
     * background apps even while a foreground service is running — the S25
     * field test showed am_kill "remove task" on the llama-server host
     * process, and the S23U showed connectivity drops while backgrounded.
     * Requests the standard "Unrestricted" battery exemption (same setting
     * Termux asks for). Denying is harmless: the server still works while
     * the app is foreground, it just may not survive backgrounding/Doze.
     */
    private void requestBatteryExemption() {
        if (batteryAsked) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                batteryAsked = true;
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }

    /**
     * v1.2.17: FLAG_KEEP_SCREEN_ON removed — the screen may sleep normally;
     * the llama-server keeps running in the background (FGS + partial
     * wake/wifi locks in ServerService, Termux "wakelock" model).
     */
    @Override
    protected void onResume() {
        super.onResume();
        // v1.2.19: the storage-grant flow returns through onResume, not
        // onCreate — so first-launch-after-install also asks here. The
        // batteryAsked flag keeps it to one dialog per session.
        requestBatteryExemption();
        // If the server was already running (service survived Activity death),
        // reattach the notification updater and re-sync the UI state machine.
        if (LlamaServerManager.hasInstance()) {
            LlamaServerManager m = LlamaServerManager.peek();
            if (m.isRunning()) {
                if (LlamaServerManager.isServiceRunning()) ServerService.start(this);
                evalJs("window.onServerState && window.onServerState(" + jq("running") + "," + jq("运行中") + ")");
            } else if (m.isIdleOrError()) {
                // v1.1.1: orphan adoption — a previous exec child may still be
                // serving after Android killed our old App process.
                new Thread(() -> {
                    boolean adopted = m.adoptOrphanIfAny(m.currentPort());
                    if (adopted) {
                        ServerService.start(this);
                        evalJs("window.onServerState && window.onServerState(" + jq("running") + "," + jq("运行中(已接管)") + ")");
                    }
                }, "orphan-adopt").start();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // T5: the FGS keeps llama-server alive after the Activity is gone;
        // the user stops it from the notification or by relaunching the app.
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /* ---------- model directory browser (llama-npu.apk verified core) ---------- */

    void openBrowser() {
        String start = prefs.getString("dir", "/storage/emulated/0/Download");
        File d = new File(start);
        if (!d.isDirectory()) d = Environment.getExternalStorageDirectory();
        showDir(d);
    }

    private void showDir(final File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            evalJs("window.onModelPicked && window.onModelPicked(" + jq("{\"error\":\"无法访问: " + dir.getPath() + "\"}") + ")");
            return;
        }
        ArrayList<File> files = new ArrayList<>();
        ArrayList<String> items = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            items.add("↑ ..");
            files.add(parent);
        }
        ArrayList<File> dirs = new ArrayList<>(), ggufs = new ArrayList<>();
        for (File f : fs) {
            if (f.isDirectory()) dirs.add(f);
            else if (f.getName().toLowerCase(Locale.US).endsWith(".gguf")) ggufs.add(f);
        }
        Collections.sort(dirs, (a, b2) -> a.getName().compareToIgnoreCase(b2.getName()));
        Collections.sort(ggufs, (a, b2) -> a.getName().compareToIgnoreCase(b2.getName()));
        for (File f : dirs)  { items.add("▶ " + f.getName() + "/"); files.add(f); }
        for (File f : ggufs) { items.add("🧠 " + f.getName()); files.add(f); }
        if (items.isEmpty()) items.add("(空目录)");

        new AlertDialog.Builder(this)
            .setTitle(dir.getPath())
            .setItems(items.toArray(new String[0]), (dlg, w) -> {
                File f = files.get(w);
                if (f.isDirectory()) {
                    prefs.edit().putString("dir", f.getPath()).apply();
                    showDir(f);
                } else {
                    prefs.edit().putString("model", f.getPath()).apply();
                    evalJs("window.onModelPicked && window.onModelPicked(" + fileJson(f) + ")");
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    /** Metadata for a real file path — no SAF/Cursor involved. */
    static String fileJson(File f) {
        JSONObject j = new JSONObject();
        try {
            j.put("path", f.getPath());
            j.put("name", f.getName());
            j.put("sizeBytes", f.length());
            long mt = f.lastModified();
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            j.put("mtime", mt > 0 ? df.format(new Date(mt)) : "");
            ModelFileHelper.Quant q = ModelFileHelper.quantOf(f.getName());
            j.put("quant", q.label);
            j.put("npuOk", q.npuOk);
        } catch (Exception ignored) {}
        return j.toString();
    }

    void evalJs(final String js) {
        web.post(() -> web.evaluateJavascript(js, null));
    }

    static String jq(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /* ---------- static nested helpers ----------
     * The bt34 d8 (8.2.2-dev) crashes dexing anonymous/inner classes and enums
     * (measured NPE on every min-api). All non-static nested structures are
     * therefore written as static nested classes with explicit references. */

    /** Chat page SSE → JS bridge. d8-safe static nested + WeakReference. */
    static final class ChatUiBridge implements ServerChatStreamer.Callbacks {
        private final java.lang.ref.WeakReference<MainActivity> act;
        ChatUiBridge(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }
        @Override public void onChunk(String text) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatChunk && window.onChatChunk(" + jq(text) + ")");
        }
        @Override public void onDone(String status, String statsJson) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatDone && window.onChatDone(" + jq(status) + "," + jq(statsJson) + ")");
        }
    }

    static final class UiForwarder implements LlamaServerManager.Ui {
        private final java.lang.ref.WeakReference<MainActivity> act;
        UiForwarder(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }
        @Override public void onServerState(String state, String msg) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onServerState && window.onServerState(" + jq(state) + "," + jq(msg) + ")");
        }
        @Override public void onServerDied(int exitCode) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onServerDied && window.onServerDied(" + exitCode + ")");
        }
    }

    static final class Bridge {
        private final java.lang.ref.WeakReference<MainActivity> act;
        Bridge(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }

        @JavascriptInterface
        public void pickModel() {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(() -> a.openBrowser());
        }

        /** Last selected model as JSON ("" if none) — restores the card after app restart. */
        @JavascriptInterface
        public String lastModelJson() {
            MainActivity a = act.get();
            if (a == null) return "";
            String p = a.prefs.getString("model", "");
            if (p.isEmpty()) return "";
            File f = new File(p);
            return f.canRead() ? fileJson(f) : "";
        }

        @JavascriptInterface
        public String startServer(final String modelPath, final String profileId, final int port,
                                  final int ctxSize, final String reasoning,
                                  final int threads, final boolean noKvOffload) {
            return startServer(modelPath, profileId, port, ctxSize, reasoning,
                    threads, noKvOffload, 0);
        }

        @JavascriptInterface
        public String startServer(final String modelPath, final String profileId, final int port,
                                  final int ctxSize, final String reasoning,
                                  final int threads, final boolean noKvOffload,
                                  final int ubatchSize) {
            MainActivity a = act.get();
            if (a == null || a.server == null) return "{\"ok\":false}";
            // v1.3.1: persist launch args so a swipe-away kill ("remove task")
            // can relaunch the server even if the orphan child also died.
            ServerService.saveRebornArgs(a.getApplicationContext(), modelPath, profileId,
                    port, ctxSize, reasoning, threads, noKvOffload, ubatchSize);
            new Thread(() -> {
                a.server.start(modelPath, profileId, port, ctxSize, reasoning,
                        threads, noKvOffload, ubatchSize);
                // T5: promote to foreground service while running
                ServerService.start(a.getApplicationContext());
            }, "bridge-start").start();
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String stopServer() {
            MainActivity a = act.get();
            if (a == null || a.server == null) return "{\"ok\":false}";
            // v1.3.1: user-initiated stop must NOT be resurrected by the alarm.
            ServerService.clearRebornArgs(a.getApplicationContext());
            new Thread(() -> a.server.stop(), "bridge-stop").start();
            ServerService.stop(a.getApplicationContext());
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String getLog() {
            MainActivity a = act.get();
            return (a != null && a.server != null) ? a.server.log().snapshot() : "";
        }

        @JavascriptInterface
        public String getStatus() {
            MainActivity a = act.get();
            return (a != null && a.server != null) ? a.server.statusJson() : "{\"state\":\"ready\"}";
        }

        /** Phone's LAN IPv4 for the endpoint card (spec §3.6: WifiManager primary, NetworkInterface fallback). */
        @JavascriptInterface
        public String getLocalIp() {
            MainActivity a = act.get();
            if (a == null) return "";
            try {
                WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress(); // little-endian int, 0 = not associated
                    if (ip != 0) {
                        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." +
                               ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                    }
                }
            } catch (Throwable ignored) {}
            // fallback: enumerate interfaces, take first non-loopback IPv4 site-local
            try {
                java.util.Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress ad = addrs.nextElement();
                        if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) {
                            return ad.getHostAddress();
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return "";
        }

        /** True when something already listens on 127.0.0.1:{port} (spec R10). */
        @JavascriptInterface
        public boolean isPortBusy(final int port) {
            MainActivity a = act.get();
            if (a == null || a.server == null) return false;
            try { return a.server.portInUse(port); } catch (Throwable e) { return false; }
        }

        /** Wipe the log ring (log page 清屏). */
        @JavascriptInterface
        public void clearLog() {
            MainActivity a = act.get();
            if (a != null && a.server != null) a.server.log().clear();
        }

        /** Async POST for the CLI page (single connection, HttpURLConnection). */
        @JavascriptInterface
        public void httpPost(final String url, final String bodyJson) {
            MainActivity a = act.get();
            if (a == null) return;
            final android.content.Context c = a.getApplicationContext();
            new Thread(() -> {
                String err = null;
                StringBuilder sb = new StringBuilder();
                try {
                    java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(300000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    }
                    err = String.valueOf(code);
                } catch (Throwable e) {
                    err = "ERR: " + e.getClass().getSimpleName();
                }
                final String payload = sb.toString();
                final String status = err;
                c.getMainExecutor().execute(() ->
                        evalJsStatic("window.onHttpDone && window.onHttpDone(" + jq(status) + "," + jq(payload) + ")"));
            }, "bridge-http").start();
        }

        /** Save the remote API key (Keystore-encrypted); JS gets a fingerprint. */
        @JavascriptInterface
        public String saveApiKey(final String key) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            try {
                if (key == null || key.isEmpty()) {
                    KeyStoreBox.clear(a.getApplicationContext());
                    return "{\"ok\":true,\"fingerprint\":\"\"}";
                }
                KeyStoreBox.save(a.getApplicationContext(), key);
                return "{\"ok\":true,\"fingerprint\":\"" + KeyStoreBox.fingerprint(key) + "\"}";
            } catch (Throwable e) {
                return "{\"ok\":false}";
            }
        }

        /** Stored key fingerprint ("" if none) — raw key never crosses the bridge. */
        @JavascriptInterface
        public String apiKeyFingerprint() {
            MainActivity a = act.get();
            if (a == null) return "";
            String k = KeyStoreBox.load(a.getApplicationContext());
            return k.isEmpty() ? "" : KeyStoreBox.fingerprint(k);
        }

        /** Welcome page (T-splash): first-run flag + device probe, JSON. */
        @JavascriptInterface
        public String welcomeInfo() {
            MainActivity a = act.get();
            if (a == null) return "{}";
            JSONObject j = new JSONObject();
            try {
                j.put("shown", a.prefs.getBoolean("welcome_shown", false));
                // API 31+: SOC_MODEL gives the precise chip (e.g. sm8750);
                // Build.HARDWARE on many 2024+ devices is just "qcom".
                String socRaw = "";
                String socPretty = "";
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    CharSequence sm = android.os.Build.SOC_MODEL;
                    if (sm != null && !sm.toString().equalsIgnoreCase("unknown")) {
                        socRaw = sm.toString();
                        socPretty = socName(socRaw);
                    }
                }
                if (socPretty.isEmpty()) {
                    String hw = android.os.Build.HARDWARE == null ? "unknown" : android.os.Build.HARDWARE;
                    socRaw = hw;
                    socPretty = socName(hw);
                }
                j.put("soc", socPretty);
                j.put("socRaw", socRaw);
                j.put("cores", Runtime.getRuntime().availableProcessors());
                android.app.ActivityManager am = (android.app.ActivityManager)
                        a.getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                j.put("ramGB", ramLabelGiB(mi.totalMem));
                j.put("model", android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);
            } catch (Throwable ignored) {}
            return j.toString();
        }

        /** Marketing RAM label. MemTotal is always below the advertised size
         *  (kernel + reserved memory), and advertisements use decimal GB, so
         *  plain GiB rounding shows "11 GB" on 12 GB phones (e.g. S25:
         *  MemTotal 11,381,576 kB = 10.86 GiB but 11.66 decimal GB).
         *  Snap up to the integer decimal-GB label; the 0.02 GB epsilon only
         *  guards MemTotal landing exactly on an integer boundary. */
        static int ramLabelGiB(long totalBytes) {
            return (int) Math.ceil(totalBytes / 1e9 - 0.02);
        }

        /** Map common SoC hardware strings to friendly Snapdragon names;
         *  unknown values pass through raw (never invent). */
        static String socName(String hw) {
            String h = hw.toLowerCase(java.util.Locale.US);
            if (h.contains("sm8750") || h.contains("sun")) return "骁龙 8 Elite";
            if (h.contains("sm8650") || h.contains("pineapple")) return "骁龙 8 Gen 3";
            if (h.contains("sm8550") || h.contains("kalama")) return "骁龙 8 Gen 2";
            if (h.contains("sm8475")) return "骁龙 8+ Gen 1";
            if (h.contains("sm8450") || h.contains("taro")) return "骁龙 8 Gen 1";
            if (h.contains("sm7650")) return "骁龙 7+ Gen 3";
            if (h.contains("sm7675")) return "骁龙 7+ Gen 3";
            if (h.contains("sm7670")) return "骁龙 7s Gen 3";
            if (h.contains("sm6650")) return "骁龙 6s Gen 3";
            if (h.contains("qcm6490") || h.contains("qcs6490")) return "骁龙 QCM6490";
            return hw;
        }

        /** Welcome acknowledged — never show again. */
        @JavascriptInterface
        public void welcomeDone() {
            MainActivity a = act.get();
            if (a != null) a.prefs.edit().putBoolean("welcome_shown", true).apply();
        }

        /** Factory reset (settings): wipe prefs, secure key, app data dirs.
         *  localStorage is cleared from the JS side before calling this. */
        @JavascriptInterface
        public void factoryReset() {
            MainActivity a = act.get();
            if (a == null) return;
            try {
                a.prefs.edit().clear().apply();
                KeyStoreBox.clear(a.getApplicationContext());
                File dataDir = a.getApplicationInfo().dataDir == null
                        ? null : new File(a.getApplicationInfo().dataDir);
                if (dataDir != null && dataDir.exists()) {
                    File[] kids = dataDir.listFiles();
                    if (kids != null) for (File f : kids) deleteRecursive(f);
                }
            } catch (Throwable ignored) {}
        }

        static void deleteRecursive(File f) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
            f.delete();
        }

        /** Export the log ring to Download/npullm-log.txt (MANAGE_EXTERNAL_STORAGE granted). */
        @JavascriptInterface
        public String exportLog() {
            MainActivity a = act.get();
            if (a == null || a.server == null) return "";
            String text = a.server.log().snapshot();
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "Download");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "npullm-log-" +
                        new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
                java.io.FileWriter w = new java.io.FileWriter(out);
                w.write(text);
                w.close();
                return out.getAbsolutePath();
            } catch (Throwable e) {
                return "";
            }
        }

        /** Remote endpoint connectivity test: GET {base}/models, 5s timeout (spec §3.4B). */
        @JavascriptInterface
        public void testEndpoint(final String baseUrl) {
            MainActivity a = act.get();
            if (a == null) return;
            new Thread(() -> {
                String status;
                String payload;
                long t0 = System.currentTimeMillis();
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(baseUrl + "/models").openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    String key = KeyStoreBox.load(a.getApplicationContext());
                    if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
                    status = String.valueOf(c.getResponseCode());
                    java.io.InputStream in = c.getResponseCode() >= 400
                            ? c.getErrorStream() : c.getInputStream();
                    StringBuilder sb2 = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                        String ln;
                        while ((ln = r.readLine()) != null) sb2.append(ln);
                    }
                    payload = sb2.toString();
                } catch (Throwable e) {
                    status = "ERR";
                    String n = e.getClass().getSimpleName();
                    if (n.contains("UnknownHost")) n = "DNS解析失败";
                    else if (n.contains("SSL")) n = "TLS握手失败";
                    else if (n.contains("Timeout")) n = "连接超时";
                    else if (n.contains("Connect")) n = "无法连接";
                    payload = "{\"error\":\"" + n + "\"}";
                }
                final long ms = System.currentTimeMillis() - t0;
                final String st = status;
                final String pl = payload;
                a.runOnUiThread(() -> evalJsStatic(
                        "window.onEndpointTest && window.onEndpointTest(" + jq(st) + "," + jq(pl) + "," + ms + ")"));
            }, "endpoint-test").start();
        }

        /** Remote chat request with per-request timeout (spec §3.4 mode B). */
        @JavascriptInterface
        public void httpPostRemote(final String url, final String bodyJson, final int timeoutSec) {
            MainActivity a = act.get();
            if (a == null) return;
            String key = KeyStoreBox.load(a.getApplicationContext());
            postAsync(url, bodyJson, key, Math.max(5000, Math.min(300000, timeoutSec * 1000)));
        }

        /** Shared POST worker. TLS is ALWAYS verified (spec R11 red line —
         *  the old global hostname-verifier opt-out is gone). */
        private void postAsync(final String url, final String bodyJson,
                               final String bearer, final int timeoutMs) {
            MainActivity a = act.get();
            if (a == null) return;
            new Thread(() -> {
                String status;
                StringBuilder sb = new StringBuilder();
                try {
                    java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(timeoutMs);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    if (bearer != null && !bearer.isEmpty())
                        conn.setRequestProperty("Authorization", "Bearer " + bearer);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    status = String.valueOf(conn.getResponseCode());
                    java.io.InputStream in = conn.getResponseCode() >= 400
                            ? conn.getErrorStream() : conn.getInputStream();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    }
                } catch (Throwable e) {
                    status = "ERR: " + e.getClass().getSimpleName();
                }
                final String payload = sb.toString();
                final String st = status;
                evalJsStatic("window.onHttpDone && window.onHttpDone(" + jq(st) + "," + jq(payload) + ")");
            }, "bridge-http").start();
        }

        /** Persist request-level sampling params (spec §4 rule 5). */
        @JavascriptInterface
        public void setSampling(final double temperature, final int topK, final double topP,
                                final double minP, final double repeatPenalty, final String systemPrompt) {
            MainActivity a = act.get();
            if (a != null && a.server != null)
                a.server.setSampling(temperature, topK, topP, minP, repeatPenalty, systemPrompt);
        }

        /** T5: notification permission granted? (Chat/Server UI hint) */
        @JavascriptInterface
        public String notifPerm() {
            MainActivity a = act.get();
            if (a == null) return "unknown";
            if (android.os.Build.VERSION.SDK_INT < 33) return "granted";
            return a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
        }

        /** Chat page: start one streaming request (spec §3.5). */
        @JavascriptInterface
        public void chatStart(final String bodyJson, final String remoteUrl) {
            MainActivity a = act.get();
            if (a == null) return;
            if (remoteUrl != null && !remoteUrl.isEmpty()) {
                // Chat remote mode: stream from any OpenAI-compatible endpoint
                // (same key as CLI remote mode; TLS always verified, R11).
                a.chat.start(remoteUrl, bodyJson, KeyStoreBox.load(a.getApplicationContext()));
                return;
            }
            if (a.server == null) return;
            int port = a.server.currentPort();
            a.chat.start("http://127.0.0.1:" + port + "/v1/chat/completions", bodyJson, null);
        }

        /** Chat page: abort current streaming request. */
        @JavascriptInterface
        public void chatAbort() {
            MainActivity a = act.get();
            if (a != null) a.chat.abort();
        }
    }

    static void evalJsStatic(String js) {
        // safe post from any thread; no-op if activity gone
        MainActivity a = instanceRef == null ? null : instanceRef.get();
        if (a != null) a.evalJs(js);
    }

    private static java.lang.ref.WeakReference<MainActivity> instanceRef = null;
}
