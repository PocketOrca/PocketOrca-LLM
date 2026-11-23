package com.dawoer.npullm.mcp.tools;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;

import org.json.JSONObject;

/**
 * device.state：L0 零权限，agent 的"出门前看一眼"。
 * 不含前台应用与锁屏态（那两个要 Shizuku，见 S3）。
 */
public final class ToolDeviceState implements McpToolDef.Executor {

    private final Context ctx;

    public ToolDeviceState(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        return new McpToolDef(
                "device.state",
                "设备状态",
                "返回手机电量、充电、温度、存储、内存、网络类型、屏幕亮度与音量、亮屏状态。仅在需要了解设备环境时调用。",
                McpParamCheck.objectSchema(new JSONObject(), null),
                McpToolDef.LEVEL_READ,
                new ToolDeviceState(ctx));
    }

    @Override
    public McpResult exec(JSONObject args) {
        JSONObject out = new JSONObject();
        // 每段独立 try/catch：单个权限缺失只让该字段变成 {"err":...}，
        // 不能整条工具报 INTERNAL（S25 实测：缺 ACCESS_NETWORK_STATE 时整条失败）。
        try { out.put("battery", battery()); } catch (Exception e) { putErr(out, "battery", e); }
        try { out.put("storage", storage()); } catch (Exception e) { putErr(out, "storage", e); }
        try { out.put("mem", mem()); } catch (Exception e) { putErr(out, "mem", e); }
        try { out.put("network", network()); } catch (Exception e) { putErr(out, "network", e); }
        try { out.put("screen", screen()); } catch (Exception e) { putErr(out, "screen", e); }
        try { out.put("volume", volume()); } catch (Exception e) { putErr(out, "volume", e); }
        try {
            out.put("uptime_h", Math.round(SystemClock.elapsedRealtime() / 3600000.0 * 10) / 10.0);
            out.put("model", android.os.Build.MODEL);
            out.put("sdk", android.os.Build.VERSION.SDK_INT);
        } catch (Exception ignored) { }
        return McpResult.json(out);
    }

    private static void putErr(JSONObject out, String key, Exception e) {
        try {
            JSONObject o = new JSONObject();
            o.put("err", e.getClass().getSimpleName());
            out.put(key, o);
        } catch (Exception ignored) { }
    }

    private JSONObject battery() throws Exception {
        JSONObject o = new JSONObject();
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        o.put("level", level);

        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            o.put("temp_c", Math.round(temp / 10.0 * 10) / 10.0);
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
        }
        return o;
    }

    private JSONObject storage() throws Exception {
        JSONObject o = new JSONObject();
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        long total = sf.getTotalBytes();
        long free = sf.getAvailableBytes();
        o.put("total_gb", Math.round(total / 1073741824.0));
        o.put("free_gb", Math.round(free / 1073741824.0));
        return o;
    }

    private JSONObject mem() throws Exception {
        JSONObject o = new JSONObject();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            o.put("avail_mb", mi.availMem / 1048576L);
            o.put("low", mi.lowMemory);
        }
        return o;
    }

    private JSONObject network() throws Exception {
        JSONObject o = new JSONObject();
        String type = "none";
        boolean metered = true;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "wifi";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "cellular";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "ethernet";
                metered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
        }
        o.put("type", type);
        o.put("metered", metered);
        o.put("ssid", ssid());
        return o;
    }

    /** Android 10+ 拿 SSID 需要定位权限；拿不到就是 null，别让 agent 误判"没连 WiFi"。 */
    @SuppressWarnings("deprecation")
    private String ssid() {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) return null;
            String s = wm.getConnectionInfo().getSSID();
            if (s == null || s.contains("unknown") || s.equals("0x")) return null;
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject screen() throws Exception {
        JSONObject o = new JSONObject();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        o.put("on", pm != null && pm.isInteractive());
        int br = -1;
        try {
            br = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        } catch (Exception ignored) { }
        o.put("brightness", br);
        return o;
    }

    private JSONObject volume() throws Exception {
        JSONObject o = new JSONObject();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            o.put("media", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            o.put("ring", am.getStreamVolume(AudioManager.STREAM_RING));
        }
        return o;
    }
}
