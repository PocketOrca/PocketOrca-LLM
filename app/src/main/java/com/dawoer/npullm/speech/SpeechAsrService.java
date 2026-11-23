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
 * vc167: ASR 前台服务（:asr 独立进程，:8085 宿主）。
 *
 * 与 TTS 服务同款形态，只是类型不同：microphone（targetSdk 34+ 要求，且识别请求
 * 期间确实要碰麦克风数据），通知通道 speech_asr_v1，NOTIF 46。
 * 进程内静态量同样只对 :asr 有效——UI 判断在跑只能 /health 探测。
 */
public class SpeechAsrService extends Service {

    static final String CHANNEL_ID = "speech_asr_v1";
    static final int NOTIF_ID = 46;
    static final String ACTION_STOP = "com.dawoer.npullm.STOP_SPEECH_ASR";
    private static final long TICK_MS = 60000L;

    public static volatile boolean alive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechAsrServer server;

    public static void start(Context c) {
        Intent i = new Intent(c, SpeechAsrService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SpeechAsrService.class));
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
                CHANNEL_ID, "语音识别 (ASR)", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("本机 ASR 端点与引擎状态");
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
            SpeechAsrServer s = new SpeechAsrServer(this, SpeechConfig.port(SpeechConfig.KIND_ASR));
            s.setApiKey(SpeechConfig.ensureGateKey(SpeechConfig.KIND_ASR));
            s.start(5000);
            server = s;
            alive = true;
            publish(null);
            main.postDelayed(new Ticker(this), TICK_MS);
        } catch (Throwable t) {
            server = null;
            android.util.Log.w("AsrFgs", "start failed: " + t);
            publish("启动失败：" + SpeechTts.msg(t));
            stopSelf();
        }
    }

    private void stopServerInternal() {
        SpeechAsrServer s = server;
        server = null;
        alive = false;
        if (s != null) {
            try { s.stop(); } catch (Throwable ignore) {}
        }
        SpeechAsr.shutdown();
        publish("已停止");
    }

    private void publish(String extra) {
        String body = extra == null
                ? "ASR :" + SpeechConfig.port(SpeechConfig.KIND_ASR) + " 运行中 · 仅限局域网"
                : extra;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getMainActivityClass()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("语音识别 ASR")
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, SpeechAsrService.class).setAction(ACTION_STOP),
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
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("AsrFgs", "startForeground rejected: " + t);
        }
    }

    // ===== named static nested workers (zero anonymous classes) =====

    private static final class Starter implements Runnable {
        private final java.lang.ref.WeakReference<SpeechAsrService> owner;
        Starter(SpeechAsrService s) {
            owner = new java.lang.ref.WeakReference<SpeechAsrService>(s);
        }
        @Override public void run() {
            SpeechAsrService s = owner.get();
            if (s != null) s.startServerInternal();
        }
    }

    /** ASR 引擎空转回收节拍。 */
    private static final class Ticker implements Runnable {
        private final java.lang.ref.WeakReference<SpeechAsrService> owner;
        Ticker(SpeechAsrService s) {
            owner = new java.lang.ref.WeakReference<SpeechAsrService>(s);
        }
        @Override public void run() {
            SpeechAsrService s = owner.get();
            if (s == null) return;
            try { SpeechAsr.tick(); } catch (Throwable ignore) {}
            s.main.postDelayed(new Ticker(s), TICK_MS);
        }
    }
}
