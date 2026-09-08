package com.dawoer.npullm;

/**
 * JNI shim for the in-process OpenCL/CPU server (spec v0.2 T6, ocl+cpu
 * profiles). Runs llama-server on a dedicated thread inside the app
 * process: the app classloader namespace resolves libOpenCL.so through
 * the vendor public-libraries channel (sphal), pulling the whole Adreno
 * GPU stack (libOpenCL_adreno/libgsl/libCB) with zero bundling. The htp
 * profile keeps its verified exec-a-subprocess path.
 *
 * d8 constraint: no anonymous/inner classes/enums (bt34 NPE).
 */
final class JniServer {
    static {
        // v1.2.4: NDK verified runtime (v1.1 tree). The old Termux-lineage
        // stack (libggm * /libllm *) computes wrong logits on 8Gen2 (S23U GPU
        // garbled text, same root cause as the v1.2.2 CPU fix).
        // Dependency order (each DT_NEEDED chain is loaded transitively):
        //   ggml-base <- ggml-cpu / ggml-opencl / ggml <- llama <- mtmd
        //   <- llama-common <- llama-server-impl <- jnisrv
        // v1.2.4: libggml-opencl DT_NEEDED was rewritten libOCLstub.so ->
        // libOpenCL.so by build.sh (dynstr in-place patch), so this load now
        // resolves through the sphal vendor channel (manifest
        // uses-native-library) and hits the real Adreno driver. Before the
        // patch it resolved to the packaged stub -> platform IDs unavailable
        // -> silent CPU fallback (v1.2.3 "GPU 假可用").
        System.loadLibrary("ggml-base");
        System.loadLibrary("ggml-cpu");
        System.loadLibrary("ggml-opencl");
        System.loadLibrary("ggml");
        System.loadLibrary("llama");
        System.loadLibrary("mtmd");
        System.loadLibrary("llama-common");
        System.loadLibrary("llama-server-impl");
        System.loadLibrary("jnisrv");
    }

    /**
     * Blocking call running llama_server() on the calling thread; returns
     * the server exit code. writeFd (raw fd from a detached
     * ParcelFileDescriptor pipe write end) is dup2'ed onto fd 1 and 2, so
     * the entire server log flows to the Java-side reader. Call on a
     * dedicated thread — it blocks for the server's lifetime.
     */
    static native int startServer(String[] argv, int writeFd);

    /** Graceful stop: llama_server_terminate() (async-safe shutdown flag). */
    static native void stopServer();
}
