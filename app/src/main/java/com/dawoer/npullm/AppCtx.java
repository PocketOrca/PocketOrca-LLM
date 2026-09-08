package com.dawoer.npullm;

import android.content.Context;

/** App-scoped context holder for sampler battery fallback (no leaks). */
final class AppCtx {
    private static Context app;
    static void init(Context c) { if (app == null) app = c.getApplicationContext(); }
    static Context get() { return app; }
    private AppCtx() {}
}
