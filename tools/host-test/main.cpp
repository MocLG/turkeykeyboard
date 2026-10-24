#include "translator_engine.h"

#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>

static const char * lang_name(const std::string & code) {
    if (code == "tr") return "Turkish";
    if (code == "en") return "English";
    return nullptr;
}

static std::string build_prompt(const std::string & text, const std::string & s, const std::string & t) {
    const std::string sn = lang_name(s), tn = lang_name(t);
    return "<start_of_turn>user\nYou are a professional " + sn + " (" + s + ") to " + tn + " (" + t +
           ") translator. Your goal is to accurately convey the meaning and nuances of the original " + sn +
           " text while adhering to " + tn + " grammar, vocabulary, and cultural sensitivities.\n"
           "Produce only the " + tn + " translation, without any additional explanations or commentary. "
           "Please translate the following " + sn + " text into " + tn + ":\n\n\n" +
           tercuman::trim(text) + "<end_of_turn>\n<start_of_turn>model\n";
}

int main(int argc, char ** argv) {
    if (argc != 5 || !lang_name(argv[2]) || !lang_name(argv[3])) {
        fprintf(stderr, "usage: %s MODEL.gguf tr|en tr|en \"text\"|-\n", argv[0]);
        return 2;
    }
    tercuman::TranslatorEngine::global_init("");
    tercuman::TranslatorEngine engine;
    tercuman::EngineConfig cfg;
    cfg.n_threads = (int) std::max(1u, std::thread::hardware_concurrency());
    std::string err;
    if (!engine.load(argv[1], cfg, err)) {
        fprintf(stderr, "load failed: %s\n", err.c_str());
        return 1;
    }

    auto run = [&](const std::string & text) {
        const auto r = engine.generate(build_prompt(text, argv[2], argv[3]), 0, nullptr);
        if (!r.ok) {
            fprintf(stderr, "error: %s\n", r.error.c_str());
            return false;
        }
        printf("%s\n", r.text.c_str());
        fprintf(stderr, "  [prompt %d tok %.0f ms (%.1f tok/s) | output %d tok %.0f ms (%.1f tok/s)]\n",
                r.prompt_tokens, r.prompt_ms, r.prompt_tokens * 1000.0 / r.prompt_ms,
                r.output_tokens, r.gen_ms, r.output_tokens * 1000.0 / std::max(1.0, r.gen_ms));
        fflush(stdout);
        return true;
    };

    if (std::strcmp(argv[4], "-") == 0) {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (!tercuman::trim(line).empty() && !run(line)) return 1;
        }
        return 0;
    }
    return run(argv[4]) ? 0 : 1;
}
