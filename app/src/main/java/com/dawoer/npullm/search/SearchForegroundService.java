package com.dawoer.npullm.search;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Independent foreground service hosting the LAN search endpoint (port
 * configurable via the search page; vc133).
 * (BRIEF-vc122-websearch-1020 §0/§2.1/v4: independent lifecycle — starts and
 * stops without touching the llama-server service; the phone becomes a
 * LAN SearXNG-compatible device).
 *
 * Template = ServerService (proven): START_STICKY + startForegroundGuarded
 * dedupe. NEW CHANNEL id (iron rule #11: channel importance is frozen at
 * first create — never reuse llama_server_v2). Zero imports from engine-side
 * classes. Zero anonymous classes (d8 rule): named static nested callbacks
 * only.
 */
public class SearchForegroundService extends Service {

    static final String CHANNEL_ID = "search_server_v1";
    static final int NOTIF_ID = 43; // ServerService owns 42
    static final String ACTION_STOP = "com.dawoer.npullm.STOP_SEARCH_SERVER";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SearchServer server;

    // ===== lifecycle entry points (Bridge wsServerToggle -> these) =====

    public static void start(Context c) {
        Intent i = new Intent(c, SearchForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SearchForegroundService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SearchConfig.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "搜索服务", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("局域网搜索端点运行状态");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServerInternal();
            stopSelf();
            return START_NOT_STICKY;
        }
        publish("启动中…");
        // start on a worker thread: bind may block on the port
        main.post(new Starter(this));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServerInternal();
        super.onDestroy();
    }

    // ===== server start/stop =====

    private void startServerInternal() {
        if (server != null) {
            publish(null); // already up (START_STICKY restart / double start)
            return;
        }
        try {
            // vc133: port comes from prefs at every start (change -> restart)
            int port = SearchConfig.searchPort();
            SearchServer s = new SearchServer(new SearchService(), port);
            s.setApiKey(SearchConfig.gateKey());
            s.start(SearchService.CONNECT_TIMEOUT_MS);
            server = s;
            SearchController.onServerStarted();
            publish(null);
        } catch (Throwable t) {
            server = null;
            SearchController.onServerFailed(msg(t));
            android.util.Log.w("SearchFgs", "start failed: " + t);
            stopSelf();
        }
    }

    private void stopServerInternal() {
        SearchServer s = server;
        server = null;
        if (s != null) {
            try { s.stop(); } catch (Throwable ignore) {}
        }
        SearchController.onServerStopped();
    }

    private static String msg(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null) return n;
        return n + ": " + m;
    }

    private void publish(String extra) {
        String body = extra == null
                ? "搜索服务 :" + SearchConfig.searchPort() + " 运行中 · 仅限局域网"
                : extra;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getMainActivityClass()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("搜索服务运行中")
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, SearchForegroundService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                        android.R.drawable.ic_media_pause),
                "停止", stopPi).build());
        startForegroundGuarded(b.build());
    }

    private Class<?> getMainActivityClass() {
        try {
            return Class.forName("com.dawoer.npullm.MainActivity");
        } catch (Throwable t) {
            return android.app.Activity.class; // never throws in the field
        }
    }

    /** Same guard pattern as the model-service FGS startForegroundGuarded. */
    private void startForegroundGuarded(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("SearchFgs", "startForeground rejected: " + t);
        }
    }

    // ===== named static nested workers (zero anonymous classes) =====

    private static final class Starter implements Runnable {
        private final java.lang.ref.WeakReference<SearchForegroundService> owner;
        Starter(SearchForegroundService s) { owner = new java.lang.ref.WeakReference<SearchForegroundService>(s); }
        @Override
        public void run() {
            SearchForegroundService s = owner.get();
            if (s != null) s.startServerInternal();
        }
    }
}
