package com.dawoer.npullm.speech;

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
 * vc166: TTS 前台服务（:tts 独立进程，:8084 宿主）。
 *
 * 进程分离（用户 1110 拍板）：TTS 与 ASR 各占一个进程，谁爆谁死，互不拖累。
 * 因此本类里所有「进程内静态量」都只对 :tts 进程有效——UI 进程（main）判断服务
 * 是否在跑，只能走 127.0.0.1 的 /health 探测，不能读本类的 alive 字段。
 *
 * 通知通道换新 ID（speech_tts_v1）：importance 首次创建即冻结，且进程/文案都变了，
 * 旧通道 speech_server_v1 留作作废。
 * NOTIF_ID 45（42=主服务，43=搜索，44=RAG，46=vc167 ASR）。
 */
public class SpeechForegroundService extends Service {

    static final String CHANNEL_ID = "speech_tts_v1";
    static final int NOTIF_ID = 45;
    static final String ACTION_STOP = "com.dawoer.npullm.STOP_SPEECH_SERVER";
    private static final long TICK_MS = 60000L;

    /** 服务真实在跑（UI 状态判据）。同进程内可见。 */
    public static volatile boolean alive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechServer server;

    public static void start(Context c) {
        Intent i = new Intent(c, SpeechForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SpeechForegroundService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SpeechConfig.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "语音合成 (TTS)", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("本机 TTS 端点与引擎状态");
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
            SpeechServer s = new SpeechServer(this, SpeechConfig.port());
            s.setApiKey(SpeechConfig.ensureGateKey());
            s.start(NanoHttpdTimeout());
            server = s;
            alive = true;
            publish(null);
            main.postDelayed(new Ticker(this), TICK_MS);
        } catch (Throwable t) {
            server = null;
            android.util.Log.w("SpeechFgs", "start failed: " + t);
            publish("启动失败：" + msg(t));
            stopSelf();
        }
    }

    private static int NanoHttpdTimeout() {
        return 5000;
    }

    private void stopServerInternal() {
        SpeechServer s = server;
        server = null;
        alive = false;
        if (s != null) {
            try { s.stop(); } catch (Throwable ignore) {}
        }
        SpeechTts.shutdown();
        publish("已停止");
    }

    private static String msg(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        return m == null ? n : n + ": " + m;
    }

    private void publish(String extra) {
        String body = extra == null
                ? "TTS :" + SpeechConfig.port(SpeechConfig.KIND_TTS) + " 运行中 · 仅限局域网"
                : extra;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getMainActivityClass()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("语音合成 TTS")
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, SpeechForegroundService.class).setAction(ACTION_STOP),
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
            android.util.Log.w("SpeechFgs", "startForeground rejected: " + t);
        }
    }

    // ===== named static nested workers (zero anonymous classes) =====

    private static final class Starter implements Runnable {
        private final java.lang.ref.WeakReference<SpeechForegroundService> owner;
        Starter(SpeechForegroundService s) {
            owner = new java.lang.ref.WeakReference<SpeechForegroundService>(s);
        }
        @Override public void run() {
            SpeechForegroundService s = owner.get();
            if (s != null) s.startServerInternal();
        }
    }

    /** TTS 引擎空转回收节拍。 */
    private static final class Ticker implements Runnable {
        private final java.lang.ref.WeakReference<SpeechForegroundService> owner;
        Ticker(SpeechForegroundService s) {
            owner = new java.lang.ref.WeakReference<SpeechForegroundService>(s);
        }
        @Override public void run() {
            SpeechForegroundService s = owner.get();
            if (s == null) return;
            try { SpeechTts.tick(); } catch (Throwable ignore) {}
            s.main.postDelayed(new Ticker(s), TICK_MS);
        }
    }
}
