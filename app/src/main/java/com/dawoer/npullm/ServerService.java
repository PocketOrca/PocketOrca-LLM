package com.dawoer.npullm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Foreground service hosting the running llama-server (spec §3.7 / T5).
 *
 * - Notification "LLM Server 运行中 :{port}" while RUNNING; tap reopens MainActivity.
 * - WifiLock (FULL_HIGH_PERF | FULL_LOW_LATENCY) so the LAN endpoint survives Doze
 *   on battery (降级后果: 息屏后可能无法从局域网访问).
 * - STOP action lets the user kill the server from the notification itself.
 *
 * bt34 d8 constraint (VERSION-ARCHIVE §v0.4.1): the toolchain NPEs dexing
 * ANONYMOUS classes (ServerService$1) — zero anonymous classes allowed here.
 * Callbacks follow the proven UiForwarder pattern: named static nested classes
 * holding WeakReferences; lambdas are fine (invokedynamic, no $1 class file).
 */
public class ServerService extends Service {

    static final String CHANNEL_ID = "llama_server";
    static final int NOTIF_ID = 42;
    static final String ACTION_STOP = "com.dawoer.npullm.STOP_SERVER";
    static final long WATCHDOG_MS = 30000;
    /** v1.3.1: reborn after swipe-away (remove task). */
    static final String ACTION_REBORN = "com.dawoer.npullm.REBORN_SERVER";
    static final String REBORN_MS = "rebornMs";

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeviceSampler sampler = new DeviceSampler();
    private boolean tickerOn = false;
    private long lastNonEmptyMs = 0;

    /** d8-safe ticker: named static nested Runnable + WeakReference. */
    static final class Ticker implements Runnable {
        private final java.lang.ref.WeakReference<ServerService> ref;
        Ticker(ServerService s) { ref = new java.lang.ref.WeakReference<>(s); }
        @Override public void run() {
            ServerService s = ref.get();
            if (s == null) return;
            s.tick();
        }
    }

    void tick() {
        if (!tickerOn) return;
        LlamaServerManager m = LlamaServerManager.peek();
        int port = (m != null) ? m.currentPort() : 8080;
        DeviceSampler.Sample sm = sampler.take(port);
        boolean idle = sm.tps <= 0; // no generation traffic -> slow the loop
        lastNonEmptyMs = idle ? lastNonEmptyMs : System.currentTimeMillis();
        publish(sm);
        // 2s while generating, 10s idle
        main.postDelayed(new Ticker(this), idle ? 10000 : 2000);
    }

    private void startTicker() {
        if (tickerOn) return;
        tickerOn = true;
        main.postDelayed(new Ticker(this), 1500);
    }

    private void stopTicker() {
        tickerOn = false;
    }

    /** d8-safe watchdog: named static nested Runnable + WeakReference.
     *  Only self-stops when the manager is gone or gave up (READY/ERROR).
     *  While STARTING it re-arms: ocl cold load can exceed 120s (JIT, spec). */
    static final class Watchdog implements Runnable {
        private final java.lang.ref.WeakReference<ServerService> ref;
        Watchdog(ServerService s) { ref = new java.lang.ref.WeakReference<>(s); }
        @Override public void run() {
            ServerService s = ref.get();
            if (s == null) return;
            LlamaServerManager m = LlamaServerManager.peek();
            if (m == null || m.isIdleOrError()) { s.stopSelf(); return; }
            if (m.isRunning()) return;   // healthy — watchdog done
            s.main.postDelayed(new Watchdog(s), WATCHDOG_MS);
        }
    }

    /** d8-safe notifier: mirrors MainActivity.UiForwarder pattern. */
    static final class NotifierImpl implements LlamaServerManager.Notifier {
        private final java.lang.ref.WeakReference<ServerService> ref;
        NotifierImpl(ServerService s) { ref = new java.lang.ref.WeakReference<>(s); }
        @Override public void onState(int state, String msg) {
            ServerService s = ref.get();
            if (s == null) return;
            if (state == LlamaServerManager.ST_RUNNING) { s.publish(null); s.startTicker(); }
            else if (state == LlamaServerManager.ST_READY) { s.stopTicker(); s.stopSelf(); }
            else s.publishStarting(msg);
        }
    }

    static void start(Context c) {
        Intent i = new Intent(c, ServerService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ServerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppCtx.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("llama-server 运行状态");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        LlamaServerManager.setServiceRunning(true);
        LlamaServerManager m = LlamaServerManager.peek();
        if (m != null) m.setNotifier(new NotifierImpl(this));

        acquireWifiLock();
        acquireWakeLock();
        publishStarting(null);
        // watch-dog: if nothing is running shortly after start, bail out
        main.postDelayed(new Watchdog(this), WATCHDOG_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            LlamaServerManager m = LlamaServerManager.peek();
            if (m != null) new Thread(() -> m.stop(), "notif-stop").start();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean reborn = intent != null && ACTION_REBORN.equals(intent.getAction());
        LlamaServerManager m = LlamaServerManager.peek();
        if (m == null || !m.isRunning()) {
            // v1.3.1: reborn path — swipe-away killed the whole process and the
            // system later redelivered our service. Adopt the orphan llama-server
            // (it survived as a reparented child) or relaunch it from persisted
            // args; leave quietly when the user had stopped the server before.
            // Runs off-main: the health probe can block up to seconds.
            if (reborn || (flags & START_FLAG_REDELIVERY) != 0) {
                new Thread(() -> {
                    if (adoptOrReborn() == REBORN_NONE) stopSelf();
                }, "reborn").start();
                return START_STICKY;
            }
            // started but nothing running (process was killed): stop quietly
            stopSelf();
            return START_NOT_STICKY;
        }
        publish(null);
        startTicker();
        return START_STICKY;
    }

    /* ---------- v1.3.1: survive swipe-away (OneUI "remove task" am_kill) ---------- */

    // int constants, not an enum: the bt34 d8 NPEs dexing enums (same reason
    // LlamaServerManager encodes its state as ints).
    static final int REBORN_ADOPTED = 1, REBORN_RELAUNCHED = 2, REBORN_NONE = 3;

    /**
     * Decision: what should the service do when started with nothing running?
     * Pure function over (orphan alive?, persisted args?) — the I/O side lives
     * in adoptOrReborn() so the logic is unit-testable without Android.
     */
    static int rebornAction(boolean orphanAlive, boolean hasSavedArgs) {
        if (orphanAlive) return REBORN_ADOPTED;
        if (hasSavedArgs) return REBORN_RELAUNCHED;
        return REBORN_NONE;
    }

    /**
     * v1.3.1: app process was killed by "remove task" but the exec child
     * survived as an orphan. Rebuild the protection layer around it: adopt it
     * (health probe, same as MainActivity orphan-adopt) or relaunch from the
     * persisted launch args (written by Bridge.startServer, cleared on
     * onDestroy when the user stops the server). Returns what happened.
     */
    private int adoptOrReborn() {
        final Context app = getApplicationContext();
        final LlamaServerManager m = LlamaServerManager.get(app, new HeadlessUi());
        if (m.isRunning()) return REBORN_ADOPTED; // raced: manager already up
        final int port = m.currentPort();
        final java.util.concurrent.atomic.AtomicBoolean adopted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread probe = new Thread(() -> adopted.set(m.adoptOrphanIfAny(port)), "reborn-adopt");
        probe.start();
        try { probe.join(4000); } catch (InterruptedException ignored) {}
        int r = rebornAction(adopted.get(), hasRebornArgs(app));
        if (r == REBORN_RELAUNCHED) {
            String[] a = readRebornArgs(app);
            android.util.Log.i("npullm", "[复活] 孤儿不存在，用持久化参数重启 llama-server :" + a[2]);
            // start() is synchronized; it pushes ST_STARTING/ST_RUNNING/ST_ERROR
            // through NotifierImpl, which re-publishes our notification.
            m.start(a[0], a[1], portOf(a), ctxOf(a), a[4],
                    threadsOf(a), noKvOf(a), ubatchOf(a));
        } else if (r == REBORN_ADOPTED) {
            android.util.Log.i("npullm", "[复活] 检测到孤儿 llama-server @:" + port + "，已重新接管");
        }
        return r;
    }

    /** Reborn bridge UI: no Activity to forward to; keep log + notifier only. */
    static final class HeadlessUi implements LlamaServerManager.Ui {
        @Override public void onServerState(String state, String msg) {
            android.util.Log.i("npullm", "[复活] state=" + state + (msg == null ? "" : " " + msg));
        }
        @Override public void onServerDied(int exitCode) {
            android.util.Log.i("npullm", "[复活] llama-server exited code=" + exitCode);
        }
    }

    /**
     * OneUI kills the whole process on swipe-away ("remove task"), orphaning
     * the llama-server child. Schedule an exact alarm ~1.5s out: if the
     * process is really gone, AlarmManager relights it via this Service and
     * the child gets adopted (protection restored) or relaunched. No-op when
     * the service ends for a normal reason (user stop / idle bail).
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LlamaServerManager m = LlamaServerManager.peek();
        if (m != null && m.isRunning()) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            boolean exact = am.canScheduleExactAlarms(); // v1.3.1: SCHEDULE_EXACT_ALARM gate
            Intent i = new Intent(this, ServerService.class);
            i.setAction(ACTION_REBORN);
            i.putExtra(REBORN_MS, SystemClock.elapsedRealtime());
            PendingIntent pi = PendingIntent.getForegroundService(this, 3, i,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + 1500;
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                // inexact fallback: usually fires within seconds; USE_EXACT_ALARM
                // in the manifest should make this path unreachable on store builds
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    /* ---------- persisted launch args for the reborn path ---------- */

    static final String REBORN_PREFS = "reborn";
    static final String[] REBORN_KEYS = {
            "model", "profile", "port", "ctx", "reasoning", "threads", "noKv", "ubatch"};

    static boolean hasRebornArgs(Context c) {
        return c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE)
                .contains(REBORN_KEYS[0]);
    }

    static void saveRebornArgs(Context c, String model, String profile, int port,
                               int ctx, String reasoning, int threads, boolean noKv, int ubatch) {
        c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE).edit()
                .putString(REBORN_KEYS[0], model)
                .putString(REBORN_KEYS[1], profile)
                .putInt(REBORN_KEYS[2], port)
                .putInt(REBORN_KEYS[3], ctx)
                .putString(REBORN_KEYS[4], reasoning)
                .putInt(REBORN_KEYS[5], threads)
                .putBoolean(REBORN_KEYS[6], noKv)
                .putInt(REBORN_KEYS[7], ubatch)
                .apply();
    }

    static void clearRebornArgs(Context c) {
        c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static String[] readRebornArgs(Context c) {
        android.content.SharedPreferences p =
                c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE);
        return new String[]{
                p.getString(REBORN_KEYS[0], ""),
                p.getString(REBORN_KEYS[1], "cpu"),
                String.valueOf(p.getInt(REBORN_KEYS[2], 8080)),
                String.valueOf(p.getInt(REBORN_KEYS[3], 4096)),
                p.getString(REBORN_KEYS[4], "auto"),
                String.valueOf(p.getInt(REBORN_KEYS[5], 0)),
                String.valueOf(p.getBoolean(REBORN_KEYS[6], false)),
                String.valueOf(p.getInt(REBORN_KEYS[7], 0))};
    }

    // arg-index helpers keep adoptOrReborn readable
    private static int portOf(String[] a) { return Integer.parseInt(a[2]); }
    private static int ctxOf(String[] a) { return Integer.parseInt(a[3]); }
    private static int threadsOf(String[] a) { return Integer.parseInt(a[5]); }
    private static boolean noKvOf(String[] a) { return Boolean.parseBoolean(a[6]); }
    private static int ubatchOf(String[] a) { return Integer.parseInt(a[7]); }


    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    mode |= WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
                wifiLock = wm.createWifiLock(mode, "npullm:server");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.2.17: PARTIAL_WAKE_LOCK moved here from MainActivity so the CPU
     * keeps running with the screen off (Termux "wakelock" model). Held for
     * the whole service lifetime: llama-server serves LAN requests between
     * user prompts, so idleness cannot be detected reliably — the lock is
     * cheap while no work is scheduled, and the server is expected to stay
     * resident anyway. Released in onDestroy (user stop / watchdog bail).
     */
    private void acquireWakeLock() {
        try {
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "npullm:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void releaseWifiLock() {
        try { if (wifiLock != null) wifiLock.release(); } catch (Throwable ignored) {}
        wifiLock = null;
    }

    /** Running-state notification with live stats (T6) + Stop action.
     *  Fields with no readable source are hidden (spec: never fake data). */
    private void publish(DeviceSampler.Sample sm) {
        LlamaServerManager m = LlamaServerManager.peek();
        int port = (m != null) ? m.currentPort() : 8080;
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ServerService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent spi = PendingIntent.getService(
                this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", spi).build());

        if (sm == null) {
            b.setContentTitle("LLM Server 运行中 :" + port)
             .setContentText("局域网端点已就绪");
        } else {
            StringBuilder l1 = new StringBuilder();
            if (sm.cpuPct >= 0) l1.append("CPU ").append(Math.round(sm.cpuPct)).append("%  ");
            if (sm.gpuPct >= 0) l1.append("GPU ").append(Math.round(sm.gpuPct)).append("%  ");
            if (sm.ramUsedGB >= 0) l1.append("RAM ").append(sm.ramUsedGB).append('/').append(sm.ramTotalGB).append("G");
            StringBuilder l2 = new StringBuilder();
            if (sm.tempC >= 0) l2.append("温度 ").append(String.format(java.util.Locale.US, "%.1f", sm.tempC)).append("°C  ");
            if (sm.tps > 0) l2.append("输出 ").append(String.format(java.util.Locale.US, "%.1f", sm.tps)).append(" t/s  ");
            if (sm.conns >= 0) l2.append("连接 ").append(sm.conns);
            b.setContentTitle("LLM Server :" + port + (sm.tps > 0 ? "  ⚡" + String.format(java.util.Locale.US, "%.1f", sm.tps) + " t/s" : ""));
            String body = (l1.length() > 0 ? l1.toString().trim() + "\n" : "")
                        + (l2.length() > 0 ? l2.toString().trim() : "空闲中");
            Notification.BigTextStyle st = new Notification.BigTextStyle()
                    .bigText(body);
            b.setStyle(st).setContentText(body.replace('\n', ' '));
        }
        startForeground(NOTIF_ID, b.build());
    }

    /** Starting-state notification (no stop action yet). */
    private void publishStarting(String msg) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("LLM Server 启动中…")
                .setContentText(msg == null ? "载入模型与后端初始化" : msg)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        stopTicker();
        releaseWifiLock();
        releaseWakeLock();
        // v1.3.1: every path that ends the service through onDestroy is a
        // deliberate stop (UI stop, notification stop, watchdog bail) — never
        // resurrect from these. The swipe-away kill path has no onDestroy.
        clearRebornArgs(this);
        LlamaServerManager m = LlamaServerManager.peek();
        if (m != null) m.setNotifier(null);
        LlamaServerManager.setServiceRunning(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
