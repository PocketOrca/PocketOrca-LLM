package com.dawoer.npullm.mcp.tools;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import com.dawoer.npullm.mcp.McpFormat;
import com.dawoer.npullm.mcp.McpParamCheck;
import com.dawoer.npullm.mcp.McpResult;
import com.dawoer.npullm.mcp.McpToolDef;
import com.dawoer.npullm.mcp.access.Permissions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * contacts.list（L2，READ_CONTACTS）：找人/找号码。
 * 一人多号会出多行（同一 contact_id），不做分组 —— 模型要的是"号码"，不是树。
 */
public final class ToolContacts implements McpToolDef.Executor {

    private static final String PERM = Permissions.READ_CONTACTS;

    private final Context ctx;

    public ToolContacts(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static McpToolDef def(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("limit", McpParamCheck.schemaInteger("返回条数", 1, 50, 20));
            props.put("query", McpParamCheck.schemaString("按姓名或号码模糊搜索，可省略"));
        } catch (Exception ignored) { }

        return new McpToolDef(
                "contacts.list",
                "查询手机联系人",
                "按姓名或号码查手机通讯录，返回姓名+号码+号码类型。用户说「找一下某某的电话」时调用。",
                McpParamCheck.objectSchema(props, null),
                McpToolDef.LEVEL_READ,
                new ToolContacts(ctx),
                true,
                new ToolSms.PermGate(ctx, PERM));
    }

    @Override
    public McpResult exec(JSONObject args) {
        if (!Permissions.granted(ctx, PERM)) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        }
        int limit = args.optInt("limit", 20);
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;
        String q = args.optString("query", "").trim();

        Cursor c = null;
        JSONArray arr = new JSONArray();
        try {
            String sel = ContactsContract.CommonDataKinds.Phone.NUMBER + " IS NOT NULL";
            java.util.ArrayList<String> selArgs = new java.util.ArrayList<>();
            if (q.length() > 0) {
                sel += " AND (" + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
                        + " OR " + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?)";
                selArgs.add("%" + q + "%");
                selArgs.add("%" + McpFormat.normalizeNumber(q) + "%");
            }

            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE
                    },
                    sel, selArgs.toArray(new String[0]),
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c == null) return McpResult.error("INTERNAL", "联系人查询返回空 cursor（provider 拒绝）");
            while (c.moveToNext() && arr.length() < limit) {
                JSONObject o = new JSONObject();
                try {
                    o.put("contact_id", c.getLong(0));
                    String name = c.getString(1);
                    o.put("name", name == null ? "" : name);
                    o.put("number", c.getString(2) == null ? "" : c.getString(2));
                    o.put("type", McpFormat.phoneTypeLabel(c.getInt(3)));
                } catch (Exception e) {
                    try { o.put("err", String.valueOf(e.getClass().getSimpleName())); } catch (Exception ignored) { }
                }
                arr.put(o);
            }
        } catch (SecurityException e) {
            return McpResult.error("PERMISSION_DENIED", Permissions.denyHint(PERM));
        } catch (Throwable t) {
            return McpResult.error("INTERNAL", "联系人读取失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", arr.length());
            out.put("query", q);
            out.put("items", arr);
        } catch (Exception ignored) { }
        return McpResult.json(out);
    }
}
