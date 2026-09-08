package com.dawoer.npullm;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Merged stdout+stderr ring buffer, keeps last N lines.
 * Handles lines split across chunk boundaries (partial line carry).
 */
final class LogRingBuffer {
    private final Deque<String> lines = new ArrayDeque<>();
    private final int maxLines;
    private long dropped;
    private String pending = "";   // tail of a line not yet terminated
    private boolean pendingValid = false;

    LogRingBuffer(int maxLines) { this.maxLines = maxLines; }

    synchronized void append(char[] buf, int len) {
        appendStr(new String(buf, 0, len));
    }

    synchronized void appendStr(String chunk) {
        String work = pendingValid ? pending + chunk : chunk;
        int start = 0;
        int nl;
        while ((nl = work.indexOf('\n', start)) >= 0) {
            push(work.substring(start, nl));
            start = nl + 1;
        }
        if (start < work.length()) {
            pending = work.substring(start);
            pendingValid = true;
        } else {
            pending = "";
            pendingValid = false;
        }
        trim();
    }

    private void push(String line) {
        lines.addLast(line);
        while (lines.size() > maxLines) { lines.pollFirst(); dropped++; }
    }

    private void trim() {
        while (lines.size() > maxLines) { lines.pollFirst(); dropped++; }
    }

    synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        if (dropped > 0) sb.append("[... ").append(dropped).append(" earlier lines dropped ...]\n");
        boolean first = true;
        for (String l : lines) {
            if (!first) sb.append('\n');
            sb.append(l);
            first = false;
        }
        if (pendingValid && pending.length() > 0) sb.append(pending);
        return sb.toString();
    }

    synchronized void clear() { lines.clear(); pending = ""; pendingValid = false; dropped = 0; }
}
