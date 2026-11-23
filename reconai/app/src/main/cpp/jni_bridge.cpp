// JNI glue for com.reconai.app.LlamaBridge
#include <jni.h>
#include <string>
#include "engine.h"

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// bytes instead of jstring: model output may contain 4-byte UTF-8 (emoji) that NewStringUTF rejects
static jbyteArray jbytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_reconai_app_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    rc_init(jstr(env, libDir).c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_com_reconai_app_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads) {
    return jbytes(env, rc_load(jstr(env, path).c_str(), nCtx, nThreads));
}

JNIEXPORT jboolean JNICALL
Java_com_reconai_app_LlamaBridge_nativeLoaded(JNIEnv *, jobject) {
    return rc_loaded() ? JNI_TRUE : JNI_FALSE;
}

// returns [ok-flag byte] + utf8 text: 1 = answer, 0 = error message
JNIEXPORT jbyteArray JNICALL
Java_com_reconai_app_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jbyteArray prompt, jint maxTokens, jfloat temp) {
    jsize n = env->GetArrayLength(prompt);
    std::string p((size_t) n, '\0');
    env->GetByteArrayRegion(prompt, 0, n, reinterpret_cast<jbyte *>(&p[0]));
    std::string err;
    std::string out = rc_generate(p, maxTokens, temp, err);
    if (!err.empty() && out.empty()) return jbytes(env, std::string(1, '\0') + err);
    return jbytes(env, std::string(1, '\1') + out);
}

JNIEXPORT void JNICALL
Java_com_reconai_app_LlamaBridge_nativeStop(JNIEnv *, jobject) { rc_stop(); }

JNIEXPORT void JNICALL
Java_com_reconai_app_LlamaBridge_nativeUnload(JNIEnv *, jobject) { rc_unload(); }

}
