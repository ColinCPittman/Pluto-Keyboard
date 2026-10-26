// libfutoctxlm is a small LM scoring runtime for the voice-input "context" stage
// (org.futo.voiceinput.shared.context.LlamaNative).
//
// The LM is used as a judge, never as a generator, so the Kotlin side asks for the
// log-probabilities of token sequences and of a few alternative tokens at chosen
// positions. Ported from tools/context-bench/ctxrun/ctxrun.cpp (--score mode). There are two evaluations.
//
//  * evalBase. Sequence 0 holds the "base" text (prompt prefix + transcript). The KV
//    cells of a prefix that is already decoded are kept (truncate to `keep` tokens) and
//    only the suffix is decoded. Returns the log-prob of every token from `needFrom`
//    on, plus the log-probs of extra token ids at chosen positions (e.g. "?" vs ".").
//  * evalVariants takes variants of the base that share a prefix with it. Each variant
//    gets its own sequence whose cells for the shared part are copied from sequence 0
//    (no recomputation), and all variant tails are decoded together in as few batches
//    as possible.
//
// llama.cpp/ggml are linked statically with hidden visibility, and only the JNI entry
// points below are exported (see exports.map), so nothing clashes with the older
// ggml copy inside libjni_latinime.so. CPU only, with no networking anywhere.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-cpu.h"
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "futoctxlm", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "futoctxlm", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

struct Lm {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_vocab = 0;
    int n_threads = 2;
    std::vector<int> cpus;

    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    llama_memory_t mem = nullptr;
    ggml_threadpool * tp = nullptr;
    int n_ctx = 0;
    int n_batch = 128;
    int n_seq_max = 8;

    std::vector<llama_token> seq0;  // tokens whose KV cells sequence 0 holds
    std::vector<float> tmp;
    std::vector<llama_token> want;
    std::atomic<bool> abort{false};

    void free_context() {
        if (batch) llama_batch_ext_free(batch);
        if (ctx) llama_free(ctx);
        batch = nullptr;
        ctx = nullptr;
        mem = nullptr;
        seq0.clear();
    }

    ~Lm() {
        free_context();
        if (tp) ggml_threadpool_free(tp);
        if (model) llama_model_free(model);
    }
};

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGW("%s", text);
}

// log-softmax of `logits` evaluated at the ids in `want` (same arithmetic as ctxrun)
void log_softmax_at(const float * logits, int n_vocab, const std::vector<llama_token> & want, std::vector<float> & out) {
    float mx = logits[0];
    for (int v = 1; v < n_vocab; v++) mx = logits[v] > mx ? logits[v] : mx;
    float sum = 0.0f;
    for (int v = 0; v < n_vocab; v++) sum += expf(logits[v] - mx);
    const float lse = mx + logf(sum);
    out.clear();
    for (auto t : want) out.push_back(logits[t] - lse);
}

Lm * get(jlong h) { return reinterpret_cast<Lm *>(h); }

std::vector<int> to_vec(JNIEnv * env, jintArray a) {
    std::vector<int> v;
    if (!a) return v;
    const jsize n = env->GetArrayLength(a);
    v.resize(n);
    if (n > 0) env->GetIntArrayRegion(a, 0, n, v.data());
    return v;
}

jfloatArray to_jfloats(JNIEnv * env, const std::vector<float> & v) {
    jfloatArray out = env->NewFloatArray((jsize) v.size());
    if (out && !v.empty()) env->SetFloatArrayRegion(out, 0, (jsize) v.size(), v.data());
    return out;
}

bool decode_batch(Lm * lm) {
    const int32_t rc = llama_process(lm->ctx, LLAMA_PROCESS_TYPE_DECODE, lm->batch);
    if (rc != 0) LOGW("decode failed (%d)", rc);
    return rc == 0;
}

}  // namespace

extern "C" {

#define JFN(name) Java_org_futo_voiceinput_shared_context_LlamaNative_##name

// Loads a GGUF (memory-mapped). cpus holds optional core ids to pin the worker threads to.
// repack lets ggml convert the weight matrices into the layout of its fastest CPU kernels
// (e.g. Q4_0 -> 4x8 interleaved on ARM). Those copies live in anonymous memory instead of
// the file mapping, but scoring is much faster.
JNIEXPORT jlong JNICALL JFN(nativeLoad)(JNIEnv * env, jobject, jstring jpath, jint threads, jintArray jcpus,
                                        jboolean repack) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.use_extra_bufts = repack == JNI_TRUE;
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;
    if (llama_model_is_recurrent(model) || llama_model_is_hybrid(model)) {
        // KV branching per variant needs a transformer KV cache
        LOGW("recurrent/hybrid models are not supported");
        llama_model_free(model);
        return 0;
    }
    auto * lm = new Lm();
    lm->model = model;
    lm->vocab = llama_model_get_vocab(model);
    lm->n_vocab = llama_vocab_n_tokens(lm->vocab);
    lm->n_threads = std::max(1, (int) threads);
    lm->cpus = to_vec(env, jcpus);
    return reinterpret_cast<jlong>(lm);
}

// (Re)creates the inference context with n_ctx KV cells shared by n_seq_max sequences.
JNIEXPORT jboolean JNICALL JFN(nativeInitContext)(JNIEnv *, jobject, jlong h, jint n_ctx, jint n_batch, jint n_seq_max) {
    Lm * lm = get(h);
    lm->free_context();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = (uint32_t) n_batch;
    cp.n_ubatch = (uint32_t) n_batch;
    cp.n_threads = lm->n_threads;
    cp.n_threads_batch = lm->n_threads;
    cp.no_perf = true;
    cp.n_seq_max = (uint32_t) n_seq_max;
    cp.kv_unified = true;  // variants share the base's KV cells
    cp.abort_callback = [](void * data) { return static_cast<Lm *>(data)->abort.load(); };
    cp.abort_callback_data = lm;
    lm->ctx = llama_init_from_model(lm->model, cp);
    if (!lm->ctx) return JNI_FALSE;
    if (!lm->cpus.empty()) {
        if (!lm->tp) {
            ggml_threadpool_params tpp = ggml_threadpool_params_default(lm->n_threads);
            for (int c : lm->cpus) if (c >= 0 && c < GGML_MAX_N_THREADS) tpp.cpumask[c] = true;
            tpp.strict_cpu = true;
            lm->tp = ggml_threadpool_new(&tpp);
        }
        if (lm->tp) llama_attach_threadpool(lm->ctx, lm->tp, lm->tp);
    }
    lm->batch = llama_batch_ext_init(lm->ctx);
    lm->mem = llama_get_memory(lm->ctx);
    lm->n_ctx = (int) llama_n_ctx(lm->ctx);
    lm->n_batch = n_batch;
    lm->n_seq_max = n_seq_max;
    return lm->batch ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL JFN(nativeTokenize)(JNIEnv * env, jobject, jlong h, jbyteArray jtext, jboolean add_special) {
    Lm * lm = get(h);
    const jsize len = env->GetArrayLength(jtext);
    std::string s((size_t) len, '\0');
    if (len > 0) env->GetByteArrayRegion(jtext, 0, len, reinterpret_cast<jbyte *>(&s[0]));
    const int n = -llama_tokenize(lm->vocab, s.c_str(), (int32_t) s.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> toks(std::max(0, n));
    if (n > 0 && llama_tokenize(lm->vocab, s.c_str(), (int32_t) s.size(), toks.data(), n, add_special, true) < 0) toks.clear();
    jintArray out = env->NewIntArray((jsize) toks.size());
    if (out && !toks.empty()) env->SetIntArrayRegion(out, 0, (jsize) toks.size(), toks.data());
    return out;
}

JNIEXPORT jbyteArray JNICALL JFN(nativeTokenPiece)(JNIEnv * env, jobject, jlong h, jint token) {
    Lm * lm = get(h);
    char buf[256];
    const int k = std::max(0, (int) llama_token_to_piece(lm->vocab, token, buf, sizeof(buf), 0, false));
    jbyteArray out = env->NewByteArray(k);
    if (out && k > 0) env->SetByteArrayRegion(out, 0, k, reinterpret_cast<const jbyte *>(buf));
    return out;
}

JNIEXPORT jstring JNICALL JFN(nativeDescribe)(JNIEnv * env, jobject, jlong h) {
    Lm * lm = get(h);
    char desc[128];
    llama_model_desc(lm->model, desc, sizeof(desc));
    char buf[256];
    snprintf(buf, sizeof(buf), "%s, %.0f MB, vocab %d, %d threads%s", desc, llama_model_size(lm->model) / 1048576.0,
             lm->n_vocab, lm->n_threads, lm->cpus.empty() ? "" : " (pinned)");
    return env->NewStringUTF(buf);
}

// Sequence 0 := tokens. KV cells of tokens[0, keep) are kept (keep is clamped to the common
// prefix with what sequence 0 holds), tokens[keep, n) are decoded. Returns first_index =
// max(keep + 1, needFrom) and then, for every token index i in [first_index, n), log P(tokens[i])
// followed by the log-probs of wantIds[wantOff[i] .. wantOff[i + 1]) at that position.
// Null on failure.
JNIEXPORT jfloatArray JNICALL JFN(nativeEvalBase)(JNIEnv * env, jobject, jlong h, jintArray jtokens, jint jkeep,
                                                  jint need_from, jintArray jwant_off, jintArray jwant_ids) {
    Lm * lm = get(h);
    if (!lm->ctx) return nullptr;
    const std::vector<int> toks = to_vec(env, jtokens);
    const std::vector<int> want_off = to_vec(env, jwant_off);
    const std::vector<int> want_ids = to_vec(env, jwant_ids);
    const int n = (int) toks.size();
    if (n < 2 || (int) want_off.size() != n + 1 || n > lm->n_ctx) return nullptr;
    int keep = std::max(0, std::min((int) jkeep, n - 1));
    int lcp = 0;
    while (lcp < (int) lm->seq0.size() && lcp < n && lm->seq0[lcp] == toks[lcp]) lcp++;
    keep = std::min(keep, lcp);
    const int out_from = std::max(keep + 1, (int) need_from);

    std::vector<float> result{(float) out_from};  // first value is the first index returned
    std::vector<int> slot(n, -1);  // start of token i's entry in result
    for (int i = out_from; i < n; i++) {
        slot[i] = (int) result.size();
        result.resize(result.size() + 1 + (want_off[i + 1] - want_off[i]), 0.0f);
    }

    llama_memory_seq_rm(lm->mem, 0, keep, -1);
    lm->seq0.assign(toks.begin(), toks.begin() + keep);
    for (int c = keep; c < n; c += lm->n_batch) {
        const int m = std::min(n - c, lm->n_batch);
        llama_batch_ext_clear(lm->batch);
        for (int k = 0; k < m; k++) {
            const int j = c + k;
            const int32_t idx = llama_batch_ext_add_token(lm->batch, 0, toks[j]);
            const llama_pos p = j;
            llama_batch_ext_set_pos(lm->batch, idx, &p);
            llama_batch_ext_set_output_logits(lm->batch, idx, j + 1 < n && j + 1 >= out_from);
        }
        if (!decode_batch(lm)) {
            llama_memory_clear(lm->mem, true);
            lm->seq0.clear();
            return nullptr;
        }
        lm->seq0.insert(lm->seq0.end(), toks.begin() + c, toks.begin() + c + m);
        for (int k = 0; k < m; k++) {
            const int j = c + k;
            if (!(j + 1 < n && j + 1 >= out_from)) continue;
            const int i = j + 1;
            lm->want.assign(1, toks[i]);
            for (int q = want_off[i]; q < want_off[i + 1]; q++) lm->want.push_back(want_ids[q]);
            log_softmax_at(llama_get_logits_ith(lm->ctx, k), lm->n_vocab, lm->want, lm->tmp);
            std::copy(lm->tmp.begin(), lm->tmp.end(), result.begin() + slot[i]);
        }
    }
    return to_jfloats(env, result);
}

// Each variant shares tokens[0, starts[v]) with sequence 0. Returns per variant the
// log-probs of tokens[starts[v], n). Null on failure.
JNIEXPORT jobjectArray JNICALL JFN(nativeEvalVariants)(JNIEnv * env, jobject, jlong h, jobjectArray jvariants,
                                                       jintArray jstarts) {
    Lm * lm = get(h);
    if (!lm->ctx) return nullptr;
    const std::vector<int> starts = to_vec(env, jstarts);
    const int nv = env->GetArrayLength(jvariants);
    if ((int) starts.size() != nv) return nullptr;
    std::vector<std::vector<int>> vars(nv);
    std::vector<std::vector<float>> lps(nv);
    for (int v = 0; v < nv; v++) {
        auto a = (jintArray) env->GetObjectArrayElement(jvariants, v);
        vars[v] = to_vec(env, a);
        env->DeleteLocalRef(a);
        const int n = (int) vars[v].size();
        const int s = starts[v];
        if (s < 1 || s > n || n > lm->n_ctx) return nullptr;
        // the shared part must be what sequence 0 holds
        if (s - 1 > (int) lm->seq0.size() || !std::equal(vars[v].begin(), vars[v].begin() + (s - 1), lm->seq0.begin())) return nullptr;
        lps[v].assign(n - s, 0.0f);
    }

    struct Out { int var; int tok; };
    std::vector<Out> outs;
    int in_batch = 0;
    int next_seq = 1;
    int cells = 0;  // cells used by the variant tails in flight
    const int base_cells = (int) lm->seq0.size();
    auto recycle = [&]() {
        for (int s = 1; s < lm->n_seq_max; s++) llama_memory_seq_rm(lm->mem, s, -1, -1);
        next_seq = 1;
        cells = 0;
    };
    auto flush = [&]() -> bool {
        if (in_batch == 0) return true;
        if (!decode_batch(lm)) return false;
        for (size_t bi = 0; bi < outs.size(); bi++) {
            if (outs[bi].var < 0) continue;
            const auto & t = vars[outs[bi].var];
            lm->want.assign(1, t[outs[bi].tok]);
            log_softmax_at(llama_get_logits_ith(lm->ctx, (int) bi), lm->n_vocab, lm->want, lm->tmp);
            lps[outs[bi].var][outs[bi].tok - starts[outs[bi].var]] = lm->tmp[0];
        }
        outs.clear();
        llama_batch_ext_clear(lm->batch);
        in_batch = 0;
        return true;
    };
    bool ok = true;
    llama_batch_ext_clear(lm->batch);
    for (int v = 0; v < nv && ok; v++) {
        const auto & t = vars[v];
        const int n = (int) t.size();
        const int s = starts[v];
        if (s >= n) continue;  // a prefix of the base, so nothing to compute
        const int from = s - 1;  // its logits predict token s
        const int tail = n - from;
        if (next_seq >= lm->n_seq_max || base_cells + cells + tail + lm->n_batch > lm->n_ctx) {
            if (!flush()) { ok = false; break; }
            recycle();
        }
        if (base_cells + tail + lm->n_batch > lm->n_ctx) { ok = false; break; }
        const int seq = next_seq++;
        llama_memory_seq_rm(lm->mem, seq, -1, -1);
        if (from > 0) llama_memory_seq_cp(lm->mem, 0, seq, 0, from);
        for (int j = from; j < n; j++) {
            if (in_batch >= lm->n_batch && !flush()) { ok = false; break; }
            const int32_t idx = llama_batch_ext_add_token(lm->batch, seq, t[j]);
            const llama_pos p = j;
            llama_batch_ext_set_pos(lm->batch, idx, &p);
            const bool want_out = j + 1 < n && j + 1 >= s;
            llama_batch_ext_set_output_logits(lm->batch, idx, want_out);
            outs.push_back({want_out ? v : -1, j + 1});
            in_batch++;
        }
        cells += tail;
    }
    if (ok) ok = flush();
    recycle();
    llama_batch_ext_clear(lm->batch);
    if (!ok) return nullptr;

    jclass fcls = env->FindClass("[F");
    jobjectArray out = env->NewObjectArray(nv, fcls, nullptr);
    for (int v = 0; v < nv; v++) {
        jfloatArray a = to_jfloats(env, lps[v]);
        env->SetObjectArrayElement(out, v, a);
        env->DeleteLocalRef(a);
    }
    return out;
}

// Makes a running (or the next) decode stop early, and it then fails and returns null.
JNIEXPORT void JNICALL JFN(nativeSetAbort)(JNIEnv *, jobject, jlong h, jboolean value) {
    get(h)->abort.store(value == JNI_TRUE);
}

JNIEXPORT void JNICALL JFN(nativeFree)(JNIEnv *, jobject, jlong h) {
    delete get(h);
}

}  // extern "C"
