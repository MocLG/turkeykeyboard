#include "translator_engine.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <vector>

namespace tercuman {
namespace {
using clock_type = std::chrono::steady_clock;

double ms_since(clock_type::time_point t0) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t0).count();
}

bool abort_cb(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

bool fill_batch(llama_batch_ext * batch, const llama_token * tokens, int n, llama_pos pos0, bool want_logits) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (idx < 0) {
            return false;
        }
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    if (want_logits && n > 0) {
        llama_batch_ext_set_output_logits(batch, n - 1, true);
    }
    return true;
}
}

size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();

    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) {
            continue;
        }
        size_t need = 1;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

std::string trim(const std::string & s) {
    const char * ws = " \t\r\n";
    const size_t b = s.find_first_not_of(ws);
    if (b == std::string::npos) {
        return "";
    }
    const size_t e = s.find_last_not_of(ws);
    return s.substr(b, e - b + 1);
}

void TranslatorEngine::global_init(const std::string & backend_dir) {
    static std::once_flag once;
    std::call_once(once, [&] {
        if (!backend_dir.empty()) {
            ggml_backend_load_all_from_path(backend_dir.c_str());
        } else {
            ggml_backend_load_all();
        }
        llama_backend_init();
    });
}

TranslatorEngine::~TranslatorEngine() {
    unload();
}

bool TranslatorEngine::load(const std::string & model_path, const EngineConfig & cfg, std::string & error) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (model_) {
        error = "already loaded";
        return false;
    }
    if (ggml_backend_dev_count() == 0) {
        error = "no compute backend found (CPU backend libraries missing?)";
        return false;
    }

    cfg_ = cfg;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    model_ = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model_) {
        error = "could not load model file (corrupt or unsupported): " + model_path;
        return false;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = cfg.n_ctx;
    cp.n_batch         = cfg.n_batch;
    cp.n_ubatch        = cfg.n_batch;
    cp.n_threads       = cfg.n_threads;
    cp.n_threads_batch = cfg.n_threads;
    cp.no_perf         = true;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = &cancel_;

    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        llama_model_free(model_);
        model_ = nullptr;
        error = "could not create inference context (not enough memory?)";
        return false;
    }

    sampler_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler_, llama_sampler_init_greedy());

    batch_ = llama_batch_ext_init(ctx_);
    return true;
}

void TranslatorEngine::unload() {
    cancel_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    if (batch_)   { llama_batch_ext_free(batch_); batch_   = nullptr; }
    if (sampler_) { llama_sampler_free(sampler_); sampler_ = nullptr; }
    if (ctx_)     { llama_free(ctx_);             ctx_     = nullptr; }
    if (model_)   { llama_model_free(model_);     model_   = nullptr; }
    cancel_.store(false);
}

TranslateResult TranslatorEngine::generate(const std::string & prompt, int max_new_tokens,
                                           const PartialCallback & on_partial) {
    std::lock_guard<std::mutex> lock(mutex_);
    cancel_.store(false);

    TranslateResult r;
    if (!model_ || !ctx_) {
        r.error = "model not loaded";
        return r;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_);

    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) {
        r.error = "tokenization failed";
        return r;
    }
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true) < 0) {
        r.error = "tokenization failed";
        return r;
    }
    r.prompt_tokens = n;

    const int n_ctx = (int) llama_n_ctx(ctx_);
    if (max_new_tokens <= 0) {
        max_new_tokens = std::max(64, n * 2);
    }
    if (n + 8 > n_ctx) {
        r.error = "text is too long to translate at once";
        return r;
    }
    max_new_tokens = std::min(max_new_tokens, n_ctx - n);

    llama_memory_clear(llama_get_memory(ctx_), true);
    llama_sampler_reset(sampler_);

    const auto t0 = clock_type::now();
    const int n_batch = (int) llama_n_batch(ctx_);
    for (int i = 0; i < n; i += n_batch) {
        const int chunk = std::min(n_batch, n - i);
        if (!fill_batch(batch_, tokens.data() + i, chunk, i, i + chunk == n)) {
            r.error = "batch overflow";
            return r;
        }
        const int ret = llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_);
        if (ret != 0) {
            r.cancelled = cancel_.load();
            r.error = r.cancelled ? "cancelled" : "prompt evaluation failed (" + std::to_string(ret) + ")";
            return r;
        }
    }
    r.prompt_ms = ms_since(t0);

    const auto t1 = clock_type::now();
    std::string out;
    llama_pos pos = n;
    for (int i = 0; i < max_new_tokens; ++i) {
        if (cancel_.load()) {
            r.cancelled = true;
            break;
        }
        const llama_token tok = llama_sampler_sample(sampler_, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }
        char buf[256];
        const int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (len < 0) {
            r.error = "detokenization failed";
            return r;
        }
        out.append(buf, len);
        r.output_tokens++;

        if (on_partial) {
            const std::string partial = out.substr(0, utf8_complete_prefix(out));
            if (!on_partial(partial)) {
                r.cancelled = true;
                break;
            }
        }

        llama_token next = tok;
        fill_batch(batch_, &next, 1, pos++, true);
        const int ret = llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_);
        if (ret != 0) {
            r.cancelled = cancel_.load();
            if (!r.cancelled) {
                r.error = "generation failed (" + std::to_string(ret) + ")";
                return r;
            }
            break;
        }
    }
    r.gen_ms = ms_since(t1);

    r.text = trim(out.substr(0, utf8_complete_prefix(out)));
    r.ok = !r.cancelled;
    if (r.cancelled) {
        r.error = "cancelled";
    }
    return r;
}

std::string TranslatorEngine::system_info() const {
    std::string s;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        if (!s.empty()) s += ", ";
        s += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    s += " | ";
    s += llama_print_system_info();
    return s;
}
}
