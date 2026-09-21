// Thin JNI bridge for whisper.cpp (Palique local voice: STT leg).
// Port of local-speaking-poc's proven whisper_jni.cpp with a language hint so
// the multilingual model follows the session's learning language.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdint>

#include "whisper.h"

#define JNI_LOG_TAG "AppLocalEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, JNI_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_LOG_TAG, __VA_ARGS__)

namespace {

void whisper_log_to_logcat(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("whisper: %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGI("whisper: %s", text);
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jkdc_app_engines_local_JniWhisperRuntime_init(JNIEnv * env, jclass, jstring jModelPath) {
    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // CPU-only: stable and measurable.
    cparams.flash_attn = false;
    whisper_log_set(whisper_log_to_logcat, nullptr);
    whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper init failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_jkdc_app_engines_local_JniWhisperRuntime_free(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(handle));
    }
}

// Returns the transcribed text (segments joined). Empty string on silence/failure.
JNIEXPORT jstring JNICALL
Java_com_jkdc_app_engines_local_JniWhisperRuntime_transcribe(
        JNIEnv * env, jclass, jlong handle, jfloatArray jSamples, jint numSamples,
        jint threads, jstring jLanguage) {
    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || numSamples <= 0) {
        return env->NewStringUTF("");
    }

    jfloat * samples = env->GetFloatArrayElements(jSamples, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("");
    }

    const char * language = jLanguage ? env->GetStringUTFChars(jLanguage, nullptr) : "en";

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = threads > 0 ? threads : 4;
    params.language         = language;
    params.translate        = false;
    params.no_context       = true;
    params.no_timestamps    = true;
    params.single_segment   = false;
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.suppress_blank   = true;
    params.suppress_nst     = true;
    params.temperature      = 0.0f;
    params.greedy.best_of   = 1;

    const int rc = whisper_full(ctx, params, samples, numSamples);
    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);
    if (jLanguage) env->ReleaseStringUTFChars(jLanguage, language);

    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        if (!out.empty() && text[0] != ' ' && text[0] != ',' && text[0] != '.' &&
            text[0] != '?' && text[0] != '!' && text[0] != '\'') {
            out += ' ';
        }
        out += text;
    }
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
