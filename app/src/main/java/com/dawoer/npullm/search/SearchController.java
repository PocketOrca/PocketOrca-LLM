package com.dawoer.npullm.search;

/**
 * Static state machine for the LAN search server (BRIEF-vc122 §2.1):
 * off -> starting -> on -> off, pollable by the UI via wsServerStatus().
 *
 * Pure logic, JVM-testable. Port-busy probe = try-bind (self-contained;
 * does not reach into MainActivity's bridge copy — isolation).
 * Zero imports from engine-side classes. No anonymous classes.
 */
public final class SearchController {

    public static final String STATE_OFF = "off";
    public static final String STATE_STARTING = "starting";
    public static final String STATE_ON = "on";
    public static final String STATE_ERROR = "error";

    private static volatile String state = STATE_OFF;
    private static volatile String lastError = "";
    private static volatile long sinceMs = 0L;

    private SearchController() {}

    public static String state() {
        return state;
    }

    public static String lastError() {
        return lastError;
    }

    /** Status JSON for the UI: {"state":..,"port":<configured>,"err":..}. */
    public static String statusJson() {
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(state).append("\",\"port\":").append(SearchServer.STATUS_PORT());
        sb.append(",\"err\":\"");
        String e = lastError;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '"' || c == 92) sb.append('\\');
            if (c == '\n') { sb.append(' '); continue; }
            if (c == '\r' || c == '\t') { sb.append(' '); continue; }
            sb.append(c);
        }
        sb.append("\"}");
        return sb.toString();
    }

    // ===== transitions (called by the FGS; guarded so stray calls never wedge) =====

    public static void onToggleRequested() {
        state = STATE_STARTING;
        lastError = "";
        sinceMs = System.currentTimeMillis();
    }

    public static void onServerStarted() {
        state = STATE_ON;
        lastError = "";
        sinceMs = System.currentTimeMillis();
    }

    public static void onServerFailed(String err) {
        state = STATE_ERROR;
        lastError = err == null ? "unknown" : err;
        sinceMs = System.currentTimeMillis();
    }

    public static void onServerStopped() {
        state = STATE_OFF;
        sinceMs = System.currentTimeMillis();
        // keep lastError visible for the UI until the next toggle
    }

    /**
     * Try-bind probe: true when the port is already taken (by our own server
     * or anything else). Closes the probe socket immediately.
     */
    public static boolean isPortBusy(int port) {
        java.net.ServerSocket ss = null;
        try {
            ss = new java.net.ServerSocket(port);
            ss.setReuseAddress(true);
            return false;
        } catch (Throwable t) {
            return true;
        } finally {
            if (ss != null) try { ss.close(); } catch (Throwable ignore) {}
        }
    }
}
