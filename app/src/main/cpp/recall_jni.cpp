// JNI bridge between Recall (Kotlin) and llama.cpp.
//
// One Session holds a loaded model + context. Every generate() call starts from an empty
// KV cache: Recall's prompts are one-shot (digest, a chat turn with its own retrieved
// context), so there is no conversation state to keep on the native side.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "llama.h"
#include "ggml-backend.h"

#define TAG "RecallLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr templates;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> cancel{false};
};

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

// Length of the longest prefix of `s` that is complete UTF-8 (a token can end mid-character).
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const auto c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if ((c & 0x80) == 0x00) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        if (i + len > n) break;
        i += len;
    }
    return i;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

std::string from_jstring(JNIEnv * env, jstring js) {
    if (!js) return {};
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

std::string format_prompt(Session * s, const std::string & system, const std::string & user, bool thinking) {
    if (!s->templates) return system + "\n\n" + user + "\n\n";
    common_chat_msg sys;
    sys.role = "system";
    sys.content = system;
    common_chat_msg usr;
    usr.role = "user";
    usr.content = user;

    common_chat_templates_inputs inputs;
    inputs.messages = {sys, usr};
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = thinking;
    try {
        inputs.use_jinja = true;
        return common_chat_templates_apply(s->templates.get(), inputs).prompt;
    } catch (const std::exception & e) {
        LOGW("jinja template failed (%s), falling back to built-in template", e.what());
    }
    try {
        inputs.use_jinja = false;
        return common_chat_templates_apply(s->templates.get(), inputs).prompt;
    } catch (const std::exception & e) {
        LOGE("chat template failed: %s", e.what());
    }
    return system + "\n\n" + user + "\n\n";
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_recall_model_LlamaNative_init(JNIEnv * env, jobject, jstring jlib_dir) {
    llama_log_set(log_callback, nullptr);
    const std::string dir = from_jstring(env, jlib_dir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    LOGI("backends loaded from %s", dir.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_recall_model_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_recall_model_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = from_jstring(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("failed to load model %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->n_ctx = n_ctx;
    try {
        s->templates = common_chat_templates_init(model, "");
    } catch (const std::exception & e) {
        LOGW("no usable chat template: %s", e.what());
    }
    llama_set_abort_callback(ctx, [](void * data) -> bool {
        return static_cast<Session *>(data)->cancel.load();
    }, s);
    LOGI("model loaded: ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_recall_model_LlamaNative_countTokens(JNIEnv * env, jobject, jlong handle, jbyteArray jtext) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return -1;
    try {
        return static_cast<jint>(common_tokenize(s->ctx, from_bytes(env, jtext), false, true).size());
    } catch (const std::exception &) {
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_recall_model_LlamaNative_setThreads(JNIEnv *, jobject, jlong handle, jint n) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) llama_set_n_threads(s->ctx, n, n);
}

extern "C" JNIEXPORT void JNICALL
Java_app_recall_model_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) s->cancel.store(true);
}

// Returns the generated text as UTF-8 bytes. Streams pieces to callback.onToken(byte[]).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_recall_model_LlamaNative_generate(JNIEnv * env, jobject, jlong handle,
                                          jbyteArray jsystem, jbyteArray juser,
                                          jint max_tokens, jfloat temperature,
                                          jboolean thinking, jobject callback) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return to_bytes(env, "");
    s->cancel.store(false);

    jmethodID on_token = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "([B)V");
    }

    std::vector<llama_token> tokens;
    try {
        const std::string prompt = format_prompt(s, from_bytes(env, jsystem), from_bytes(env, juser), thinking);
        tokens = common_tokenize(s->ctx, prompt, true, true);
    } catch (const std::exception & e) {
        LOGE("tokenize failed: %s", e.what());
        return to_bytes(env, "");
    }

    const int budget = s->n_ctx - max_tokens - 8;
    if (budget <= 0) return to_bytes(env, "");
    if ((int) tokens.size() > budget) {
        // Keep the start (system prompt + instructions) and the end (the question and
        // generation prompt); drop the middle, which is the least important context.
        const int head = budget / 3;
        const int tail = budget - head;
        std::vector<llama_token> cut(tokens.begin(), tokens.begin() + head);
        cut.insert(cut.end(), tokens.end() - tail, tokens.end());
        LOGW("prompt too long (%d tokens), trimmed to %d", (int) tokens.size(), (int) cut.size());
        tokens.swap(cut);
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    // Prompt processing in batches; only the final position needs logits.
    llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
    for (size_t i = 0; i < tokens.size(); i += s->n_batch) {
        const size_t n = std::min(tokens.size() - i, (size_t) s->n_batch);
        common_batch_clear(batch);
        for (size_t j = 0; j < n; j++) {
            const bool last = (i + j == tokens.size() - 1);
            common_batch_add(batch, tokens[i + j], (llama_pos) (i + j), {0}, last);
        }
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("prompt decode failed");
            llama_batch_free(batch);
            return to_bytes(env, "");
        }
        if (s->cancel.load()) {
            llama_batch_free(batch);
            return to_bytes(env, "");
        }
    }

    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_k = 40;
    sparams.top_p = 0.9f;
    sparams.min_p = 0.05f;
    sparams.penalty_last_n = 64;
    sparams.penalty_repeat = 1.08f;
    common_sampler * sampler = common_sampler_init(s->model, sparams);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    std::string out;
    std::string pending;
    llama_pos pos = (llama_pos) tokens.size();

    for (int i = 0; i < max_tokens && pos < s->n_ctx - 1; i++) {
        if (s->cancel.load()) break;
        const llama_token id = common_sampler_sample(sampler, s->ctx, -1);
        common_sampler_accept(sampler, id, true);
        if (llama_vocab_is_eog(vocab, id)) break;

        pending += common_token_to_piece(s->ctx, id, true);
        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            const std::string piece = pending.substr(0, ok);
            pending.erase(0, ok);
            out += piece;
            if (on_token) {
                jbyteArray arr = to_bytes(env, piece);
                env->CallVoidMethod(callback, on_token, arr);
                env->DeleteLocalRef(arr);
            }
        }

        common_batch_clear(batch);
        common_batch_add(batch, id, pos, {0}, true);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode failed at %d", pos);
            break;
        }
        pos++;
    }

    common_sampler_free(sampler);
    llama_batch_free(batch);
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_app_recall_model_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    s->templates.reset();
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
