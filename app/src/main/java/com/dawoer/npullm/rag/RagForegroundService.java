package com.dawoer.npullm.rag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * RAG 服务前台服务（:8082 的宿主）。
 *
 * 与搜索服务同款模板：START_STICKY + startForegroundGuarded 去重 + **新建
 * CHANNEL_ID**（铁律：channel importance 首次创建即冻结，绝不复用
 * search_server_v1 / llama_server_v2）。NOTIF_ID 44（42=主服务，43=搜索）。
 *
 * 只依赖 rag 包内部类，零引擎侧 import。
 */
public class RagForegroundService extends Service {

    static final String CHANNEL_ID = "rag_server_v1";
    static final int NOTIF_ID = 44;
    static final String ACTION_STOP = "com.dawoer.npullm.STOP_RAG_SERVER";
    private static final long TICK_MS = 60000L;

    /** 服务真实在跑（UI 状态判据，别用配置端口猜）。同进程内可见。 */
    public static volatile boolean alive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RagServer server;
    private RagService svc;

    public static void start(Context c) {
        Intent i = new Intent(c, RagForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, RagForegroundService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RagConfig.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "知识库服务", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("RAG 知识库端点运行状态");
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
        main.post(new Starter(this));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServerInternal();
        super.onDestroy();
    }

    private void startServerInternal() {
        if (server != null) {
            publish(null);
            return;
        }
        try {
            svc = RagService.get(this);
            RagServer s = new RagServer(svc, RagConfig.port());
            s.setApiKey(RagConfig.ensureGateKey());
            s.start(RagServer.SOCKET_READ_TIMEOUT);
            server = s;
            alive = true;
            publish(null);
            main.postDelayed(new Ticker(this), TICK_MS);
        } catch (Throwable t) {
            server = null;
            android.util.Log.w("RagFgs", "start failed: " + t);
            publish("启动失败：" + msg(t));
            stopSelf();
        }
    }

    private void stopServerInternal() {
        RagServer s = server;
        server = null;
        alive = false;
        if (s != null) {
            try { s.stop(); } catch (Throwable ignore) {}
        }
        RagService rs = svc;
        if (rs != null) {
            try { rs.onStop(); } catch (Throwable ignore) {}
        }
        publish("已停止");
    }

    private static String msg(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        return m == null ? n : n + ": " + m;
    }

    private void publish(String extra) {
        String body = extra == null
                ? "知识库服务 :" + RagConfig.port() + " 运行中 · 仅限局域网"
                : extra;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getMainActivityClass()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("知识库服务")
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, RagForegroundService.class).setAction(ACTION_STOP),
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
            return android.app.Activity.class;
        }
    }

    private void startForegroundGuarded(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("RagFgs", "startForeground rejected: " + t);
        }
    }

    // ===== named static nested workers (zero anonymous classes) =====

    private static final class Starter implements Runnable {
        private final java.lang.ref.WeakReference<RagForegroundService> owner;
        Starter(RagForegroundService s) {
            owner = new java.lang.ref.WeakReference<RagForegroundService>(s);
        }
        @Override public void run() {
            RagForegroundService s = owner.get();
            if (s != null) s.startServerInternal();
        }
    }

    /** 空闲回收节拍：非常驻嵌入进程 5 分钟无请求就释放。 */
    private static final class Ticker implements Runnable {
        private final java.lang.ref.WeakReference<RagForegroundService> owner;
        Ticker(RagForegroundService s) {
            owner = new java.lang.ref.WeakReference<RagForegroundService>(s);
        }
        @Override public void run() {
            RagForegroundService s = owner.get();
            if (s == null) return;
            RagService rs = s.svc;
            if (rs != null) {
                try { rs.tick(); } catch (Throwable ignore) {}
            }
            s.main.postDelayed(new Ticker(s), TICK_MS);
        }
    }
}
