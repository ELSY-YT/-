// ReconAI on-device LLM engine (llama.cpp). Plain C++, no JNI, so it can be tested on a PC.
#include "engine.h"
#include "llama.h"
#include "ggml-backend.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
const llama_vocab *g_vocab = nullptr;
llama_batch_ext *g_batch = nullptr;
int g_n_batch = 512;
std::vector<llama_token> g_cached;   // tokens currently in the KV cache (sequence 0)
std::atomic<bool> g_stop{false};
std::mutex g_mu;
bool g_backend_ready = false;

void free_all() {
    if (g_batch) { llama_batch_ext_free(g_batch); g_batch = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_cached.clear();
}

// decode n tokens at positions pos0..pos0+n-1; logits only for the last one
bool decode_tokens(const llama_token *toks, size_t n, llama_pos pos0) {
    for (size_t i = 0; i < n; i += g_n_batch) {
        const size_t end = std::min(n, i + (size_t) g_n_batch);
        llama_batch_ext_clear(g_batch);
        for (size_t k = i; k < end; ++k) {
            const int32_t idx = llama_batch_ext_add_token(g_batch, 0, toks[k]);
            if (idx < 0) return false;
            const llama_pos pos = pos0 + (llama_pos) k;
            llama_batch_ext_set_pos(g_batch, idx, &pos);
        }
        if (end == n) llama_batch_ext_set_output_logits(g_batch, (int32_t) (end - i - 1), true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch) != 0) return false;
        if (g_stop.load()) return false;
    }
    return true;
}
}  // namespace

void rc_init(const char *backend_dir) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_backend_ready) return;
    llama_log_set([](enum ggml_log_level level, const char *text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
    }, nullptr);
    if (backend_dir && *backend_dir) ggml_backend_load_all_from_path(backend_dir);
    else ggml_backend_load_all();
    llama_backend_init();
    g_backend_ready = true;
}

std::string rc_load(const char *path, int n_ctx, int n_threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;     // CPU only: phone GPUs are not reliable with llama.cpp
    g_model = llama_model_load_from_file(path, mp);
    if (!g_model) return "не удалось загрузить модель (файл повреждён или это не GGUF)";
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    g_n_batch = std::min(512, n_ctx);
    cp.n_batch = (uint32_t) g_n_batch;
    cp.n_ubatch = (uint32_t) g_n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { free_all(); return "не хватило памяти под контекст, уменьшите n_ctx"; }
    g_batch = llama_batch_ext_init(g_ctx);
    if (!g_batch) { free_all(); return "не удалось создать batch"; }
    return "";
}

bool rc_loaded() { return g_ctx != nullptr; }

void rc_stop() { g_stop = true; }

std::string rc_generate(const std::string &prompt, int max_tokens, float temp, std::string &err) {
    std::lock_guard<std::mutex> lk(g_mu);
    err.clear();
    g_stop = false;
    if (!g_ctx) { err = "модель не загружена"; return ""; }

    // tokenize (special tokens like <|im_start|> are parsed)
    int n = -llama_tokenize(g_vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(g_vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true) < 0) {
        err = "ошибка токенизации"; return "";
    }
    const int n_ctx = (int) llama_n_ctx(g_ctx);
    if ((int) toks.size() + max_tokens > n_ctx) {
        err = "контекст переполнен (" + std::to_string(toks.size()) + " токенов из " + std::to_string(n_ctx) +
              "): начните новый чат или увеличьте n_ctx";
        return "";
    }

    // reuse the KV cache for the common prefix (the agent resends the whole dialog every step)
    size_t common = 0;
    while (common < g_cached.size() && common < toks.size() && g_cached[common] == toks[common]) ++common;
    if (common == toks.size()) --common;   // at least one token must be decoded to get logits
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cached.assign(toks.begin(), toks.begin() + common);
    if (!decode_tokens(toks.data() + common, toks.size() - common, (llama_pos) common)) {
        llama_memory_clear(mem, true); g_cached.clear();
        err = g_stop ? "остановлено" : "ошибка обработки промпта";
        return "";
    }
    g_cached = toks;

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // small models loop easily ("DNS-записи у ... DNS-записи у ..."), a light repeat penalty stops that
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(g_vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    for (int i = 0; i < max_tokens && !g_stop.load(); ++i) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, t)) break;
        char buf[256];
        int m = llama_token_to_piece(g_vocab, t, buf, sizeof(buf), 0, false);
        if (m > 0) out.append(buf, m);
        if (!decode_tokens(&t, 1, (llama_pos) g_cached.size())) { if (!g_stop) err = "ошибка генерации"; break; }
        g_cached.push_back(t);
    }
    llama_sampler_free(smpl);
    return out;
}

void rc_unload() {
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
}
