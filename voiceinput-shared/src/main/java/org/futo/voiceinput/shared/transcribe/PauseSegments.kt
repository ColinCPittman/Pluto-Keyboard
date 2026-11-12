package org.futo.voiceinput.shared.transcribe

/**
 * Splits a transcript into the utterances between pauses, from its word times, which is what
 * Moonshine reports by itself and what the context stage needs (spoken commands, quotes and
 * names count at pauses).
 */
object PauseSegments {
    /** A silence this long between two words ends an utterance (Moonshine's VAD splits at about this). */
    const val DEFAULT_GAP_MS = 500L

    /**
     * The utterances of [text], whose [words] have [times] [t0, t1, t0, t1, ...] in ms. Without
     * usable times (all 0, or a count that doesn't match), the whole text is one utterance.
     */
    fun split(text: String, words: List<String>, times: LongArray, gapMs: Long = DEFAULT_GAP_MS): List<String> {
        val whole = listOf(text.trim()).filter { it.isNotEmpty() }
        if (words.isEmpty() || times.size != 2 * words.size || times.all { it == 0L }) return whole
        val out = ArrayList<String>()
        val current = StringBuilder()
        // the end of the last word kept (an empty word in between has no real times)
        var lastEnd = 0L
        for (i in words.indices) {
            val w = words[i].trim()
            if (w.isEmpty()) continue
            if (current.isNotEmpty() && times[2 * i] - lastEnd >= gapMs) {
                out.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(w)
            lastEnd = times[2 * i + 1]
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.ifEmpty { whole }
    }
}
