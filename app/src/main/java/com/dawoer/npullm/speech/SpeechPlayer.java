package com.dawoer.npullm.speech;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.File;

/**
 * vc166: 播放器（main 进程，仅扬声器）。
 *
 * WebView 里的 `new Audio(file://…)` 在 WebViewAssetLoader 页面里是跨源请求，
 * 实测被拦（vc165 语音页「试听」从来没响过）。改原生 MediaPlayer：
 *   USAGE_MEDIA + CONTENT_TYPE_SPEECH，即媒体音量、语音内容类型（不走听筒，
 *   用户 1110 拍板：不做 MODE_IN_COMMUNICATION，不改音频模式）。
 * 播完由调用方或下次 play() 释放；页面「停止」走 stop()。
 */
public final class SpeechPlayer {

    private static MediaPlayer mp;
    /** vc172: 播完/出错回调宿主（MainActivity 的 WeakReference 形态由调用方持有，这里只存 Context）。 */
    private static android.content.Context ownerCtx;

    private SpeechPlayer() {}

    /** vc172: 播放结束（含出错）时向 WebView 发 window.onSpeechPlayDone()，供分段队列接续。 */
    private static void notifyDone() {
        ownerCtx = null;
        com.dawoer.npullm.MainActivity.evalJsStatic("window.onSpeechPlayDone && window.onSpeechPlayDone()");
    }

    /** 只允许播应用自己目录下的文件（cacheDir / filesDir），防任意路径。 */
    public static String play(Context c, String path) {
        if (c == null) return "未初始化";
        ownerCtx = c.getApplicationContext();
        File f = new File(String.valueOf(path));
        if (!f.isFile()) return "文件不存在";
        String abs = f.getAbsolutePath();
        String cache = c.getCacheDir().getAbsolutePath();
        String files = c.getFilesDir().getAbsolutePath();
        if (!abs.startsWith(cache) && !abs.startsWith(files)) return "路径不在应用目录内";
        synchronized (SpeechPlayer.class) {
            releaseSync();
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                p.setDataSource(abs);
                // vc172: 播完/出错 → 回调 WebView（分段播放队列靠它接续下一段）
                p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer player) { notifyDone(); }
                });
                p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer player, int what, int extra) {
                        notifyDone();
                        return false;
                    }
                });
                p.prepare();
                p.start();
                mp = p;
                return null;
            } catch (Throwable t) {
                releaseSync();
                notifyDone();
                return String.valueOf(t);
            }
        }
    }

    public static void stop() {
        synchronized (SpeechPlayer.class) {
            releaseSync();
        }
    }

    /** 是否在播（UI 按钮态）。 */
    public static synchronized boolean playing() {
        try {
            return mp != null && mp.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void releaseSync() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.setOnCompletionListener(null); } catch (Throwable ignore) {}
        try { p.setOnErrorListener(null); } catch (Throwable ignore) {}
        try { p.stop(); } catch (Throwable ignore) {}
        try { p.release(); } catch (Throwable ignore) {}
    }
}
