package com.dawoer.npullm;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dawoer.npullm.rag.ChatStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;

import com.dawoer.npullm.search.SearchConfig;
import com.dawoer.npullm.search.EngineRegistry;
import com.dawoer.npullm.search.SearchController;
import com.dawoer.npullm.search.SearchForegroundService;
import com.dawoer.npullm.rag.RagConfig;
import com.dawoer.npullm.rag.RagForegroundService;
import com.dawoer.npullm.rag.RagService;
import com.dawoer.npullm.search.SearchServer;
import com.dawoer.npullm.search.SearchService;
import com.dawoer.npullm.search.SpeedTester;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

    /* ---------- vc99 C1: chat media picker state ---------- */
    static final int REQ_CHAT_CAMERA = 3101;
    static final int REQ_CHAT_MEDIA = 3102;
    static final int REQ_CHAT_RECORD = 3103;
    static final int REQ_RAG_DIR = 3104;
    /** "image" | "video" | "audio" — what the current media pick is for. */
    private String chatPickKind;
    /** mime for document picks, e.g. "image/*" or "video/*". */
    private String chatPickMime;
    /** vc100: MediaStore row pre-inserted for the camera shot (EXTRA_OUTPUT). */
    private android.net.Uri chatCameraOut;
    /** vc100: slot file saved via the 📎 menu; its onSlotSaved callback. */
    private String pendingMenuSlotSave;

    /** vc102: latest chat transcript pushed from JS (chatHistPush), consumed
     *  by the slot-save sidecar write. Null-safe: save without history works. */
    private volatile String chatHistMirror;

    /* ---------- vc99 C2: slot attach picker state ---------- */
    /** slot filename chosen on the Chat page, waiting to attach. */
    private String pendingSlotAttach;
    /** v1.2.19: ask for the battery exemption at most once per session. */
    private boolean batteryAsked;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getPreferences(MODE_PRIVATE);
        instanceRef = new java.lang.ref.WeakReference<>(this);
        web = new WebView(this);
        // v1.4.0(vc75): push bmoe download/import progress to the WebView.
        // The JS poller alone is not enough — see onBmoeDlStatus notes; without
        // this registration the DlStatus notifier was always null (progress
        // never reached the UI during import).
        BmoeModelManager.get(getApplicationContext()).setNotifier(json -> {
            if (web == null) return; // notifier fires from bg threads; web not yet built
            evalJs("window.onBmoeDlStatus && window.onBmoeDlStatus(" + jq(json) + ")");
        });
        // Wrap the WebView in a FrameLayout and pad THAT layer: Chromium's
        // compositor can ignore padding set directly on the WebView, but a
        // plain ViewGroup padding always applies. Combined with the
        // windowOptOutEdgeToEdgeEnforcement style (API 35) this keeps the
        // app content below the status bar on Android 15 (v0.5.1 fix).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // vc120: include ime() — the keyboard inset was previously ignored,
            // so on small screens (S25) the IME covered the focused input and
            // everything below it. The IME inset replaces the nav-bar inset
            // (it already includes it), so the padding is max() of both.
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.displayCutout()
                        | android.view.WindowInsets.Type.navigationBars()
                        | android.view.WindowInsets.Type.ime());
                v.setPadding(0, bars.top, 0, bars.bottom);
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
        // vc99 C1: stale chat media from previous sessions never carries over;
        // a pending "new chat" request (button pressed before process death)
        // is applied now that the WebView is fresh.
        ChatMedia.wipeAll(getApplicationContext());
        boolean newChatPending = prefs.getBoolean("chatNewPending", false);
        if (newChatPending) {
            prefs.edit().remove("chatNewPending").apply();
            evalJs("window.chatApplyNewPending && window.chatApplyNewPending()");
        }
        // v1.4.0: bmoe engine chunks flow through the static UiHook delegate
        BmoeAdapter.UiHook.setDelegate(new BmoeChatUiBridge(this));
        requestNotifPermission();
        // v1.3.7 vc67: the FGS now hosts the standby notification — start it
        // with the app so the shade shows the server state ("服务未启动")
        // from the first second. Idempotent: while running it just rebinds
        // the live card; while idle it only publishes the standby card.
        ServerService.start(this);

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
        // vc99 C1: never leave the mic hot after the shell dies.
        if (ChatMedia.isRecording()) ChatMedia.stopRecording(getApplicationContext(), false);
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

    /** v1.4.0: bmoe import picker — same browser, imports instead of selecting.
     *  Returns to the WebView through onBmoeDlStatus progress (import phase). */
    void bmoeImportBrowser() {
        String start = prefs.getString("dir", "/storage/emulated/0/Download");
        File d = new File(start);
        if (!d.isDirectory()) d = Environment.getExternalStorageDirectory();
        showImportDir(d);
    }

    private void showImportDir(final File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            evalJs("window.onBmoeDlStatus && window.onBmoeDlStatus(" + jq("{\"phase\":\"error\",\"error\":\"无法访问: " + dir.getPath() + "\"}") + ")");
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
                    showImportDir(f);
                } else {
                    String err = BmoeModelManager.get(getApplicationContext()).startImport(f.getPath());
                    if (err != null) {
                        evalJs("window.onBmoeDlStatus && window.onBmoeDlStatus(" + jq("{\"phase\":\"error\",\"error\":\"" + err.replace("\"", "'") + "\"}") + ")");
                    }
                }
            })
            .setNegativeButton("关闭", null)
            .show();
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

    /** vc141: 索引任务（后台线程 → JS 回调）。零匿名类。 */
    /** vc147: 聊天记忆索引（后台 → window.onRagIndex）。 */
    static final class RagIndexChatTask implements Runnable {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        RagIndexChatTask(MainActivity a) {
            this.owner = new java.lang.ref.WeakReference<MainActivity>(a);
        }
        @Override public void run() {
            MainActivity a = owner.get();
            if (a == null) return;
            String json;
            try {
                json = RagService.get(a.getApplicationContext()).indexChat(false);
            } catch (Throwable t) {
                json = "{\"ok\":false,\"error\":\"" + RagService.escJs(String.valueOf(t)) + "\"}";
            }
            a.evalJs("window.onRagIndex && window.onRagIndex(" + jq(json) + ")");
        }
    }

    /** vc147: 记忆召回（后台 → window.onRagRecall）。 */
    static final class RagRecallTask implements Runnable {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        private final String q;
        private final int k;
        RagRecallTask(MainActivity a, String q, int k) {
            this.owner = new java.lang.ref.WeakReference<MainActivity>(a);
            this.q = q;
            this.k = k;
        }
        @Override public void run() {
            MainActivity a = owner.get();
            if (a == null) return;
            String json;
            try {
                json = RagService.get(a.getApplicationContext()).recall(q, k);
            } catch (Throwable t) {
                json = "{\"ok\":false,\"error\":\"" + RagService.escJs(String.valueOf(t)) + "\"}";
            }
            a.evalJs("window.onRagRecall && window.onRagRecall(" + jq(json) + ")");
        }
    }

    static final class RagIndexTask implements Runnable {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        private final String dir;
        private final boolean rebuild;
        RagIndexTask(MainActivity a, String dir, boolean rebuild) {
            this.owner = new java.lang.ref.WeakReference<MainActivity>(a);
            this.dir = dir;
            this.rebuild = rebuild;
        }
        @Override public void run() {
            MainActivity a = owner.get();
            if (a == null) return;
            String json;
            try {
                json = RagService.get(a.getApplicationContext()).index(dir, rebuild);
            } catch (Throwable t) {
                json = "{\"ok\":false,\"error\":\"" + RagService.escJs(String.valueOf(t)) + "\"}";
            }
            a.evalJs("window.onRagIndex && window.onRagIndex(" + jq(json) + ")");
        }
    }

    /** vc141: 检索任务（后台线程 → JS 回调）。 */
    static final class RagSearchTask implements Runnable {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        private final String q;
        private final int k;
        RagSearchTask(MainActivity a, String q, int k) {
            this.owner = new java.lang.ref.WeakReference<MainActivity>(a);
            this.q = q;
            this.k = k;
        }
        @Override public void run() {
            MainActivity a = owner.get();
            if (a == null) return;
            String json;
            try {
                json = RagService.get(a.getApplicationContext()).search(q, k, -1f, -1);
            } catch (Throwable t) {
                json = "{\"ok\":false,\"error\":\"" + RagService.escJs(String.valueOf(t)) + "\"}";
            }
            a.evalJs("window.onRagSearch && window.onRagSearch(" + jq(json) + ")");
        }
    }

    /** vc141: SAF 目录选择（必须在 UI 线程发起）。 */
    static final class RagPickDirTask implements Runnable {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        RagPickDirTask(MainActivity a) {
            this.owner = new java.lang.ref.WeakReference<MainActivity>(a);
        }
        @Override public void run() {
            MainActivity a = owner.get();
            if (a == null) return;
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                a.startActivityForResult(i, REQ_RAG_DIR);
            } catch (Throwable t) {
                a.evalJs("window.onRagDir && window.onRagDir(null)");
            }
        }
    }

    /** Chat page SSE → JS bridge. d8-safe static nested + WeakReference. */
    static final class ChatUiBridge implements ServerChatStreamer.Callbacks {
        private final java.lang.ref.WeakReference<MainActivity> act;
        ChatUiBridge(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }
        @Override public void onChunk(String text) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatChunk && window.onChatChunk(" + jq(text) + ")");
        }
        /** vc158: 思考流 → 独立回调，不进正文。 */
        @Override public void onReasoning(String text) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatReasoning && window.onChatReasoning(" + jq(text) + ")");
        }
        @Override public void onDone(String status, String statsJson) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatDone && window.onChatDone(" + jq(status) + "," + jq(statsJson) + ")");
        }
    }

    /* ---------- vc99 C1: chat media flows ---------- */

    /** Bridge entry: fire the picker for kind = image|video|audio. */
    void chatPickMedia(String kind) {
        chatPickKind = kind;
        try {
            if ("video".equals(kind)) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("video/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "video"), REQ_CHAT_MEDIA);
            } else if ("audio".equals(kind)) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("audio/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "audio"), REQ_CHAT_MEDIA);
            } else if ("gallery".equals(kind)) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "image"), REQ_CHAT_MEDIA);
            } else {
                // Camera first, gallery via chooser fallback (no permission dialog).
                // vc100: the camera writes into OUR MediaStore row (EXTRA_OUTPUT +
                // ClipData grant). Pre-v100 the shot's location was app-
                // unpredictable (Samsung OneUI: "camera failed" every time) and a
                // raw file:// URI is forbidden on targetSdk>=24; a MediaStore row
                // needs no FileProvider and no androidx (minSdk 29 = API 29 APIs).
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (cam.resolveActivity(getPackageManager()) != null) {
                    try {
                        // IS_PENDING row: invisible to the gallery until we
                        // finalize it; deleted after staging — the shot lives
                        // in the chat, not the album (chat-camera semantics).
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                                "cam-" + System.currentTimeMillis() + ".jpg");
                        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketOrca");
                        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                        android.net.Uri out = getContentResolver().insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (out == null) throw new Exception("MediaStore insert failed");
                        chatCameraOut = out;
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, out);
                        // ClipData is what propagates the URI grant to the camera
                        // process on every API level; flags alone cover data URIs.
                        cam.setClipData(android.content.ClipData.newRawUri("output", out));
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(cam, REQ_CHAT_CAMERA);
                    } catch (Exception e) {
                        chatCameraOut = null;
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(i, "image"), REQ_CHAT_MEDIA);
                    }
                } else {
                    chatCameraOut = null;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "image"), REQ_CHAT_MEDIA);
                }
            }
        } catch (Exception e) {
            evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("picker failed") + ")");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHAT_CAMERA) {
            if (resultCode != RESULT_OK) {
                // cancelled / failed: drop the pending row entirely
                finishCameraRow();
                chatPickKind = null;
                return;
            }
            // vc100: EXTRA_OUTPUT decides where the shot lands — the row we
            // pre-inserted (chatCameraOut) is the authoritative shot. Pre-v100
            // the intent carried no EXTRA_OUTPUT and the "latest MediaStore
            // image" guesswork failed on Samsung (camera failed every time).
            try {
                if (chatCameraOut == null) throw new Exception("no output uri");
                InputStream in = getContentResolver().openInputStream(chatCameraOut);
                if (in == null) throw new Exception("no stream");
                String ref = ChatMedia.store(getApplicationContext(), in, "image/jpeg", ".jpg");
                evalJs("window.onChatAttached && window.onChatAttached(" + jq(ref == null ? "" : ref) + ")");
            } catch (Exception e) {
                evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("camera failed") + ")");
            }
            finishCameraRow();
            chatPickKind = null;
            return;
        }
        if (requestCode == REQ_RAG_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                evalJs("window.onRagDir && window.onRagDir(null)");
                return;
            }
            android.net.Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignore) {
            }
            RagConfig.init(getApplicationContext());
            RagConfig.setDirUri(u.toString());
            evalJs("window.onRagDir && window.onRagDir(" + jq(u.toString()) + ")");
            return;
        }
        if (requestCode == REQ_CHAT_MEDIA) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) { chatPickKind = null; return; }
            try {
                Uri u = data.getData();
                String kind = chatPickKind != null ? chatPickKind : "image";
                String ext = extForMime(null, u);
                String ref = ChatMedia.store(getApplicationContext(),
                        getContentResolver().openInputStream(u), null, ext);
                evalJs("window.onChatAttached && window.onChatAttached(" + jq(ref == null ? "" : ref) + ")");
            } catch (Exception e) {
                evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("attach failed") + ")");
            }
            chatPickKind = null;
            chatPickMime = null;
            return;
        }
    }

    /**
     * vc99 C1: requestPermissions callback (NOT onActivityResult — the record
     * permission grant lands here). Resume the pending voice-memo request on
     * grant; report back to the WebView on denial.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CHAT_RECORD) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (ChatMedia.recordPendingPermission) {
                ChatMedia.recordPendingPermission = false;
                chatRecordStart();
            }
        } else {
            ChatMedia.recordPendingPermission = false;
            evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("no mic permission") + ")");
        }
    }

    /** vc100: finalize the pending MediaStore row — visible=false + delete
     *  (the shot lives in the chat, not the album); clears chatCameraOut. */
    private void finishCameraRow() {
        Uri u = chatCameraOut;
        chatCameraOut = null;
        if (u == null) return;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(u, cv, null, null);
        } catch (Exception ignored) {}
        try { getContentResolver().delete(u, null, null); } catch (Exception ignored) {}
    }

    private String extForMime(String mime, Uri u) {
        String m = mime == null ? "" : mime;
        String guess = getContentResolver().getType(u);
        if (guess != null) m = guess;
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        if (m.contains("webm")) return ".webm";
        if (m.contains("mp4") || m.contains("mpeg4")) return ".mp4";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("flac")) return ".flac";
        if (m.contains("wav")) return ".wav";
        if (m.contains("aac")) return ".aac";
        if (m.contains("mpeg")) return ".mp3";
        if (m.startsWith("video/")) return ".mp4";
        if (m.startsWith("audio/")) return ".m4a";
        return ".jpg";
    }

    /** Bridge entry: RECORD_AUDIO gate, then capture start. */
    void chatRecordStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ChatMedia.recordPendingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CHAT_RECORD);
            return;
        }
        String r = ChatMedia.startRecording();
        evalJs("window.onChatRecStarted && window.onChatRecStarted(" + jq(r) + ")");
    }

    void chatRecordStop(boolean keep) {
        String r = ChatMedia.stopRecording(getApplicationContext(), keep);
        evalJs("window.onChatRecStopped && window.onChatRecStopped(" + jq(r) + ")");
    }

    /** vc99 C1: capability-gated attachment menu entries. */
    void showChatAttachMenu() {
        boolean mm = false;
        try {
            JSONObject s = new JSONObject(server.statusJson());
            mm = s.optBoolean("running") &&
                    ("htp".equals(s.optString("profile")) || "mtp".equals(s.optString("profile"))
                     || "ocl".equals(s.optString("profile")));
        } catch (Exception ignored) {}
        boolean slots = server != null && server.slotSaveActive();
        if (ChatMedia.isRecording()) {
            chatRecordStop(true); // 📎 acts as the stop button mid-recording
            return;
        }
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<String> actions = new ArrayList<>();
        // vc102: 拍照/选择视频 hidden per user call 1007 — camera still emits
        // HEIC (stb can't decode it) and video is a hard upstream dead end
        // (MTMD_VIDEO forced OFF under LLAMA_SUBPROCESS=OFF). Gallery works.
        // labels.add("\u62CD\u7167"); actions.add("camera");            // 拍照
        labels.add("\u76F8\u518C\u56FE\u7247"); actions.add("gallery");      // 相册图片
        if (mm) {
            // labels.add("\u9009\u62E9\u89C6\u9891"); actions.add("video"); // 选择视频
            labels.add("\u8BED\u97F3\u4FBF\u7B7E"); actions.add("voice");    // 语音便签
        }
        if (slots) {
            labels.add("\u4FDD\u5B58\u4F1A\u8BDD"); actions.add("saveSlot"); // 保存会话
            labels.add("\u6062\u590D\u4F1A\u8BDD\u2026"); actions.add("slot"); // 恢复会话…
        }
        labels.add("\u65B0\u4F1A\u8BDD"); actions.add("new");              // 新会话
        final String[] ls = labels.toArray(new String[0]);
        final String[] as = actions.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("\u9644\u4EF6");
        b.setItems(ls, new AttachMenuClick(this, as));
        b.setNegativeButton("\u53D6\u6D88", null);
        b.show();
    }

    /** d8-safe named menu click dispatcher (no anonymous classes, rule 1). */
    private static final class AttachMenuClick implements DialogInterface.OnClickListener {
        private final java.lang.ref.WeakReference<MainActivity> act;
        private final String[] actions;
        AttachMenuClick(MainActivity a, String[] as) { act = new java.lang.ref.WeakReference<>(a); actions = as; }
        @Override public void onClick(DialogInterface d, int which) {
            MainActivity a = act.get();
            if (a == null || which < 0 || which >= actions.length) return;
            String k = actions[which];
            if ("camera".equals(k) || "gallery".equals(k) || "video".equals(k)) a.chatPickMedia(k);
            else if ("voice".equals(k)) a.chatRecordStart();
            else if ("saveSlot".equals(k)) a.chatSlotSaveInternal();
            else if ("slot".equals(k)) a.chatPickSlot();
            else if ("new".equals(k)) a.chatNewChatInternal();
        }
    }

    /** vc99 C2: save the live slot KV to a timestamped .slot file (menu). */
    void chatSlotSaveInternal() {
        if (server == null || !server.slotSaveActive()) return;
        String fname = "chat-" + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".slot";
        pendingMenuSlotSave = fname;
        final String f = fname;
        // vc100: run off the UI thread — slotAction is a blocking HTTP POST to
        // 127.0.0.1 (pre-v100 it ran on the UI thread; NetworkOnMainThreadException
        // killed the click silently = "保存对话没反应").
        new Thread(() -> {
            String r = server.slotAction(getApplicationContext(), "save", f);
            // vc102: the .slot file holds only server KV (tokens). Chat text
            // lives in a sidecar next to it so 恢复会话 can replay bubbles.
            boolean histOk = slotHistWrite(getApplicationContext(), f, chatHistMirror);
            try {
                JSONObject rj = new JSONObject(r == null || r.isEmpty() ? "{}" : r);
                rj.put("histSaved", histOk);
                r = rj.toString();
            } catch (Exception ignored) {}
            if (f.equals(pendingMenuSlotSave)) pendingMenuSlotSave = null;
            evalJs("window.onSlotSaved && window.onSlotSaved(" + jq(r) + ")");
        }, "chat-slot-save").start();
    }

    /** vc102: sidecar filename for a slot's chat text (excluded from the
     *  slot picker, which only lists names ending in ".slot"). */
    private static String slotHistFile(String slotName) {
        return slotName + ".hist.json";
    }

    /** vc102: write chatMsgs (parsed from the JS bridge) next to the slot file.
     *  Returns false silently when the payload is unusable — the slot KV save
     *  itself stays authoritative for the restore. */
    private static boolean slotHistWrite(Context c, String slotName, String histJson) {
        try {
            if (slotName == null || slotName.isEmpty() || histJson == null) return false;
            JSONArray h = new JSONArray(histJson);
            if (h.length() == 0) return false;
            JSONObject o = new JSONObject();
            o.put("vc", 102);
            o.put("hist", h);
            File f = new File(LlamaServerManager.slotsDir(c), slotHistFile(slotName));
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
            fo.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** vc102: read the sidecar back for a slot restore (null when absent). */
    private static String slotHistRead(Context c, String slotName) {
        try {
            File f = new File(LlamaServerManager.slotsDir(c), slotHistFile(slotName));
            if (!f.isFile()) return null;
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
            fi.close();
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            return o.optJSONArray("hist") == null ? null : o.getJSONArray("hist").toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** vc102: restore path — replay chat text into the WebView bubbles. The
     *  server KV restore is issued by JS before the next send. */
    private void slotHistRestore(String slotName) {
        String h = slotHistRead(getApplicationContext(), slotName);
        evalJs("window.onSlotHistLoaded && window.onSlotHistLoaded(" + jq(h) + ")");
    }

    /** Shared by the menu and the bridge: reset chat history + staged media. */
    void chatNewChatInternal() {
        ChatMedia.wipeAll(getApplicationContext());
        prefs.edit().putBoolean("chatNewPending", true).apply();
        evalJs("window.chatApplyNewPending && window.chatApplyNewPending()");
    }

    /* ---------- vc99 C2: slot attach picker (Chat page) ---------- */

    /** Modal list of saved slot files; pick one to attach to the next send. */
    void chatPickSlot() {
        File dir = LlamaServerManager.slotsDir(getApplicationContext());
        File[] kids = dir.listFiles();
        java.util.List<String> names = new ArrayList<>();
        if (kids != null) for (File k : kids) if (k.getName().endsWith(".slot")) names.add(k.getName());
        Collections.sort(names, Collections.reverseOrder());
        if (names.isEmpty()) {
            evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("no saved slots") + ")");
            return;
        }
        final String[] arr = names.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("\u68C3\u4F4D\u6587\u4EF6\u5217\u8868");
        b.setItems(arr, new SlotPickClick(this, arr));
        b.setPositiveButton("\u5220\u9664\u4F1A\u8BDD\u2026", new SlotDeleteOpen(this)); // vc103: 删除会话…
        b.setNegativeButton("\u53D6\u6D88", null);
        b.show();
    }

    /** vc103: 删除会话 — pick a saved conversation then confirm; removes the
     *  .slot KV file and its .hist.json text sidecar. Pure file housekeeping,
     *  no server round-trip (works regardless of server state). */
    void chatDeleteSlot() {
        File dir = LlamaServerManager.slotsDir(getApplicationContext());
        File[] kids = dir.listFiles();
        java.util.List<String> names = new ArrayList<>();
        if (kids != null) for (File k : kids) if (k.getName().endsWith(".slot")) names.add(k.getName());
        Collections.sort(names, Collections.reverseOrder());
        if (names.isEmpty()) {
            evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("no saved slots") + ")");
            return;
        }
        final String[] arr = names.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("\u5220\u9664\u4F1A\u8BDD\u2026"); // 删除会话…
        b.setItems(arr, new SlotDeleteClick(this, arr));
        b.setNegativeButton("\u53D6\u6D88", null);
        b.show();
    }

    /** vc103: two-step confirm before an unrecoverable deletion. */
    void confirmSlotDelete(String name) {
        new AlertDialog.Builder(this)
                .setTitle("\u5220\u9664\u4F1A\u8BDD") // 删除会话
                .setMessage("\u5220\u9664 " + name + " \uFF1F\u4E0D\u53EF\u6062\u590D") // 删除 xxx？不可恢复
                .setPositiveButton("\u5220\u9664", new SlotDeleteConfirm(this, name))
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
    }

    /** vc103: remove a saved conversation — the server KV file + text sidecar.
     *  Refuses anything that is not a *.slot entry (path-safety). */
    private static boolean deleteSlotFiles(Context c, String name) {
        if (name == null || !name.endsWith(".slot")) return false;
        boolean ok = true;
        File f = new File(LlamaServerManager.slotsDir(c), name);
        if (f.exists()) ok = f.delete();
        File h = new File(LlamaServerManager.slotsDir(c), slotHistFile(name));
        if (h.exists()) ok = h.delete() && ok;
        return ok;
    }

    /** d8-safe named click handler (no anonymous classes, rule 1). */
    private static final class SlotDeleteOpen implements DialogInterface.OnClickListener {
        private final java.lang.ref.WeakReference<MainActivity> act;
        SlotDeleteOpen(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }
        @Override public void onClick(DialogInterface d, int which) {
            MainActivity a = act.get();
            if (a != null) a.chatDeleteSlot();
        }
    }

    /** d8-safe named click handler (no anonymous classes, rule 1). */
    private static final class SlotDeleteClick implements DialogInterface.OnClickListener {
        private final java.lang.ref.WeakReference<MainActivity> act;
        private final String[] names;
        SlotDeleteClick(MainActivity a, String[] n) { act = new java.lang.ref.WeakReference<>(a); names = n; }
        @Override public void onClick(DialogInterface d, int which) {
            MainActivity a = act.get();
            if (a == null || which < 0 || which >= names.length) return;
            a.confirmSlotDelete(names[which]);
        }
    }

    /** d8-safe named click handler (no anonymous classes, rule 1). */
    private static final class SlotDeleteConfirm implements DialogInterface.OnClickListener {
        private final java.lang.ref.WeakReference<MainActivity> act;
        private final String name;
        SlotDeleteConfirm(MainActivity a, String n) { act = new java.lang.ref.WeakReference<>(a); name = n; }
        @Override public void onClick(DialogInterface d, int which) {
            MainActivity a = act.get();
            if (a == null) return;
            boolean ok = deleteSlotFiles(a.getApplicationContext(), name);
            if (name.equals(a.pendingSlotAttach)) a.pendingSlotAttach = null;
            a.evalJs("window.onSlotDeleted && window.onSlotDeleted(" + jq(ok ? name : "") + ")");
        }
    }

    /** d8-safe named click handler (no anonymous classes, hard rule 1). */
    private static final class SlotPickClick implements DialogInterface.OnClickListener {
        private final java.lang.ref.WeakReference<MainActivity> act;
        private final String[] names;
        SlotPickClick(MainActivity a, String[] n) { act = new java.lang.ref.WeakReference<>(a); names = n; }
        @Override public void onClick(DialogInterface d, int which) {
            MainActivity a = act.get();
            if (a == null || which < 0 || which >= names.length) return;
            a.pendingSlotAttach = names[which];
            a.slotHistRestore(names[which]); // vc102: replay chat text bubbles
            a.evalJs("window.onSlotPicked && window.onSlotPicked(" + jq(names[which]) + ")");
        }
    }

    /** v1.4.0: bmoe engine chat UI bridge — registers as the static UiHook delegate
     *  (BmoeAdapter) so chunks survive Activity recreation; forwards reasoning
     *  deltas into the same onChatChunk stream (bubble think-span styling is a
     *  v1.4.x refinement; content arrives clean because the engine parses it). */
    static final class BmoeChatUiBridge implements BmoeAdapter.UiHook.Delegate {
        private final java.lang.ref.WeakReference<MainActivity> act;
        BmoeChatUiBridge(MainActivity a) { act = new java.lang.ref.WeakReference<>(a); }
        @Override public void onChunk(String text) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatChunk && window.onChatChunk(" + jq(text) + ")");
        }
        /** vc158: bmoe 的 delta_reasoning 走独立回调。 */
        @Override public void onReasoning(String text) {
            MainActivity a = act.get();
            if (a != null) a.evalJs("window.onChatReasoning && window.onChatReasoning(" + jq(text) + ")");
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

        /* ---------- vc105: MoE detection (service-page engine form) ---------- */

        /** GGUF header probe for the service page: {"ok":true,"moe":bool} when
         *  the header parses (expert_count>0 means MoE), {"ok":false} otherwise.
         *  The parse walks only the metadata KV section (a few KB) so it is
         *  safe on the bridge thread even for 20 GB files; the JS caller
         *  caches per picked path (vc105 moeProbe). */
        @JavascriptInterface
        public String moeProbe(final String modelPath) {
            GgufProbe.Result r = GgufProbe.probe(new File(modelPath == null ? "" : modelPath));
            return "{\"ok\":" + r.parsed + ",\"moe\":" + r.moe + "}";
        }

        /** Last selected model as JSON ("" if none) — restores the card after app restart.
         *  v1.4.0: "__bmoe__<name>" entries resolve inside the app models dir. */
        @JavascriptInterface
        public String lastModelJson() {
            MainActivity a = act.get();
            if (a == null) return "";
            String p = a.prefs.getString("model", "");
            if (p.isEmpty()) return "";
            if (p.startsWith("__bmoe__")) {
                File f = new File(BmoeModelManager.modelsDir(a.getApplicationContext()),
                        p.substring("__bmoe__".length()));
                return f.canRead() ? fileJson(f) : "";
            }
            File f = new File(p);
            return f.canRead() ? fileJson(f) : "";
        }

        @JavascriptInterface
        public String startServer(final String modelPath, final String profileId, final int port,
                                  final int ctxSize, final String reasoning,
                                  final int threads, final boolean noKvOffload,
                                  final int ubatchSize, final int sleepIdleSeconds,
                                  final boolean slotsOn,
                                  final boolean publicOn, final boolean keyOn,
                                  final String kvSel, final String powerMode,
                                  final String extraArgs) {
            MainActivity a = act.get();
            if (a == null || a.server == null) return "{\"ok\":false}";
            // vc104: the local key is auto-generated and Keystore-sealed in a
            // DEDICATED slot (KeyStoreBox.ensureLocal) — never the CLI remote
            // endpoint key. The WebView sends only the switch state.
            final String apiKey = keyOn ? KeyStoreBox.ensureLocal(a.getApplicationContext()) : null;
            // v1.3.1: persist launch args so a swipe-away kill ("remove task")
            // can relaunch the server even if the orphan child also died.
            // (vc99: slotSavePath intentionally NOT persisted — the reborn
            // relaunch goes through LlamaServerManager.start, which re-creates
            // the dir and re-adds the flag itself. One source of truth.)
            ServerService.saveRebornArgs(a.getApplicationContext(), modelPath, profileId,
                    port, ctxSize, reasoning, threads, noKvOffload, ubatchSize, sleepIdleSeconds,
                    publicOn, keyOn, kvSel == null ? "" : kvSel,
                    powerMode == null ? "" : powerMode, extraArgs);
            new Thread(() -> {
                a.server.start(modelPath, profileId, port, ctxSize, reasoning,
                        threads, noKvOffload, ubatchSize, sleepIdleSeconds, kvSel, powerMode,
                        slotsOn,
                        publicOn, apiKey, extraArgs);
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

        /* ---------- vc104: local server API key (auto-generated) ---------- */

        /**
         * Ensure a local-server key exists and return it in PLAINTEXT for the
         * UI to show (user copies it into other clients). The key itself is
         * AES-GCM sealed in a Keystore slot SEPARATE from the CLI remote key.
         * Idempotent: same value across calls and across server restarts.
         */
        @JavascriptInterface
        public String keyEnsure() {
            MainActivity a = act.get();
            if (a == null) return "";
            return KeyStoreBox.ensureLocal(a.getApplicationContext());
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
                    // vc109: MoE 档大 prompt prefill 期间无任何字节，300s 必超时 → 960s（stall 墙 900s 之上）
                    conn.setReadTimeout(960000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    // vc105: bmoe 服务档启用本地 Key 时，CLI 测试页同源请求须带
                    // Bearer（与 vc104 chat SSE/健康站点点语义一致；key==空=不鉴权）
                    if (a.server != null && a.server.apiKeyActive()
                            && url.contains("127.0.0.1")) {
                        conn.setRequestProperty("Authorization",
                                "Bearer " + a.server.apiKeyPlain());
                    }
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

        /** vc131 B: real update check (Gitee -> GitHub fallback). Spawned on a
         *  worker thread (the shared JavaBridge thread must never block —
         *  wsFetch precedent); result via window.onCheckUpdate(json).
         *  Version comes from JS APP_VER (single source of truth). */
        @JavascriptInterface
        public void updateCheck(final String appVer) {
            final MainActivity a = act.get();
            if (a == null) return;
            new Thread(new Vc131UpdateWorker(appVer, a), "vc131-update-check").start();
        }

        /** vc131 B: open the releases page in the system browser.
         *  Must stay resident on the UI thread; see Vc131.openUrl(). */
        @JavascriptInterface
        public void openUrl(final String url) {
            Vc131.openUrl(url);
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
            final boolean remote = remoteUrl != null && !remoteUrl.isEmpty();
            String send = bodyJson;
            // vc99 C1: resolve media_ref pointers into real base64 content
            // objects right here on the JI thread — ServerChatStreamer just
            // POSTs whatever body it gets. Stale ref / remote+video guard
            // (materializeRefs returns null) → bubble shows the pointer text,
            // request is NOT sent, no server 400 round-trip.
            if (ChatMedia.hasRefs(bodyJson)) {
                String resolved = ChatMedia.materializeRefs(
                        a.getApplicationContext(), bodyJson, remote);
                if (resolved == null) {
                    a.evalJs("window.onChatDone && window.onChatDone(\"400\","
                            + jq("{\"error\":\"attachment unavailable — re-attach the media\"}") + ")");
                    return;
                }
                send = resolved;
            }
            if (remote) {
                // Chat remote mode: stream from any OpenAI-compatible endpoint
                // (same key as CLI remote mode; TLS always verified, R11).
                a.chat.start(remoteUrl, send, KeyStoreBox.load(a.getApplicationContext()));
                return;
            }
            if (a.server == null) return;
            int port = a.server.currentPort();
            // vc104: local server may require Bearer auth (--api-key).
            a.chat.start("http://127.0.0.1:" + port + "/v1/chat/completions", send,
                    a.server.apiKeyPlain());
        }

        /* ---------- vc99 C1: chat media bridge ---------- */

        /** Fire a picker: kind = image | video | audio (audio = voice memo). */
        @JavascriptInterface
        public void chatAttach(final String kind) {
            MainActivity a = act.get();
            if (a == null) return;
            if (ChatMedia.isRecording()) {
                a.evalJs("window.onChatAttachError && window.onChatAttachError(" + jq("recording in progress") + ")");
                return;
            }
            a.runOnUiThread(() -> {
                if ("audio".equals(kind)) a.chatRecordStart(); else a.chatPickMedia(kind);
            });
        }

        /** Remove one staged attachment (also deletes the backing file). */
        @JavascriptInterface
        public void chatAttachCancel(final String ref) {
            MainActivity a = act.get();
            if (a == null) return;
            ChatMedia.deleteRef(a.getApplicationContext(), ref);
        }

        /** Voice memo stop: keep=true → wav staged + ref back; keep=false → discard. */
        @JavascriptInterface
        public void chatRecStop(final boolean keep) {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(() -> a.chatRecordStop(keep));
        }

        /** Whether the current profile/runtime can take multimodal input.
         *  Gate (plan ②): running server + NPU/GPU profile = yes; CPU/bmoe =
         *  no. statusJson is local state — no HTTP probe on the JI thread. */
        @JavascriptInterface
        public boolean chatMultimodalSupported() {
            MainActivity a = act.get();
            if (a == null || a.server == null) return false;
            try {
                JSONObject s = new JSONObject(a.server.statusJson());
                if (!s.optBoolean("running")) return false;
                String p = s.optString("profile");
                return "htp".equals(p) || "mtp".equals(p) || "ocl".equals(p);
            } catch (Throwable t) { return false; }
        }

        /* ---------- vc99 C2: slot save/restore bridge ---------- */

        /** Whether the running server exposes the slots endpoint (--slot-save-path). */
        @JavascriptInterface
        public boolean chatSlotsSupported() {
            MainActivity a = act.get();
            return a != null && a.server != null && a.server.slotSaveActive();
        }

        /** POST /slots/{id}?action=save|restore|erase for the Chat page. */
        @JavascriptInterface
        public String chatSlotAction(final String action, final String filename) {
            MainActivity a = act.get();
            if (a == null) return "{\"error\":\"no activity\"}";
            if (a.server == null) return "{\"error\":\"server manager gone\"}";
            return a.server.slotAction(a.getApplicationContext(), action, filename);
        }

        /** Modal file list of already-saved slots; picking one stages it. */
        @JavascriptInterface
        public void chatSlotPick() {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(() -> a.chatPickSlot());
        }

        /** JS drop of the staged slot filename (chip removed). */
        @JavascriptInterface
        public void chatSlotDetach() {
            MainActivity a = act.get();
            if (a == null) return;
            a.pendingSlotAttach = null;
        }

        /** vc102: JS mirrors chatMsgs here after each assistant turn and at
         *  boot restore, so a slot save can attach the transcript sidecar. */
        @JavascriptInterface
        public void chatHistPush(String histJson) {
            MainActivity a = act.get();
            if (a == null) return;
            a.chatHistMirror = histJson;
        }

        /** New-chat: wipe staged media + history flag; applied on next launch
         *  if the process is dead, via window.chatApplyNewPending. */
        @JavascriptInterface
        public void chatNewChat() {
            MainActivity a = act.get();
            if (a == null) return;
            a.chatNewChatInternal();
        }

        /** vc99 C1: describe one staged ref for the JS chip (kind/mime/size). */
        @JavascriptInterface
        public String chatMediaDescribe(final String ref) {
            MainActivity a = act.get();
            if (a == null) return "{}";
            return ChatMedia.describe(a.getApplicationContext(), ref);
        }

        /** vc99 C1: the chat-page attachment menu (native dialog, capability-
         *  gated entries). Runs on the UI thread from the JI thread. */
        @JavascriptInterface
        public void chatAttachMenu() {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(() -> a.showChatAttachMenu());
        }

        /* ---------- v1.4.0: bmoe engine bridge ---------- */

        /** Chat page: one bmoe generate turn (bmoe profile replaces the HTTP
         *  SSE path). Sends only the new user message; the engine keeps the
         *  conversation and re-renders its own chat template (clear_kv=false
         *  after the first turn, true on a fresh conversation). */
        @JavascriptInterface
        public void bmoeChatStart(final String bodyJson) {
            MainActivity a = act.get();
            if (a == null || a.server == null) return;
            final boolean fresh = (window_bMoeTurn == null) || window_bMoeTurn;
            new Thread(() -> {
                try {
                    BmoeAdapter ad = a.server.bmoeAdapter();
                    if (ad == null) {
                        BmoeAdapter.UiHook.done("ERR", "{\"error\":\"bmoe 未运行\"}");
                        return;
                    }
                    String prompt = BmoeAdapter.promptFromMessages(bodyJson);
                    if (prompt == null) {
                        BmoeAdapter.UiHook.done("ERR", "{\"error\":\"empty prompt\"}");
                        return;
                    }
                    int nPredict = BmoeAdapter.maxTokensFrom(bodyJson);
                    boolean ok = ad.generate(prompt, nPredict, false, fresh);
                    if (ok) {
                        ad.markTurnSent();
                        window_bMoeTurn = Boolean.FALSE;   // continuation turns
                    } else {
                        BmoeAdapter.UiHook.done("ERR", "{\"error\":\"引擎忙或未就绪\"}");
                    }
                } catch (Throwable t) {
                    BmoeAdapter.UiHook.done("ERR", "{\"error\":\"" + t.getClass().getSimpleName() + "\"}");
                }
            }, "bmoe-chat").start();
        }
        /** mirrors window._bmoeTurn from JS (set true on load/new-chat). */
        private volatile Boolean window_bMoeTurn = Boolean.TRUE;

        @JavascriptInterface
        public String bmoeList() { MainActivity a2 = act.get(); return (a2 == null) ? "{}" : BmoeModelManager.listJson(a2.getApplicationContext()); }

        @JavascriptInterface
        public String bmoeDelete(final String fileName) {
            MainActivity a2 = act.get();
            return (a2 == null) ? "{\"ok\":false}" : BmoeModelManager.delete(a2.getApplicationContext(), fileName == null ? "" : fileName);
        }

        /** Returns error JSON, or null when the download started (async). */
        @JavascriptInterface
        public String bmoeDownload(final String url) {
            MainActivity a2 = act.get();
            if (a2 == null) return "{\"error\":\"no activity\"}";
            BmoeModelManager mm = BmoeModelManager.get(a2.getApplicationContext());
            String err = mm.startDownload(url);
            return err == null ? null : "{\"error\":\"" + err.replace("\"", "'") + "\"}";
        }

        /** Import-pick: native dir browser → user picks a .gguf → startImport. */
        @JavascriptInterface
        public void bmoeImportPick() {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(() -> a.bmoeImportBrowser());
        }

        /** vc108 rev4: import the currently browsed model directly (no second
         *  dir picker). Real paths start immediately; "__bmoe__" refs are the
         *  already-imported /data copies — nothing to do. Errors surface
         *  through the same onBmoeDlStatus channel as the picker flow. */
        @JavascriptInterface
        public void bmoeImportPath(final String modelPath) {
            MainActivity a = act.get();
            if (a == null || modelPath == null || modelPath.isEmpty()) return;
            if (modelPath.startsWith("__bmoe__")) return; // already in /data modelsDir
            BmoeModelManager mm = BmoeModelManager.get(a.getApplicationContext());
            String err = mm.startImport(modelPath);
            if (err != null) {
                final String msg = err;
                a.runOnUiThread(() -> a.evalJs(
                        "window.onBmoeDlStatus && window.onBmoeDlStatus("
                        + jq("{\"phase\":\"error\",\"error\":\"" + msg.replace("\"", "'") + "\"}") + ")"));
            }
        }

        /** Download status snapshot (JS polls every 1.2s while the card is open). */
        @JavascriptInterface
        public String bmoeDlStatus() {
            MainActivity a2 = act.get();
            return (a2 == null) ? "{\"phase\":\"idle\"}" : BmoeModelManager.get(a2.getApplicationContext()).statusJson();
        }

        /** Chat page: abort current streaming request. */
        @JavascriptInterface
        public void chatAbort() {
            MainActivity a = act.get();
            if (a != null) a.chat.abort();
        }

        /** vc91: persisted --sleep-idle-seconds value (0 = disabled). */
        @JavascriptInterface
        public int sleepIdleGet() {
            MainActivity a = act.get();
            if (a == null) return 0;
            return a.prefs.getInt("sleepIdleSeconds", 0);
        }

        /** vc91: persist the sleep-idle choice; read at startServer time. */
        @JavascriptInterface
        public void sleepIdleSet(final int seconds) {
            MainActivity a = act.get();
            if (a == null) return;
            a.prefs.edit().putInt("sleepIdleSeconds", seconds).apply();
        }

        /**
         * vc91: real-time "end thinking now" — posts the llama.cpp control
         * action for an in-flight streaming completion. Contract verified in
         * mtp16 source (server-context.cpp post_control):
         * {"id": <completion_id>, "action": "reasoning_end"}. Returns the
         * raw response body ("" on transport failure) — best-effort by design.
         * vc148: the old version did t.join(3500) on the bridge thread —
         * up to 3.5s of WebView-wide freeze on every 结束思考 tap. Now fires
         * the POST on a worker thread and returns "" immediately; the result
         * (informational only) arrives via window.onReasoningEnd(json).
         */
        @JavascriptInterface
        public String reasoningEnd(final String completionId) {
            MainActivity a = act.get();
            if (a == null || completionId == null || completionId.isEmpty()) return "";
            final String url = "http://127.0.0.1:" + a.server.currentPort()
                    + "/v1/chat/completions/control";
            // android org.json has no JSONTokener.quote in the compile stub —
            // escape the only two chars that can break a JSON string literal.
            final String safeId = completionId.replace("\\", "\\\\").replace("\"", "\\\"");
            final String body = "{\"id\":\"" + safeId + "\",\"action\":\"reasoning_end\"}";
            final StringBuilder[] out = new StringBuilder[]{ new StringBuilder() };
            Thread t = new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(3000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    // vc104: local server may require Bearer auth (--api-key).
                    if (a.server.apiKeyActive())
                        c.setRequestProperty("Authorization", "Bearer " + a.server.apiKeyPlain());
                    OutputStream os = c.getOutputStream();
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    java.io.InputStream is = (c.getResponseCode() == 200)
                            ? c.getInputStream() : c.getErrorStream();
                    if (is != null) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8));
                        char[] buf = new char[1024];
                        int n;
                        while ((n = r.read(buf)) > 0) out[0].append(buf, 0, n);
                        r.close();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                    // vc148: push the best-effort result off-thread instead of join().
                    evalJsStatic("window.onReasoningEnd && window.onReasoningEnd("
                            + jq(out[0].toString()) + ")");
                }
            }, "reasoning-end");
            t.start();
            return "";
        }

        /* ---------- vc122: web-search service (search package, BRIEF §2.2) ----------
         * The search module is isolated from the ENGINE side (llama/bmoe/sampler);
         * MainActivity is the designated shell-side bridge. wsFetch returns the
         * reqId immediately (bridge thread does no network — vc100 worker pattern);
         * the result arrives via window.onWsResult(payloadJson). */

        /** Async search over the SearXNG channel. Empty baseUrl falls back to
         *  the search-service persisted config. */
        @JavascriptInterface
        public String wsFetch(final String baseUrl, final String query,
                              final int count, final String reqId) {
            MainActivity a = act.get();
            final String id = (reqId == null) ? "" : reqId;
            if (a == null) return id;
            SearchConfig.init(a.getApplicationContext());
            new Thread(new WsFetchWorker(baseUrl, query, count, id), "ws-fetch").start();
            return id;
        }

        /** Protocol version of the search service bridge. */
        @JavascriptInterface
        public String wsVersion() {
            return SearchServer.PROTO_VERSION;
        }

        /** Start/stop the independent LAN search FGS; returns status JSON. */
        @JavascriptInterface
        public String wsServerToggle(final boolean on) {
            MainActivity a = act.get();
            if (a == null) return SearchController.statusJson();
            SearchConfig.init(a.getApplicationContext());
            if (on) {
                SearchController.onToggleRequested();
                SearchForegroundService.start(a);
            } else {
                SearchForegroundService.stop(a);
            }
            return SearchController.statusJson();
        }

        /** Pollable state JSON: {"state":..,"port":<configured>,"err":..}. */
        @JavascriptInterface
        public String wsServerStatus() {
            return SearchController.statusJson();
        }

        /** Persist search config from the UI (vc122): baseUrl and/or gate key.
         *  Null argument = field untouched. Key never echoes back to JS. */
        @JavascriptInterface
        public void wsServerConfig(final String baseUrl, final String key) {
            MainActivity a = act.get();
            if (a == null) return;
            SearchConfig.init(a.getApplicationContext());
            if (baseUrl != null) SearchConfig.setBaseUrl(baseUrl);
            if (key != null) SearchConfig.setGateKey(key);
        }

        // ===== vc141: RAG 知识库 :8082（独立服务于主服务/搜索服务）=====

        /** 起停 :8082 独立服务；返回状态 JSON。 */
        @JavascriptInterface
        public String ragToggle(final boolean on) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            RagConfig.setEnabled(on);
            if (on) RagForegroundService.start(a);
            else RagForegroundService.stop(a);
            return RagService.get(a.getApplicationContext()).status();
        }

        /** 状态 JSON（库/块数、嵌入进程、索引进度）。 */
        @JavascriptInterface
        public String ragStatus() {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            return RagService.get(a.getApplicationContext()).status();
        }

        /** 索引：后台线程跑，结果经 window.onRagIndex(json) 回 JS。 */
        @JavascriptInterface
        public String ragIndex(final boolean rebuild) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            final String dir = RagConfig.dirUri();
            if (dir.length() == 0) {
                return "{\"ok\":false,\"error\":\"先选择文档目录\"}";
            }
            new Thread(new RagIndexTask(a, dir, rebuild), "rag-index").start();
            return "{\"ok\":true,\"started\":true}";
        }

        /** 检索：后台线程，结果经 window.onRagSearch(json) 回 JS。 */
        @JavascriptInterface
        public String ragSearch(final String q, final int k) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            new Thread(new RagSearchTask(a, q, k), "rag-search").start();
            return "{\"ok\":true,\"started\":true}";
        }

        @JavascriptInterface
        public String ragDrop(final int libId) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            return RagService.get(a.getApplicationContext()).drop(libId);
        }

        // ===== vc147: 聊天记忆（chat.db 持久化 + 记忆召回）=====

        /** 落一条聊天消息（role=user/assistant）。返回 id；-1=空/重复/失败。 */
        @JavascriptInterface
        public long chatSave(final String role, final String content) {
            MainActivity a = act.get();
            if (a == null) return -1L;
            try {
                return ChatStore.get(a.getApplicationContext())
                        .append(ChatStore.MAIN_CID, role, content, System.currentTimeMillis());
            } catch (Throwable t) {
                return -1L;
            }
        }

        /** 页面回填历史：[{role,content,ts}] */
        @JavascriptInterface
        public String chatHistory(final int limit) {
            MainActivity a = act.get();
            if (a == null) return "[]";
            try {
                return ChatStore.get(a.getApplicationContext())
                        .historyJson(ChatStore.MAIN_CID, limit);
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public int chatClear() {
            MainActivity a = act.get();
            if (a == null) return 0;
            try {
                return ChatStore.get(a.getApplicationContext()).clearAll();
            } catch (Throwable t) {
                return 0;
            }
        }

        /** 记忆面板状态：消息数 + 召回开关。 */
        @JavascriptInterface
        public String chatMemStatus() {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            try {
                RagConfig.init(a.getApplicationContext());
                ChatStore cs = ChatStore.get(a.getApplicationContext());
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ok", true);
                o.put("msgs", cs.count());
                o.put("recall_on", RagConfig.recallOn());
                return o.toString();
            } catch (Throwable t) {
                return "{\"ok\":false}";
            }
        }

        /** 索引聊天记忆（后台线程 → window.onRagIndex）。 */
        @JavascriptInterface
        public String ragIndexChat() {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            new Thread(new RagIndexChatTask(a), "rag-index-chat").start();
            return "{\"ok\":true,\"started\":true}";
        }

        /** 记忆召回（后台线程 → window.onRagRecall）。 */
        @JavascriptInterface
        public String ragRecall(final String q, final int k) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            new Thread(new RagRecallTask(a, q, k), "rag-recall").start();
            return "{\"ok\":true,\"started\":true}";
        }

        /** 同步召回：嵌入已就绪才查，否则返空并后台预热（不阻塞发送）。 */
        @JavascriptInterface
        public String ragRecallSync(final String q, final int k) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            RagService svc = RagService.get(a.getApplicationContext());
            if (!svc.embedReady()) {
                new Thread(new RagRecallTask(a, q, k), "rag-recall-warm").start();
                return "{\"ok\":false,\"note\":\"warm\"}";
            }
            return svc.recall(q, k);
        }

        @JavascriptInterface
        public boolean recallOnGet() {
            MainActivity a = act.get();
            if (a != null) RagConfig.init(a.getApplicationContext());
            return RagConfig.recallOn();
        }

        @JavascriptInterface
        public void recallOnSet(final boolean on) {
            MainActivity a = act.get();
            if (a != null) RagConfig.init(a.getApplicationContext());
            RagConfig.setRecallOn(on);
        }

        /** vc152: chat 页 📚 本地知识库注入开关（默认关）。 */
        @JavascriptInterface
        public boolean kbOnGet() {
            MainActivity a = act.get();
            if (a != null) RagConfig.init(a.getApplicationContext());
            return RagConfig.kbOn();
        }

        @JavascriptInterface
        public void kbOnSet(final boolean on) {
            MainActivity a = act.get();
            if (a != null) RagConfig.init(a.getApplicationContext());
            RagConfig.setKbOn(on);
        }

        /**
         * vc152: 同步知识库检索（只查文档库，排除聊天记忆库）。
         * 嵌入未就绪返空 + 后台预热，绝不阻塞发送按钮。
         */
        @JavascriptInterface
        public String ragKbSearchSync(final String q, final int k) {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            RagConfig.init(a.getApplicationContext());
            RagService svc = RagService.get(a.getApplicationContext());
            if (!svc.embedReady()) {
                new Thread(new RagRecallTask(a, q, k), "rag-kb-warm").start();
                return "{\"ok\":false,\"note\":\"warm\"}";
            }
            return svc.searchDocs(q, k, -1f);
        }

        /** SAF 选目录（tree uri）；结果在 onActivityResult 落 prefs。 */
        @JavascriptInterface
        public void ragPickDir() {
            MainActivity a = act.get();
            if (a == null) return;
            a.runOnUiThread(new RagPickDirTask(a));
        }

        @JavascriptInterface
        public String ragDirGet() {
            String u = RagConfig.dirUri();
            return u.length() == 0 ? "" : u;
        }

        @JavascriptInterface
        public String ragPortGet() {
            return String.valueOf(RagConfig.port());
        }

        /** 合法=非特权且不等于 8080/8081；非法值拒收并回 "invalid"。 */
        @JavascriptInterface
        public String ragPortSet(final String v) {
            try {
                int p = Integer.parseInt(String.valueOf(v).trim());
                if (!RagConfig.validatePort(p)) return "invalid";
                RagConfig.setPort(p);
                return "ok";
            } catch (Throwable t) {
                return "invalid";
            }
        }

        /** vc157: 取完整 key（仅本机 UI 复制用；服务端只吐指纹）。 */
        @JavascriptInterface
        public String ragKeyGet() {
            MainActivity a = act.get();
            if (a == null) return "";
            RagConfig.init(a.getApplicationContext());
            return RagConfig.ensureGateKey();
        }

        /** vc157: 重置 key → {"ok":true,"fp":"abcd"}；调用方负责重启服务生效。 */
        @JavascriptInterface
        public String ragKeyReset() {
            MainActivity a = act.get();
            if (a == null) return "{\"ok\":false}";
            try {
                RagConfig.init(a.getApplicationContext());
                RagConfig.resetGateKey();
                return "{\"ok\":true,\"fp\":\"" + RagConfig.gateKeyFingerprint() + "\"}";
            } catch (Throwable t) {
                return "{\"ok\":false,\"error\":\"" + RagService.escJs(String.valueOf(t)) + "\"}";
            }
        }

        /** vc133: current search port (junk prefs already default to 8081). */
        @JavascriptInterface
        public String wsPortGet() {
            return String.valueOf(SearchConfig.searchPort());
        }

        /** vc133: persist the search port; returns "ok" / "invalid" /
         *  "conflict" (8080 = model port). NOT applied to a running server —
         *  the UI asks the user to restart the search service. */
        @JavascriptInterface
        public String wsPortSet(final String v) {
            MainActivity a = act.get();
            if (a == null) return "invalid";
            SearchConfig.init(a.getApplicationContext());
            int p;
            try {
                p = Integer.parseInt(String.valueOf(v).trim());
            } catch (Throwable t) {
                return "invalid";
            }
            if (p == 8080) return "conflict";
            if (!SearchConfig.validatePortRange(p)) return "invalid";
            SearchConfig.setSearchPort(p);
            return "ok";
        }

        /* ---------- vc123: built-in engine toggles + daily counter ---------- */

        /** All engines with their effective enabled flags (JSON array:
         *  [{id,label,builtin,on}]; builtin rows are not rendered as toggles
         *  by the UI). Empty array when config is unavailable. */
        @JavascriptInterface
        public String wsEnginesGet() {
            MainActivity a = act.get();
            if (a == null) return "[]";
            SearchConfig.init(a.getApplicationContext());
            return enginesJson();
        }

        /** Persist one engine toggle (id + on). Unknown id = no-op. */
        @JavascriptInterface
        public void wsEngineSet(final String id, final boolean on) {
            MainActivity a = act.get();
            if (a == null || id == null) return;
            SearchConfig.init(a.getApplicationContext());
            String want = id.trim();
            for (int i = 0; i < SEED_IDS.length; i++) {
                if (SEED_IDS[i].equals(want)) { // id whitelist, engines.json order
                    SearchConfig.setEngineEnabled(want, on);
                    return;
                }
            }
        }

        /** Today's search request count (vc122 挂账). */
        @JavascriptInterface
        public String wsRequestsToday() {
            MainActivity a = act.get();
            if (a == null) return "0";
            SearchConfig.init(a.getApplicationContext());
            return String.valueOf(SearchConfig.requestsToday());
        }

        /* ---------- vc124: speed test + region default set ---------- */

        /** vc148: speed probe moved OFF the bridge thread. The old version ran
         *  SpeedTester.measure() inline (serial HTTP across all enabled
         *  engines, seconds each) — it froze the shared JavaBridge thread, so
         *  every other bridge() call (getStatus/bmoeDlStatus polls included)
         *  hung and the whole WebView felt frozen (user report on v1.5.0).
         *  Now: returns a reqId immediately (wsFetch precedent), the probe
         *  runs on a worker thread, and the result arrives via
         *  window.onWsSpeedResult(jsonArray). */
        @JavascriptInterface
        public String wsSpeedTest() {
            MainActivity a = act.get();
            final String rid = "spd" + System.currentTimeMillis();
            if (a == null) return rid;
            new Thread(new Vc148SpeedWorker(a.getApplicationContext(), rid), "vc148-speed").start();
            return rid;
        }

    }

    /** vc148: named static nested worker (iron rule 1 — no anonymous classes).
     *  Builds the enabled-channel list and measures latency off the bridge
     *  thread; result pushed via evalJsStatic(window.onWsSpeedResult). */
    static final class Vc148SpeedWorker implements Runnable {
        private final android.content.Context ctx;
        private final String rid;
        Vc148SpeedWorker(android.content.Context c, String r) { ctx = c; rid = r; }

        @Override public void run() {
            SearchConfig.init(ctx);
            List<EngineRegistry.EngineSpec> engines = EngineRegistry.parse(
                    SearchConfig.assetText("engines.json"));
            List<SearchService.ChannelSpec> chans = new ArrayList<SearchService.ChannelSpec>();
            String base = SearchService.normalizeBaseUrl(SearchConfig.baseUrl());
            for (int i = 0; i < engines.size(); i++) {
                EngineRegistry.EngineSpec e = engines.get(i);
                if (!EngineRegistry.isKnownType(e.type)) continue;
                boolean on = SearchConfig.engineEnabled(e.id, e.defaultOn);
                if (!on) continue;
                if (e.builtin) {
                    if (base.length() > 0) {
                        chans.add(new SearchService.ChannelSpec(e.id, e.type, base, e));
                    }
                } else {
                    chans.add(new SearchService.ChannelSpec(e.id, e.type, "", e));
                }
            }
            String payload = "[]";
            if (!chans.isEmpty()) {
                SearchService svc = new SearchService();
                List<SpeedTester.EngineSpeedResult> speeds =
                        SpeedTester.measure(svc, chans, "test");
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (SpeedTester.EngineSpeedResult s : speeds) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"engineId\":\"").append(jsonEsc(s.engineId));
                    sb.append("\",\"engineLabel\":\"").append(jsonEsc(s.engineLabel));
                    sb.append("\",\"latency\":").append(s.latencyMs);
                    sb.append(",\"hasResults\":").append(s.hasResults);
                    sb.append(",\"status\":\"").append(jsonEsc(s.status)).append("\"}");
                }
                sb.append("]");
                payload = sb.toString();
            }
            final String js = "window.onWsSpeedResult && window.onWsSpeedResult("
                    + jq(rid) + "," + jq(payload) + ")";
            MainActivity a = instanceRef == null ? null : instanceRef.get();
            if (a != null) a.evalJs(js); else evalJsStatic(js);
        }
    }

    /** Engine ids in engines.json order (searxng first = builtin). */
    static final String[] SEED_IDS = {"searxng", "bing", "bing_cn", "ddg",
            "wikipedia", "sogou", "baidu", "github"};

    /** vc124: minimal JSON string escaping for bridge-built payloads
     *  (quotes, backslash, control chars). */
    static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == 92) sb.append('\\').append(c);
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Shared registry JSON builder for the bridge (id/label/builtin/on). */
    static String enginesJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            List<EngineRegistry.EngineSpec> engines = EngineRegistry.parse(
                    SearchConfig.assetText("engines.json"));
            for (int i = 0; i < engines.size(); i++) {
                EngineRegistry.EngineSpec e = engines.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", e.id);
                o.put("label", e.label);
                o.put("builtin", e.builtin);
                o.put("on", SearchConfig.engineEnabled(e.id, e.defaultOn));
                arr.put(o);
            }
        } catch (Throwable t) {
            return "[]"; // org.json put throws checked: guarded helper
        }
        return arr.toString();
    }

    /** vc122 worker: runs the search on its own thread, pushes the payload with
     *  the pre-assembled block to window.onWsResult (JS never assembles the
     *  block). Named static nested class — d8 zero-anonymous rule. */
    private static final class WsFetchWorker implements Runnable {
        private final String baseUrl;
        private final String query;
        private final int count;
        private final String reqId;

        WsFetchWorker(String baseUrl, String query, int count, String reqId) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.query = query == null ? "" : query;
            this.count = count;
            this.reqId = reqId == null ? "" : reqId;
        }

        @Override
        public void run() {
            long t0 = System.currentTimeMillis();
            String payload;
            try {
                String base = baseUrl.trim();
                if (base.length() == 0) base = SearchConfig.baseUrl();
                // vc123: channels from the engine registry (arg base wins for the
                // builtin searxng); legacy single channel when engines.json is
                // unreadable but a base URL exists (vc122 behavior preserved).
                java.util.List<EngineRegistry.EngineSpec> engines = EngineRegistry.parse(
                        SearchConfig.assetText("engines.json"));
                java.util.List<SearchService.ChannelSpec> chs =
                        new java.util.ArrayList<SearchService.ChannelSpec>();
                if (!engines.isEmpty()) {
                    java.util.List<Boolean> flags = new java.util.ArrayList<Boolean>();
                    for (int i = 0; i < engines.size(); i++) {
                        EngineRegistry.EngineSpec e = engines.get(i);
                        flags.add(Boolean.valueOf(
                                SearchConfig.engineEnabled(e.id, e.defaultOn)));
                    }
                    chs = EngineRegistry.channelsFor(base, engines, flags);
                } else if (base.length() > 0) {
                    chs.add(new SearchService.ChannelSpec("searxng", "searxng", base));
                }
                if (chs.isEmpty()) {
                    payload = "{\"ok\":false,\"reqId\":\"" + esc(reqId) + "\",\"block\":\"\","
                            + "\"titles\":[],\"ms\":0,\"err\":\"未启用任何搜索引擎\"}";
                } else {
                    SearchService svc = new SearchService();
                    SearchService.SearchResponse r = svc.search(
                            new SearchService.SearchRequest(query, count, chs));
                    org.json.JSONArray ta = new org.json.JSONArray();
                    for (int i = 0; i < r.results.size(); i++) ta.put(r.results.get(i).title);
                    org.json.JSONArray ea = new org.json.JSONArray();
                    for (int i = 0; i < r.sources.size(); i++) ea.put(r.sources.get(i));
                    String label = r.sources.isEmpty()
                            ? SearchService.hostOf(base)
                            : joinIds(r.sources);
                    String block = SearchService.buildSearchBlock(label, r.results,
                            SearchService.BLOCK_MAX_CHARS);
                    // vc127: structured data source line (quote > weather),
                    // silent miss = passthrough. Same helper as the search path.
                    try {
                        String sl = com.dawoer.npullm.search.DataSources.structuredLine(query);
                        if (sl != null) {
                            block = SearchService.insertStructuredLine(block, sl,
                                    SearchService.BLOCK_MAX_CHARS);
                        }
                    } catch (Throwable st) {
                        // never break the search path
                    }
                    org.json.JSONObject po = new org.json.JSONObject();
                    po.put("ok", true);
                    po.put("reqId", reqId);
                    po.put("block", block);
                    po.put("titles", ta);
                    po.put("engines", ea);
                    po.put("ms", System.currentTimeMillis() - t0);
                    po.put("err", "");
                    payload = po.toString();
                }
            } catch (Throwable e) {
                long ms = System.currentTimeMillis() - t0;
                String err = e.getClass().getSimpleName();
                String m = e.getMessage();
                if (m != null && m.length() > 0) err = err + ": " + m;
                if (err.length() > 200) err = err.substring(0, 200);
                payload = "{\"ok\":false,\"reqId\":\"" + esc(reqId) + "\",\"block\":\"\","
                        + "\"titles\":[],\"ms\":" + ms + ",\"err\":\"" + esc(err) + "\"}";
            }
            final String js = "window.onWsResult && window.onWsResult(" + jq(payload) + ");";
            evalJsStatic(js);
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", " ").replace("\r", " ");
        }

        static String joinIds(java.util.List<String> ids) {
            java.util.Iterator<String> it = ids.iterator();
            StringBuilder sb = new StringBuilder();
            while (it.hasNext()) {
                sb.append(it.next());
                if (it.hasNext()) sb.append(", ");
            }
            return sb.toString();
        }
    }

    static void evalJsStatic(String js) {
        // safe post from any thread; no-op if activity gone
        MainActivity a = instanceRef == null ? null : instanceRef.get();
        if (a != null) a.evalJs(js);
    }

    /** vc131 (v1.5.0 batch2): update-check helpers. Static nested, no anonymous
     *  classes (iron rule 1). The JS bridge thread is NOT the UI thread — the
     *  network call runs on it directly (bridge semantics, like wsFetch), UI
     *  work is routed through runOnUiThread. */
    static final class Vc131 {
        private Vc131() {}

        /** Latest-version URL list (Gitee primary, GitHub fallback). Static so
         *  the JVM test can assert the contract without network. */
        static final String[] UPDATE_URLS = {
                "https://gitee.com/api/v5/repos/PocketOrca/pocket-orca-llm/releases/latest",
                "https://api.github.com/repos/PocketOrca/PocketOrca-LLM/releases/latest",
        };

        /** Blocking GET (connect timeout = timeoutSec/2, read timeout =
         *  timeoutSec seconds). Returns a JSON object string {"code":..,"body":..}
         *  — never throws, body capped at 256KiB. TLS verified (spec R11). */
        static String httpGet(final String url, final int timeoutSec) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("code", "ERR: NOURL");
                o.put("body", "");
            } catch (Throwable ignored) {
            }
            if (url == null || url.length() == 0 || !url.startsWith("http")) {
                return o.toString();
            }
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(Math.max(1000, timeoutSec * 1000) / 2);
                conn.setReadTimeout(Math.max(1000, timeoutSec * 1000));
                conn.setRequestProperty("User-Agent",
                        "PocketOrca-LLM (Android; update-check)");
                int code = conn.getResponseCode();
                java.io.InputStream in = code >= 400
                        ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in,
                                    java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                            if (sb.length() > 262144) break; // 256KiB hard stop
                        }
                    }
                }
                o.put("code", String.valueOf(code));
                o.put("body", sb.toString());
            } catch (java.net.SocketTimeoutException e) {
                try {
                    o.put("code", "ERR: TIMEOUT");
                    o.put("body", "");
                } catch (Throwable ignored) {
                }
            } catch (Throwable e) {
                try {
                    o.put("code", "ERR: " + e.getClass().getSimpleName());
                    o.put("body", "");
                } catch (Throwable ignored) {
                }
            }
            return o.toString();
        }

        /** Open URL in the system browser; toast on failure. UI thread only. */
        static void openUrl(final String url) {
            MainActivity a = instanceRef == null ? null : instanceRef.get();
            if (a == null || url == null || url.length() == 0) return;
            a.runOnUiThread(() -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable e) {
                    android.widget.Toast.makeText(a.getApplicationContext(),
                            "Open failed", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ---- pure logic (JVM-testable, no Android imports) ------------------

        /** Extract tag_name from a releases/latest JSON body (org.json).
         *  Returns null when absent or not a JSON object. */
        static String parseTagOf(String body) {
            if (body == null) return null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(body);
                String t = o.optString("tag_name", "");
                return t.length() == 0 ? null : t;
            } catch (Throwable e) {
                return null;
            }
        }

        /** Numeric-segment compare, "v" prefix tolerated ("v1.5.0" == "1.5.0").
         *  Returns >0 local newer, 0 equal, <0 remote newer, null unparseable. */
        static Integer cmpVer(String local, String remote) {
            int[] a = segs(local), b = segs(remote);
            if (a == null || b == null) return null;
            int n = Math.max(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int x = i < a.length ? a[i] : 0;
                int y = i < b.length ? b[i] : 0;
                if (x != y) return x > y ? 1 : -1;
            }
            return 0;
        }

        private static int[] segs(String v) {
            if (v == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\d+(?:\\.\\d+)*").matcher(v);
            if (!m.find()) return null;
            String[] parts = m.group().split("\\.");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (Throwable e) {
                    out[i] = 0;
                }
            }
            return out;
        }

        /** Decision payload for window.onCheckUpdate. state: "new" | "same"
         *  | "check_failed"; tag = remote tag (nullable); relPage = releases
         *  page for the openUrl bridge. Single source of the JS contract. */
        static String decisionJson(String appVer, String tag, String relPage) {
            String state;
            Integer c = cmpVer(appVer, tag);
            if (tag == null || c == null) state = "check_failed";
            else state = c < 0 ? "new" : "same";
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("state", state);
                o.put("tag", tag == null ? "" : tag);
                o.put("relPage", relPage == null ? "" : relPage);
            } catch (Throwable ignored) {
            }
            return o.toString();
        }

        static String releasesPage() {
            return "https://gitee.com/PocketOrca/pocket-orca-llm/releases";
        }
    }

    /** vc131 B: named static nested worker (iron rule 1 — no anonymous classes).
     *  Gitee primary -> GitHub fallback; check-failed is a REAL outcome and is
     *  never reported as "up to date" (BRIEF batch2 B hard requirement). */
    static final class Vc131UpdateWorker implements Runnable {
        private final String appVer;
        private final MainActivity activity;

        Vc131UpdateWorker(String appVer, MainActivity activity) {
            this.appVer = appVer == null ? "" : appVer;
            this.activity = activity;
        }

        @Override
        public void run() {
            String tag = null;
            for (int i = 0; i < Vc131.UPDATE_URLS.length && tag == null; i++) {
                String rsp = Vc131.httpGet(Vc131.UPDATE_URLS[i], 8);
                if (rsp != null && rsp.contains("\"code\":\"2")) {
                    tag = Vc131.parseTagOf(bodyOf(rsp));
                }
            }
            final String payload = Vc131.decisionJson(
                    appVer, tag, Vc131.releasesPage());
            activity.runOnUiThread(() -> evalJsStatic(
                    "window.onCheckUpdate && window.onCheckUpdate(" + jq(payload) + ")"));
        }

        private static String bodyOf(String rsp) {
            // rsp shape: {"code":"200","body":"..."} — org.json round-trip
            try {
                return new org.json.JSONObject(rsp).optString("body", "");
            } catch (Throwable e) {
                return "";
            }
        }
    }

    private static java.lang.ref.WeakReference<MainActivity> instanceRef = null;
}
