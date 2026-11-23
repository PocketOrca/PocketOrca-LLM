package com.dawoer.npullm.mcp.transport;

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
import com.dawoer.npullm.mcp.McpConfig;
import com.dawoer.npullm.mcp.McpRateLimit;
import com.dawoer.npullm.mcp.McpServer;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpToolRegistry;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.shizuku.ShizukuShell;
import com.dawoer.npullm.mcp.access.Permissions;
import com.dawoer.npullm.mcp.tools.ToolEvents;
import com.dawoer.npullm.mcp.tools.ToolTts;
import com.dawoer.npullm.mcp.tools.ToolCallLog;
import com.dawoer.npullm.mcp.tools.ToolContacts;
import com.dawoer.npullm.mcp.tools.ToolDeviceState;
import com.dawoer.npullm.mcp.tools.ToolMessageSend;
import com.dawoer.npullm.mcp.tools.ToolNotifications;
import com.dawoer.npullm.mcp.tools.ToolSms;
import com.dawoer.npullm.mcp.tools.ToolApps;
import com.dawoer.npullm.mcp.tools.ToolSettings;
import com.dawoer.npullm.mcp.tools.ToolDiag;
import com.dawoer.npullm.mcp.tools.ToolUi;
import com.dawoer.npullm.mcp.tools.ToolAudio;
import com.dawoer.npullm.mcp.diag.McpTraceFile;
import com.dawoer.npullm.mcp.shell.ShellClient;

/**
 * MCP 常驻服务：跑在 :mcp 独立进程，FGS type = specialUse（不受 dataSync 6h/24h 限制）。
 * 与 :tts/:asr/:rag 同构：一能力面 = 一进程 = 一端口 = 一 key。
 */
public final class McpForegroundService extends Service {

    private static final String TAG = "PoMcpSvc";
    private static volatile boolean running = false;

    private McpHttpServer server;
    private McpServer core;

    public static boolean isRunning() {
        return running;
    }

    /** UI 侧拉起（main 进程调用；服务本身跑在 :mcp）。 */
    public static void start(Context ctx) {
        if (ctx == null) return;
        Intent i = new Intent(ctx, McpForegroundService.class);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            Log.e(TAG, "start failed: " + t.getClass().getSimpleName());
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, McpForegroundService.class));
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
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null || !server.isAlive()) startServer();
        // 权限可能在 UI 侧刚被授予/撤销：每次 start 刷新快照（tools/list 本身实时查，不依赖这个快照）
        if (core != null) core.setShizukuState(ShellClient.state(this));
        return START_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // 硬约束：通道 importance 首次创建即冻结，改 importance 必须换 CHANNEL_ID
        NotificationChannel ch = new NotificationChannel(
                McpConfig.CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("对外提供 MCP 接口，供 PC 上的 agent 调用手机能力");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, McpConfig.CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("MCP 服务运行中")
                .setContentText("端口 " + McpConfig.PORT + " · 仅供局域网内已配对客户端使用")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(McpConfig.NOTIF_ID, b.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(McpConfig.NOTIF_ID, b.build());
        }
    }

    private void startServer() {
        String key = McpConfig.ensureKey(this);
        if (key == null) {
            Log.e(TAG, "key 生成失败，服务不启动");
            return;
        }

        McpRateLimit rate = McpRateLimit.defaults();
        // vc186：trace 落盘（滚动 200KB）。logcat 在 release 包看不到 tag、还会被系统挤掉，
        // 没有文件就没有「静默失败率=0」的事实来源。参数只存 hash，正文/号码不进日志。
        McpTraceFile traceFile = new McpTraceFile(
                new java.io.File(getFilesDir(), "mcp"), ToolDiag.TRACE_NAME,
                McpTraceFile.DEFAULT_MAX_BYTES);
        McpTrace trace = new McpTrace(new LogSink(), traceFile);
        McpToolRegistry reg = new McpToolRegistry(rate, trace);

        // S1 工具：零运行时权限（通知使用权是一次性开关，不是运行时弹窗）
        reg.register(ToolDeviceState.def(this));
        reg.register(ToolNotifications.def(this, trace));
        // S2 工具：L2 运行时权限，闸门=实时 checkSelfPermission，未授权不出现在 tools/list
        reg.register(ToolSms.def(this, trace));
        reg.register(ToolCallLog.def(this));
        reg.register(ToolContacts.def(this));
        reg.register(ToolMessageSend.def(this, trace));   // 不可逆 + scope，默认隐藏
        // S3 工具：L3 = shell uid 2000，闸门=Shizuku ready（未激活时不进 tools/list）
        reg.register(ToolApps.defLaunch(this, trace));
        reg.register(ToolApps.defForeground(this, trace));
        reg.register(ToolSettings.defGet(this, trace));
        reg.register(ToolSettings.defSet(this, trace));
        // vc184 S4：GUI 三件套，闸门 = Shizuku ready + scope ui.control（默认关，开了才进 tools/list）
        reg.register(ToolUi.defDump(this, trace));
        reg.register(ToolUi.defTap(this, trace));
        reg.register(ToolUi.defSwipe(this, trace));
        // vc185 S5：outbox 事实源 + 让手机开口（无其中一个，"收到短信→手机主动说"就断链）
        reg.register(ToolEvents.def(this));
        reg.register(ToolTts.def(this));
        // vc190：音量（不是 settings key，只能走 AudioManager / media_session）
        reg.register(ToolAudio.def(this));
        // vc186：环境自诊（L0 只读）。agent 失败后先看它，不要靠猜、不要盲目重试。
        reg.register(ToolDiag.def(this, reg));

        ShizukuShell.init(this);

        core = new McpServer(key, McpConfig.allowOrigins(this), reg, trace, rate,
                "PocketOrca-LLM", "1.5.2");
        core.setShizukuState(ShellClient.state(this));
        core.setPermSource(new PermSnap(this));
        core.setDiagSource(new DiagSnap(this, reg));

        // 通知使用权：能用 Shizuku 就直接开，省掉"允许受限制的设置"四步
        tryAutoGrantListener();

        server = new McpHttpServer(McpConfig.PORT, core);
        if (server.startServer()) {
            running = true;
            Log.i(TAG, "MCP listening on " + McpConfig.PORT + " tools=" + reg.visibleCount());
        } else {
            Log.e(TAG, "端口 " + McpConfig.PORT + " 启动失败");
        }
    }

    /** /health 的权限摘要：每次请求实时算（快照会在用户刚授权/撤权时说谎）。命名静态嵌套（d8 铁律）。 */
    private static final class PermSnap implements McpServer.PermSource {
        private final Context ctx;

        PermSnap(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }

        @Override
        public String json() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("sms", Permissions.granted(ctx, Permissions.READ_SMS));
                o.put("calllog", Permissions.granted(ctx, Permissions.READ_CALL_LOG));
                o.put("contacts", Permissions.granted(ctx, Permissions.READ_CONTACTS));
                o.put("send_sms", Permissions.granted(ctx, Permissions.SEND_SMS));
            } catch (Exception ignored) { }
            return o.toString();
        }
    }

    /** /diagnose 的数据源：与 device.diagnose 工具共用 ToolDiag.build，两边永不分叉。 */
    private static final class DiagSnap implements McpServer.DiagSource {
        private final Context ctx;
        private final McpToolRegistry reg;

        DiagSnap(Context ctx, McpToolRegistry reg) {
            this.ctx = ctx.getApplicationContext();
            this.reg = reg;
        }

        @Override
        public String json(int lines, boolean deep) {
            try {
                return ToolDiag.build(ctx, reg, lines, deep).toString();
            } catch (Throwable t) {
                return "{\"verdict\":\"blocked\",\"error\":\"诊断异常: " + t.getClass().getSimpleName() + "\"}";
            }
        }
    }

    private void tryAutoGrantListener() {
        String comp = getPackageName() + "/" + getPackageName() + ".mcp.access.NotificationCollector";
        if (ShizukuShell.isReady()) {
            boolean ok = ShizukuShell.allowNotificationListener(comp);
            Log.i(TAG, "allow_listener=" + ok);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (server != null) server.stop();
        } catch (Exception ignored) { }
        server = null;
        core = null;
        running = false;
        super.onDestroy();
    }

    /** 命名静态嵌套（d8 零匿名类铁律）。 */
    private static final class LogSink implements McpTrace.Sink {
        @Override
        public void line(String msg) {
            Log.i(TAG, msg);
        }
    }
}
