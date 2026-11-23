package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.util.Log;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.speech.SpeechConfig;
import com.dawoer.npullm.speech.SpeechPlayer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * tts.speak（vc185）：让手机开口说话。
 *
 * 没有这个工具，outbox 唤醒了 agent 也只能发文字——「手机主动说一句」这条闭环缺的就是它。
 * 通路复用现成的 TTS 服务：:mcp 进程 POST 127.0.0.1:8084 /v1/audio/speech（meta=1 拿文件路径），
 * 再用 SpeechPlayer 原生播放（MediaPlayer，USAGE_MEDIA + CONTENT_TYPE_SPEECH）。
 *
 * 不跨进程直接调 SpeechTts：引擎在 :tts 进程里，HTTP 是既定规矩（UI 通=接口通）。
 */
public final class ToolTts implements McpToolDef.Executor {

    private static final String TAG = "PoMcpTts";
    private static final int MAX_TEXT = 300;

    private final Context ctx;

    public ToolTts(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("text", McpParamCheck.schemaString("要念出来的文本（≤300 字）"));
            props.put("speed", McpParamCheck.schemaNumber("语速，默认 1.0", 0.5d, 2.0d, 1.0d));
            props.put("voice", McpParamCheck.schemaString("音色：default 或数字（多音色模型才有意义）"));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "tts.speak",
                "让手机说话",
                "把文本合成语音并用手机扬声器播出（覆盖当前正在播放的语音）。"
                        + "用于通知用户、播报提醒。需要手机已导入 TTS 模型并启动语音服务。",
                McpParamCheck.objectSchema(props, new String[]{"text"}),
                McpToolDef.LEVEL_REVERSIBLE,
                new ToolTts(ctx));
    }

    @Override
    public McpResult exec(JSONObject args) {
        String text = args.optString("text", "");
        if (text == null) text = "";
        text = text.trim();
        if (text.length() == 0) return McpResult.error("INVALID_ARGUMENT", "text 必填");
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        double speed = args.optDouble("speed", 1.0d);
        if (speed < 0.5d) speed = 0.5d;
        if (speed > 2.0d) speed = 2.0d;
        String voice = args.optString("voice", "");

        String key = SpeechConfig.gateKey(SpeechConfig.KIND_TTS);
        if (key == null || key.length() == 0) {
            return McpResult.error("NOT_AVAILABLE",
                    "语音服务尚未初始化：请在服务页启动 TTS 服务（未生成 key 说明从没启动过）");
        }
        int port = SpeechConfig.port(SpeechConfig.KIND_TTS);

        long t0 = System.currentTimeMillis();
        String[] res;
        try {
            res = post(port, key, text, speed, voice);
        } catch (Throwable t) {
            return McpResult.error("INTERNAL", "TTS 请求失败: " + t.getClass().getSimpleName());
        }
        int code = res[0] == null ? 0 : parseInt(res[0], 0);
        if (code == 401) return McpResult.error("NOT_AVAILABLE", "TTS 服务 key 不匹配（重启语音服务重试）");
        if (code == 0) {
            return McpResult.error("NOT_AVAILABLE", "连不上本机 TTS 服务：" + res[1]
                    + "（需在服务页启动 TTS 服务并导入模型）");
        }
        if (code < 200 || code >= 300) {
            return McpResult.error("INTERNAL", "TTS 合成失败 HTTP " + code + " " + res[1]);
        }

        String file = "";
        try {
            JSONObject o = new JSONObject(res[2]);
            file = o.optString("file", "");
        } catch (Throwable ignored) { }

        if (file.length() == 0) return McpResult.error("INTERNAL", "TTS 未返回音频文件路径");
        String err = SpeechPlayer.play(ctx, file);
        if (err != null) return McpResult.error("INTERNAL", "播放失败: " + err);

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("chars", text.length());
            out.put("synth_ms", System.currentTimeMillis() - t0);
        } catch (Exception ignored) { }
        Log.i(TAG, "speak " + text.length() + " chars in " + (System.currentTimeMillis() - t0) + "ms");
        return McpResult.json(out);
    }

    /** 返回 {code, errBody, okBody}。 */
    private static String[] post(int port, String key, String text, double speed, String voice) {
        String[] out = new String[]{"0", "", ""};
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("speed", speed);
            body.put("meta", true);
            body.put("response_format", "wav");
            if (voice != null && voice.length() > 0) body.put("voice", voice);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

            URL u = new URL("http://127.0.0.1:" + port + "/v1/audio/speech");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            out[0] = String.valueOf(c.getResponseCode());
            InputStream is = out[0].startsWith("2") ? c.getInputStream() : c.getErrorStream();
            String resp = read(is);
            if (out[0].startsWith("2")) out[2] = resp;
            else out[1] = resp == null ? "" : (resp.length() > 200 ? resp.substring(0, 200) : resp);
        } catch (Throwable t) {
            out[0] = "0";
            out[1] = t.getClass().getSimpleName();
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static String read(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }
}
