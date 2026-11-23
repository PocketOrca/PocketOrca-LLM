package com.dawoer.npullm.mcp.outbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.dawoer.npullm.MainActivity;

/**
 * 主动推送（outbox）独立服务（vc185b）——跑在 :mcp 进程，与 MCP HTTP 服务**互不依赖**。
 *
 * 为什么必须独立：outbox 是「手机侧主动出站」，MCP 是「PC 侧主动入站」。绑在一起会出现
 * 「不想开 MCP 端口，就没法收手机事件」这种荒唐耦合；也让“关一个东西”变成“全关”。
 * 所以：一个独立开关、一条独立常驻通知、一条独立生命周期（START_STICKY）。
 *
 * 采集不归它管：通知监听由系统绑定 NotificationListenerService，进程被杀系统会重拉，
 * 事件先落 SQLite（事实源）。本服务只管“投递 + 唤醒”，挂了也不丢事。
 */
public final class OutboxForegroundService extends Service {

    private static final String TAG = "PoMcpOutbox";

    /** 与 MCP 服务的通知 id/通道都分开：两条常驻通知，各自可辨。 */
    public static final int NOTIF_ID = 48;
    public static final String CHANNEL_ID = "mcp_outbox_v1";

    public static void start(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startForegroundService(new Intent(ctx, OutboxForegroundService.class));
        } catch (Throwable t) {
            Log.e(TAG, "start failed: " + t.getClass().getSimpleName());
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, OutboxForegroundService.class));
        } catch (Throwable t) {
            Log.e(TAG, "stop failed: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        OutboxConfig.markRunning(this, true);
        OutboxWorker.start(this);
        Log.i(TAG, "outbox service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!OutboxWorker.isRunning()) OutboxWorker.start(this);
        OutboxConfig.markRunning(this, true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            OutboxWorker.stop();
        } catch (Throwable ignored) { }
        OutboxConfig.markRunning(this, false);
        Log.i(TAG, "outbox service destroyed");
        super.onDestroy();
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "主动推送", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("把白名单应用的通知/短信等事件推给已配对的 agent");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        String ep = OutboxConfig.endpoint(this);
        String host = ep == null ? "" : ep.replace("http://", "").replace("https://", "");
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("主动推送运行中")
                .setContentText(host.length() > 0 ? ("推送到 " + host) : "未配置推送端点（只在本地排队）")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }
}
