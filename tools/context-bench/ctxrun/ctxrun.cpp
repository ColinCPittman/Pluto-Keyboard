// ctxrun is a tiny benchmark runner for the voice-input "context model" stage.
//
// Mirrors what the app would do. It loads a GGUF once, decodes a fixed prompt
// prefix (system prompt + few-shot examples) once and snapshots its state, then
// for every transcript restores the snapshot, decodes only the per-item suffix,
// and greedily generates the corrected text.
//
// Input JSON (-f) looks like {"prefix": "...", "items": [{"id": "x", "suffix": "...", "max_tokens": 80}, ...]}
// Output is one JSON object per line on stdout (header, one per item, summary).
//
// Usage: ctxrun -m model.gguf -f prompts.json [-t threads] [--cpus 6,7] [-c n_ctx]
//               [--no-prefix-cache] [--repeat N] [--spec N]
//
// --spec N is "copy speculation". The corrected text is almost identical to the input, so
// the item's "draft" (the input transcript) is used as a free draft: after each token the
// next N draft tokens are verified in one batch (prefill speed instead of generation
// speed) and the longest prefix that matches the greedy choice is accepted. Output is
// bit-identical to plain greedy decoding. Needs partial KV removal (transformer models only, since
// recurrent/hybrid models fall back to plain decoding).

#include "llama.h"
#include "ggml-cpu.h"
#include "nlohmann/json.hpp"

#ifndef _WIN32
#include <sys/resource.h>
#endif
#include <chrono>
#include <clocale>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

using json = nlohmann::ordered_json;

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

static long peak_rss_kb() {
#ifdef _WIN32
    return -1;
#else
    struct rusage ru;
    getrusage(RUSAGE_SELF, &ru);
    return ru.ru_maxrss;  // kB on Linux/Android
#endif
}

static long cur_rss_kb() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) return std::stol(line.substr(6));
    }
    return -1;
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & s, bool add_special) {
    int n = -llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> out(n);
    if (llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), out.data(), n, add_special, true) < 0) out.clear();
    return out;
}

// Decode tokens starting at pos (only the last token produces logits), split into n_batch chunks.
static bool decode(llama_context * ctx, llama_batch_ext * batch, const std::vector<llama_token> & toks, int pos, int n_batch) {
    for (size_t i = 0; i < toks.size(); i += n_batch) {
        size_t n = std::min(toks.size() - i, (size_t) n_batch);
        llama_batch_ext_clear(batch);
        for (size_t j = 0; j < n; j++) {
            int32_t idx = llama_batch_ext_add_token(batch, 0, toks[i + j]);
            llama_pos p = pos + (llama_pos) (i + j);
            llama_batch_ext_set_pos(batch, idx, &p);
        }
        if (i + n == toks.size()) llama_batch_ext_set_output_logits(batch, (int32_t) n - 1, true);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Scoring server (--score), where the LM is used as a judge instead of a generator.
// Reads one JSON request per stdin line:
//   {"prefix": "...", "variants": ["text0", "text1", ...], "alts": ["?", "."], "detail": true}
// and writes {"variants": [{"lp": total log-prob, ...}], "ms": ..., "decoded": n}.
//
// "detail" requests (one variant, the full transcript) decode the text into sequence 0
// and return per-token pieces/log-probs plus, for every position, the log-prob of each
// "alts" token there (e.g. "?" vs "." vs ","). Sequence 0 is then kept as the base.
// Other requests score variants that share a prefix with the base, and each variant gets
// its own sequence (KV cells of the shared part are copied, not recomputed) and all
// variant tails are decoded together in ONE batch. Variants identical to a prefix of
// the base cost nothing (their log-probs are already known).

static float log_softmax_at(const float * logits, int n_vocab, std::vector<llama_token> const & want, std::vector<float> & out) {
    float mx = logits[0];
    for (int v = 1; v < n_vocab; v++) mx = logits[v] > mx ? logits[v] : mx;
    float sum = 0.0f;
    for (int v = 0; v < n_vocab; v++) sum += expf(logits[v] - mx);
    const float lse = mx + logf(sum);
    out.clear();
    for (auto t : want) out.push_back(logits[t] - lse);
    return lse;
}

static int run_score(llama_context * ctx, llama_batch_ext * batch, const llama_vocab * vocab, llama_memory_t mem,
                     int n_batch, bool recurrent, int n_seq_max) {
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::string cur_prefix;
    bool have_prefix = false;
    std::vector<llama_token> prefix_toks;
    std::vector<float> first_logits;      // logits predicting the first text token
    std::vector<uint8_t> snap;            // prefix state (recurrent models)
    std::vector<llama_token> base;        // tokens of the base text in seq 0
    std::vector<float> base_lp;           // their log-probs
    std::vector<std::vector<float>> base_alt;  // alt log-probs per position
    std::vector<llama_token> base_alt_ids;
    std::vector<float> tmp;
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty() || line == "\r") continue;
        json req = json::parse(line);
        const double ts = now_ms();
        int n_decoded = 0;
        const std::string prefix = req.value("prefix", "");
        if (!have_prefix || prefix != cur_prefix) {
            llama_memory_clear(mem, true);
            prefix_toks = tokenize(vocab, prefix, true);
            if (prefix_toks.empty()) { fprintf(stderr, "empty prefix\n"); return 1; }
            if (!decode(ctx, batch, prefix_toks, 0, n_batch)) { fprintf(stderr, "prefix decode failed\n"); return 1; }
            n_decoded += (int) prefix_toks.size();
            const float * lg = llama_get_logits_ith(ctx, -1);
            first_logits.assign(lg, lg + n_vocab);
            if (recurrent) {
                snap.resize(llama_state_seq_get_size(ctx, 0));
                llama_state_seq_get_data(ctx, snap.data(), snap.size(), 0);
            }
            cur_prefix = prefix;
            have_prefix = true;
            base.clear(); base_lp.clear();
        }
        const int P = (int) prefix_toks.size();
        std::vector<llama_token> alt_ids;
        for (auto & a : req.value("alts", json::array())) {
            auto t = tokenize(vocab, a.get<std::string>(), false);
            alt_ids.push_back(t.empty() ? 0 : t[0]);
        }
        json res_vars = json::array();

        if (req.value("detail", false)) {
            // ---- full pass over one text into seq 0, reusing the common prefix with the old base
            std::vector<llama_token> toks = tokenize(vocab, req["variants"][0].get<std::string>(), false);
            const int n = (int) toks.size();
            std::vector<llama_token> want;
            // probes: [[char_offset, ["word", ...]], ...] -> log-prob of each word's first
            // token at the token that starts at char_offset (a free pre-filter for candidates)
            std::vector<std::string> piece_str(n);
            std::vector<int> piece_off(n);
            {
                int off = 0;
                for (int i = 0; i < n; i++) {
                    char buf[256];
                    int k = llama_token_to_piece(vocab, toks[i], buf, sizeof(buf), 0, false);
                    piece_str[i] = std::string(buf, std::max(0, k));
                    piece_off[i] = off;
                    off += (int) piece_str[i].size();
                }
            }
            std::vector<std::vector<llama_token>> probe_toks(n);
            std::vector<std::pair<int, int>> probe_where;  // (token index, count) per probe
            for (auto & pr : req.value("probes", json::array())) {
                const int c = pr[0].get<int>();
                int ti = -1;
                for (int i = 0; i < n; i++) {
                    const std::string & ps = piece_str[i];
                    int lead = 0;
                    while (lead < (int) ps.size() && ps[lead] == ' ') lead++;
                    if (piece_off[i] + lead == c) { ti = i; break; }
                }
                int cnt = 0;
                if (ti >= 0) {
                    const bool sp = !piece_str[ti].empty() && piece_str[ti][0] == ' ';
                    for (auto & w : pr[1]) {
                        auto t = tokenize(vocab, (sp ? " " : "") + w.get<std::string>(), false);
                        probe_toks[ti].push_back(t.empty() ? 0 : t[0]);
                        cnt++;
                    }
                }
                probe_where.push_back({ti, cnt});
            }
            // Reuse the part shared with the previous base (streaming, so the earlier sentences);
            // only tokens from the first difference on are decoded. Probes are only
            // evaluated at re-decoded positions.
            int L = 0;
            if (!recurrent && alt_ids == base_alt_ids) {
                while (L < n && L < (int) base.size() && toks[L] == base[L]) L++;
            }
            for (auto & pw : probe_where) if (pw.first >= 0) L = std::min(L, pw.first);  // probes need fresh logits
            const int from = std::max(0, L - 1);
            if (recurrent) {
                llama_memory_clear(mem, true);
                llama_state_seq_set_data(ctx, snap.data(), snap.size(), 0);
            } else {
                llama_memory_seq_rm(mem, 0, P + from, -1);
            }
            std::vector<float> lp(n, 0.0f);
            std::vector<std::vector<float>> alt(n);
            for (int i = 0; i < L; i++) { lp[i] = base_lp[i]; alt[i] = base_alt[i]; }
            std::vector<std::vector<float>> probe_lp(n);
            auto fill = [&](int pos, const float * logits) {
                want = alt_ids;
                want.insert(want.end(), probe_toks[pos].begin(), probe_toks[pos].end());
                want.push_back(toks[pos]);
                log_softmax_at(logits, n_vocab, want, tmp);
                lp[pos] = tmp.back();
                alt[pos].assign(tmp.begin(), tmp.begin() + alt_ids.size());
                probe_lp[pos].assign(tmp.begin() + alt_ids.size(), tmp.end() - 1);
            };
            if (n > 0 && L == 0) fill(0, first_logits.data());
            for (int c = from; c < n; c += n_batch) {
                const int m = std::min(n - c, n_batch);
                llama_batch_ext_clear(batch);
                for (int k = 0; k < m; k++) {
                    int32_t idx = llama_batch_ext_add_token(batch, 0, toks[c + k]);
                    llama_pos p = P + c + k;
                    llama_batch_ext_set_pos(batch, idx, &p);
                    llama_batch_ext_set_output_logits(batch, idx, c + k + 1 < n);
                }
                if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) { fprintf(stderr, "decode failed\n"); return 1; }
                n_decoded += m;
                for (int k = 0; k < m; k++) {
                    const int j = c + k;
                    if (j + 1 >= n) break;
                    fill(j + 1, llama_get_logits_ith(ctx, k));
                }
            }
            double total = 0;
            for (float x : lp) total += x;
            json pieces = json::array(), lps = json::array(), alts = json::array();
            for (int i = 0; i < n; i++) {
                char buf[256];
                int k = llama_token_to_piece(vocab, toks[i], buf, sizeof(buf), 0, false);
                pieces.push_back(std::string(buf, std::max(0, k)));
                lps.push_back(lp[i]);
                alts.push_back(alt[i]);
            }
            json probes = json::array();
            for (size_t q = 0; q < probe_where.size(); q++) probes.push_back(json::array());
            // fill probe results in request order
            {
                std::vector<int> used(n, 0);
                size_t pi = 0;
                for (auto & pw : probe_where) {
                    json one = json::array();
                    if (pw.first >= 0) {
                        for (int q = 0; q < pw.second; q++) one.push_back(probe_lp[pw.first][used[pw.first]++]);
                        one.push_back(lp[pw.first]);  // the last entry is the original token
                    }
                    probes[pi++] = one;
                }
            }
            res_vars.push_back({{"lp", total}, {"n", n}, {"pieces", pieces}, {"lps", lps}, {"alts", alts},
                                {"probes", probes}});
            base = toks; base_lp = lp; base_alt = alt; base_alt_ids = alt_ids;
        } else {
            // ---- variants against the base in seq 0, decoded together on seqs 1..N
            struct Var { std::vector<llama_token> toks; int start; int seq; std::vector<float> lp; };
            std::vector<Var> vars;
            for (auto & vj : req["variants"]) {
                Var v;
                v.toks = tokenize(vocab, vj.get<std::string>(), false);
                int L = 0;
                const int n = (int) v.toks.size();
                while (L < n && L < (int) base.size() && v.toks[L] == base[L]) L++;
                v.lp.assign(n, 0.0f);
                for (int i = 0; i < L; i++) v.lp[i] = base_lp[i];
                v.start = L;  // first token whose log-prob must be computed
                v.seq = -1;
                vars.push_back(std::move(v));
            }
            if (recurrent) {
                // no cheap KV branching, so decode each variant from the prefix snapshot
                for (auto & v : vars) {
                    const int n = (int) v.toks.size();
                    if (v.start >= n) continue;
                    llama_memory_clear(mem, true);
                    llama_state_seq_set_data(ctx, snap.data(), snap.size(), 0);
                    std::vector<llama_token> want{v.toks[0]};
                    log_softmax_at(first_logits.data(), n_vocab, want, tmp);
                    v.lp[0] = tmp[0];
                    for (int c = 0; c < n; c += n_batch) {
                        const int m = std::min(n - c, n_batch);
                        llama_batch_ext_clear(batch);
                        for (int k = 0; k < m; k++) {
                            int32_t idx = llama_batch_ext_add_token(batch, 0, v.toks[c + k]);
                            llama_pos p = P + c + k;
                            llama_batch_ext_set_pos(batch, idx, &p);
                            llama_batch_ext_set_output_logits(batch, idx, c + k + 1 < n);
                        }
                        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return 1;
                        n_decoded += m;
                        for (int k = 0; k < m && c + k + 1 < n; k++) {
                            want = {v.toks[c + k + 1]};
                            log_softmax_at(llama_get_logits_ith(ctx, k), n_vocab, want, tmp);
                            v.lp[c + k + 1] = tmp[0];
                        }
                    }
                }
                base.clear(); base_lp.clear();  // seq 0 no longer holds the base
            } else {
                // Each variant re-decodes from token start-1 (its logits predict token start),
                // and the KV cells before that are shared with seq 0.
                int next_seq = 1;
                struct Out { int var; int tok; };  // batch index -> which log-prob it fills
                std::vector<Out> outs;
                llama_batch_ext_clear(batch);
                int in_batch = 0;
                auto flush = [&]() -> bool {
                    if (in_batch == 0) return true;
                    if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return false;
                    n_decoded += in_batch;
                    for (size_t bi = 0; bi < outs.size(); bi++) {
                        if (outs[bi].var < 0) continue;
                        Var & v = vars[outs[bi].var];
                        std::vector<llama_token> want{v.toks[outs[bi].tok]};
                        log_softmax_at(llama_get_logits_ith(ctx, (int) bi), n_vocab, want, tmp);
                        v.lp[outs[bi].tok] = tmp[0];
                    }
                    outs.clear();
                    llama_batch_ext_clear(batch);
                    in_batch = 0;
                    return true;
                };
                for (size_t vi = 0; vi < vars.size(); vi++) {
                    Var & v = vars[vi];
                    const int n = (int) v.toks.size();
                    if (v.start >= n) continue;  // pure prefix of the base
                    if (v.start == 0) {
                        std::vector<llama_token> want{v.toks[0]};
                        log_softmax_at(first_logits.data(), n_vocab, want, tmp);
                        v.lp[0] = tmp[0];
                    }
                    const int from = std::max(0, v.start - 1);
                    if (n - from + in_batch > n_batch && !flush()) return 1;
                    if (next_seq >= n_seq_max) { if (!flush()) return 1; }
                    if (next_seq >= n_seq_max) {
                        // out of sequences, so recycle
                        for (int s = 1; s < n_seq_max; s++) llama_memory_seq_rm(mem, s, -1, -1);
                        next_seq = 1;
                    }
                    v.seq = next_seq++;
                    llama_memory_seq_rm(mem, v.seq, -1, -1);
                    if (from > 0 || P > 0) llama_memory_seq_cp(mem, 0, v.seq, 0, P + from);
                    for (int j = from; j < n; j++) {
                        int32_t idx = llama_batch_ext_add_token(batch, v.seq, v.toks[j]);
                        llama_pos p = P + j;
                        llama_batch_ext_set_pos(batch, idx, &p);
                        const bool want_out = j + 1 < n && j + 1 >= v.start;
                        llama_batch_ext_set_output_logits(batch, idx, want_out);
                        outs.push_back({want_out ? (int) vi : -1, j + 1});
                        in_batch++;
                    }
                }
                if (!flush()) return 1;
                for (int s = 1; s < n_seq_max; s++) llama_memory_seq_rm(mem, s, -1, -1);
            }
            for (auto & v : vars) {
                double total = 0;
                for (float x : v.lp) total += x;
                res_vars.push_back({{"lp", total}, {"n", (int) v.toks.size()}});
            }
        }
        json out = {{"variants", res_vars}, {"ms", now_ms() - ts}, {"decoded", n_decoded}};
        printf("%s\n", out.dump().c_str());
        fflush(stdout);
    }
    return 0;
}

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    std::string model_path, prompts_path, cpus;
    int n_threads = 4, n_ctx = 1024, n_batch = 128, repeat = 1;  // n_batch 512 made generation ~2x slower on the phone
    bool prefix_cache = true;
    int spec_n = 0;
    bool score_mode = false;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto next = [&]() { return std::string(i + 1 < argc ? argv[++i] : ""); };
        if (a == "-m") model_path = next();
        else if (a == "-f") prompts_path = next();
        else if (a == "-t") n_threads = std::stoi(next());
        else if (a == "-c") n_ctx = std::stoi(next());
        else if (a == "-b") n_batch = std::stoi(next());
        else if (a == "--cpus") cpus = next();
        else if (a == "--repeat") repeat = std::stoi(next());
        else if (a == "--no-prefix-cache") prefix_cache = false;
        else if (a == "--spec") spec_n = std::stoi(next());
        else if (a == "--score") score_mode = true;
        else { fprintf(stderr, "unknown arg %s\n", a.c_str()); return 1; }
    }
    if (model_path.empty() || (prompts_path.empty() && !score_mode)) {
        fprintf(stderr, "usage: %s -m model.gguf -f prompts.json [-t N] [--cpus 6,7] [--no-prefix-cache]\n", argv[0]);
        return 1;
    }

    json input;
    if (!score_mode) {
        std::ifstream f(prompts_path);
        std::stringstream ss; ss << f.rdbuf();
        input = json::parse(ss.str());
    }

    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
    }, nullptr);

    double t0 = now_ms();
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) { fprintf(stderr, "load failed\n"); return 1; }
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const bool recurrent = llama_model_is_recurrent(model) || llama_model_is_hybrid(model);
    if (recurrent && spec_n > 0) {
        fprintf(stderr, "recurrent/hybrid model: partial KV removal unsupported, disabling --spec\n");
        spec_n = 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_batch;
    cp.n_ubatch = n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    const int n_seq_max = score_mode ? 8 : 1;
    if (score_mode) {
        cp.n_seq_max = n_seq_max;
        cp.kv_unified = true;  // variants share the base's KV cells
    }
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { fprintf(stderr, "ctx failed\n"); return 1; }

    ggml_threadpool * tp = nullptr;
    if (!cpus.empty()) {
        ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
        std::stringstream ss(cpus); std::string c;
        while (std::getline(ss, c, ',')) tpp.cpumask[std::stoi(c)] = true;
        tpp.strict_cpu = true;
        tp = ggml_threadpool_new(&tpp);
        llama_attach_threadpool(ctx, tp, tp);
    }
    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    llama_memory_t mem = llama_get_memory(ctx);
    double t_load = now_ms() - t0;
    if (score_mode) {
        fprintf(stderr, "ready load_ms=%.0f rss_kb=%ld\n", t_load, cur_rss_kb());
        int rc = run_score(ctx, batch, vocab, mem, n_batch, recurrent, n_seq_max);
        fprintf(stderr, "peak_rss_kb=%ld\n", peak_rss_kb());
        return rc;
    }

    // Prefix: decode once and snapshot state.
    const std::string prefix = input.value("prefix", "");
    std::vector<llama_token> prefix_toks = tokenize(vocab, prefix, true);
    std::vector<uint8_t> snapshot;
    double t_prefix = 0;
    if (prefix_cache && !prefix_toks.empty()) {
        double ts = now_ms();
        if (!decode(ctx, batch, prefix_toks, 0, n_batch)) { fprintf(stderr, "prefix decode failed\n"); return 1; }
        t_prefix = now_ms() - ts;
        snapshot.resize(llama_state_seq_get_size(ctx, 0));
        llama_state_seq_get_data(ctx, snapshot.data(), snapshot.size(), 0);
    }
    json hdr = {{"type", "header"}, {"model", model_path}, {"threads", n_threads}, {"cpus", cpus},
                {"model_size_mb", llama_model_size(model) / 1048576.0}, {"load_ms", t_load},
                {"prefix_tokens", (int) prefix_toks.size()}, {"prefix_ms", t_prefix},
                {"snapshot_kb", snapshot.size() / 1024}, {"rss_kb", cur_rss_kb()}, {"prefix_cache", prefix_cache},
                {"spec", spec_n}, {"recurrent", recurrent}};
    printf("%s\n", hdr.dump().c_str()); fflush(stdout);

    double sum_total = 0; int n_items = 0;
    for (int r = 0; r < repeat; r++) {
        for (auto & item : input["items"]) {
            const std::string suffix = item.value("suffix", "");
            const int max_tokens = item.value("max_tokens", 128);
            double ts = now_ms();
            std::vector<llama_token> toks;
            int pos = 0;
            if (prefix_cache) {
                llama_memory_clear(mem, true);
                if (!snapshot.empty()) llama_state_seq_set_data(ctx, snapshot.data(), snapshot.size(), 0);
                pos = (int) prefix_toks.size();
                toks = tokenize(vocab, suffix, false);
            } else {
                llama_memory_clear(mem, true);
                toks = tokenize(vocab, prefix + suffix, true);
            }
            if (!decode(ctx, batch, toks, pos, n_batch)) { fprintf(stderr, "decode failed\n"); return 1; }
            pos += (int) toks.size();
            double t_pp = now_ms() - ts;

            double tg0 = now_ms();
            std::string out;
            int n_gen = 0, n_batches = 0, n_drafted = 0, n_accepted = 0;
            const int n_vocab = llama_vocab_n_tokens(vocab);
            auto argmax = [&](int idx) {
                const float * lg = llama_get_logits_ith(ctx, idx);
                int best = 0;
                for (int v = 1; v < n_vocab; v++) if (lg[v] > lg[best]) best = v;
                return (llama_token) best;
            };
            auto emit = [&](llama_token id) {
                char buf[256];
                int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
                if (n > 0) out.append(buf, n);
                n_gen++;
            };
            std::vector<llama_token> draft;
            if (spec_n > 0 && item.contains("draft")) draft = tokenize(vocab, item["draft"].get<std::string>(), false);
            std::vector<llama_token> gen;
            int dp = 0;          // next expected draft position
            bool synced = true;  // last generated token matched the draft
            auto resync = [&](llama_token tok) {
                if (dp < (int) draft.size() && draft[dp] == tok) { dp++; synced = true; return; }
                synced = false;
                // bigram match near dp, then unigram
                size_t g = gen.size();
                for (int k = std::max(1, dp - 4); k < std::min((int) draft.size(), dp + 12); k++) {
                    if (g >= 2 && draft[k] == tok && draft[k - 1] == gen[g - 2]) { dp = k + 1; synced = true; return; }
                }
                for (int k = dp; k < std::min((int) draft.size(), dp + 4); k++) {
                    if (draft[k] == tok) { dp = k + 1; synced = true; return; }
                }
            };
            llama_token next_tok = argmax(-1);
            while (n_gen < max_tokens) {
                if (llama_vocab_is_eog(vocab, next_tok)) break;
                gen.push_back(next_tok);
                emit(next_tok);
                if (!draft.empty()) resync(next_tok);
                std::vector<llama_token> d;
                if (!draft.empty() && synced) {
                    for (int k = dp; k < (int) draft.size() && (int) d.size() < spec_n && n_gen + (int) d.size() < max_tokens; k++) d.push_back(draft[k]);
                }
                // batch = next_tok + draft, logits for every position
                llama_batch_ext_clear(batch);
                for (size_t k = 0; k <= d.size(); k++) {
                    llama_token t = k == 0 ? next_tok : d[k - 1];
                    int32_t idx = llama_batch_ext_add_token(batch, 0, t);
                    llama_pos p = pos + (llama_pos) k;
                    llama_batch_ext_set_pos(batch, idx, &p);
                    llama_batch_ext_set_output_logits(batch, idx, true);
                }
                if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) { fprintf(stderr, "gen decode failed\n"); return 1; }
                n_batches++;
                n_drafted += (int) d.size();
                size_t acc = 0;
                llama_token pred = 0;
                for (size_t k = 0; k <= d.size(); k++) {
                    pred = argmax((int) k);
                    if (k < d.size() && pred == d[k] && !llama_vocab_is_eog(vocab, pred)) {
                        acc++;
                        gen.push_back(pred);
                        emit(pred);
                        dp++;
                        continue;
                    }
                    break;
                }
                n_accepted += (int) acc;
                pos += 1 + (int) acc;
                if (acc < d.size()) {
                    if (!llama_memory_seq_rm(mem, 0, pos, -1)) {
                        fprintf(stderr, "partial KV removal unsupported; use --spec 0 for this model\n");
                        return 1;
                    }
                }
                next_tok = pred;
            }
            double t_tg = now_ms() - tg0;
            double total = now_ms() - ts;
            sum_total += total; n_items++;
            json o = {{"type", "item"}, {"id", item.value("id", "")}, {"rep", r},
                      {"n_prompt", (int) toks.size()}, {"pp_ms", t_pp}, {"pp_tps", toks.size() / (t_pp / 1000.0)},
                      {"n_gen", n_gen}, {"tg_ms", t_tg}, {"tg_tps", n_gen > 0 ? n_gen / (t_tg / 1000.0) : 0.0},
                      {"total_ms", total}, {"gen_batches", n_batches}, {"drafted", n_drafted},
                      {"accepted", n_accepted}, {"text", out}};
            printf("%s\n", o.dump().c_str()); fflush(stdout);
        }
    }
    json s = {{"type", "summary"}, {"items", n_items}, {"mean_total_ms", n_items ? sum_total / n_items : 0},
              {"peak_rss_kb", peak_rss_kb()}};
    printf("%s\n", s.dump().c_str());

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    llama_free(ctx);
    if (tp) ggml_threadpool_free(tp);
    llama_model_free(model);
    return 0;
}
