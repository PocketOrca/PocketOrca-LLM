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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

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

    // v1.3.7 (vc65): new channel id + DEFAULT importance. Root cause of
    // "notification never shows": the original channel was created with
    // IMPORTANCE_LOW, which OneUI files under the silent group (hidden from
    // the Shade by default). Channel importance is FROZEN at first create,
    // so fixing the importance requires a new channel id — the stale
    // llama_server channel stays on-device but goes unused.
    static final String CHANNEL_ID = "llama_server_v2";
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
    // v1.3.7 vc67: dedupe repeated startForeground() calls — re-notifying an
    // ongoing notification with identical content vibrates on Samsung builds.
    private String lastBody = null;

    // ===== vc91: 内存压力自检（0929 vc89 静默死破案后的告警线）=====
    // vc89 实锤：mtp 64K ctx 下 dmabuf_rss 4.67GB/14 块 → nandswap 风暴 →
    // lmkd 击杀最重进程组（前台也杀，am_proc_died 无 am_kill = 静默死）。
    // 本告警不假装能防杀（保活与系统搏斗 = 死路），只做两件事：
    // 提前亮红（用户能主动降 ctx/换档）+ 事后日志留痕（logcat 有指纹可查）。
    static final String[] MEM_SCAN_NAMES = {
            "libllamaserver.so", "libllmoclsrv.so", "libbmoecli.so" };
    /** 纯函数：告警进/退决策（迟滞回差，防 2s tick 抖动）。 */
    static boolean memAlarm(boolean currentlyAlarming, long rssBytes) {
        final long ON = 4_500_000_000L;   // 4.5GB — vc89 死案 4.67G 前留余量
        final long OFF = 3_400_000_000L;  // 3.4GB — 低于 vc83 稳态 2.9G 之上
        if (!currentlyAlarming) return rssBytes >= ON;
        return rssBytes > OFF;
    }
    /** 扫 /proc：自进程 VmRSS + 推理引擎子进程 VmRSS，返回合计字节（全不可读 = -1）。 */
    private long inferRssBytes() {
        long total = procRss("/proc/self/status");
        boolean any = total > 0;
        File[] procs = new File("/proc").listFiles();
        if (procs != null) {
            for (File p : procs) {
                if (!p.isDirectory()) continue;
                String n = readFirstLine(new File(p, "cmdline"));
                if (n == null) continue;
                boolean hit = false;
                for (String want : MEM_SCAN_NAMES) if (n.contains(want)) { hit = true; break; }
                if (!hit) continue;
                long r = procRss(new File(p, "status").getAbsolutePath());
                if (r > 0) { total += r; any = true; }
            }
        }
        return any ? total : -1;
    }
    /** VmRSS 行（kB）→ 字节；读不到 = -1。 */
    private static long procRss(String statusPath) {
        try (BufferedReader r = new BufferedReader(new FileReader(statusPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** first line of a small text file (cmdline), null on any failure. */
    private static String readFirstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Throwable ignored) {}
        return null;
    }
    private boolean memAlarmOn = false;
    private long lastRssBytes = -1;

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
        if (m == null || m.isIdleOrError()) {
            stopTicker();
            publishIdle();
            return;
        }
        int port = m.currentPort();
        DeviceSampler.Sample sm = sampler.take(port);
        // vc91: memory-pressure self-check (own process + engine children VmRSS)
        lastRssBytes = inferRssBytes();
        boolean alarm = memAlarm(memAlarmOn, lastRssBytes);
        if (alarm != memAlarmOn) {
            memAlarmOn = alarm;
            android.util.Log.w("npullm", alarm
                    ? String.format(java.util.Locale.US,
                        "[内存告警] 进程组 RSS %.2fGB ≥ 4.5G — 请降上下文/换档，否则可能被系统静默击杀(vc89 案指纹)",
                        lastRssBytes / 1073741824.0)
                    : String.format(java.util.Locale.US,
                        "[内存告警解除] 进程组 RSS %.2fGB", lastRssBytes / 1073741824.0));
        }
        // v1.4.0: bmoe engine has no HTTP /metrics — fold stream-side tps in
        if (m != null && m.isBmoeRunning()) {
            BmoeAdapter ad = m.bmoeAdapter();
            if (ad != null) {
                double t = ad.tokPerSec();
                sm.tps = (float) (t > 0 ? t : -1);
            }
        }
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
     *  While STARTING it re-arms: ocl cold load can exceed 120s (JIT, spec).
     *  watchdogShouldStop 纯函数 → JVM 直测。 */
    static final class Watchdog implements Runnable {
        private final java.lang.ref.WeakReference<ServerService> ref;
        Watchdog(ServerService s) { ref = new java.lang.ref.WeakReference<>(s); }
        @Override public void run() {
            ServerService s = ref.get();
            if (s == null) return;
            LlamaServerManager m = LlamaServerManager.peek();
            if (watchdogShouldStop(m == null ? -1 : m.stateCode())) {
                s.stopSelf();
                return;
            }
            if (m != null && m.isRunning()) return;   // healthy — watchdog done
            s.main.postDelayed(new Watchdog(s), WATCHDOG_MS);
        }
    }

    /**
     * watchdog 自杀决策（纯函数）。vc90: worker 参数随 RPC 集群档移除。
     * @param managerState LlamaServerManager manager 状态（-1 = manager 不存在）
     * manager 给出终态（idle/error）→ 停；否则续命。
     */
    static boolean watchdogShouldStop(int managerState) {
        if (managerState < 0) return true;                       // no manager at all
        // LlamaServerManager 实值：ST_READY=0 / ST_STARTING=1 / ST_RUNNING=2 / ST_ERROR=3
        boolean idleOrError = managerState == ST_READY_MIRROR || managerState == ST_ERROR_MIRROR;
        return idleOrError;
    }
    // manager 状态 int 镜像（必须与 LlamaServerManager.ST_READY/ST_ERROR 同值）
    static final int ST_READY_MIRROR = 0, ST_ERROR_MIRROR = 3;

    /** d8-safe notifier: mirrors MainActivity.UiForwarder pattern. */
    static final class NotifierImpl implements LlamaServerManager.Notifier {
        private final java.lang.ref.WeakReference<ServerService> ref;
        NotifierImpl(ServerService s) { ref = new java.lang.ref.WeakReference<>(s); }
        @Override public void onState(int state, String msg) {
            ServerService s = ref.get();
            if (s == null) return;
            if (state == LlamaServerManager.ST_RUNNING) { s.publish(null); s.startTicker(); }
            else if (state == LlamaServerManager.ST_READY) { s.stopTicker(); s.publishIdle(); }
            // v1.3.7: failed startup must not leave a zombie "starting"
            // notification hanging until the watchdog fires.
            else if (state == LlamaServerManager.ST_ERROR) { s.stopTicker(); s.stopSelf(); }
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
                CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("llama-server 运行状态");
        ch.setShowBadge(false);
        // visible on the lock screen (was omitted before → PRIVATE fallback)
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
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
            // v1.3.7: quiet-stop ONLY when truly idle/error (or no manager).
            // The bridge-start flow starts the manager (ST_STARTING) and then
            // starts this FGS immediately; the old blanket stopSelf() killed
            // the service seconds before ST_RUNNING arrived — the "notification
            // never shows" bug. STARTING keeps the FGS alive with the starting
            // notification; NotifierImpl promotes it on ST_RUNNING.
            // v1.3.7 vc67: idle FGS (standby card) must NOT self-kill — the
            // notification is the "service not started" state the user asked
            // for. startServer clears it by pushing ST_STARTING.
            publishIdle();          // v1.3.7 vc67: idle card — user launches the
                                    // app and the notification is already there
            return START_STICKY;
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
     * v1.3.7: pure decision for the non-reborn start. Quiet-stop only when
     * the manager is truly idle/error; STARTING is a legitimate phase (model
     * load takes seconds) and must keep the FGS alive — self-stopping there
     * killed the notification before ST_RUNNING ever arrived.
     * vc67: onStartCommand no longer calls this — the idle FGS stays alive on
     * purpose (standby notification). Kept as the tested policy function for
     * future callers.
     */
    static boolean quietStop(boolean managerStarting) {
        return !managerStarting;
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
                    threadsOf(a), noKvOf(a), ubatchOf(a), sleepIdleOf(a),
                    kvSelOf(a), pmModeOf(a), false,
                    Boolean.parseBoolean(a[9]),
                    Boolean.parseBoolean(a[10]) ? KeyStoreBox.ensureLocal(app) : null,
                    cliArgsOf(a));
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
            "model", "profile", "port", "ctx", "reasoning", "threads", "noKv", "ubatch", "sleepIdle",
            "pubOn", "keyOn", "cliArgs", "kvSel", "pmMode"};

    static boolean hasRebornArgs(Context c) {
        return c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE)
                .contains(REBORN_KEYS[0]);
    }

    static void saveRebornArgs(Context c, String model, String profile, int port,
                               int ctx, String reasoning, int threads, boolean noKv, int ubatch,
                               int sleepIdle, boolean pubOn, boolean keyOn, String cliArgs,
                               String kvSel, String pmMode) {
        c.getSharedPreferences(REBORN_PREFS, Context.MODE_PRIVATE).edit()
                .putString(REBORN_KEYS[0], model)
                .putString(REBORN_KEYS[1], profile)
                .putInt(REBORN_KEYS[2], port)
                .putInt(REBORN_KEYS[3], ctx)
                .putString(REBORN_KEYS[4], reasoning)
                .putInt(REBORN_KEYS[5], threads)
                .putBoolean(REBORN_KEYS[6], noKv)
                .putInt(REBORN_KEYS[7], ubatch)
                .putInt(REBORN_KEYS[8], sleepIdle)
                .putBoolean(REBORN_KEYS[9], pubOn)
                .putBoolean(REBORN_KEYS[10], keyOn)
                .putString(REBORN_KEYS[11], cliArgs == null ? "" : cliArgs)
                .putString(REBORN_KEYS[12], kvSel == null ? "" : kvSel)
                .putString(REBORN_KEYS[13], pmMode == null ? "" : pmMode)
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
                String.valueOf(p.getInt(REBORN_KEYS[7], 0)),
                String.valueOf(p.getInt(REBORN_KEYS[8], 0)),
                String.valueOf(p.getBoolean(REBORN_KEYS[9], false)),
                String.valueOf(p.getBoolean(REBORN_KEYS[10], false)),
                p.getString(REBORN_KEYS[11], ""),
                p.getString(REBORN_KEYS[12], ""),
                p.getString(REBORN_KEYS[13], "")};
    }

    // arg-index helpers keep adoptOrReborn readable
    private static int portOf(String[] a) { return Integer.parseInt(a[2]); }
    private static int ctxOf(String[] a) { return Integer.parseInt(a[3]); }
    private static int threadsOf(String[] a) { return Integer.parseInt(a[5]); }
    private static boolean noKvOf(String[] a) { return Boolean.parseBoolean(a[6]); }
    private static int ubatchOf(String[] a) { return Integer.parseInt(a[7]); }
    private static int sleepIdleOf(String[] a) { return Integer.parseInt(a[8]); }
    private static String cliArgsOf(String[] a) { return a[11] == null ? "" : a[11]; }
    private static String kvSelOf(String[] a) { return a[12] == null ? "" : a[12]; }
    private static String pmModeOf(String[] a) { return a[13] == null ? "" : a[13]; }


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
        String ip = lanIp();    // v1.3.7 vc67: show IP:port, not just the port
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
                .setOnlyAlertOnce(true)   // v1.3.7 vc67: stat updates must not re-vibrate
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", spi).build());

        if (sm == null) {
            b.setContentTitle("LLM Server 运行中 " + ip + ":" + port)
             .setContentText("局域网端点已就绪");
            lastBody = "run:" + ip + ":" + port;
            startForegroundGuarded(b.build());
            return;
        }
        StringBuilder l1 = new StringBuilder();
        if (sm.cpuPct >= 0) l1.append("CPU ").append(Math.round(sm.cpuPct)).append("%  ");
        if (sm.gpuPct >= 0) l1.append("GPU ").append(Math.round(sm.gpuPct)).append("%  ");
        if (sm.ramUsedGB >= 0) l1.append("RAM ").append(String.format(java.util.Locale.US, "%.1f", sm.ramUsedGB)).append('/').append(sm.ramTotalGB).append("G");
        StringBuilder l2 = new StringBuilder();
        if (sm.tempC >= 0) l2.append("温度 ").append(String.format(java.util.Locale.US, "%.1f", sm.tempC)).append("°C  ");
        if (sm.tps > 0) l2.append("输出 ").append(String.format(java.util.Locale.US, "%.1f", sm.tps)).append(" t/s  ");
        if (sm.conns >= 0) l2.append("连接 ").append(sm.conns);
        if (memAlarmOn) l2.append("  ⚠ 内存过高，建议降上下文");
        b.setContentTitle("LLM Server " + ip + ":" + port + (sm.tps > 0 ? "  ⚡" + String.format(java.util.Locale.US, "%.1f", sm.tps) + " t/s" : ""));
        String body = (l1.length() > 0 ? l1.toString().trim() + "\n" : "")
                    + (l2.length() > 0 ? l2.toString().trim() : "空闲中");
        Notification.BigTextStyle st = new Notification.BigTextStyle()
                .bigText(body);
        b.setStyle(st).setContentText(body.replace('\n', ' '));
        // v1.3.7 vc67: re-notifying an ongoing notification with identical
        // content re-fires alerting (vibration) on Samsung — skip when the
        // rendered text hasn't changed.
        String sig = ip + ":" + port + "|" + body;
        if (sig.equals(lastBody)) return;
        lastBody = sig;
        startForegroundGuarded(b.build());
    }


    /** v1.3.7 vc67: standby card while the server is NOT started (manager
     *  idle). The notification appears the moment the app starts the FGS and
     *  stays until the user stops the server; tap reopens the app. */
    private void publishIdle() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("LLM Server")
                .setContentText("服务未启动 · 点按打开")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
        if ("idle".equals(lastBody)) return;   // dedupe repeated rebinds
        lastBody = "idle";
        startForegroundGuarded(n);
    }

    /** v1.3.7 vc67: LAN IPv4 for the notification title (WifiManager primary,
     *  NetworkInterface fallback — same logic as Bridge.getLocalIp). */
    private String lanIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." +
                           ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
        } catch (Throwable ignored) {}
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

    /** vc70 FGS hardening: OneUI/A15 can reject startForeground (dataSync
     *  6h/24h quota exhausted, BG-start restriction). Every publish path
     *  goes through this guard — a rejected promotion must NEVER crash the
     *  process (v1.3.8 field failure: crash loop at app start, orphan exec
     *  children). Server itself (exec child) is unaffected; only the
     *  notification/hosting shell degrades. */
    private void startForegroundGuarded(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("ServerService", "startForeground rejected: " + t);
        }
    }

    /** Starting-state notification (no stop action yet). */
    private void publishStarting(String msg) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("LLM Server 启动中…")
                .setContentText(msg == null ? "载入模型与后端初始化" : msg)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        lastBody = null;    // v1.3.7 vc67: next tick must re-emit the live card
        startForegroundGuarded(n);
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
