package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.media.AudioManager;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.audio.McpAudioLogic;
import com.dawoer.npullm.mcp.shell.ShellClient;

import org.json.JSONObject;

/**
 * vc190：audio.set_volume —— 调音量（媒体/铃声/闹钟/通话/系统/通知）。
 *
 * 为什么单独一个工具：**音量不是 settings key**，`settings put system volume_*` 不存在，
 * settings 白名单里永远不会有它。音量只能走 AudioManager / AudioService。
 *
 * 双路执行（先便宜后兜底）：
 *   1. 进程内 AudioManager（L0，MODIFY_AUDIO_SETTINGS 是 normal 权限，声明即授予）
 *   2. 读回不一致且 Shizuku 就绪 → shell `/system/bin/cmd media_session volume ...`（S25 实测通路）
 * 判定一律以**读回值**为准，不信返回值（与 settings.set 同一护栏原则）。
 */
public final class ToolAudio implements McpToolDef.Executor {

    private static final String BIN_CMD = "/system/bin/cmd";

    private final Context ctx;

    private ToolAudio(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("stream", McpParamCheck.schemaString("音频流：" + McpAudioLogic.STREAM_LIST + "（默认 music）"));
            props.put("action", McpParamCheck.schemaString("get（默认）/ up / down / set"));
            props.put("level", McpParamCheck.schemaInteger("目标音量百分比 0-100，仅 action=set 时必填", 0, 100, -1));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "audio.set_volume",
                "调节音量",
                "调节手机音量。stream 选音频流（music 媒体 / ring 铃声 / alarm 闹钟 / call 通话音 / "
                        + "system 系统音 / notification 通知），action=up/down 调一档，set 配 level(0-100) 定值，"
                        + "get 只读当前档位。执行后读回校验，未生效即报失败。音量不是系统设置项，不要用 settings.set 改音量。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_REVERSIBLE,
                new ToolAudio(ctx));
    }

    @Override
    public McpResult exec(JSONObject args) {
        String streamArg = args.optString("stream", "music");
        String action = args.optString("action", "get").trim().toLowerCase(java.util.Locale.ROOT);
        int level = args.optInt("level", -1);

        int sid = McpAudioLogic.streamId(streamArg);
        if (sid < 0) {
            return McpResult.error("INVALID_ARGUMENT",
                    "未知音频流: " + streamArg + "。可用：" + McpAudioLogic.STREAM_LIST);
        }
        if (!("get".equals(action) || "up".equals(action) || "down".equals(action) || "set".equals(action))) {
            return McpResult.error("INVALID_ARGUMENT", "未知动作: " + action + "。可用：get / up / down / set");
        }
        if ("set".equals(action) && (level < 0 || level > 100)) {
            return McpResult.error("INVALID_ARGUMENT", "action=set 需要 level（0-100），收到 " + level);
        }

        AudioManager am = null;
        try {
            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable ignored) { }

        int max = 0, cur = -1;
        if (am != null) {
            try {
                max = am.getStreamMaxVolume(sid);
                cur = am.getStreamVolume(sid);
            } catch (Throwable t) {
                max = 0;
                cur = -1;
            }
        }

        // AudioManager 读不到（权限/进程限制）→ 直接走 shell 读
        if (cur < 0 || max <= 0) {
            int[] pm = shellGet(sid);
            if (pm == null) {
                return McpResult.error("NOT_AVAILABLE",
                        "读不到音量：AudioManager 不可用，且 shell 通道不可用（"
                                + ShellClient.Res.unavailableHint(ShellClient.state(ctx)) + "）");
            }
            cur = pm[0];
            max = pm[1];
        }

        if ("get".equals(action)) {
            return McpResult.json(body(sid, cur, max, cur, true, "read"));
        }

        int target;
        if ("up".equals(action)) target = McpAudioLogic.step(cur, max, true);
        else if ("down".equals(action)) target = McpAudioLogic.step(cur, max, false);
        else target = McpAudioLogic.percentToIndex(level, max);

        String via = "none";
        int after = cur;
        if (target == cur) {
            // 已经到顶/到底：不做无意义写调用
            via = "noop";
        } else {
            boolean wrote = false;
            if (am != null) {
                try {
                    am.setStreamVolume(sid, target, 0);
                    wrote = true;
                    via = "audiomanager";
                } catch (Throwable ignored) { }
            }
            if (wrote) {
                try { after = am.getStreamVolume(sid); } catch (Throwable ignored) { after = cur; }
            }
            // 读回不一致 → shell 兜底（S25 实测 `cmd media_session volume --stream N --set` 可用）
            if (after != target && ShellClient.ready(ctx)) {
                shellSet(sid, target);
                int[] pm = shellGet(sid);
                if (pm != null) {
                    after = pm[0];
                    max = pm[1];
                    via = "shell";
                } else {
                    via = "shell-unverified";
                }
            }
        }

        boolean verified = after == target;
        if (!verified) {
            return McpResult.error("INTERNAL",
                    "音量未生效：期望档位 " + target + "（" + McpAudioLogic.indexToPercent(target, max) + "%），"
                            + "读回 " + after + "。该流可能被勿扰模式或系统策略锁定。");
        }
        return McpResult.json(body(sid, target, max, cur, true, via));
    }

    private JSONObject body(int sid, int idx, int max, int prev, boolean verified, String via) {
        JSONObject o = new JSONObject();
        try {
            o.put("stream", McpAudioLogic.streamName(sid));
            o.put("index", idx);
            o.put("max", max);
            o.put("percent", McpAudioLogic.indexToPercent(idx, max));
            o.put("previous_index", prev);
            o.put("verified", verified);
            o.put("via", via);
        } catch (Exception ignored) { }
        return o;
    }

    private int[] shellGet(int sid) {
        ShellClient.Res r = ShellClient.run(ctx, BIN_CMD,
                "media_session", "volume", "--stream", String.valueOf(sid), "--get");
        if (r == null) return null;
        return McpAudioLogic.parseGet(r.out);
    }

    private void shellSet(int sid, int idx) {
        ShellClient.run(ctx, BIN_CMD, "media_session", "volume",
                "--stream", String.valueOf(sid), "--set", String.valueOf(idx));
    }
}
