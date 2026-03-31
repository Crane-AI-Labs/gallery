#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct inference_context {
    llama_model * model;
    llama_context * ctx;
    llama_sampler * sampler;
    mtmd_context * mtmd_ctx;  // vision encoder, nullptr if not loaded
    bool stop_requested;
    float temperature;
    int top_k;
    float top_p;
    int n_past;               // number of tokens currently in the KV cache
    std::vector<llama_token> cache_tokens;  // token history matching KV cache contents
};

static void rebuild_sampler(inference_context * inf_ctx, float temperature, int top_k, float top_p) {
    if (inf_ctx->sampler) {
        llama_sampler_free(inf_ctx->sampler);
    }
    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    inf_ctx->sampler = sampler;
    inf_ctx->temperature = temperature;
    inf_ctx->top_k = top_k;
    inf_ctx->top_p = top_p;
}

extern "C" {

// Map integer KV cache type codes to ggml types
// 0=f16 (default), 1=q8_0, 2=q4_0, 3=turbo3, 4=turbo4
static enum lm_ggml_type kv_type_from_code(int code) {
    switch (code) {
        case 1:  return LM_GGML_TYPE_Q8_0;
        case 2:  return LM_GGML_TYPE_Q4_0;
        case 3:  return LM_GGML_TYPE_TURBO3_0;
        case 4:  return LM_GGML_TYPE_TURBO4_0;
        default: return LM_GGML_TYPE_F16;
    }
}

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeInitModel(
    JNIEnv *env,
    jobject /* this */,
    jstring modelPath,
    jint nCtx,
    jint nGpuLayers,
    jint kvCacheType
) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("initModel: %s, nCtx=%d, nGpuLayers=%d, kvCacheType=%d", path, nCtx, nGpuLayers, kvCacheType);

    llama_backend_init();

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;

    llama_model * model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = 512;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    // TurboQuant KV cache compression (arXiv 2504.19874)
    enum lm_ggml_type kv_type = kv_type_from_code(kvCacheType);
    ctx_params.type_k = kv_type;
    ctx_params.type_v = kv_type;
    LOGI("KV cache type: %d (ggml type %d)", kvCacheType, (int)kv_type);

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * inf_ctx = new inference_context();
    inf_ctx->model = model;
    inf_ctx->ctx = ctx;
    inf_ctx->sampler = nullptr;
    inf_ctx->mtmd_ctx = nullptr;
    inf_ctx->stop_requested = false;
    inf_ctx->n_past = 0;

    // Default sampler params
    rebuild_sampler(inf_ctx, 0.7f, 40, 0.9f);

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(inf_ctx);
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeCompletion(
    JNIEnv *env,
    jobject /* this */,
    jlong handle,
    jstring prompt,
    jint nPredict,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jstring stopSequences,
    jobject callback
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->ctx) {
        return env->NewStringUTF("{\"error\":\"Invalid context\"}");
    }

    inf_ctx->stop_requested = false;

    // Rebuild sampler if params changed
    if (temperature != inf_ctx->temperature ||
        topK != inf_ctx->top_k ||
        topP != inf_ctx->top_p) {
        rebuild_sampler(inf_ctx, temperature, topK, topP);
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    // Tokenize the full prompt (entire conversation so far)
    const llama_vocab * vocab = llama_model_get_vocab(inf_ctx->model);
    std::vector<llama_token> tokens(prompt_str.size() + 16);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    // --- KV cache reuse: find common prefix with cached tokens ---
    int n_ctx = llama_n_ctx(inf_ctx->ctx);
    int n_keep = 0;  // how many cached tokens match the new prompt prefix

    // Check if the new prompt extends the cached token sequence
    int n_cached = (int)inf_ctx->cache_tokens.size();
    for (int i = 0; i < std::min(n_cached, n_tokens); i++) {
        if (inf_ctx->cache_tokens[i] == tokens[i]) {
            n_keep++;
        } else {
            break;
        }
    }

    // If the cache diverged from the new prompt, we need to truncate the KV cache
    // back to the common prefix point
    if (n_keep < n_cached) {
        // Remove KV cache entries beyond the common prefix
        // llama_kv_self_seq_rm removes tokens from pos [p0, p1)
        // We keep [0, n_keep) and remove [n_keep, n_cached)
        llama_memory_seq_rm(llama_get_memory(inf_ctx->ctx), 0, n_keep, n_cached);
        inf_ctx->cache_tokens.resize(n_keep);
        inf_ctx->n_past = n_keep;
        LOGI("KV cache truncated: kept %d of %d cached tokens", n_keep, n_cached);
    }

    int n_new = n_tokens - n_keep;  // tokens that need processing

    // Safety: if total tokens after generation might exceed n_ctx, clear and reprocess
    // Reserve space for generation (nPredict tokens)
    if (n_tokens + nPredict > n_ctx) {
        LOGI("Prompt + nPredict (%d + %d = %d) exceeds n_ctx (%d), clearing cache",
             n_tokens, nPredict, n_tokens + nPredict, n_ctx);
        llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);
        inf_ctx->cache_tokens.clear();
        inf_ctx->n_past = 0;
        n_keep = 0;
        n_new = n_tokens;
    }

    LOGI("Prompt: %d tokens, cached: %d reused, %d new to process", n_tokens, n_keep, n_new);

    // Evaluate only the NEW tokens (skip the common prefix already in KV cache)
    const int n_batch = 512;
    for (int i = n_keep; i < n_tokens; i += n_batch) {
        int n_eval = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(inf_ctx->ctx, batch) != 0) {
            LOGE("Failed to evaluate prompt at position %d", i);
            return env->NewStringUTF("{\"error\":\"Failed to evaluate prompt\"}");
        }
    }

    // Update cache tracking: the KV cache now holds all prompt tokens
    inf_ctx->cache_tokens = tokens;
    inf_ctx->n_past = n_tokens;

    // Generate tokens
    std::string result_text;
    int n_generated = 0;

    for (int i = 0; i < nPredict; i++) {
        if (inf_ctx->stop_requested) {
            break;
        }

        llama_token new_token = llama_sampler_sample(inf_ctx->sampler, inf_ctx->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_str(buf, n);
            result_text += token_str;
            n_generated++;

            // Stream token via callback
            jstring jtoken = env->NewStringUTF(token_str.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
        }

        // Track generated token in cache
        inf_ctx->cache_tokens.push_back(new_token);
        inf_ctx->n_past++;

        // Prepare next batch
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(inf_ctx->ctx, next_batch) != 0) {
            LOGE("Failed to decode token %d", i);
            break;
        }
    }

    // Build result JSON — include cache stats for Kotlin layer
    std::string json = "{\"text\":\"";
    for (char c : result_text) {
        switch (c) {
            case '"': json += "\\\""; break;
            case '\\': json += "\\\\"; break;
            case '\n': json += "\\n"; break;
            case '\r': json += "\\r"; break;
            case '\t': json += "\\t"; break;
            default: json += c;
        }
    }
    json += "\",\"tokens_generated\":" + std::to_string(n_generated);
    json += ",\"tokens_cached\":" + std::to_string(inf_ctx->n_past);
    json += ",\"prompt_tokens_reused\":" + std::to_string(n_keep);
    json += "}";

    // NOTE: KV cache is intentionally NOT cleared here.
    // The cache persists across turns so multi-turn conversation
    // doesn't re-process system prompt + history each time.
    // Call nativeClearContext() explicitly when starting a new conversation.

    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeStopCompletion(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (inf_ctx) {
        inf_ctx->stop_requested = true;
    }
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeClearContext(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (inf_ctx && inf_ctx->ctx) {
        llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);
        inf_ctx->cache_tokens.clear();
        inf_ctx->n_past = 0;
        LOGI("KV cache cleared (new conversation)");
    }
}

JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeGetCacheTokenCount(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx) return 0;
    return inf_ctx->n_past;
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeReleaseModel(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (inf_ctx) {
        if (inf_ctx->mtmd_ctx) {
            mtmd_free(inf_ctx->mtmd_ctx);
        }
        if (inf_ctx->sampler) {
            llama_sampler_free(inf_ctx->sampler);
        }
        if (inf_ctx->ctx) {
            llama_free(inf_ctx->ctx);
        }
        if (inf_ctx->model) {
            llama_model_free(inf_ctx->model);
        }
        delete inf_ctx;
        LOGI("Model released");
    }
}

// --- Vision / Multimodal support ---

JNIEXPORT jboolean JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeInitVision(
    JNIEnv *env,
    jobject /* this */,
    jlong handle,
    jstring mmprojPath
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->model) {
        LOGE("initVision: invalid context");
        return JNI_FALSE;
    }

    // Release existing vision context if any
    if (inf_ctx->mtmd_ctx) {
        mtmd_free(inf_ctx->mtmd_ctx);
        inf_ctx->mtmd_ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(mmprojPath, nullptr);
    LOGI("initVision: loading mmproj from %s", path);

    auto params = mtmd_context_params_default();
    params.use_gpu = false;  // CPU only for now
    params.n_threads = 4;

    inf_ctx->mtmd_ctx = mtmd_init_from_file(path, inf_ctx->model, params);
    env->ReleaseStringUTFChars(mmprojPath, path);

    if (!inf_ctx->mtmd_ctx) {
        LOGE("Failed to load vision encoder");
        return JNI_FALSE;
    }

    LOGI("Vision encoder loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeCompletionWithImage(
    JNIEnv *env,
    jobject /* this */,
    jlong handle,
    jstring prompt,
    jbyteArray imageData,
    jint nPredict,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jobject callback
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->ctx || !inf_ctx->mtmd_ctx) {
        return env->NewStringUTF("{\"error\":\"Invalid context or vision not initialized\"}");
    }

    inf_ctx->stop_requested = false;

    // Rebuild sampler if params changed
    if (temperature != inf_ctx->temperature ||
        topK != inf_ctx->top_k ||
        topP != inf_ctx->top_p) {
        rebuild_sampler(inf_ctx, temperature, topK, topP);
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    // Get image bytes
    jsize imgLen = env->GetArrayLength(imageData);
    jbyte *imgBytes = env->GetByteArrayElements(imageData, nullptr);

    LOGI("completionWithImage: prompt=%zu chars, image=%d bytes", prompt_str.size(), imgLen);

    // Create bitmap from image data (supports jpg, png, etc via stb_image)
    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(
        inf_ctx->mtmd_ctx,
        reinterpret_cast<const unsigned char *>(imgBytes),
        imgLen
    );
    env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);

    if (!bitmap) {
        LOGE("Failed to decode image");
        return env->NewStringUTF("{\"error\":\"Failed to decode image\"}");
    }

    // Tokenize prompt with image marker
    // The prompt should contain <__media__> where the image should be inserted
    mtmd_input_text input_text;
    input_text.text = prompt_str.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    const mtmd_bitmap * bitmaps[] = { bitmap };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();

    int32_t ret = mtmd_tokenize(inf_ctx->mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);

    if (ret != 0) {
        LOGE("Failed to tokenize multimodal input (error %d)", ret);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("{\"error\":\"Failed to tokenize multimodal input\"}");
    }

    // Evaluate all chunks (text + image)
    llama_pos n_past = 0;
    ret = mtmd_helper_eval_chunks(
        inf_ctx->mtmd_ctx, inf_ctx->ctx, chunks,
        n_past, 0, 512, true, &n_past
    );
    mtmd_input_chunks_free(chunks);

    if (ret != 0) {
        LOGE("Failed to evaluate multimodal input (error %d)", ret);
        return env->NewStringUTF("{\"error\":\"Failed to evaluate multimodal input\"}");
    }

    LOGI("Multimodal prompt evaluated, n_past=%d", n_past);

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    const llama_vocab * vocab = llama_model_get_vocab(inf_ctx->model);

    // Generate tokens
    std::string result_text;
    int n_generated = 0;

    for (int i = 0; i < nPredict; i++) {
        if (inf_ctx->stop_requested) break;

        llama_token new_token = llama_sampler_sample(inf_ctx->sampler, inf_ctx->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_str(buf, n);
            result_text += token_str;
            n_generated++;

            jstring jtoken = env->NewStringUTF(token_str.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(inf_ctx->ctx, next_batch) != 0) {
            LOGE("Failed to decode token %d", i);
            break;
        }
    }

    // Build result JSON
    std::string json = "{\"text\":\"";
    for (char c : result_text) {
        switch (c) {
            case '"': json += "\\\""; break;
            case '\\': json += "\\\\"; break;
            case '\n': json += "\\n"; break;
            case '\r': json += "\\r"; break;
            case '\t': json += "\\t"; break;
            default: json += c;
        }
    }
    json += "\",\"tokens_generated\":" + std::to_string(n_generated) + "}";

    // Update cache tracking for vision completions.
    // Vision completions use mtmd_helper_eval_chunks which manages positions internally,
    // so we track n_past but don't try prefix-matching for vision turns.
    inf_ctx->n_past = n_past + n_generated;
    inf_ctx->cache_tokens.clear();  // can't do prefix-match after vision chunks

    // NOTE: KV cache is intentionally NOT cleared here.
    // Call nativeClearContext() explicitly when starting a new conversation.

    return env->NewStringUTF(json.c_str());
}

} // extern "C"
