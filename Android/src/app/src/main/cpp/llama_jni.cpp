#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <limits>
#include <ctime>
#include <android/log.h>
#include <sys/system_properties.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Route llama.cpp + ggml/clip internal logs (incl. mtmd print_timings and clip's
// "flash attention is enabled/disabled" line) to logcat so we can actually see
// the vision-encode breakdown. Without this, LOG_INF/LOG_WRN from clip.cpp go
// nowhere on Android.
static void gallery_log_cb(enum lm_ggml_log_level level, const char * text, void * /*ud*/) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    if (level == LM_GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == LM_GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    __android_log_write(prio, "LlamaCppLog", text);
}

static void install_log_routing_once() {
    static bool done = false;
    if (done) return;
    done = true;
    // Route ONLY the mtmd/clip logger to logcat — the vision diagnostics we want
    // in the field (flash-attn status, reduced-resolution confirmation, mtmd
    // print_timings, encode errors) at low volume. We deliberately do NOT route
    // llama_log_set / lm_ggml_log_set: those dump ~1000 lines of model-loader /
    // ggml metadata on every launch, which is noise in a production build.
    mtmd_log_set(gallery_log_cb, nullptr);
}

// Monotonic milliseconds for on-device stage timing.
static double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1.0e6;
}

// ─── Single-model speculative decoding: ngram-map-k4v ───────────────────────
// Port of llama.cpp common/ngram-map.{h,cpp} (ref: ggml-org/llama.cpp PR-18471)
// into this JNI so it works against the prebuilt librnllama (which has no
// common/ library). "k4v" = each key n-gram tracks up to 4 candidate value
// m-grams with occurrence statistics; a draft is proposed only when one value
// clearly dominates (max_occur >= 2 * sum_others). Crane Mesh measured this
// variant at +9.5% tok/s on the Galaxy A17 with MedGemma-4B Q4, lossless.
// Single-stream only — never combine with batched serving.

#define SPEC_NGRAM_MAX_VALUES 4
#define SPEC_NGRAM_HASH_MAP_SIZE 262144
#define SPEC_NGRAM_MAX_VALUE_COUNT 16380

struct spec_ngram_value {
    size_t   value_idx  = 0;
    uint16_t value_num  = 0;
    int16_t  n_accepted = -1;
};

struct spec_ngram_key {
    size_t   key_idx;
    size_t   stat_idx;
    uint16_t key_num;
    spec_ngram_value values[SPEC_NGRAM_MAX_VALUES];
};

struct spec_ngram_map {
    // Tuned DOWN from llama.cpp defaults (n=12, m=48). Those defaults target
    // long/repetitive generation (code, reasoning). This app emits ~150-token
    // clinical XML where 12-token exact repeats never occur → measured
    // rounds=0. n=4 catches structural repeats (tags, bullet scaffolding);
    // m=16 keeps drafts short so mis-speculation is cheap.
    uint16_t size_key   = 4;
    uint16_t size_value = 16;
    uint16_t min_hits   = 1;   // llama.cpp default ngram_min_hits

    std::vector<spec_ngram_key> keys;

    size_t   size_last_begin      = 0;
    bool     last_draft_created   = false;
    size_t   last_draft_key_idx   = 0;
    uint16_t last_draft_value_idx = 0;
    size_t   idx_last_check       = 0;

    std::vector<uint32_t> key_map;   // hash -> ngram index in history
    uint32_t key_map_last_idx = 0;

    spec_ngram_map() { key_map.resize(SPEC_NGRAM_HASH_MAP_SIZE); }
};

// LCG hash (prime near (sqrt(5)-1)/2 * 2^32), identical to upstream.
static uint32_t spec_ngram_hash(const std::vector<llama_token> & tokens, size_t start, size_t len) {
    uint32_t hash = 0;
    for (size_t i = 0; i < len; ++i) {
        hash = hash * 2654435761UL + tokens[start + i];
    }
    return hash;
}

// Reset/refresh the map when the token history shrank (KV truncation between
// turns). Mirrors common_ngram_map_begin.
static void spec_ngram_begin(spec_ngram_map & map, const std::vector<llama_token> & tokens) {
    const size_t size_begin = tokens.size();

    if (!map.key_map.empty() && size_begin < map.idx_last_check) {
        for (size_t i = 0; i < map.key_map.size(); ++i) {
            if (map.key_map[i] >= map.size_last_begin) {
                map.key_map[i] = 0;
            }
        }
        map.key_map_last_idx = (map.size_last_begin > 0) ? (uint32_t)(map.size_last_begin - 1) : 0;
    }

    if (size_begin < map.idx_last_check && !map.keys.empty()) {
        for (int32_t i = (int32_t)map.keys.size() - 1; i >= 0; --i) {
            spec_ngram_key & key = map.keys[i];
            if (key.key_idx >= map.size_last_begin) {
                map.keys.erase(map.keys.begin() + i);
                continue;
            }
            for (int16_t j = SPEC_NGRAM_MAX_VALUES - 1; j >= 0; --j) {
                spec_ngram_value & value = key.values[j];
                if (value.value_idx >= map.size_last_begin) {
                    for (uint16_t k = j; k < SPEC_NGRAM_MAX_VALUES - 1; ++k) {
                        key.values[k] = key.values[k + 1];
                    }
                    key.values[SPEC_NGRAM_MAX_VALUES - 1].value_idx = 0;
                    key.values[SPEC_NGRAM_MAX_VALUES - 1].value_num = 0;
                }
            }
            if (key.values[0].value_idx == 0) {
                map.keys.erase(map.keys.begin() + i);
            }
        }
    }

    map.idx_last_check   = (map.size_last_begin > 0) ? map.size_last_begin - 1 : 0;
    map.size_last_begin  = size_begin;
}

// Propose a draft continuation for `sampled` given history `inp` (which does
// NOT include `sampled`). Mirrors common_ngram_map_draft (k4v mode).
static void spec_ngram_draft(spec_ngram_map & map,
        const std::vector<llama_token> & inp, llama_token sampled,
        std::vector<llama_token> & draft) {
    map.last_draft_created   = false;
    map.last_draft_key_idx   = 0;
    map.last_draft_value_idx = 0;

    const size_t   cur_len = inp.size();
    const uint16_t n = map.size_key;
    const uint16_t m = map.size_value;
    if (cur_len < (size_t)(2 * n + m)) return;
    if (map.idx_last_check > cur_len)  return;  // defensive (upstream aborts)
    map.idx_last_check = cur_len;

    std::vector<llama_token> key_tokens;
    key_tokens.reserve(n);
    for (size_t j = cur_len - n + 1; j < cur_len; ++j) key_tokens.push_back(inp[j]);
    key_tokens.push_back(sampled);

    size_t match_pos = 0;
    if (map.size_last_begin > cur_len) return;  // defensive

    if (!map.key_map.empty()) {
        uint32_t idx_hash = spec_ngram_hash(key_tokens, 0, n) % map.key_map.size();
        uint32_t idx_key  = map.key_map[idx_hash];
        if (idx_key != 0 && (size_t)idx_key < cur_len - n - m - 1) {
            bool match = true;
            for (size_t k = 0; k < n; ++k) {
                if (inp[idx_key + k] != key_tokens[k]) { match = false; break; }
            }
            if (match) match_pos = idx_key;
        }
    }
    if (match_pos == 0 && map.size_last_begin > (size_t)(n + m + 1)) {
        for (size_t j = map.size_last_begin - n - m - 1; j > map.key_map_last_idx; --j) {
            bool match = true;
            for (size_t k = 0; k < n; ++k) {
                if (inp[j + k] != key_tokens[k]) { match = false; break; }
            }
            if (match) { match_pos = j; break; }
        }
    }
    if (match_pos == 0) {
        for (size_t j = cur_len - n - m - 1; j > map.size_last_begin && j > map.key_map_last_idx; --j) {
            bool match = true;
            for (size_t k = 0; k < n; ++k) {
                if (inp[j + k] != key_tokens[k]) { match = false; break; }
            }
            if (match) { match_pos = j; break; }
        }
    }

    if (!map.key_map.empty()) {
        if (map.size_last_begin > (size_t)(n + m + 1)) {
            for (size_t j = map.size_last_begin - n - m - 1; j > map.key_map_last_idx; --j) {
                uint32_t idx_hash = spec_ngram_hash(inp, j, n) % map.key_map.size();
                if (map.key_map[idx_hash] == 0) map.key_map[idx_hash] = (uint32_t)j;
            }
        }
        for (size_t j = cur_len - n - m - 1; j > map.size_last_begin && j > map.key_map_last_idx; --j) {
            uint32_t idx_hash = spec_ngram_hash(inp, j, n) % map.key_map.size();
            if (map.key_map[idx_hash] == 0) map.key_map[idx_hash] = (uint32_t)j;
        }
        map.key_map_last_idx = std::max((uint32_t)(cur_len - n - m - 1), map.key_map_last_idx);
    }

    if (match_pos == 0) return;

    // Find or create key statistics entry.
    size_t key_offset = map.keys.size();
    for (size_t i = 0; i < map.keys.size(); ++i) {
        bool match = true;
        for (size_t j = 0; j < n; ++j) {
            if (inp[map.keys[i].key_idx + j] != key_tokens[j]) { match = false; break; }
        }
        if (match) { key_offset = i; break; }
    }
    if (key_offset == map.keys.size()) {
        spec_ngram_key new_key;
        new_key.key_idx  = match_pos;
        new_key.stat_idx = 0;
        new_key.key_num  = 0;
        for (int i = 0; i < SPEC_NGRAM_MAX_VALUES; ++i) {
            new_key.values[i].value_num  = 0;
            new_key.values[i].n_accepted = (int16_t)m;
        }
        map.keys.push_back(new_key);
    }

    spec_ngram_key & curr_key = map.keys[key_offset];
    curr_key.key_num = (uint16_t)std::min((int)curr_key.key_num + 1, (int)SPEC_NGRAM_MAX_VALUE_COUNT);

    if (curr_key.key_num < map.min_hits) return;

    // Collect value m-gram statistics after each occurrence of the key.
    for (size_t i = curr_key.stat_idx; i <= match_pos; ++i) {
        bool match_key = true;
        for (size_t k = 0; k < n; ++k) {
            if (inp[i + k] != key_tokens[k]) { match_key = false; break; }
        }
        if (!match_key) continue;

        size_t idx_begin_value_key = i + n;
        int idx_value = -1;
        for (int v = 0; v < SPEC_NGRAM_MAX_VALUES; ++v) {
            size_t idx_begin_value_v = curr_key.values[v].value_idx;
            if (idx_begin_value_v == 0) {
                curr_key.values[v].value_idx  = idx_begin_value_key;
                curr_key.values[v].value_num  = 0;
                curr_key.values[v].n_accepted = (int16_t)m;
                idx_value = v;
                break;
            }
            bool match = true;
            for (size_t j = 0; j < m; ++j) {
                if (inp[idx_begin_value_key + j] != inp[idx_begin_value_v + j]) { match = false; break; }
            }
            if (match) { idx_value = v; break; }
        }
        if (idx_value >= 0) {
            curr_key.values[idx_value].value_num =
                (uint16_t)std::min((int)curr_key.values[idx_value].value_num + 1, (int)SPEC_NGRAM_MAX_VALUE_COUNT);
        }
    }
    curr_key.stat_idx = match_pos;

    // Draft only when one value dominates: max_occur >= 2 * sum(others).
    uint16_t max_occur = 0;
    int slot_max = 0;
    for (int v = 0; v < SPEC_NGRAM_MAX_VALUES; ++v) {
        if (curr_key.values[v].value_num > max_occur) {
            max_occur = curr_key.values[v].value_num;
            slot_max  = v;
        }
    }
    uint32_t sum_occur = 0;
    for (int v = 0; v < SPEC_NGRAM_MAX_VALUES; ++v) {
        if (v != slot_max) sum_occur += curr_key.values[v].value_num;
    }
    if (sum_occur > 0 && max_occur < 2 * sum_occur) return;

    int n_draft_tokens = std::min((int)m, (int)curr_key.values[slot_max].n_accepted);
    for (int i = 0; i < n_draft_tokens; ++i) {
        draft.push_back(inp[match_pos + n + i]);
    }

    map.last_draft_created   = true;
    map.last_draft_key_idx   = key_offset;
    map.last_draft_value_idx = (uint16_t)slot_max;
}

// Feed back how many draft tokens were accepted (self-tunes future draft len).
static void spec_ngram_accept(spec_ngram_map & map, uint16_t n_accepted) {
    if (!map.last_draft_created) return;
    map.keys[map.last_draft_key_idx].values[map.last_draft_value_idx].n_accepted = (int16_t)n_accepted;
}

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
    int n_threads;            // number of threads bound to perf cores
    std::vector<int> perf_core_ids;  // CPU IDs of performance cores (for affinity)
    spec_ngram_map * spec_map = nullptr;  // ngram-map-k4v state (lazy, text completion only)

    // Eager vision-encode overlap: the ~184s SigLIP encode depends only on the
    // photo, so it runs in the background at image-attach time. We stash the
    // resident [constant-prefix + image] KV here so Generate only has to prefill
    // the patient-text tail. Bit-identical to a full eval (Gemma3 is non-mrope).
    uint64_t vision_prefix_hash = 0;   // FNV-1a of the constant prefix string
    uint64_t vision_image_hash  = 0;   // FNV-1a of the raw image bytes
    int      vision_prefix_n_past = 0; // KV length after [prefix + image]
    bool     vision_prefix_ready = false;
};

// FNV-1a over bytes — cheap identity check for the cached vision prefix/image.
static uint64_t fnv1a(const void * data, size_t len) {
    const unsigned char * p = (const unsigned char *) data;
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < len; i++) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}

// ─── CPU Topology Detection ──────────────────────────────────────────────────
// Read each CPU's max frequency from /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq
// and return the IDs of cores within 90% of the SoC max frequency.
// On big.LITTLE chips this isolates the performance cluster (X1/X3/A76/A78/A715).
// Falls back to all cores if cpufreq is unreadable.
static std::vector<int> detect_perf_cores() {
    std::vector<int> all_cores;
    std::vector<long> max_freqs;
    long soc_max_freq = 0;

    // Try CPU 0..15 (covers all current Android SoCs)
    for (int cpu_id = 0; cpu_id < 16; cpu_id++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu_id);
        std::ifstream f(path);
        if (!f.is_open()) break;

        long freq = 0;
        f >> freq;
        if (freq > 0) {
            all_cores.push_back(cpu_id);
            max_freqs.push_back(freq);
            if (freq > soc_max_freq) soc_max_freq = freq;
        }
    }

    if (all_cores.empty()) {
        LOGE("Could not read cpufreq, returning empty perf core list");
        return all_cores;
    }

    // Performance cores: within 90% of the highest frequency on the SoC
    long threshold = (soc_max_freq * 9) / 10;
    std::vector<int> perf_cores;
    for (size_t i = 0; i < all_cores.size(); i++) {
        if (max_freqs[i] >= threshold) {
            perf_cores.push_back(all_cores[i]);
        }
    }

    LOGI("CPU topology: %zu total cores, %zu perf cores (>=%ld kHz of %ld kHz max)",
         all_cores.size(), perf_cores.size(), threshold, soc_max_freq);

    // If only 1 perf core (Tensor G3 case), expand to top 2 cores anyway
    // for better parallelism. A single thread leaves perf on the table.
    if (perf_cores.size() < 2 && all_cores.size() >= 2) {
        // Sort cores by frequency descending and pick top 2
        std::vector<std::pair<long, int>> ranked;
        for (size_t i = 0; i < all_cores.size(); i++) {
            ranked.push_back({max_freqs[i], all_cores[i]});
        }
        std::sort(ranked.rbegin(), ranked.rend());
        perf_cores.clear();
        for (int i = 0; i < std::min((int)ranked.size(), 2); i++) {
            perf_cores.push_back(ranked[i].second);
        }
        LOGI("Expanded perf cores to top 2 by frequency");
    }

    return perf_cores;
}

// All online CPU ids sorted by max frequency DESCENDING. Used by the tuned
// init path to pin to the N fastest cores — critical on Helio-G99-class chips
// where the A55 littles clock to 2.0 GHz (>=90% of the 2.2 GHz A76 bigs), so
// the freq heuristic wrongly treats all 8 as "perf" and 6 slow threads
// throttle the layer barrier. Big-cores-first lets the benchmark find the
// true optimum thread count.
static std::vector<int> cores_by_freq_desc() {
    std::vector<std::pair<long,int>> ranked;
    for (int cpu_id = 0; cpu_id < 16; cpu_id++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu_id);
        std::ifstream f(path);
        if (!f.is_open()) break;
        long freq = 0; f >> freq;
        if (freq > 0) ranked.push_back({freq, cpu_id});
    }
    std::sort(ranked.rbegin(), ranked.rend());
    std::vector<int> ids;
    for (auto & p : ranked) ids.push_back(p.second);
    return ids;
}

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

// Shared model-init implementation. Tuning knobs (0 = "use default"):
//   nUbatch          : physical batch size (ctx_params.n_ubatch)
//   nThreadsOverride : if >0, pin to the N fastest cores (big-cores-first)
//                      instead of the freq-heuristic perf-core set
//   flashAttn        : 0 = disabled, 1 = enabled
static jlong init_model_full(const char *path, int nCtx, int nGpuLayers,
                             int kvCacheType, int nBatch, int nUbatch,
                             int nThreadsOverride, int flashAttn) {
    LOGI("initModel: %s, nCtx=%d, nGpuLayers=%d, kvCacheType=%d, nBatch=%d, nUbatch=%d, nThreadsOverride=%d, flash=%d",
         path, nCtx, nGpuLayers, kvCacheType, nBatch, nUbatch, nThreadsOverride, flashAttn);

    install_log_routing_once();
    llama_backend_init();

    // ─── Thread / affinity selection ─────────────────────────────────────────
    std::vector<int> pin_cores;
    if (nThreadsOverride > 0) {
        std::vector<int> ranked = cores_by_freq_desc();
        for (int i = 0; i < std::min(nThreadsOverride, (int)ranked.size()); i++) {
            pin_cores.push_back(ranked[i]);
        }
    } else {
        pin_cores = detect_perf_cores();
    }
    int n_threads = std::max(1, (int)pin_cores.size());
    LOGI("Using %d threads (cores: %s)", n_threads,
         [&]{ std::string s; for (int c : pin_cores) s += std::to_string(c) + " "; return s; }().c_str());

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;

    llama_model * model = llama_model_load_from_file(path, model_params);
    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = nBatch;
    if (nUbatch > 0) ctx_params.n_ubatch = nUbatch;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.flash_attn_type = flashAttn ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                           : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    enum lm_ggml_type kv_type = kv_type_from_code(kvCacheType);
    ctx_params.type_k = kv_type;
    ctx_params.type_v = kv_type;
    LOGI("KV cache type: %d (ggml type %d), n_ubatch=%u", kvCacheType, (int)kv_type, ctx_params.n_ubatch);

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    std::vector<int> perf_cores = pin_cores;
    auto * inf_ctx = new inference_context();
    inf_ctx->model = model;
    inf_ctx->ctx = ctx;
    inf_ctx->sampler = nullptr;
    inf_ctx->mtmd_ctx = nullptr;
    inf_ctx->stop_requested = false;
    inf_ctx->n_past = 0;
    inf_ctx->n_threads = n_threads;
    inf_ctx->perf_core_ids = perf_cores;

    // Bind a dedicated threadpool to the performance cores.
    // This prevents the Linux scheduler from migrating threads to LITTLE cores,
    // which is the dominant cause of poor inference performance on Tensor/Pixel.
    auto tp_params = lm_ggml_threadpool_params_default(n_threads);
    // Clear the default mask and set only our perf cores
    for (int i = 0; i < LM_GGML_MAX_N_THREADS; i++) {
        tp_params.cpumask[i] = false;
    }
    for (int cpu_id : perf_cores) {
        if (cpu_id < LM_GGML_MAX_N_THREADS) {
            tp_params.cpumask[cpu_id] = true;
        }
    }
    tp_params.strict_cpu = true;  // hard pin to these cores
    tp_params.prio = LM_GGML_SCHED_PRIO_HIGH;
    tp_params.n_threads = n_threads;

    auto * tp = lm_ggml_threadpool_new(&tp_params);
    if (tp) {
        llama_attach_threadpool(ctx, tp, nullptr);
        LOGI("Threadpool attached: %d threads on perf cores", n_threads);
    } else {
        LOGE("Failed to create threadpool, falling back to default");
    }

    // Default sampler params
    rebuild_sampler(inf_ctx, 0.7f, 40, 0.9f);

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(inf_ctx);
}

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeInitModel(
    JNIEnv *env, jobject, jstring modelPath,
    jint nCtx, jint nGpuLayers, jint kvCacheType, jint nBatch
) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    jlong h = init_model_full(path, nCtx, nGpuLayers, kvCacheType, nBatch,
                              /*nUbatch*/ 0, /*nThreadsOverride*/ 0, /*flashAttn*/ 1);
    env->ReleaseStringUTFChars(modelPath, path);
    return h;
}

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeInitModelTuned(
    JNIEnv *env, jobject, jstring modelPath,
    jint nCtx, jint nGpuLayers, jint kvCacheType, jint nBatch,
    jint nUbatch, jint nThreadsOverride, jint flashAttn
) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    jlong h = init_model_full(path, nCtx, nGpuLayers, kvCacheType, nBatch,
                              nUbatch, nThreadsOverride, flashAttn);
    env->ReleaseStringUTFChars(modelPath, path);
    return h;
}

// Prefill a prompt (typically the constant instruction+few-shot prefix) into
// the KV cache WITHOUT generating — used by the app's background prewarm so the
// first real assessment finds the prefix already cached. Returns the number of
// prompt tokens now resident, or -1 on error. Uses the same KV-reuse logic as
// completion, so calling it when a prefix is already cached is a cheap no-op.
JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativePrefill(
    JNIEnv *env, jobject, jlong handle, jstring prompt
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->ctx) return -1;
    inf_ctx->vision_prefix_ready = false;  // text prefill clobbers any resident vision prefix

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr ? prompt_cstr : "");
    if (prompt_cstr) env->ReleaseStringUTFChars(prompt, prompt_cstr);

    const llama_vocab * vocab = llama_model_get_vocab(inf_ctx->model);
    std::vector<llama_token> tokens(prompt_str.size() + 16);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                  tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                  tokens.data(), tokens.size(), true, true);
    }
    if (n_tokens <= 0) return -1;
    tokens.resize(n_tokens);

    const int n_ctx = llama_n_ctx(inf_ctx->ctx);
    if (n_tokens > n_ctx) return -1;  // prefix must fit; don't disturb an oversized prompt

    int n_keep = 0;
    const int n_cached = (int)inf_ctx->cache_tokens.size();
    for (int i = 0; i < std::min(n_cached, n_tokens); i++) {
        if (inf_ctx->cache_tokens[i] == tokens[i]) n_keep++; else break;
    }
    if (n_keep < n_cached) {
        llama_memory_seq_rm(llama_get_memory(inf_ctx->ctx), 0, n_keep, n_cached);
        inf_ctx->cache_tokens.resize(n_keep);
        inf_ctx->n_past = n_keep;
    }

    const int n_batch = llama_n_batch(inf_ctx->ctx);
    for (int i = n_keep; i < n_tokens; i += n_batch) {
        int n_eval = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(inf_ctx->ctx, batch) != 0) {
            LOGE("nativePrefill: decode failed at %d", i);
            return -1;
        }
    }
    inf_ctx->cache_tokens = tokens;
    inf_ctx->n_past = n_tokens;
    LOGI("nativePrefill: %d tokens resident (%d reused, %d new)", n_tokens, n_keep, n_tokens - n_keep);
    return n_tokens;
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
    jint nMinTokens,
    jboolean specDecode,
    jobject callback
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->ctx) {
        return env->NewStringUTF("{\"error\":\"Invalid context\"}");
    }
    inf_ctx->vision_prefix_ready = false;  // text completion clobbers any resident vision prefix

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

    // Stop sequence (e.g. "</r>"): caps runaway generation past the closing
    // XML tag. Checked at while-loop boundaries where the KV cache is
    // consistent — never mid-verify in the spec path — so stopping never
    // leaves the KV in a state that would corrupt the next patient's prefix
    // reuse. Content after the tag is discarded by the parser anyway.
    std::string stop_seq;
    if (stopSequences) {
        const char *stop_cstr = env->GetStringUTFChars(stopSequences, nullptr);
        if (stop_cstr) { stop_seq = stop_cstr; env->ReleaseStringUTFChars(stopSequences, stop_cstr); }
    }

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

    // Full-cache-hit guard (fixes the n_new==0 stale-logits bug). If the ENTIRE
    // prompt is already resident (n_keep == n_tokens), the eval loop below never
    // runs, so no llama_decode happens this call — and the first token is then
    // sampled at idx=-1 from the PREVIOUS generation's logits. Truncating the KV
    // (seq_rm) restores the prompt's KV cells but does NOT recompute the logits
    // for the prompt's final position, so the model samples its first token from
    // a stale, wrong distribution → off-distribution/garbage → parse failure →
    // retry. Force at least one token to be re-decoded so idx=-1 holds the correct
    // logits. Only bites the identical-prompt re-submit case (benchmark warm
    // repeats / a "regenerate same patient"); normal per-patient prompts differ in
    // the tail, so n_new is always > 0 there and this is a no-op.
    if (n_keep == n_tokens && n_tokens > 0) {
        n_keep = n_tokens - 1;
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
    // Use the same batch size we configured at init (matches ctx_params.n_batch)
    const int n_batch = llama_n_batch(inf_ctx->ctx);
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

    // Single-model speculative decoding (ngram-map-k4v). When enabled, each
    // step may verify a multi-token draft in ONE llama_decode instead of
    // decoding token-by-token. Lossless: every emitted token is exactly what
    // the sampler chain would have produced sequentially.
    const bool use_spec = (specDecode == JNI_TRUE);
    int spec_rounds = 0, spec_proposed = 0, spec_accepted = 0;
    llama_batch spec_batch = {};
    if (use_spec) {
        if (!inf_ctx->spec_map) inf_ctx->spec_map = new spec_ngram_map();
        spec_ngram_begin(*inf_ctx->spec_map, inf_ctx->cache_tokens);
        spec_batch = llama_batch_init(inf_ctx->spec_map->size_value + 1, 0, 1);
    }
    const int n_ctx_total = (int)llama_n_ctx(inf_ctx->ctx);

    // Emit one token to the stream + result. Returns emitted count delta.
    auto emit_token = [&](llama_token tok) {
        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_str(buf, n);
            result_text += token_str;
            n_generated++;
            jstring jtoken = env->NewStringUTF(token_str.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
        }
    };

    // Sample at logits index `idx` with the min_tokens EOG guard applied at
    // emission index `emit_idx` (same semantics as the pre-spec loop: if the
    // warm KV cache makes the sampler instantly emit EOG/EOS in the first
    // nMinTokens emissions, substitute the best non-EOG token; afterwards EOG
    // is honored as a normal stop and flagged via is_eog).
    auto sample_guarded = [&](int idx, int emit_idx, bool & is_eog) -> llama_token {
        llama_token tok = llama_sampler_sample(inf_ctx->sampler, inf_ctx->ctx, idx);
        is_eog = false;
        if (emit_idx < nMinTokens && llama_vocab_is_eog(vocab, tok)) {
            float * logits = llama_get_logits_ith(inf_ctx->ctx, idx);
            int n_vocab = llama_vocab_n_tokens(vocab);
            llama_token best = -1;
            float best_logit = -std::numeric_limits<float>::infinity();
            for (int t = 0; t < n_vocab; t++) {
                if (!llama_vocab_is_eog(vocab, t) && logits[t] > best_logit) {
                    best_logit = logits[t];
                    best = t;
                }
            }
            if (best >= 0) {
                LOGI("min_tokens guard: replaced EOG sample with best non-EOG token at i=%d", emit_idx);
                tok = best;
                llama_sampler_accept(inf_ctx->sampler, tok);
            } else {
                is_eog = true;
            }
        } else if (llama_vocab_is_eog(vocab, tok)) {
            is_eog = true;
        }
        return tok;
    };

    int i = 0;                    // emitted-token count (nPredict budget)
    bool has_carry = false;       // a token sampled during verification, pending emit+decode
    llama_token carry = 0;

    while (i < nPredict) {
        if (inf_ctx->stop_requested) break;
        // KV-consistent stop check (loop boundary): if the emitted text has
        // reached the stop sequence, halt. Costs one substring scan per round.
        if (!stop_seq.empty() && result_text.find(stop_seq) != std::string::npos) break;

        llama_token new_token;
        if (has_carry) {
            new_token = carry;
            has_carry = false;
        } else {
            bool is_eog = false;
            new_token = sample_guarded(-1, i, is_eog);
            if (is_eog) break;
        }

        // Try an ngram draft for the continuation of new_token.
        std::vector<llama_token> draft;
        if (use_spec && i + 1 < nPredict) {
            spec_ngram_draft(*inf_ctx->spec_map, inf_ctx->cache_tokens, new_token, draft);
            int budget = nPredict - i - 1;
            int space  = n_ctx_total - inf_ctx->n_past - 2;
            int cap    = std::max(0, std::min(budget, space));
            if ((int)draft.size() > cap) draft.resize(cap);
        }

        if (draft.empty()) {
            // Plain path — identical to the pre-spec loop.
            emit_token(new_token);
            i++;
            inf_ctx->cache_tokens.push_back(new_token);
            inf_ctx->n_past++;
            llama_batch next_batch = llama_batch_get_one(&new_token, 1);
            if (llama_decode(inf_ctx->ctx, next_batch) != 0) {
                LOGE("Failed to decode token %d", i);
                break;
            }
            continue;
        }

        // Speculative path: decode [new_token, draft...] in one batch with
        // logits at every position, then verify the draft against the sampler.
        spec_rounds++;
        spec_proposed += (int)draft.size();
        const int n_past0 = inf_ctx->n_past;

        spec_batch.n_tokens = (int)draft.size() + 1;
        spec_batch.token[0]    = new_token;
        spec_batch.pos[0]      = n_past0;
        spec_batch.n_seq_id[0] = 1;
        spec_batch.seq_id[0][0] = 0;
        spec_batch.logits[0]   = 1;
        for (size_t j = 0; j < draft.size(); j++) {
            spec_batch.token[j + 1]    = draft[j];
            spec_batch.pos[j + 1]      = n_past0 + 1 + (int)j;
            spec_batch.n_seq_id[j + 1] = 1;
            spec_batch.seq_id[j + 1][0] = 0;
            spec_batch.logits[j + 1]   = 1;
        }
        if (llama_decode(inf_ctx->ctx, spec_batch) != 0) {
            LOGE("Speculative batch decode failed at i=%d", i);
            break;
        }

        emit_token(new_token);
        i++;
        inf_ctx->cache_tokens.push_back(new_token);

        int  n_acc = 0;
        bool ended = false;
        for (int j = 0; j <= (int)draft.size(); j++) {
            if (i >= nPredict || inf_ctx->stop_requested) break;
            bool is_eog = false;
            llama_token s = sample_guarded(j, i, is_eog);
            if (is_eog) { ended = true; break; }
            if (j < (int)draft.size() && s == draft[j]) {
                emit_token(s);
                i++;
                inf_ctx->cache_tokens.push_back(s);
                n_acc++;
                continue;
            }
            // Mismatch (correction) or bonus token after full acceptance —
            // either way it's the genuine next token; carry it forward.
            carry = s;
            has_carry = true;
            break;
        }

        spec_accepted += n_acc;
        spec_ngram_accept(*inf_ctx->spec_map, (uint16_t)n_acc);

        // Roll back the KV entries of rejected draft tokens.
        if (n_acc < (int)draft.size()) {
            llama_memory_seq_rm(llama_get_memory(inf_ctx->ctx), 0, n_past0 + 1 + n_acc, -1);
        }
        inf_ctx->n_past = n_past0 + 1 + n_acc;

        if (ended) break;
    }

    if (use_spec) {
        llama_batch_free(spec_batch);
        LOGI("spec ngram-map-k4v: rounds=%d proposed=%d accepted=%d (%.0f%%)",
             spec_rounds, spec_proposed, spec_accepted,
             spec_proposed > 0 ? 100.0 * spec_accepted / spec_proposed : 0.0);
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
        inf_ctx->vision_prefix_ready = false;
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

JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeGetThreadCount(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx) return 0;
    return inf_ctx->n_threads;
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeGetPerfCoreInfo(
    JNIEnv *env,
    jobject /* this */
) {
    // Standalone helper that doesn't require an active model handle
    std::vector<int> perf_cores = detect_perf_cores();
    std::string result;
    for (size_t i = 0; i < perf_cores.size(); i++) {
        if (i > 0) result += ",";
        result += std::to_string(perf_cores[i]);
    }
    return env->NewStringUTF(result.c_str());
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
        delete inf_ctx->spec_map;
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
    jstring mmprojPath,
    jint imageSize
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
    // Use the same thread count as the LLM (already bound to perf cores)
    params.n_threads = inf_ctx->n_threads > 0 ? inf_ctx->n_threads : 4;

    // EXPERIMENT (vision-encode perf): the SigLIP encode barriers after nearly
    // every op, so heterogeneous unpinned threads sync to the slowest little
    // core. Allow overriding the clip encode thread count at runtime to find the
    // sweet spot without a rebuild:  adb shell setprop debug.eh.clipthreads N
    // (0/unset = default above). Once the best N is known this becomes a constant.
    char prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.eh.clipthreads", prop) > 0) {
        int t = atoi(prop);
        if (t > 0 && t <= 16) {
            params.n_threads = t;
            LOGI("initVision: clip n_threads overridden to %d via debug.eh.clipthreads", t);
        }
    }
    LOGI("initVision: clip encode using %d threads", params.n_threads);

    // Optional reduced-resolution vision (opt-in "Fast image mode" setting).
    // imageSize > 0 shrinks the SigLIP square input (e.g. 448) to cut the encode
    // ~quadratically, trading fine detail for speed. A debug property overrides
    // it for on-device tuning without a rebuild: adb shell setprop debug.eh.visionsize N
    int vsize = imageSize;
    char vprop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.eh.visionsize", vprop) > 0) {
        int v = atoi(vprop);
        if (v > 0) vsize = v;
    }
    if (vsize > 0) {
        params.vision_image_size = vsize;
        LOGI("initVision: reduced-resolution vision requested (image_size=%d)", vsize);
    }

    inf_ctx->mtmd_ctx = mtmd_init_from_file(path, inf_ctx->model, params);
    env->ReleaseStringUTFChars(mmprojPath, path);

    if (!inf_ctx->mtmd_ctx) {
        LOGE("Failed to load vision encoder");
        return JNI_FALSE;
    }

    LOGI("Vision encoder loaded successfully");
    return JNI_TRUE;
}

// Eager vision encode (background overlap). Runs the ~184s SigLIP image encode +
// prefill of the CONSTANT prompt prefix [instructions + few-shot + "Clinical
// image:" + <__media__>] and leaves the resulting KV resident. A subsequent
// nativeCompletionWithImage with the same image + prefix then only prefills the
// patient-text tail (skipping the encode). Call this the moment the photo is
// attached, while the health worker is still entering symptoms. Returns the KV
// length after [prefix + image], or -1 on error.
JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_llm_LlamaCpp_nativeEncodeImagePrefix(
    JNIEnv *env, jobject, jlong handle, jstring prefixPrompt, jbyteArray imageData
) {
    auto * inf_ctx = reinterpret_cast<inference_context *>(handle);
    if (!inf_ctx || !inf_ctx->ctx || !inf_ctx->mtmd_ctx) return -1;

    const char *prefix_cstr = env->GetStringUTFChars(prefixPrompt, nullptr);
    std::string prefix_std(prefix_cstr ? prefix_cstr : "");
    if (prefix_cstr) env->ReleaseStringUTFChars(prefixPrompt, prefix_cstr);

    jsize imgLen = env->GetArrayLength(imageData);
    jbyte *imgBytes = env->GetByteArrayElements(imageData, nullptr);
    uint64_t img_hash = fnv1a(imgBytes, (size_t) imgLen);

    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(
        inf_ctx->mtmd_ctx, reinterpret_cast<const unsigned char *>(imgBytes), imgLen);
    env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
    if (!bitmap) { LOGE("encodeImagePrefix: failed to decode image"); return -1; }

    mtmd_input_text input_text;
    input_text.text = prefix_std.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;
    const mtmd_bitmap * bitmaps[] = { bitmap };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();

    int32_t ret = mtmd_tokenize(inf_ctx->mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (ret != 0) {
        LOGE("encodeImagePrefix: tokenize failed (error %d)", ret);
        mtmd_input_chunks_free(chunks);
        return -1;
    }

    // Fresh cache — the multimodal batch must start at position 0.
    llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);
    inf_ctx->cache_tokens.clear();
    inf_ctx->n_past = 0;
    inf_ctx->vision_prefix_ready = false;

    double t0 = now_ms();
    llama_pos n_past = 0;
    ret = mtmd_helper_eval_chunks(
        inf_ctx->mtmd_ctx, inf_ctx->ctx, chunks, 0, 0, 512, true, &n_past);
    mtmd_input_chunks_free(chunks);
    if (ret != 0) {
        LOGE("encodeImagePrefix: eval failed (error %d)", ret);
        return -1;
    }

    inf_ctx->n_past = n_past;
    inf_ctx->cache_tokens.clear();  // vision breaks text prefix-matching
    inf_ctx->vision_prefix_hash = fnv1a(prefix_std.data(), prefix_std.size());
    inf_ctx->vision_image_hash = img_hash;
    inf_ctx->vision_prefix_n_past = n_past;
    inf_ctx->vision_prefix_ready = true;
    LOGI("encodeImagePrefix: [prefix+image] resident, n_past=%d, encode+prefill=%.0f ms (this runs in the background)",
         n_past, now_ms() - t0);
    return n_past;
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

    // Get image bytes (held until we commit to a path)
    jsize imgLen = env->GetArrayLength(imageData);
    jbyte *imgBytes = env->GetByteArrayElements(imageData, nullptr);

    LOGI("completionWithImage: prompt=%zu chars, image=%d bytes", prompt_str.size(), imgLen);

    // ── Eager-encode overlap fast path ───────────────────────────────────────
    // If the background prewarmImage() already encoded [constant prefix + this
    // image] and it is still resident in the KV cache, skip the ~184s SigLIP
    // encode entirely and only prefill the patient-text tail. The split is
    // bit-identical to a full eval: Gemma3 is non-mrope (sequential positions)
    // and mtmd already evaluated the image block non-causally during the eager
    // step, so the tail attends causally to the same resident KV.
    const std::string MEDIA_MARKER = "<__media__>";
    uint64_t img_hash = fnv1a(imgBytes, (size_t) imgLen);
    size_t marker_pos = prompt_str.find(MEDIA_MARKER);
    std::string prefix_str = (marker_pos == std::string::npos)
        ? prompt_str : prompt_str.substr(0, marker_pos + MEDIA_MARKER.size());
    std::string tail_str = (marker_pos == std::string::npos)
        ? std::string() : prompt_str.substr(marker_pos + MEDIA_MARKER.size());
    uint64_t prefix_hash = fnv1a(prefix_str.data(), prefix_str.size());

    llama_pos n_past = 0;
    bool fast_path = inf_ctx->vision_prefix_ready
        && marker_pos != std::string::npos
        && inf_ctx->vision_image_hash == img_hash
        && inf_ctx->vision_prefix_hash == prefix_hash;
    // Single-use: appending the tail below replaces the "just [prefix+image]"
    // KV state, so any later call must re-encode.
    inf_ctx->vision_prefix_ready = false;

    if (fast_path) {
        env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
        n_past = inf_ctx->vision_prefix_n_past;
        // Tokenize the patient tail as a continuation (no BOS). mtmd splits text
        // chunks on the marker, so this matches how the full path tokenizes the
        // post-image text.
        const llama_vocab * vocab = llama_model_get_vocab(inf_ctx->model);
        std::vector<llama_token> tail(tail_str.size() + 16);
        int nt = llama_tokenize(vocab, tail_str.c_str(), tail_str.size(),
                                tail.data(), tail.size(), false, true);
        if (nt < 0) { tail.resize(-nt); nt = llama_tokenize(vocab, tail_str.c_str(),
                        tail_str.size(), tail.data(), tail.size(), false, true); }
        tail.resize(nt > 0 ? nt : 0);

        double t_tail0 = now_ms();
        bool ok = true;
        const int nb = llama_n_batch(inf_ctx->ctx);
        for (int i = 0; i < (int) tail.size(); i += nb) {
            int n_eval = std::min(nb, (int) tail.size() - i);
            llama_batch b = llama_batch_get_one(tail.data() + i, n_eval);
            if (llama_decode(inf_ctx->ctx, b) != 0) { ok = false; break; }
        }
        if (!ok) {
            LOGE("overlap fast-path tail decode failed; resident KV is now inconsistent");
            return env->NewStringUTF("{\"error\":\"overlap tail decode failed\"}");
        }
        n_past += (int) tail.size();
        inf_ctx->n_past = n_past;
        LOGI("completionWithImage OVERLAP: reused %d resident [prefix+image] tokens + %d tail; SigLIP encode SKIPPED (tail-prefill=%.0f ms)",
             inf_ctx->vision_prefix_n_past, (int) tail.size(), now_ms() - t_tail0);
    } else {
        // ── FULL PATH: encode the image + prefill the whole prompt (~184s) ──
        mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(
            inf_ctx->mtmd_ctx,
            reinterpret_cast<const unsigned char *>(imgBytes), imgLen);
        env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
        if (!bitmap) {
            LOGE("Failed to decode image");
            return env->NewStringUTF("{\"error\":\"Failed to decode image\"}");
        }

        mtmd_input_text input_text;
        input_text.text = prompt_str.c_str();
        input_text.add_special = true;
        input_text.parse_special = true;
        const mtmd_bitmap * bitmaps[] = { bitmap };
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();

        double t_tok0 = now_ms();
        int32_t ret = mtmd_tokenize(inf_ctx->mtmd_ctx, chunks, &input_text, bitmaps, 1);
        mtmd_bitmap_free(bitmap);
        if (ret != 0) {
            LOGE("Failed to tokenize multimodal input (error %d)", ret);
            mtmd_input_chunks_free(chunks);
            return env->NewStringUTF("{\"error\":\"Failed to tokenize multimodal input\"}");
        }
        LOGI("VISION-TIMING tokenize=%.0f ms", now_ms() - t_tok0);

        // Clear any resident KV (prewarm / prior assessment) so the multimodal
        // batch evaluates cleanly from position 0 (otherwise error -1).
        llama_memory_clear(llama_get_memory(inf_ctx->ctx), true);
        inf_ctx->cache_tokens.clear();
        inf_ctx->n_past = 0;

        double t_eval0 = now_ms();
        ret = mtmd_helper_eval_chunks(
            inf_ctx->mtmd_ctx, inf_ctx->ctx, chunks, 0, 0, 512, true, &n_past);
        mtmd_input_chunks_free(chunks);
        if (ret != 0) {
            LOGE("Failed to evaluate multimodal input (error %d)", ret);
            return env->NewStringUTF("{\"error\":\"Failed to evaluate multimodal input\"}");
        }
        LOGI("VISION-TIMING eval(encode+prefill)=%.0f ms, n_past=%d", now_ms() - t_eval0, n_past);
    }

    LOGI("Multimodal prompt evaluated, n_past=%d", n_past);
    double t_dec0 = now_ms();

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

    {
        double dec = now_ms() - t_dec0;
        LOGI("VISION-TIMING decode=%.0f ms, %d tokens (%.2f tok/s)",
             dec, n_generated, n_generated > 0 ? n_generated * 1000.0 / dec : 0.0);
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
