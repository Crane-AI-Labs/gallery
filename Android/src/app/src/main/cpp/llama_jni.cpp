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

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeInitModel(
    JNIEnv *env,
    jobject /* this */,
    jstring modelPath,
    jint nCtx,
    jint nGpuLayers
) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("initModel: %s, nCtx=%d, nGpuLayers=%d", path, nCtx, nGpuLayers);

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

    // Tokenize prompt
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

    LOGI("Prompt tokenized: %d tokens", n_tokens);

    // Evaluate prompt in batches (n_batch = 512)
    const int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int n_eval = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(inf_ctx->ctx, batch) != 0) {
            LOGE("Failed to evaluate prompt at position %d", i);
            return env->NewStringUTF("{\"error\":\"Failed to evaluate prompt\"}");
        }
    }

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

        // Prepare next batch
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

    // Clear KV cache for next completion (fast — no context recreation)
    llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);

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

    // Clear KV cache for next completion
    llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);

    return env->NewStringUTF(json.c_str());
}

} // extern "C"
