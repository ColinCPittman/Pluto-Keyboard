// libplutotranscribe.so: JNI bridge from org.futo.voiceinput.shared.transcribe.TranscribeNative
// to transcribe.cpp (https://github.com/handy-computer/transcribe.cpp, MIT), which runs
// NVIDIA Nemotron / Parakeet and other GGUF speech models on ggml.
//
// One handle = one loaded model + one session (voice input runs one recognition at a time).
// Calls on a handle must not overlap, except nativeAbort, which may come from any thread.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "transcribe.h"

#ifdef __linux__
#include <sched.h>
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "PlutoTranscribe", __VA_ARGS__)
#else
#include <cstdio>
#define LOGW(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

struct Handle {
    transcribe_model *   model   = nullptr;
    transcribe_session * session = nullptr;
    std::atomic<bool>    abort{false};
    std::vector<int>     cpus;  // cores to run on, or empty for any
};

/**
 * Restricts the calling thread to the handle's cores for one engine call, then restores it. ggml
 * starts its worker threads for each computation, and they inherit this restriction.
 */
class Pinned {
  public:
    explicit Pinned([[maybe_unused]] const Handle * h) {
#ifdef __linux__
        if (h->cpus.empty() || sched_getaffinity(0, sizeof(old_), &old_) != 0) return;
        cpu_set_t set;
        CPU_ZERO(&set);
        for (int c : h->cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
        pinned_ = sched_setaffinity(0, sizeof(set), &set) == 0;
#endif
    }

    ~Pinned() {
#ifdef __linux__
        if (pinned_) sched_setaffinity(0, sizeof(old_), &old_);
#endif
    }

    Pinned(const Pinned &)             = delete;
    Pinned & operator=(const Pinned &) = delete;

  private:
#ifdef __linux__
    cpu_set_t old_{};
    bool      pinned_ = false;
#endif
};

thread_local std::string g_last_error;

Handle * H(jlong h) { return reinterpret_cast<Handle *>(h); }

/** Null (with an exception pending) if Java is out of memory. */
jstring utf8(JNIEnv * env, const char * s, size_t n) {
    // NewStringUTF wants modified UTF-8 (and CheckJNI aborts on a token piece that splits a
    // character), so decode real UTF-8 through a Java byte array, which replaces broken bytes.
    if (s == nullptr) return env->NewStringUTF("");
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(n));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(s));
    jclass    cls  = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring   cs   = env->NewStringUTF("UTF-8");
    auto      out  = static_cast<jstring>(env->NewObject(cls, ctor, bytes, cs));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cs);
    env->DeleteLocalRef(cls);
    return out;
}

jstring utf8(JNIEnv * env, const char * s) { return utf8(env, s, s ? strlen(s) : 0); }

/**
 * A Java string as UTF-8 into [out]; false (with an exception pending) if that fails.
 * GetStringUTFChars gives modified UTF-8, which mangles characters outside the BMP in file names.
 */
bool utf8(JNIEnv * env, jstring s, std::string & out) {
    jclass     cls   = env->GetObjectClass(s);
    jmethodID  get   = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring    cs    = env->NewStringUTF("UTF-8");
    auto       bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, get, cs));
    env->DeleteLocalRef(cs);
    env->DeleteLocalRef(cls);
    if (bytes == nullptr) return false;
    const jsize n = env->GetArrayLength(bytes);
    out.resize(static_cast<size_t>(n));
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    return true;
}

void log_cb(transcribe_log_level level, const char * text, void *) {
    if (level == TRANSCRIBE_LOG_LEVEL_WARN || level == TRANSCRIBE_LOG_LEVEL_ERROR) LOGW("%s", text);
}

bool abort_cb(void * user) { return static_cast<Handle *>(user)->abort.load(); }

struct Word {
    std::string text;
    int64_t     t0 = 0, t1 = 0;
};

/** Whether UTF-8 [s] is only punctuation: ASCII, Latin-1 ("¿", "«"), General Punctuation ("…", "—", "”") or CJK ("。"). */
bool is_punctuation(const std::string & s) {
    for (size_t i = 0; i < s.size();) {
        const auto b   = [&](size_t k) { return static_cast<unsigned char>(s[i + k]); };
        size_t     len = b(0) < 0x80 ? 1 : (b(0) & 0xE0) == 0xC0 ? 2 : (b(0) & 0xF0) == 0xE0 ? 3 : 0;
        if (len == 0 || i + len > s.size()) return false;  // 4-byte or broken: not punctuation
        uint32_t cp = len == 1 ? b(0) : len == 2 ? (b(0) & 0x1F) : (b(0) & 0x0F);
        for (size_t k = 1; k < len; ++k) {
            if ((b(k) & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (b(k) & 0x3F);
        }
        const bool p = (cp < 0x80 && std::ispunct(static_cast<int>(cp))) || cp == 0xA1 || cp == 0xAB ||
                       cp == 0xB7 || cp == 0xBB || cp == 0xBF || (cp >= 0x2010 && cp <= 0x205E) ||
                       (cp >= 0x3001 && cp <= 0x303F);
        if (!p) return false;
        i += len;
    }
    return !s.empty();
}

/**
 * The words of the last result with their times (0 without timestamps that fine). A stream
 * (Nemotron) has no word rows, only timed token pieces, so then a space starts a word and pieces
 * without one ("ever", ",") join the word before. A piece can be only a space (SentencePiece's
 * lone "▁" before a digit), which still ends the word before.
 */
std::vector<Word> result_words(transcribe_session * s) {
    std::vector<Word>               out;
    const transcribe_timestamp_kind kind = transcribe_returned_timestamp_kind(s);
    const int                       nw   = transcribe_n_words(s);
    if (nw > 0) {
        const bool timed = kind == TRANSCRIBE_TIMESTAMPS_WORD || kind == TRANSCRIBE_TIMESTAMPS_TOKEN;
        // Parakeet emits the mark after a word ("you" 0.72-0.96, "?" 1.44-1.60) once speech
        // resumes, so the word row "you?" ends after the pause. A word ends with its last token
        // that isn't punctuation, as in a stream below.
        std::vector<int64_t> ends(static_cast<size_t>(nw), -1);
        if (timed) {
            const int nt = transcribe_n_tokens(s);
            for (int i = 0; i < nt; ++i) {
                transcribe_token t;
                transcribe_token_init(&t);
                transcribe_get_token(s, i, &t);
                if (t.text == nullptr || t.word_index < 0 || t.word_index >= nw) continue;
                const std::string piece = t.text;
                const size_t      a     = piece.find_first_not_of(' ');
                if (a == std::string::npos || is_punctuation(piece.substr(a))) continue;
                ends[static_cast<size_t>(t.word_index)] = t.t1_ms;
            }
        }
        for (int i = 0; i < nw; ++i) {
            transcribe_word w;
            transcribe_word_init(&w);
            transcribe_get_word(s, i, &w);
            // a blank word would read as a pause (its times are another word's or 0)
            if (w.text == nullptr || w.text[strspn(w.text, " ")] == '\0') continue;
            const int64_t end = ends[static_cast<size_t>(i)];
            const int64_t t1  = end >= w.t0_ms && end <= w.t1_ms ? end : w.t1_ms;
            out.push_back({ w.text, timed ? w.t0_ms : 0, timed ? t1 : 0 });
        }
        return out;
    }
    const bool timed = kind == TRANSCRIBE_TIMESTAMPS_TOKEN;
    const int  nt    = transcribe_n_tokens(s);
    bool       space = false;  // a space came since the last word's text
    for (int i = 0; i < nt; ++i) {
        transcribe_token t;
        transcribe_token_init(&t);
        transcribe_get_token(s, i, &t);
        if (t.text == nullptr) continue;
        const std::string piece = t.text;
        for (size_t a = 0; a < piece.size();) {
            if (piece[a] == ' ') {
                space = true;
                ++a;
                continue;
            }
            size_t b = piece.find(' ', a);
            if (b == std::string::npos) b = piece.size();
            const std::string part = piece.substr(a, b - a);
            if (space || out.empty()) {
                out.push_back({ part, timed ? t.t0_ms : 0, timed ? t.t1_ms : 0 });
            } else {
                out.back().text += part;
                // a stream often emits the mark ending a phrase only once speech resumes after the
                // pause, so a punctuation piece doesn't move the word's end (that would hide the pause)
                if (timed && !is_punctuation(part)) out.back().t1 = t.t1_ms;
            }
            space = false;
            a     = b;
        }
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeOpen(JNIEnv * env, jclass, jstring jpath, jint threads, jintArray jcpus) {
    // once per process, before any model (thread-safe: models may load on different threads)
    static const bool inited = [] {
        transcribe_log_set(log_cb, nullptr);
        transcribe_init_backends_default();
        return true;
    }();
    (void) inited;

    std::string path;
    if (!utf8(env, jpath, path)) return 0;
    std::vector<int> cpus;
    if (jcpus != nullptr) {
        jsize  n = env->GetArrayLength(jcpus);
        jint * c = env->GetIntArrayElements(jcpus, nullptr);
        if (c == nullptr) return 0;
        cpus.assign(c, c + n);
        env->ReleaseIntArrayElements(jcpus, c, JNI_ABORT);
    }

    auto * h = new Handle();
    h->cpus  = std::move(cpus);
    transcribe_model_load_params lp;
    transcribe_model_load_params_init(&lp);
    transcribe_status st = transcribe_model_load_file(path.c_str(), &lp, &h->model);
    if (st != TRANSCRIBE_OK) {
        g_last_error = std::string("load: ") + transcribe_status_string(st);
        delete h;
        return 0;
    }

    transcribe_session_params sp;
    transcribe_session_params_init(&sp);
    sp.n_threads = threads;
    st           = transcribe_session_init(h->model, &sp, &h->session);
    if (st != TRANSCRIBE_OK) {
        g_last_error = std::string("session: ") + transcribe_status_string(st);
        transcribe_model_free(h->model);
        delete h;
        return 0;
    }
    transcribe_set_abort_callback(h->session, abort_cb, h);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeLastError(JNIEnv * env, jclass) {
    return utf8(env, g_last_error.c_str());
}

JNIEXPORT void JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeClose(JNIEnv *, jclass, jlong jh) {
    Handle * h = H(jh);
    if (h == nullptr) return;
    transcribe_session_free(h->session);
    transcribe_model_free(h->model);
    delete h;
}

/** "arch variant" of the loaded model, e.g. "nemotron nemotron-speech-streaming-en-0.6b". */
JNIEXPORT jstring JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeDescription(JNIEnv * env, jclass, jlong jh) {
    Handle *     h       = H(jh);
    std::string  d       = transcribe_model_arch_string(h->model);
    const char * variant = transcribe_model_variant_string(h->model);  // may be empty
    if (variant != nullptr && *variant != '\0') d.append(" ").append(variant);
    return utf8(env, d.c_str());
}

/**
 * Bits: 1 streaming, 2 punctuation + capitalization can be switched (not whether the model
 * punctuates: Nemotron and Parakeet do, without the switch), 4 language detection, 8 vocabulary
 * (personal words).
 */
JNIEXPORT jint JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeFeatures(JNIEnv *, jclass, jlong jh) {
    Handle *                 h = H(jh);
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    if (transcribe_model_get_capabilities(h->model, &caps) != TRANSCRIBE_OK) return 0;
    jint f = 0;
    if (caps.supports_streaming) f |= 1;
    if (transcribe_model_supports(h->model, TRANSCRIBE_FEATURE_PNC)) f |= 2;
    if (caps.supports_language_detect) f |= 4;
    if (transcribe_model_supports(h->model, TRANSCRIBE_FEATURE_VOCABULARY)) f |= 8;
    return f;
}

JNIEXPORT jobjectArray JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeLanguages(JNIEnv * env, jclass, jlong jh) {
    Handle *                 h = H(jh);
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    int n = 0;
    if (transcribe_model_get_capabilities(h->model, &caps) == TRANSCRIBE_OK) n = caps.n_languages;
    jclass       cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, cls, nullptr);
    env->DeleteLocalRef(cls);
    if (out == nullptr) return nullptr;
    for (int i = 0; i < n; ++i) {
        jstring s = utf8(env, caps.languages[i]);
        if (s == nullptr) return nullptr;
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeAbort(JNIEnv *, jclass, jlong jh, jboolean abort) {
    if (Handle * h = H(jh)) h->abort.store(abort);
}

/** Run params with the language hint [jlang] (null for none) kept in [lang]; false if Java threw. */
static bool fill_run_params(JNIEnv * env, transcribe_run_params & rp, jstring jlang, std::string & lang) {
    transcribe_run_params_init(&rp);
    rp.timestamps = TRANSCRIBE_TIMESTAMPS_AUTO;
    if (jlang == nullptr) return true;
    if (!utf8(env, jlang, lang)) return false;
    rp.language = lang.c_str();
    return true;
}

/** Starts a stream (the model must stream). Returns a transcribe_status (0 is OK). */
JNIEXPORT jint JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeStreamBegin(JNIEnv * env, jclass, jlong jh, jstring jlang) {
    Handle * h = H(jh);
    h->abort.store(false);
    transcribe_run_params rp;
    std::string           lang;
    if (!fill_run_params(env, rp, jlang, lang)) return TRANSCRIBE_ERR_OOM;
    // begin refuses an ACTIVE session, so a stream a dictation left behind would fail every later one
    if (transcribe_stream_get_state(h->session) == TRANSCRIBE_STREAM_ACTIVE) transcribe_stream_reset(h->session);
    return transcribe_stream_begin(h->session, &rp, nullptr);
}

/** Feeds 16 kHz mono samples. Returns 1 if the transcript changed, 0 if not, or -status on failure. */
JNIEXPORT jint JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeStreamFeed(JNIEnv * env, jclass, jlong jh, jfloatArray jpcm, jint n) {
    Handle * h = H(jh);
    n          = std::min(n, env->GetArrayLength(jpcm));
    if (n <= 0) return 0;  // the engine rejects an empty feed, and there is nothing to do
    float * pcm = env->GetFloatArrayElements(jpcm, nullptr);
    if (pcm == nullptr) return -TRANSCRIBE_ERR_OOM;
    transcribe_stream_update upd;
    transcribe_stream_update_init(&upd);
    transcribe_status st;
    {
        Pinned pin(h);
        st = transcribe_stream_feed(h->session, pcm, n, &upd);
    }
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (st != TRANSCRIBE_OK) return -static_cast<jint>(st);
    return upd.result_changed ? 1 : 0;
}

/** Flushes the stream. Returns a transcribe_status. */
JNIEXPORT jint JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeStreamFinalize(JNIEnv *, jclass, jlong jh) {
    Handle *          h = H(jh);
    transcribe_status st;
    {
        Pinned pin(h);
        st = transcribe_stream_finalize(h->session, nullptr);
    }
    // a stream reports running past the model's position cap only through this flag
    if (st == TRANSCRIBE_OK && transcribe_was_truncated(h->session)) LOGW("stream transcript was truncated");
    return st;
}

/** Abandons the stream (cancelled dictation), leaving the session ready for the next one. */
JNIEXPORT void JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeStreamReset(JNIEnv *, jclass, jlong jh) {
    transcribe_stream_reset(H(jh)->session);
}

/** The live text of a stream: the settled part plus the part that may still change. */
JNIEXPORT jstring JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeStreamDisplayText(JNIEnv * env, jclass, jlong jh) {
    transcribe_stream_text t;
    transcribe_stream_text_init(&t);
    if (transcribe_stream_get_text(H(jh)->session, &t) != TRANSCRIBE_OK) return utf8(env, "");
    std::string s;
    if (t.committed_text) s.append(t.committed_text, t.committed_text_bytes);
    if (t.tentative_text) s.append(t.tentative_text, t.tentative_text_bytes);
    return utf8(env, s.data(), s.size());
}

/** Transcribes a whole recording at once (models that don't stream). Returns a transcribe_status. */
JNIEXPORT jint JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeRun(JNIEnv * env, jclass, jlong jh, jfloatArray jpcm, jint n, jstring jlang) {
    Handle * h = H(jh);
    h->abort.store(false);
    transcribe_run_params rp;
    std::string           lang;
    if (!fill_run_params(env, rp, jlang, lang)) return TRANSCRIBE_ERR_OOM;
    n = std::min(n, env->GetArrayLength(jpcm));
    if (n <= 0) return TRANSCRIBE_ERR_INVALID_ARG;
    float * pcm = env->GetFloatArrayElements(jpcm, nullptr);
    if (pcm == nullptr) return TRANSCRIBE_ERR_OOM;
    transcribe_status st;
    {
        Pinned pin(h);
        st = transcribe_run(h->session, pcm, n, &rp);
    }
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    return st;
}

/** The final transcript of the last run or finished stream. */
JNIEXPORT jstring JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeFullText(JNIEnv * env, jclass, jlong jh) {
    return utf8(env, transcribe_full_text(H(jh)->session));
}

/** The words of the last result. Their times come from [nativeWordTimes]. */
JNIEXPORT jobjectArray JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeWords(JNIEnv * env, jclass, jlong jh) {
    std::vector<Word> words = result_words(H(jh)->session);
    jclass            cls   = env->FindClass("java/lang/String");
    jobjectArray      out   = env->NewObjectArray(static_cast<jsize>(words.size()), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < words.size(); ++i) {
        jstring s = utf8(env, words[i].text.data(), words[i].text.size());
        if (s == nullptr) return nullptr;
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

/** Start and end (ms) of each word of the last result, as [t0, t1, t0, t1, ...]; all 0 without timestamps. */
JNIEXPORT jlongArray JNICALL
Java_org_futo_voiceinput_shared_transcribe_TranscribeNative_nativeWordTimes(JNIEnv * env, jclass, jlong jh) {
    std::vector<Word>  words = result_words(H(jh)->session);
    std::vector<jlong> times(2 * words.size(), 0);
    for (size_t i = 0; i < words.size(); ++i) {
        times[2 * i]     = words[i].t0;
        times[2 * i + 1] = words[i].t1;
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(times.size()));
    if (out == nullptr) return nullptr;
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(times.size()), times.data());
    return out;
}

}  // extern "C"
