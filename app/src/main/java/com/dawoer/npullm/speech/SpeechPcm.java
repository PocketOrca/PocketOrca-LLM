package com.dawoer.npullm.speech;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * vc167: 音频解码/重采样（D4 —— 「对外提供接口」的关键）。
 *
 * sherpa OfflineRecognizer 只吃 16 kHz mono float PCM；外部客户端发的是
 * wav / mp3 / m4a / webm。三种来源统一到这里：
 *   1. 页内录音：Recorder 直接 16k mono PCM16（零转换）
 *   2. 外部上传：decodeToPcm() —— MediaExtractor + MediaCodec 解码 → 线性插值重采样到 16k
 *   3. wav 直传：readWav() —— 解析 RIFF，已是 16k mono 则零拷贝
 */
public final class SpeechPcm {

    public static final int TARGET_RATE = 16000;

    private SpeechPcm() {}

    /** 任意容器 → 16k mono float PCM。失败抛异常（带原因）。 */
    public static float[] decode(Context c, File f) throws Exception {
        if (f == null || !f.isFile()) throw new Exception("音频文件不存在");
        if (f.length() > 64L * 1024 * 1024) throw new Exception("音频过大（上限 64MB）");
        // 先试 wav（RIFF 头）：16k mono 直接复用，其它采样率/双声道就地转换
        float[] wav = readWav(f);
        if (wav != null) return wav;
        return decodeMedia(f);
    }

    /** RIFF/WAVE 解析；不是 wav 返回 null。只支持 PCM 16bit（录音与常见客户端导出）。 */
    public static float[] readWav(File f) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] head = new byte[12];
            if (!readFully(in, head, 12)) return null;
            if (!"RIFF".equals(new String(head, 0, 4, "US-ASCII"))) return null;
            if (!"WAVE".equals(new String(head, 8, 4, "US-ASCII"))) return null;
            int channels = 1;
            int rate = 16000;
            int bits = 16;
            byte[] data = null;
            byte[] chunk = new byte[8];
            while (readFully(in, chunk, 8)) {
                String id = new String(chunk, 0, 4, "US-ASCII");
                long size = le32(chunk, 4);
                if (size < 0 || size > 128L * 1024 * 1024) return null;
                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[(int) size];
                    if (!readFully(in, fmt, fmt.length)) return null;
                    if (fmt.length < 16) return null;
                    channels = Math.max(1, le16(fmt, 2));
                    rate = (int) le32(fmt, 4);
                    bits = le16(fmt, 14);
                } else if ("data".equals(id)) {
                    data = new byte[(int) size];
                    int got = 0;
                    while (got < data.length) {
                        int r = in.read(data, got, data.length - got);
                        if (r <= 0) break;
                        got += r;
                    }
                    if (got < data.length) {
                        byte[] trim = new byte[got];
                        System.arraycopy(data, 0, trim, 0, got);
                        data = trim;
                    }
                    break;
                } else {
                    skipFully(in, size + (size % 2));
                }
            }
            if (data == null) return null;
            if (bits != 16) throw new Exception("wav 位深 " + bits + " bit 不支持（导出 16bit PCM）");
            return toMonoFloat(data, channels, rate);
        } finally {
            SpeechConfig.closeQuietly(in);
        }
    }

    /** MediaExtractor + MediaCodec 同步解码 → 16k mono float。 */
    public static float[] decodeMedia(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = mf;
                    break;
                }
            }
            if (track < 0) throw new Exception("文件里没有音频轨（支持 wav/mp3/m4a/webm/ogg）");
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            List<byte[]> out = new ArrayList<byte[]>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eosIn = false;
            while (true) {
                if (!eosIn) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosIn = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (info.size > 0 && ob != null) {
                        byte[] b = new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.get(b);
                        out.add(b);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
                if (eosIn && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            }
            int total = 0;
            for (byte[] b : out) total += b.length;
            if (total == 0) throw new Exception("解码后没有音频数据");
            byte[] pcm = new byte[total];
            int off = 0;
            for (byte[] b : out) {
                System.arraycopy(b, 0, pcm, off, b.length);
                off += b.length;
            }
            return toMonoFloat(pcm, channels, rate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignore) {}
                try { codec.release(); } catch (Throwable ignore) {}
            }
            try { ex.release(); } catch (Throwable ignore) {}
        }
    }

    /** 16bit PCM（可多声道）→ mono float（取各声道均值），再重采样到 16k。 */
    static float[] toMonoFloat(byte[] pcm, int channels, int rate) {
        int ch = Math.max(1, channels);
        int frames = pcm.length / (2 * ch);
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            float sum = 0f;
            for (int c = 0; c < ch; c++) {
                int off = (i * ch + c) * 2;
                int lo = pcm[off] & 0xff;
                int hi = pcm[off + 1];
                int s = (hi << 8) | lo;
                sum += s / 32768f;
            }
            mono[i] = sum / ch;
        }
        return resample(mono, rate, TARGET_RATE);
    }

    /**
     * vc176 修复：降采样必须先做抗混叠滤波。
     * 旧实现（vc167）直接线性插值—— 48k→16k 时 8kHz 以上频谱（中文声母 s/x/q/c 能量区）折叠进语音带，
     * sensevoice 实测大量丢字。修法 = 整数倍降采样先取 M 点均值（盒式低通），非整数倍先盒式滤波到就近整数倍率再线性插值。
     * 端到端 A/B 实证（1112，S25 sensevoice）：48k 中文线性插值丢 3 字，盒式一字不差。
     */
    public static float[] resample(float[] in, int from, int to) {
        if (in == null) return new float[0];
        if (from <= 0 || to <= 0 || from == to) return in;
        if (from > to) {
            int m = from / to;
            if (m >= 2) {
                int frames = in.length / m;
                if (frames > 0) {
                    // 两级盒式（≈三角波低通）后跨步抽取：单盒对靠近旧 Nyquist 的成分只有 ~1/3 衰减，两级后 ~1/9
                    float[] b1 = boxRun(in, m);
                    float[] b2 = boxRun(b1, m);
                    float[] dec = new float[frames];
                    for (int i = 0; i < frames; i++) dec[i] = b2[i * m];
                    if (m * to == from) return dec;
                    return linear(dec, frames, to);
                }
            }
        }
        return linear(in, from, to);
    }

    /** 盒式滤波：每点取前后 m/2 邻域均值（零相位，长度不变）。 */
    private static float[] boxRun(float[] in, int m) {
        int half = m / 2;
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            double s = 0;
            int n = 0;
            for (int j = i - half; j <= i + half; j++) {
                if (j >= 0 && j < in.length) { s += in[j]; n++; }
            }
            out[i] = (float) (s / n);
        }
        return out;
    }

    /** 旧线性插值主体（vc167 原样保留，供升采样与非整数倍尾段复用）。 */
    private static float[] linear(float[] in, int from, int to) {
        int n = (int) Math.round(in.length * (double) to / from);
        if (n <= 0) return new float[0];
        float[] out = new float[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            if (i0 >= in.length) {
                out[i] = 0f;
            } else {
                out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
            }
        }
        return out;
    }

    /** float PCM → 16bit little-endian 字节。 */
    public static byte[] toBytes(float[] pcm) {
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            float v = pcm[i];
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            int s = (int) (v * 32767f);
            b[i * 2] = (byte) (s & 0xff);
            b[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }

    /** 16bit PCM WAV 落盘（16k mono）。 */
    public static void writeWav16k(File out, byte[] pcm) throws Exception {
        SpeechTts.writeWavPcm16(out, pcm, TARGET_RATE);
    }

    // ===== 小工具 =====

    static boolean readFully(InputStream in, byte[] b, int len) throws Exception {
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, len - got);
            if (r <= 0) break;
            got += r;
        }
        return got == len;
    }

    static void skipFully(InputStream in, long n) throws Exception {
        long left = n;
        byte[] junk = new byte[4096];
        while (left > 0) {
            int r = in.read(junk, 0, (int) Math.min(junk.length, left));
            if (r <= 0) return;
            left -= r;
        }
    }

    static int le16(byte[] b, int off) {
        return ((b[off + 1] & 0xff) << 8) | (b[off] & 0xff);
    }

    static long le32(byte[] b, int off) {
        return ((long) (b[off + 3] & 0xff) << 24) | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 1] & 0xff) << 8) | (b[off] & 0xff);
    }
}
