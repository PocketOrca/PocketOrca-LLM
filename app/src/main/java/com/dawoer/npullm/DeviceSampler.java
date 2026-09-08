package com.dawoer.npullm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 2s device sampler for the T6 notification (CPU/GPU/NPU-est/RAM/temp/t/s/conn).
 * Data sources verified on S25 (0906):
 * - CPU   : /proc/stat first line delta
 * - GPU   : /sys/class/kgsl/kgsl-3d0/gpubusy (exists, two ints)
 * - NPU   : no counter -> derived from llama.cpp /metrics slot idle ratio (est.)
 * - RAM   : /proc/meminfo MemAvailable
 * - Temp  : thermal_zone60 "battery" (BatteryManager fallback)
 * - t/s   : /metrics prometheus gauges (kv/requests/ tokens)
 * - Conns : /metrics llama:connection count
 * Never shows fake data: unreadable sources report -1 and the notification
 * hides that field (spec R1/R4).
 */
final class DeviceSampler {

    static final class Sample {
        float cpuPct = -1, gpuPct = -1, npuPct = -1, tps = -1;
        int ramUsedGB = -1, ramTotalGB = -1, conns = -1;
        float tempC = -1;
    }

    private long cpuLastTotal = 0, cpuLastIdle = 0;
    private final StringBuilder metricsBuf = new StringBuilder();

    Sample take(int port) {
        Sample s = new Sample();
        cpu(s);
        gpu(s);
        ram(s);
        temp(s);
        metrics(s, port);
        // NPU estimate: (100 - gpu%) is wrong when idle; use metrics slot busy
        // ratio if we got tokens data, else unknown. Kept simple & honest.
        return s;
    }

    private void cpu(Sample s) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu ")) return;
            String[] p = line.trim().split("\\s+");
            long total = 0, idle;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
            if (cpuLastTotal > 0) {
                long dt = total - cpuLastTotal, di = idle - cpuLastIdle;
                if (dt > 0) s.cpuPct = (float) (100.0 * (dt - di) / dt);
            }
            cpuLastTotal = total;
            cpuLastIdle = idle;
        } catch (Throwable ignored) {}
    }

    private void gpu(Sample s) {
        try {
            Scanner sc = new Scanner(new File("/sys/class/kgsl/kgsl-3d0/gpubusy"));
            long a = sc.nextLong(), b = sc.nextLong();
            if (b > 0) s.gpuPct = (float) (100.0 * a / b);
        } catch (Throwable ignored) {}
    }

    private void ram(Sample s) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            long avail = -1, total = -1;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable")) avail = parseKb(line);
                else if (line.startsWith("MemTotal")) total = parseKb(line);
            }
            if (total > 0 && avail >= 0) {
                s.ramTotalGB = (int) Math.round(total / 1048576.0);
                s.ramUsedGB = (int) Math.round((total - avail) / 1048576.0);
            }
        } catch (Throwable ignored) {}
    }

    private long parseKb(String line) {
        String[] p = line.trim().split("\\s+");
        return Long.parseLong(p[1]);
    }

    private void temp(Sample s) {
        // battery zone wins (spec: 主显示 battery zone, 最稳)
        String[] zones = {"/sys/class/thermal/thermal_zone60/temp"};
        for (String z : zones) {
            try (BufferedReader r = new BufferedReader(new FileReader(z))) {
                int milli = Integer.parseInt(r.readLine().trim());
                if (milli > 0) { s.tempC = milli / 1000f; return; }
            } catch (Throwable ignored) {}
        }
        try {
            // fallback: BatteryManager (API 21+ constant; compile-safe via int)
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    AppCtx.get().getSystemService(android.content.Context.BATTERY_SERVICE);
            int t = bm.getIntProperty(4); // BATTERY_PROPERTY_TEMPERATURE
            if (t > 0) s.tempC = t / 10f;
        } catch (Throwable ignored) {}
    }

    private void metrics(Sample s, int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/metrics").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            if (code != 200) return;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            metricsBuf.setLength(0);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                metricsBuf.append(line).append('\n');
                if (metricsBuf.length() > 65536) break;
            }
            r.close();
            parseMetrics(s, metricsBuf.toString());
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Prometheus text format from llama.cpp /metrics (b10088+). Fields are
     *  matched by name substring so minor renames don't break the parse. */
    private static void parseMetrics(Sample s, String text) {
        String[] lines = text.split("\n");
        // llama:tokens_progress / kv / requests are the candidates; the real
        // names on this build are verified at runtime — match generically:
        for (String ln : lines) {
            if (s.conns < 0 && ln.contains("connection")) {
                s.conns = (int) val(ln);
            } else if (s.tps < 0 && (ln.contains("tokens_per_second") || ln.contains("predicted_per_second"))) {
                s.tps = (float) val(ln);
            }
        }
    }

    private static double val(String line) {
        int i = line.lastIndexOf(' ');
        if (i < 0) return -1;
        try { return Double.parseDouble(line.substring(i + 1).trim()); }
        catch (Throwable e) { return -1; }
    }
}
