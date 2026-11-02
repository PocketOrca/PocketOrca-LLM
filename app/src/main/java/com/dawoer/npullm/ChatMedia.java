package com.dawoer.npullm;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * vc99 (C1): chat multimodal support.
 *
 * <ul>
 *   <li>Media picking (camera / gallery / video / voice memo) writes the bytes
 *       into the app's external files dir "chatmedia/" and returns a compact
 *       JSON descriptor. The WebView layer NEVER holds the base64 payload —
 *       the bridge carries only "media_ref://chatmedia/&lt;id&gt;/&lt;name&gt;" pointers
 *       (WeChat-sized bridges and slow JSON round-trips would choke on
 *       multi-MB strings). Base64 materialization happens once, right before
 *       the HTTP POST, inside {@link #materializeRefs}.</li>
 *   <li>Audio is recorded as PCM16 mono 16 kHz via AudioRecord and wrapped
 *       into a RIFF/WAVE container — MediaRecorder's AAC is NOT understood by
 *       llama.cpp mtmd (miniaudio demuxer: mp3/wav/flac only), plan pitfall
 *       ①. The WAV path needs no permission dialog on any API level.</li>
 *   <li>{@link #materializeRefs} walks an OAI messages array, and for every
 *       user content part whose text is a bare media_ref pointer replaces it
 *       with the proper {"type":"input_audio"|"input_video"|"image_url"}
 *       object carrying a data: URI — the exact shapes llama.cpp
 *       oaicompat_content_load_media() consumes (server-common.cpp:1150).</li>
 *   <li>Media files are wiped when the composing UI resets them via
 *       {@link #deleteRef} (single) or on app start / new chat via
 *       {@link #wipeAll} — nothing accumulates. vc100 semantic: a stale ref
 *       in a send body only degrades that ONE message (ref line dropped,
 *       text kept) — it never blocks the send any more.</li>
 * </ul>
 *
 * d8-safe: no anonymous classes, no enums (hard rule 1). All work runs on the
 * bridge caller thread (WebView JI thread) — cheap file ops only; the big
 * base64 also lives here on purpose: chatStart already hops threads.
 */
final class ChatMedia {

    static final String REF_PREFIX = "media_ref://chatmedia/";

    private ChatMedia() {}

    /** --------------------------------------------------------------- dirs */

    static File mediaDir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "chatmedia");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Recreate the dir from scratch (new chat / app start). */
    static void wipeAll(Context ctx) {
        File d = mediaDir(ctx);
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) k.delete();
    }

    /** -------------------------------------------------------------- picking */

    /**
     * Simplest robust picker flow (no FileProvider dance, no permission
     * dialogs): the caller (MainActivity, already holding the data stream from
     * ACTION_IMAGE_CAPTURE result or a document ACTION_OPEN_DOCUMENT /
     * ACTION_GET_CONTENT result) hands us an InputStream + mime, we snapshot
     * the bytes under chatmedia/. Returns the ref string, null on failure.
     */
    static String store(Context ctx, InputStream in, String mime, String ext) {
        try {
            String id = UUID.randomUUID().toString().substring(0, 8);
            String name = id + "-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ext;
            File out = new File(mediaDir(ctx), name);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            try { in.close(); } catch (Exception ignored) {}
            return REF_PREFIX + name;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ref string → stored file, or null when the ref is stale. */
    static File fileFor(Context ctx, String ref) {
        if (ref == null || !ref.startsWith(REF_PREFIX)) return null;
        String name = ref.substring(REF_PREFIX.length());
        if (name.contains("/") || name.contains("..")) return null; // traversal guard
        File f = new File(mediaDir(ctx), name);
        return f.canRead() ? f : null;
    }

    /** metadata for the JS chip: {ref, kind, mime, size, durMs, name} */
    static String describe(Context ctx, String ref) {
        try {
            File f = fileFor(ctx, ref);
            if (f == null) return "{}";
            JSONObject o = new JSONObject();
            o.put("ref", ref);
            o.put("name", f.getName());
            o.put("size", f.length());
            String mime = mimeOf(f.getName());
            o.put("mime", mime);
            o.put("kind", kindOf(mime));
            if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                long dur = probeDurationMs(ctx, f);
                if (dur > 0) o.put("durMs", dur);
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static void deleteRef(Context ctx, String ref) {
        File f = fileFor(ctx, ref);
        if (f != null) f.delete();
    }

    /** ------------------------------------------------------------- recorder */

    // vc99 plan ① verbatim: AudioRecord emits PCM16 directly — no MediaRecorder
    // (AAC container mtmd cannot demux), no MediaCodec transcode, zero failure
    // surface beyond the read loop. 16 kHz mono s16le = 32 KB/s, a 5-min voice
    // note is ~9.6 MB in RAM during capture — bounded and freed on stop.
    private static final int REC_RATE = 16000;
    private static android.media.AudioRecord recAudio;
    private static Thread recThread;
    private static volatile boolean recRunning;
    private static ByteArrayOutputStream recPcm;
    private static final Object recorderLock = new Object();
    /** set when JS asked to record but the permission dialog is still out. */
    static volatile boolean recordPendingPermission;

    /**
     * Caller must have verified Manifest.permission.RECORD_AUDIO first
     * (MainActivity.chatRecordStart does the grant dance). Returns
     * {"ok":true} or {"error":...} JSON.
     */
    static String startRecording() {
        synchronized (recorderLock) {
            try {
                if (recRunning) return "{\"error\":\"already recording\"}";
                int minBuf = android.media.AudioRecord.getMinBufferSize(REC_RATE,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) return "{\"error\":\"audio init failed\"}";
                android.media.AudioRecord r = new android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC, REC_RATE,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2);
                if (r.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                    r.release();
                    return "{\"error\":\"audio init failed\"}";
                }
                recPcm = new ByteArrayOutputStream(1 << 20);
                recRunning = true;
                r.startRecording();
                recAudio = r;
                final android.media.AudioRecord fr = r;
                recThread = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    while (recRunning) {
                        int n = fr.read(buf, 0, buf.length);
                        if (n > 0) {
                            ByteArrayOutputStream p = recPcm;
                            if (p != null) p.write(buf, 0, n);
                        } else if (n < 0) {
                            break;
                        }
                    }
                }, "chat-rec");
                recThread.start();
                return "{\"ok\":true}";
            } catch (Throwable t) {
                recRunning = false;
                recAudio = null;
                recPcm = null;
                return "{\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
            }
        }
    }

    /** Stop capture + wrap WAV. Returns {"ok":true,"ref":...} or {"error":...}. */
    static String stopRecording(Context ctx, boolean keep) {
        synchronized (recorderLock) {
            try {
                if (!recRunning || recAudio == null) return "{\"error\":\"not recording\"}";
                recRunning = false;
                android.media.AudioRecord r = recAudio;
                recAudio = null;
                try { r.stop(); } catch (Throwable ignored) {}
                r.release();
                try { recThread.join(1500); } catch (Throwable ignored) {}
                recThread = null;
                ByteArrayOutputStream p = recPcm;
                recPcm = null;
                byte[] pcm = p == null ? new byte[0] : p.toByteArray();
                if (!keep || pcm.length == 0) return "{\"ok\":true,\"ref\":null}";
                String id = UUID.randomUUID().toString().substring(0, 8);
                File wav = new File(mediaDir(ctx), id + "-voice.wav");
                writeWav(new FileOutputStream(wav), pcm, REC_RATE, 1, 16);
                return "{\"ok\":true,\"ref\":\"" + REF_PREFIX + wav.getName() + "\"}";
            } catch (Throwable t) {
                recRunning = false;
                recAudio = null;
                recPcm = null;
                return "{\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
            }
        }
    }

    static boolean isRecording() {
        synchronized (recorderLock) { return recRunning; }
    }

    /** RIFF little-endian WAV writer (PCM16). */
    static void writeWav(OutputStream os, byte[] pcm, int rate, int ch, int bits) throws Exception {
        int byteRate = rate * ch * bits / 8;
        int blockAlign = ch * bits / 8;
        int dataLen = pcm.length;
        ByteArrayOutputStream h = new ByteArrayOutputStream(44);
        wAscii(h, "RIFF"); wLe32(h, 36 + dataLen); wAscii(h, "WAVE");
        wAscii(h, "fmt "); wLe32(h, 16); wLe16(h, 1); wLe16(h, ch);
        wLe32(h, rate); wLe32(h, byteRate); wLe16(h, blockAlign); wLe16(h, bits);
        wAscii(h, "data"); wLe32(h, dataLen);
        h.writeTo(os);
        os.write(pcm);
        os.close();
    }

    private static void wAscii(ByteArrayOutputStream o, String s) { for (byte b : s.getBytes()) o.write(b); }
    private static void wLe32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
    private static void wLe16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }

    /** ------------------------------------------------------- ref resolution */

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // camera + gallery default
    }

    private static String kindOf(String mime) {
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        return "audio";
    }

    static String kindFor(Context ctx, String ref) {
        File f = fileFor(ctx, ref);
        if (f == null) return "image";
        return kindOf(mimeOf(f.getName()));
    }

    private static long probeDurationMs(Context ctx, File f) {
        try {
            MediaMetadataRetriever mm = new MediaMetadataRetriever();
            mm.setDataSource(f.getAbsolutePath());
            String d = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            mm.release();
            return d == null ? -1 : Long.parseLong(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** small helper for the test: wrap raw PCM as a wav file */
    static void writeTestWav(File dst, int rate, int seconds) throws Exception {
        byte[] pcm = new byte[rate * 2 * seconds];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) ((i * 7) & 0xFF);
        writeWav(new FileOutputStream(dst), pcm, rate, 1, 16);
    }

    /**
     * vc99 core: walk the OAI messages array; user messages carry attachments
     * as media_ref lines inside the plain-string content (one ref per line,
     * free text around them):
     *   "看这张图\nmedia_ref://chatmedia/ab12-153000.jpg"
     * Each ref line is replaced by a real multimodal content object:
     *   image  → {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
     *   audio  → {"type":"input_audio","input_audio":{"data":"...","format":"wav"}}
     *   video  → {"type":"input_video","input_video":{"data":"...","format":...}}
     * (input_audio: llama.cpp reads "data" first then "url" — server-common.cpp:1174;
     *  input_video: same shape, "data" preferred — server-common.cpp:1188.
     *  image_url: standard {"url": dataURI} — server-common.cpp:1156.)
     * Text lines merge into a single {"type":"text"} part (emitted only when
     * non-empty). Messages without refs pass through untouched.
     * Returns the rebuilt bodyJson, or null when a ref is stale (caller keeps
     * the original — the model will just see the literal text, same as today).
     */
    static String materializeRefs(Context ctx, String bodyJson, boolean remote) {
        try {
            JSONObject body = new JSONObject(bodyJson);
            if (!body.has("messages")) return null;
            JSONArray msgs = body.getJSONArray("messages");
            boolean changed = false;
            JSONArray out = new JSONArray();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.getJSONObject(i);
                if (!"user".equals(m.optString("role")) || !m.has("content")
                        || !(m.get("content") instanceof String)) {
                    out.put(m);
                    continue;
                }
                String c = m.getString("content");
                if (!c.contains(REF_PREFIX)) { out.put(m); continue; }
                JSONArray parts = new JSONArray();
                StringBuilder text = new StringBuilder();
                String[] lines = c.split("\n", -1);
                for (String line : lines) {
                    String t = line.trim();
                    if (!t.startsWith(REF_PREFIX)) {
                        if (t.length() > 0) {
                            if (text.length() > 0) text.append('\n');
                            text.append(t);
                        }
                        continue;
                    }
                    File f = fileFor(ctx, t);
                    // vc100: stale ref (media wiped by 新会话 / restored history
                    // pointing at erased files) — drop that ref line and keep
                    // the message sendable. Pre-v100 any stale ref aborted the
                    // whole send ("attachment unavailable") forever.
                    if (f == null) continue;
                    String mime = mimeOf(f.getName());
                    String kind = kindOf(mime);
                    if (remote && kind.equals("video")) {
                        // plan guard: remote providers vary on video; drop the
                        // ref instead of shipping a request they reject.
                        continue;
                    }
                    String b64 = base64Of(f);
                    if (kind.equals("image")) {
                        JSONObject part = new JSONObject();
                        part.put("type", "image_url");
                        JSONObject iu = new JSONObject();
                        iu.put("url", "data:" + mime + ";base64," + b64);
                        part.put("image_url", iu);
                        parts.put(part);
                    } else if (kind.equals("audio")) {
                        JSONObject part = new JSONObject();
                        part.put("type", "input_audio");
                        JSONObject ia = new JSONObject();
                        ia.put("data", b64);
                        ia.put("format", "wav".equals(extOf(f.getName())) ? "wav" : extOf(f.getName()));
                        part.put("input_audio", ia);
                        parts.put(part);
                    } else {
                        JSONObject part = new JSONObject();
                        part.put("type", "input_video");
                        JSONObject iv = new JSONObject();
                        iv.put("data", b64);
                        iv.put("format", extOf(f.getName()));
                        part.put("input_video", iv);
                        parts.put(part);
                    }
                }
                // vc100: refs all stale → fall back to plain text send (message
                // stays deliverable); pre-v100 this returned null = blocked.
                if (parts.length() == 0) { out.put(m); changed = true; continue; }
                if (text.length() > 0) {
                    JSONObject tp = new JSONObject();
                    tp.put("type", "text");
                    tp.put("text", text.toString());
                    parts.put(tp);
                }
                JSONObject m2 = new JSONObject();
                m2.put("role", "user");
                m2.put("content", parts);
                out.put(m2);
                changed = true;
            }
            if (!changed) return null;
            body.put("messages", out);
            return body.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.US);
    }

    static String base64Of(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
        byte[] buf = new byte[16384];
        int n;
        while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
        fi.close();
        // java.util.Base64 (API 26+; minSdk 29): JVM-testable, unlike the
        // android.util.Base64 stub which throws "Stub!" outside an emulator.
        return java.util.Base64.getEncoder().encodeToString(bo.toByteArray());
    }

    /** TRUE when the messages array carries at least one media_ref (JS hint). */
    static boolean hasRefs(String bodyJson) {
        return bodyJson != null && bodyJson.contains(REF_PREFIX);
    }
}
