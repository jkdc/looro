// Thin JNI bridge for llama.cpp (Mural local conversation engine).
// Port of local-speaking-poc's proven llama_jni.cpp with two changes:
//   - JNI symbols live under chat.mural.engines.local.LlamaNative
//   - the POC-specific fallback grammar is gone: this engine serves arbitrary
//     Mural schemas, so when llama.cpp's chat-template machinery yields no
//     grammar we generate unconstrained rather than guess a shape.
// All timing is measured on the Kotlin side to keep metrics consistent.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdint>
#include <algorithm>

#include "llama.h"
#include "common.h"
#include "chat.h"

#define JNI_LOG_TAG "MuralLocalEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, JNI_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_LOG_TAG, __VA_ARGS__)

namespace {

void llama_log_to_logcat(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("llama: %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGI("llama: %s", text);
    }
}

struct LlamaSession {
    llama_model *             model = nullptr;
    llama_context *           ctx   = nullptr;
    const llama_vocab *       vocab = nullptr;
    common_chat_templates_ptr tmpls; // frees itself
    uint32_t                  n_ctx   = 2048;
    uint32_t                  n_batch = 256;
};

jmethodID findMethod(JNIEnv * env, jobject obj, const char * name, const char * sig) {
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) {
        return nullptr;
    }
    jmethodID id = env->GetMethodID(cls, name, sig);
    env->DeleteLocalRef(cls);
    return id;
}

bool isAborted(JNIEnv * env, jobject abortFlag, jmethodID getMethod) {
    if (abortFlag == nullptr || getMethod == nullptr) {
        return false;
    }
    return env->CallBooleanMethod(abortFlag, getMethod) == JNI_TRUE;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_chat_mural_engines_local_LlamaNative_nativeInit(
        JNIEnv * env, jclass, jstring jModelPath,
        jint nCtx, jint nThreads, jint nBatch) {
    const char * path = env->GetStringUTFChars(jModelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only: stable and measurable on every device.

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (model == nullptr) {
        LOGE("llama model load failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = nCtx > 0 ? (uint32_t) nCtx : 2048;
    cparams.n_batch         = nBatch > 0 ? (uint32_t) nBatch : 256;
    cparams.n_ubatch        = nBatch > 0 ? (uint32_t) nBatch : 256;
    cparams.n_threads       = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = nThreads > 0 ? nThreads : 4;
    cparams.no_perf         = false;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama context init failed");
        llama_model_free(model);
        return 0;
    }

    // Route llama.cpp logging to Logcat so inference errors are visible.
    llama_log_set(llama_log_to_logcat, nullptr);

    auto * session = new LlamaSession();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx   = cparams.n_ctx;
    session->n_batch = cparams.n_batch;
    session->tmpls = common_chat_templates_init(model, ""); // "" = template from GGUF metadata
    LOGI("llama session ready ctx=%u threads=%d", cparams.n_ctx, nThreads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_chat_mural_engines_local_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr) {
        return;
    }
    if (session->ctx != nullptr) {
        llama_free(session->ctx);
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
    }
    delete session;
}

// messages: parallel arrays of role/content. out (jlongArray of size 2) receives
// {promptTokens, generatedTokens}. Returns 0 on success, negative on error:
//  -1 generic failure, -2 prompt too large for context, -3 aborted by caller.
JNIEXPORT jint JNICALL
Java_chat_mural_engines_local_LlamaNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle,
        jobjectArray jRoles, jobjectArray jContents,
        jstring jSchema, jboolean jUseGrammar,
        jint maxTokens, jfloat temperature, jint seed,
        jobject tokenCallback, jobject abortFlag, jlongArray jOutStats) {

    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->ctx == nullptr || session->model == nullptr) {
        return -1;
    }

    // ---- 1. Chat template -------------------------------------------------
    std::vector<common_chat_msg> messages;
    const jsize n_messages = env->GetArrayLength(jRoles);
    messages.reserve(n_messages);
    for (jsize i = 0; i < n_messages; ++i) {
        auto * jRole    = (jstring) env->GetObjectArrayElement(jRoles, i);
        auto * jContent = (jstring) env->GetObjectArrayElement(jContents, i);
        const char * role    = jRole    ? env->GetStringUTFChars(jRole, nullptr)    : "";
        const char * content = jContent ? env->GetStringUTFChars(jContent, nullptr) : "";
        common_chat_msg msg;
        msg.role    = role;
        msg.content = content;
        messages.push_back(std::move(msg));
        if (jRole)    env->ReleaseStringUTFChars(jRole, role);
        if (jContent) env->ReleaseStringUTFChars(jContent, content);
    }

    const char * schema = jSchema ? env->GetStringUTFChars(jSchema, nullptr) : nullptr;
    const bool   wantGrammar = jUseGrammar == JNI_TRUE && schema != nullptr && schema[0] != '\0';

    common_chat_templates_inputs inputs;
    inputs.messages              = messages;
    inputs.add_generation_prompt = true;
    inputs.use_jinja             = true;
    inputs.enable_thinking       = false;
    if (wantGrammar) {
        inputs.json_schema = schema;
    }

    common_chat_params chat = common_chat_templates_apply(session->tmpls.get(), inputs);
    if (schema) {
        env->ReleaseStringUTFChars(jSchema, schema);
    }

    const std::string grammar = chat.grammar; // empty => generate unconstrained (generic schemas)

    // ---- 2. Tokenize prompt ----------------------------------------------
    const std::string & prompt = chat.prompt;
    const int n_prompt_max = (int) session->n_ctx - maxTokens - 8;
    if (n_prompt_max <= 0) {
        LOGE("context too small: ctx=%u maxTokens=%d", session->n_ctx, maxTokens);
        return -2;
    }
    std::vector<llama_token> prompt_tokens((size_t) n_prompt_max);
    const int n_prompt = llama_tokenize(
        session->vocab, prompt.c_str(), (int32_t) prompt.size(),
        prompt_tokens.data(), n_prompt_max, /* add_special */ true, /* parse_special */ true);
    if (n_prompt <= 0) {
        LOGE("tokenization failed (prompt %zu bytes)", prompt.size());
        return -2;
    }
    prompt_tokens.resize((size_t) n_prompt);

    // ---- 3. Sampler chain --------------------------------------------------
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler_chain_add(smpl, llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root"));
    }
    const int32_t n_vocab = llama_vocab_n_tokens(session->vocab);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, /*last_n*/ 64, /*repeat*/ 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed >= 0 ? (uint32_t) seed : LLAMA_DEFAULT_SEED));

    // ---- 4. Generation loop ------------------------------------------------
    llama_memory_clear(llama_get_memory(session->ctx), /*data*/ true);

    jmethodID onToken = tokenCallback ? findMethod(env, tokenCallback, "onToken", "(Ljava/lang/String;)V") : nullptr;
    jmethodID isAbort = abortFlag     ? findMethod(env, abortFlag, "get", "()Z") : nullptr;

    auto emitToken = [&](const std::string & piece) -> bool {
        if (onToken == nullptr) {
            return true;
        }
        jstring jPiece = env->NewStringUTF(piece.c_str());
        if (jPiece == nullptr) {
            return false; // OOM in JVM
        }
        env->CallVoidMethod(tokenCallback, onToken, jPiece);
        env->DeleteLocalRef(jPiece);
        return true;
    };

    int64_t prompt_tokens_val = n_prompt;
    int64_t generated_tokens  = 0;

    // Prompt must be fed in chunks of at most n_batch tokens (ggml asserts).
    llama_token new_token_id;
    int status = 0;
    for (int i = 0; i < n_prompt && status == 0; i += (int) session->n_batch) {
        if (isAborted(env, abortFlag, isAbort)) {
            status = -3;
            break;
        }
        const int n_chunk = std::min((int) session->n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + i, n_chunk);
        if (llama_decode(session->ctx, batch)) {
            LOGE("llama_decode failed on prompt chunk");
            status = -1;
        }
    }

    while (status == 0) {
        if (isAborted(env, abortFlag, isAbort)) {
            status = -3;
            break;
        }
        new_token_id = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, new_token_id)) {
            break;
        }

        char piece_buf[256];
        const int n_piece = llama_token_to_piece(
            session->vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece > 0) {
            if (!emitToken(std::string(piece_buf, (size_t) n_piece))) {
                status = -1;
                break;
            }
        }
        ++generated_tokens;

        if (generated_tokens >= maxTokens) {
            break;
        }
        llama_batch batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(session->ctx, batch)) {
            LOGE("llama_decode failed during generation");
            status = -1;
        }
    }

    if (jOutStats != nullptr && env->GetArrayLength(jOutStats) >= 2) {
        jlong stats[2] = { (jlong) prompt_tokens_val, (jlong) generated_tokens };
        env->SetLongArrayRegion(jOutStats, 0, 2, stats);
    }

    llama_sampler_free(smpl);
    return status;
}

} // extern "C"
