package com.dawoer.npullm.speech;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * vc167: 页内录音（main 进程，「按住说话」）。
 *
 * 直接吃 16k mono PCM16（零转换）；上限 60s，超时自动停。
 * 录音放在 main 进程而不是 :asr：Activity 前台有权限上下文，录完把 wav 走 HTTP
 * 发给 :asr 识别——与外部客户端完全同一条路径（UI 通＝接口通）。
 */
public final class SpeechRecorder {

    public static final int RATE = 16000;
    private static final int MAX_MS = 60000;
    private static final int BUF_BYTES = MAX_MS / 1000 * RATE * 2;

    private static volatile boolean recording;
    private static volatile int level;
    private static long startedAt;
    private static AudioRecord ar;
    private static Thread worker;
    private static final ByteArrayOutputStream BUF = new ByteArrayOutputStream(BUF_BYTES);

    private SpeechRecorder() {}

    /** 没权限 → 返回提示文字；有权限则开始录音返回 null。 */
    public static String start(Context c) {
        if (c == null) return "未初始化";
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return "no_permission";
        }
        synchronized (SpeechRecorder.class) {
            if (recording) return null;
            BUF.reset();
            level = 0;
            startedAt = System.currentTimeMillis();
            recording = true;
            Thread t = new Thread(new Worker(), "speech-rec");
            t.setDaemon(true);
            worker = t;
            t.start();
            return null;
        }
    }

    /** 停止并落 wav → 返回路径；失败/没在录 → null。 */
    public static String stop(Context c) {
        Thread t;
        synchronized (SpeechRecorder.class) {
            if (!recording) return null;
            recording = false;
            t = worker;
            worker = null;
        }
        if (t != null) {
            try { t.join(2000); } catch (Throwable ignore) {}
        }
        if (c == null) return null;
        byte[] pcm;
        synchronized (BUF) {
            pcm = BUF.toByteArray();
            BUF.reset();
        }
        if (pcm.length < RATE / 4) return null;   // <125ms 视为误触
        try {
            File dir = new File(c.getCacheDir(), "asr_in");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File wav = new File(dir, "rec-" + System.currentTimeMillis() + ".wav");
            SpeechPcm.writeWav16k(wav, pcm);
            return wav.getPath();
        } catch (Throwable t2) {
            return null;
        }
    }

    /** {"recording":bool,"ms":N,"level":0..100} */
    public static String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"recording\":").append(recording);
        sb.append(",\"ms\":").append(recording ? (System.currentTimeMillis() - startedAt) : 0);
        sb.append(",\"level\":").append(level).append("}");
        return sb.toString();
    }

    public static boolean recording() {
        return recording;
    }

    public static int ms() {
        return recording ? (int) (System.currentTimeMillis() - startedAt) : 0;
    }

    private static final class Worker implements Runnable {
        @Override public void run() {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int size = Math.max(min, RATE / 10 * 2);   // ≥100ms
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2);
                ar = rec;
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    recording = false;
                    return;
                }
                rec.startRecording();
                byte[] b = new byte[RATE / 10 * 2];
                int peak = 0;
                while (recording) {
                    int n = rec.read(b, 0, b.length);
                    if (n <= 0) continue;
                    synchronized (BUF) {
                        if (BUF.size() + n > BUF_BYTES) {
                            recording = false;
                            break;
                        }
                        BUF.write(b, 0, n);
                    }
                    int p = 0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int s = Math.abs((short) ((b[i + 1] << 8) | (b[i] & 0xff)));
                        if (s > p) p = s;
                    }
                    peak = Math.max(p, (int) (peak * 0.7f));
                    level = Math.min(100, peak * 100 / 32768);
                }
                level = 0;
            } catch (Throwable ignore) {
                recording = false;
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Throwable ignore) {}
                    try { rec.release(); } catch (Throwable ignore) {}
                }
                ar = null;
            }
        }
    }
}
