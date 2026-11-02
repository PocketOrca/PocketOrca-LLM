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
 * - Temp  : first thermal zone with type==battery (cached) + 5..65C sanity
 *           window, BatteryManager fallback (vc72: zone idx is per-device)
 * - t/s   : /metrics prometheus gauges (kv/requests/ tokens)
 * - Conns : /metrics llama:connection count
 * Never shows fake data: unreadable sources report -1 and the notification
 * hides that field (spec R1/R4).
 */
final class DeviceSampler {

    static final class Sample {
        float cpuPct = -1, gpuPct = -1, npuPct = -1, tps = -1;
        // v1.3.7 vc67: RAM as float GB — "10/11G" read confused (used value
        // rounded up while total rounded down on the same device).
        float ramUsedGB = -1;
        int ramTotalGB = -1, conns = -1;
        float tempC = -1;
    }

    private long cpuLastTotal = 0, cpuLastIdle = 0;
    private final StringBuilder metricsBuf = new StringBuilder();
    // v1.3.7 vc67: 0.5°C quantization — the thermal zone jitters ±0.1–0.3°C
    // per 2s tick, which used to change the notification text every tick and
    // re-fire the alerting (vibration) each time.
    private float lastTempC = -100f;

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
                // v1.3.7 vc68: denominator must match the spec-sheet number —
                // reuse the UI's marketing-GB mapper (Bridge.ramLabelGiB,
                // v1.2.14) so notification and app card agree: 11.66 dec GB
                // -> "12G", not binary-GiB round/ceil (11). Numerator keeps
                // the one-decimal GiB the user already approved.
                // vc68 hotfix: meminfo `total` is KiB; ramLabelGiB expects
                // BYTES (passing KiB raw -> ~0.011 "GB" -> 0G on device).
                s.ramTotalGB = MainActivity.Bridge.ramLabelGiB(total * 1024L);
                s.ramUsedGB = (float) ((total - avail) / 1048576.0);
            }
        } catch (Throwable ignored) {}
    }

    private long parseKb(String line) {
        String[] p = line.trim().split("\\s+");
        return Long.parseLong(p[1]);
    }

    // vc72: thermal zone indexes are device-specific — hard-coded
    // thermal_zone60 is "battery" on S25 but an unrelated ~2.5°C sensor on
    // S23U (fake notification temp). Resolve by type name instead, once,
    // sanity-check every reading; bad readings fall back to BatteryManager,
    // unreadable -> -1 -> field hidden (never fake data).
    private File zoneTempFile = null;
    private boolean zoneResolved = false;

    private void temp(Sample s) {
        File f = batteryZoneTempFile();
        if (f != null) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                int milli = Integer.parseInt(r.readLine().trim());
                float c = milli / 1000f;
                if (plausibleTemp(c)) { s.tempC = quantize(c); return; }
            } catch (Throwable ignored) {}
        }
        try {
            // fallback: BatteryManager (API 21+ constant; compile-safe via int)
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    AppCtx.get().getSystemService(android.content.Context.BATTERY_SERVICE);
            int t = bm.getIntProperty(4); // BATTERY_PROPERTY_TEMPERATURE, 0.1°C
            float c = t / 10f;
            if (plausibleTemp(c)) s.tempC = quantize(c);
        } catch (Throwable ignored) {}
    }

    private File batteryZoneTempFile() {
        if (zoneResolved) return zoneTempFile;
        zoneResolved = true;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return null;
        File best = null;
        for (File z : zones) {
            String ty = readFirstLine(new File(z, "type"));
            if (ty == null) continue;
            ty = ty.trim().toLowerCase(java.util.Locale.US);
            if (!ty.contains("batt")) continue;
            if (ty.equals("battery")) { best = new File(z, "temp"); break; }
            if (best == null) best = new File(z, "temp");
        }
        zoneTempFile = best;
        return zoneTempFile;
    }

    private static String readFirstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Throwable ignored) {}
        return null;
    }

    /** vc72 pure core (JVM-testable): reject readings outside 5..65°C —
     *  a wrong-sensor mapping (S23U zone60 = 2.5°C) must never be shown. */
    static boolean plausibleTemp(float c) {
        return c >= 5f && c <= 65f;
    }

    /** v1.3.7 vc67: snap to 0.5°C buckets — kills per-tick jitter so the
     *  notification text (and its alerting) stays stable between real
     *  temperature moves. */
    private float quantize(float t) {
        float q = quantizeTemp(t, lastTempC);
        lastTempC = q;
        return q;
    }

    /** Pure core (JVM-testable): 0.5°C bucket snapping; unchanged value when
     *  the snapped temperature equals the previous one (dedupe signal). */
    static float quantizeTemp(float t, float last) {
        float q = Math.round(t * 2f) / 2f;
        return q == last ? last : q;
    }

    private void metrics(Sample s, int port) {
        // v1.4.0 note: bmoe engine has no HTTP /metrics — notification tps for the
        // bmoe profile is folded in by ServerService via BmoeAdapter, the HTTP
        // metrics path below stays the llama.cpp-only concern it always was.
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
