package com.dawoer.npullm.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 最小 JSON-Schema 校验：只要能把"类型错/缺必填/超范围/多字段"挡住就够。
 * 不追求完整 draft，够用即可 —— 契约不通过就不进执行器。
 */
public final class McpParamCheck {

    private McpParamCheck() {}

    /** 返回 null = 通过；否则返回人话错误（供 isError 用）。 */
    public static String check(JSONObject schema, JSONObject args) {
        if (schema == null || schema.length() == 0) return null;
        JSONObject input = args == null ? new JSONObject() : args;

        String type = schema.optString("type", "object");
        if (!"object".equals(type)) {
            // 我们所有工具入参都是 object；别的形态不支持
            return "schema.type 只支持 object";
        }

        JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                String k = required.optString(i, null);
                if (k != null && !input.has(k)) return "缺必填参数: " + k;
            }
        }

        JSONObject props = schema.optJSONObject("properties");
        boolean noExtra = schema.has("additionalProperties")
                && !schema.optBoolean("additionalProperties", true);

        java.util.Iterator<String> it = input.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject p = props == null ? null : props.optJSONObject(key);
            if (p == null) {
                if (noExtra) return "不认识的参数: " + key;
                continue;
            }
            String err = checkValue(key, p, input.opt(key));
            if (err != null) return err;
        }
        return null;
    }

    private static String checkValue(String key, JSONObject p, Object v) {
        if (v == null || v == JSONObject.NULL) {
            if (p.optBoolean("required", false)) return "参数不能为 null: " + key;
            return null;
        }
        String t = p.optString("type", null);
        if (t != null) {
            if ("string".equals(t) && !(v instanceof String)) return "参数类型应为 string: " + key;
            if ("boolean".equals(t) && !(v instanceof Boolean)) return "参数类型应为 boolean: " + key;
            if ("integer".equals(t) || "number".equals(t)) {
                if (!(v instanceof Integer) && !(v instanceof Long)
                        && !(v instanceof Double) && !(v instanceof Float)) {
                    return "参数类型应为 number: " + key;
                }
                if ("integer".equals(t) && (v instanceof Double || v instanceof Float)) {
                    double d = ((Number) v).doubleValue();
                    if (d != Math.floor(d)) return "参数应为整数: " + key;
                }
            }
            if ("array".equals(t) && !(v instanceof JSONArray)) return "参数类型应为 array: " + key;
            if ("object".equals(t) && !(v instanceof JSONObject)) return "参数类型应为 object: " + key;
        }

        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (p.has("minimum") && d < p.optDouble("minimum")) {
                return "参数小于最小值: " + key;
            }
            if (p.has("maximum") && d > p.optDouble("maximum")) {
                return "参数大于最大值: " + key;
            }
        }

        JSONArray en = p.optJSONArray("enum");
        if (en != null) {
            boolean hit = false;
            for (int i = 0; i < en.length(); i++) {
                Object e = en.opt(i);
                if (e != null && e.equals(v)) { hit = true; break; }
            }
            if (!hit) return "参数不在允许取值内: " + key;
        }

        if (v instanceof String) {
            int len = ((String) v).length();
            if (p.has("maxLength") && len > p.optInt("maxLength")) {
                return "参数超长: " + key;
            }
        }
        return null;
    }

    // ---- schema 构造小工具（避免各工具重复拼 JSON）----
    public static JSONObject schemaString(String desc) { return of("string", desc); }

    public static JSONObject schemaInteger(String desc, int min, int max, int def) {
        JSONObject p = of("integer", desc);
        try {
            p.put("minimum", min);
            p.put("maximum", max);
            p.put("default", def);
        } catch (Exception ignored) { }
        return p;
    }

    public static JSONObject schemaBool(String desc, boolean def) {
        JSONObject p = of("boolean", desc);
        try { p.put("default", def); } catch (Exception ignored) { }
        return p;
    }

    /** vc185：小数参数（语速这类）。null 值不传。 */
    public static JSONObject schemaNumber(String desc, double min, double max, double def) {
        JSONObject p = of("number", desc);
        try {
            p.put("minimum", min);
            p.put("maximum", max);
            p.put("default", def);
        } catch (Exception ignored) { }
        return p;
    }

    private static JSONObject of(String type, String desc) {
        JSONObject p = new JSONObject();
        try {
            p.put("type", type);
            if (desc != null) p.put("description", desc);
        } catch (Exception ignored) { }
        return p;
    }

    /** 组装 object schema：props 为已构造的属性，required 可为 null。 */
    public static JSONObject objectSchema(JSONObject props, String[] required) {
        JSONObject s = new JSONObject();
        try {
            s.put("type", "object");
            s.put("properties", props == null ? new JSONObject() : props);
            if (required != null && required.length > 0) {
                JSONArray arr = new JSONArray();
                for (String r : required) arr.put(r);
                s.put("required", arr);
            }
            s.put("additionalProperties", false);
        } catch (Exception ignored) { }
        return s;
    }
}
