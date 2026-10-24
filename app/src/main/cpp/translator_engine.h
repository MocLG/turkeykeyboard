#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_batch_ext;

namespace tercuman {
struct EngineConfig {
    int n_threads = 4;
    int n_ctx     = 2048;
    int n_batch   = 512;
};

struct TranslateResult {
    bool        ok = false;
    bool        cancelled = false;
    std::string text;
    std::string error;
    int         prompt_tokens = 0;
    int         output_tokens = 0;
    double      prompt_ms = 0;
    double      gen_ms = 0;
};

using PartialCallback = std::function<bool(const std::string & text_so_far)>;

class TranslatorEngine {
public:
    TranslatorEngine() = default;
    ~TranslatorEngine();

    TranslatorEngine(const TranslatorEngine &) = delete;
    TranslatorEngine & operator=(const TranslatorEngine &) = delete;

    static void global_init(const std::string & backend_dir);

    bool load(const std::string & model_path, const EngineConfig & cfg, std::string & error);
    void unload();
    bool is_loaded() const { return model_ != nullptr; }

    TranslateResult generate(const std::string & prompt, int max_new_tokens, const PartialCallback & on_partial);

    void cancel() { cancel_.store(true); }

    std::string system_info() const;

private:
    llama_model     * model_   = nullptr;
    llama_context   * ctx_     = nullptr;
    llama_sampler   * sampler_ = nullptr;
    llama_batch_ext * batch_   = nullptr;
    EngineConfig      cfg_;
    std::atomic<bool> cancel_{false};
    std::mutex        mutex_;
};

size_t utf8_complete_prefix(const std::string & s);

std::string trim(const std::string & s);
}
