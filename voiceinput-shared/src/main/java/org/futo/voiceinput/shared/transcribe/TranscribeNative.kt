package org.futo.voiceinput.shared.transcribe

/** JNI entry points of libplutotranscribe (src/main/cpp/transcribe/transcribe_jni.cpp). */
internal object TranscribeNative {
    init {
        System.loadLibrary("plutotranscribe")
    }

    /** [cpus]: the cores the engine runs on, or null for any. */
    @JvmStatic external fun nativeOpen(path: String, threads: Int, cpus: IntArray?): Long
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeDescription(handle: Long): String
    @JvmStatic external fun nativeFeatures(handle: Long): Int
    @JvmStatic external fun nativeLanguages(handle: Long): Array<String>
    @JvmStatic external fun nativeAbort(handle: Long, abort: Boolean)
    @JvmStatic external fun nativeStreamBegin(handle: Long, language: String?): Int
    @JvmStatic external fun nativeStreamFeed(handle: Long, pcm: FloatArray, n: Int): Int
    @JvmStatic external fun nativeStreamFinalize(handle: Long): Int
    @JvmStatic external fun nativeStreamReset(handle: Long)
    @JvmStatic external fun nativeStreamDisplayText(handle: Long): String
    @JvmStatic external fun nativeRun(handle: Long, pcm: FloatArray, n: Int, language: String?): Int
    @JvmStatic external fun nativeFullText(handle: Long): String
    @JvmStatic external fun nativeWords(handle: Long): Array<String>
    @JvmStatic external fun nativeWordTimes(handle: Long): LongArray
}
