package org.futo.voiceinput.shared.context

/**
 * Words that sound exactly alike ("meat" / "meet", "their" / "there"), as opposed to the
 * lexicon's near sound-alikes ("pool" / "poll", "kite" / "cat"). A voice model mixes up exact
 * homophones all the time, but a near one it heard differently, so swapping it needs much more
 * evidence (see [ContextRescoreConfig.nearMargin]), and one that differs in a clearly different
 * sound ([confusable] false) is never swapped. Built by tools/context-bench/build_homophones.py
 * from the speech engine's pronunciations of the lexicon's pairs, and a word that is not listed has
 * no exact homophone.
 */
object Homophones {
    private const val RESOURCE = "org/futo/voiceinput/shared/context/homophones-en.txt"

    private class Data(val groupOf: Map<String, Int>, val near: Set<String>)

    private val data: Data by lazy {
        val groups = HashMap<String, Int>()
        val near = HashSet<String>()
        val stream = Homophones::class.java.classLoader?.getResourceAsStream(RESOURCE)
        stream?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            var g = 0
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                if (line.startsWith("~ ")) {
                    // a pair of near sound-alikes a voice model can confuse ("bulls" / "bowls")
                    val p = line.substring(2).trim().split(' ')
                    if (p.size == 2) near.add(key(p[0], p[1]))
                    continue
                }
                for (w in line.trim().split(' ')) if (w.isNotEmpty()) groups[w] = g
                g++
            }
        }
        Data(groups, near)
    }

    private val groupOf get() = data.groupOf

    private fun key(a: String, b: String) = if (a < b) "$a $b" else "$b $a"

    /**
     * [a] and [b] differ in one similar sound a voice model can mix up ("bulls" / "bowls",
     * "pool" / "poll"), not in a clearly different one ("fan" / "phone", "kite" / "cat").
     */
    fun confusable(a: String, b: String): Boolean =
        key(TranscriptGuard.normWord(a), TranscriptGuard.normWord(b)) in data.near

    /** Whether the homophone list could be read (tests). */
    val isLoaded: Boolean get() = groupOf.isNotEmpty()

    /** [a] and [b] sound exactly alike (any case). */
    fun same(a: String, b: String): Boolean {
        val ga = groupOf[TranscriptGuard.normWord(a)] ?: return false
        return ga == groupOf[TranscriptGuard.normWord(b)]
    }
}
