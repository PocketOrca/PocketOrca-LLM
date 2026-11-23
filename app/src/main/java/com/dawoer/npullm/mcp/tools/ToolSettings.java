package com.dawoer.npullm.mcp.tools;

import android.content.Context;

import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.McpTrace;
import com.dawoer.npullm.mcp.shell.McpShellLogic;
import com.dawoer.npullm.mcp.shell.ShellClient;

import org.json.JSONObject;

/**
 * vc183（S3）L3 工具：settings.get / settings.set。
 *
 * 走 `/system/bin/settings`（shell uid 2000）而不是 Settings.System + WRITE_SETTINGS：
 *   - 三个 namespace（system/secure/global）一次覆盖，WRITE_SETTINGS 只覆盖 system 的一部分
 *   - 与 UI 里「一句话改设置」共用同一条执行路径（同 key 同语义）
 *
 * 护栏（不可退让）：
 *   - 读：挡敏感片段（token/password/adb_/android_id/imei…），值超 512 截断
 *   - 写：**白名单准入**（白名单外的 key 一律拒绝并回可写清单），写入后**读回校验**，
 *         读回值不等于期望值即报失败（不算成功）；返回 previous 供调用方回滚
 */
public final class ToolSettings {

    private static final String BIN_SETTINGS = "/system/bin/settings";

    private ToolSettings() {}

    public static McpToolDef defGet(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("namespace", McpParamCheck.schemaString("system / secure / global（必填）"));
            props.put("key", McpParamCheck.schemaString("设置项名，如 screen_brightness（必填）"));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "settings.get",
                "读取系统设置项",
                "读取 Android 系统设置项的当前值（只读）。用于诊断（为什么没声音/字太小）或写入前确认原值。"
                        + "key 名不确定时可先按常见名试探，返回 null 表示该 key 不存在。"
                        + "敏感项（token/密码/设备唯一标识等）一律拒绝读取。",
                McpParamCheck.objectSchema(props, new String[]{"namespace", "key"}),
                McpToolDef.LEVEL_READ,
                new Getter(ctx, trace),
                true,
                new ToolApps.ShizukuGate(ctx));
    }

    public static McpToolDef defSet(Context ctx, McpTrace trace) {
        JSONObject props = new JSONObject();
        try {
            props.put("namespace", McpParamCheck.schemaString("system / secure / global（必填）"));
            props.put("key", McpParamCheck.schemaString("设置项名（必须在可写白名单内，见工具描述）"));
            props.put("value", McpParamCheck.schemaString("新值（字符串，数字也写成字符串，如 \"128\"）"));
        } catch (Exception ignored) { }
        return new McpToolDef(
                "settings.set",
                "修改系统设置项",
                "修改系统设置项，**仅限可写白名单**：" + McpShellLogic.writableList() + "。"
                        + "写入后读回校验，值未变即报失败；返回 previous（旧值）与 current（读回值），"
                        + "需要撤销时用 previous 再调一次本工具。"
                        + "锁屏/无障碍/设备管理员/VPN 等安全项一律拒绝。"
                        + "改之前先向用户说明「将改什么、影响什么」。",
                McpParamCheck.objectSchema(props, new String[]{"namespace", "key", "value"}),
                McpToolDef.LEVEL_REVERSIBLE,
                new Setter(ctx, trace),
                true,
                new ToolApps.ShizukuGate(ctx));
    }

    /** settings.get。 */
    static final class Getter implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Getter(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            String ns = args.optString("namespace", "").trim();
            String key = args.optString("key", "").trim();
            String deny = McpShellLogic.readDenyReason(ns, key);
            if (deny != null) return McpResult.error("INVALID_ARGUMENT", deny);

            ShellClient.Res r = ShellClient.run(ctx, BIN_SETTINGS, "get", ns, key);
            if (r == null) {
                return McpResult.error("NOT_AVAILABLE",
                        ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }
            String v = r.out.trim();
            boolean isNull = v.length() == 0 || "null".equals(v);
            JSONObject o = new JSONObject();
            try {
                o.put("namespace", ns);
                o.put("key", key);
                o.put("value", isNull ? JSONObject.NULL : clip(v));
                o.put("exists", !isNull);
                if (isNull) o.put("hint", "该 key 在当前系统上不存在（不同 ROM/版本 key 集不同）");
                if (r.code != 0) o.put("note", clip(r.err));
            } catch (Exception ignored) { }
            if (trace != null) trace.proto("settings.get " + ns + "/" + key);
            return McpResult.json(o);
        }
    }

    /** settings.set：get → put → get 读回。 */
    static final class Setter implements McpToolDef.Executor {
        private final Context ctx;
        private final McpTrace trace;

        Setter(Context ctx, McpTrace trace) {
            this.ctx = ctx.getApplicationContext();
            this.trace = trace;
        }

        @Override
        public McpResult exec(JSONObject args) {
            String ns = args.optString("namespace", "").trim();
            String key = args.optString("key", "").trim();
            String value = args.optString("value", "");

            String deny = McpShellLogic.writeDenyReason(ns, key);
            if (deny != null) return McpResult.error("INVALID_ARGUMENT", deny);
            String bad = McpShellLogic.checkValue(value);
            if (bad != null) return McpResult.error("INVALID_ARGUMENT", bad);

            ShellClient.Res before = ShellClient.run(ctx, BIN_SETTINGS, "get", ns, key);
            if (before == null) {
                return McpResult.error("NOT_AVAILABLE",
                        ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }
            String prev = before.out.trim();
            boolean prevNull = prev.length() == 0 || "null".equals(prev);

            ShellClient.Res put = ShellClient.run(ctx, BIN_SETTINGS, "put", ns, key, value);
            if (put == null) {
                return McpResult.error("NOT_AVAILABLE",
                        ShellClient.Res.unavailableHint(ShellClient.state(ctx)));
            }

            // 读回校验：settings put 的 exit code 不代表生效（OEM 可能拦），只认读回值
            ShellClient.Res after = ShellClient.run(ctx, BIN_SETTINGS, "get", ns, key);
            String now = after == null ? "" : after.out.trim();
            boolean verified = now.equals(value);

            JSONObject o = new JSONObject();
            try {
                o.put("namespace", ns);
                o.put("key", key);
                o.put("previous", prevNull ? JSONObject.NULL : clip(prev));
                o.put("current", clip(now));
                o.put("verified", verified);
                if (put.code != 0) o.put("put_err", clip(put.err));
            } catch (Exception ignored) { }
            if (!verified) {
                return McpResult.error("INTERNAL",
                        "写入未生效：期望 \"" + value + "\"，读回 \"" + now + "\""
                                + (put.err.length() > 0 ? "（" + clip(put.err) + "）" : "")
                                + "。该 ROM 可能不允许用 shell 改这个 key。");
            }
            if (trace != null) trace.proto("settings.set " + ns + "/" + key + " " + prev + "→" + now);
            return McpResult.json(o);
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 512 ? s.substring(0, 512) + "…" : s;
    }
}
