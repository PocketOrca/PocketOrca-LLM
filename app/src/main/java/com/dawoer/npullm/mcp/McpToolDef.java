package com.dawoer.npullm.mcp;

import org.json.JSONObject;

/**
 * 一个工具的静态定义 + 执行器。
 * level 是代码常量（护栏），annotations 只是给 client 的提示（规范明确要求 client 视其为不可信）。
 */
public final class McpToolDef {

    public static final int LEVEL_READ = 0;
    public static final int LEVEL_REVERSIBLE = 1;
    public static final int LEVEL_IRREVERSIBLE = 2;

    public interface Executor {
        McpResult exec(JSONObject args) throws Exception;
    }

    /**
     * 运行时闸门（S2）：权限类工具只有真正可用时才进 tools/list。
     * 每次 tools/list / tools/call 都调用一次，实现方必须实时查（不缓存）。
     */
    public interface Gate {
        boolean open();
    }

    public final String name;
    public final String title;
    public final String description;
    public final JSONObject inputSchema;
    public final int level;
    public final Executor executor;
    /** 是否默认对 client 可见（不可逆工具为 false）。 */
    public final boolean visibleByDefault;
    /** 运行时闸门；null = 无闸门（只受 visibleByDefault 约束）。 */
    public final Gate gate;

    public McpToolDef(String name, String title, String description,
                      JSONObject inputSchema, int level, Executor executor) {
        this(name, title, description, inputSchema, level, executor, level < LEVEL_IRREVERSIBLE, null);
    }

    public McpToolDef(String name, String title, String description, JSONObject inputSchema,
                      int level, Executor executor, boolean visibleByDefault) {
        this(name, title, description, inputSchema, level, executor, visibleByDefault, null);
    }

    public McpToolDef(String name, String title, String description, JSONObject inputSchema,
                      int level, Executor executor, boolean visibleByDefault, Gate gate) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.inputSchema = inputSchema == null ? new JSONObject() : inputSchema;
        this.level = level;
        this.executor = executor;
        this.visibleByDefault = visibleByDefault;
        this.gate = gate;
    }

    /** 当前是否真的可用：默认可见 + 闸门放行。tools/list 与 tools/call 都以它为准。 */
    public boolean visible() {
        if (!visibleByDefault) return false;
        if (gate == null) return true;
        try {
            return gate.open();
        } catch (Throwable t) {
            return false;   // 闸门自身异常一律当关，宁可少给工具也不给错误的
        }
    }

    /** tools/list 里的单条定义（顺序由上层按名排序，保证确定性）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("title", title);
            o.put("description", description);
            o.put("inputSchema", inputSchema);
            JSONObject ann = new JSONObject();
            ann.put("readOnlyHint", level == LEVEL_READ);
            ann.put("destructiveHint", level == LEVEL_IRREVERSIBLE);
            ann.put("idempotentHint", level == LEVEL_READ);
            ann.put("openWorldHint", false);
            o.put("annotations", ann);
        } catch (Exception ignored) { }
        return o;
    }
}
