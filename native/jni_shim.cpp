// JNI shim: runs llama_server() in-process so the app classloader
// namespace resolves libOpenCL.so via the vendor public-libraries channel.
// Calls the C++ symbols by their Itanium-mangled names exactly as the
// compiler would mangle the declarations below (verified with llvm-nm:
// _Z12llama_serveriPPc / _Z22llama_server_terminatev).
#include <jni.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>

int llama_server(int argc, char **argv);
void llama_server_terminate(void);

extern "C" JNIEXPORT jint JNICALL
Java_com_dawoer_npullm_JniServer_startServer(JNIEnv *env, jclass,
                                             jobjectArray argvArr, jint writeFd) {
    const jint argc = env->GetArrayLength(argvArr);
    char **argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    if (argv == nullptr) return -100;
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring) env->GetObjectArrayElement(argvArr, i);
        const char *s = env->GetStringUTFChars(js, nullptr);
        argv[i] = strdup(s != nullptr ? s : "");
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    // route ALL server output (llama.cpp logs to stderr) into the pipe
    dup2(writeFd, STDOUT_FILENO);
    dup2(writeFd, STDERR_FILENO);
    close(writeFd);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    int code = llama_server(argc, argv);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return code;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dawoer_npullm_JniServer_stopServer(JNIEnv *, jclass) {
    llama_server_terminate();
}
