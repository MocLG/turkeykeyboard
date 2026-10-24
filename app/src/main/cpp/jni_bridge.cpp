#include <jni.h>

#include <string>

#include "translator_engine.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "TercumanNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

using tercuman::TranslatorEngine;

namespace {
std::string to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

std::string to_string(JNIEnv * env, jstring str) {
    if (!str) return {};
    const char * c = env->GetStringUTFChars(str, nullptr);
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(str, c);
    return s;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg.c_str());
}

TranslatorEngine * engine(jlong handle) {
    return reinterpret_cast<TranslatorEngine *>(handle);
}
}

extern "C" {
JNIEXPORT void JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring backend_dir) {
    const std::string dir = to_string(env, backend_dir);
    LOGI("Loading ggml backends from '%s'", dir.c_str());
    TranslatorEngine::global_init(dir);
}

JNIEXPORT jlong JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jstring model_path,
                                                         jint n_threads, jint n_ctx) {
    auto * e = new TranslatorEngine();
    tercuman::EngineConfig cfg;
    cfg.n_threads = n_threads > 0 ? n_threads : 4;
    cfg.n_ctx     = n_ctx > 0 ? n_ctx : 2048;
    std::string err;
    if (!e->load(to_string(env, model_path), cfg, err)) {
        delete e;
        throw_java(env, "java/io/IOException", err);
        return 0;
    }
    LOGI("Model loaded, %d threads, ctx %d", cfg.n_threads, cfg.n_ctx);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jbyteArray JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeGenerate(JNIEnv * env, jclass, jlong handle,
                                                             jbyteArray prompt_utf8, jint max_tokens,
                                                             jobject listener) {
    TranslatorEngine * e = engine(handle);
    if (!e) {
        throw_java(env, "java/lang/IllegalStateException", "engine is closed");
        return nullptr;
    }

    jmethodID on_partial = nullptr;
    if (listener) {
        jclass lc = env->GetObjectClass(listener);
        on_partial = env->GetMethodID(lc, "onPartial", "([B)Z");
        if (!on_partial) return nullptr;
    }

    tercuman::PartialCallback cb;
    if (listener) {
        cb = [&](const std::string & so_far) -> bool {
            jbyteArray arr = to_bytes(env, so_far);
            const jboolean keep = env->CallBooleanMethod(listener, on_partial, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                return false;
            }
            return keep == JNI_TRUE;
        };
    }

    const tercuman::TranslateResult r = e->generate(to_string(env, prompt_utf8), max_tokens, cb);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    LOGI("prompt %d tok in %.0f ms, output %d tok in %.0f ms", r.prompt_tokens, r.prompt_ms,
         r.output_tokens, r.gen_ms);
    if (r.cancelled) {
        throw_java(env, "java/util/concurrent/CancellationException", "translation cancelled");
        return nullptr;
    }
    if (!r.ok) {
        throw_java(env, "java/io/IOException", r.error);
        return nullptr;
    }
    return to_bytes(env, r.text);
}

JNIEXPORT void JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    if (TranslatorEngine * e = engine(handle)) e->cancel();
}

JNIEXPORT void JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    delete engine(handle);
}

JNIEXPORT jstring JNICALL
Java_com_tercuman_keyboard_engine_LlamaBridge_nativeSystemInfo(JNIEnv * env, jclass, jlong handle) {
    TranslatorEngine probe;
    TranslatorEngine * e = handle ? engine(handle) : &probe;
    return env->NewStringUTF(e->system_info().c_str());
}
}
