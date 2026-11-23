package com.dawoer.npullm.mcp;

/**
 * 预算断路器的最小实现：60 次/分 + tools/call 全局串行 1。
 * 串行用同一个对象锁（单客户端场景，D4 决策），慢工具会阻塞后续请求 —— 已知代价。
 */
public final class McpRateLimit {

    private final int maxPerWindow;
    private final long windowMs;
    private long windowStart;
    private int count;
    private final Object serialLock = new Object();

    public McpRateLimit(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        this.windowStart = System.currentTimeMillis();
    }

    public static McpRateLimit defaults() {
        return new McpRateLimit(60, 60000L);
    }

    /** false = 超限，调用方回 429。 */
    public synchronized boolean tryAcquire(long nowMs) {
        if (nowMs - windowStart >= windowMs) {
            windowStart = nowMs;
            count = 0;
        }
        if (count >= maxPerWindow) return false;
        count++;
        return true;
    }

    public synchronized int used(long nowMs) {
        if (nowMs - windowStart >= windowMs) return 0;
        return count;
    }

    /** 串行门：所有 tools/call 在这里排队。 */
    public Object serialGate() {
        return serialLock;
    }
}
