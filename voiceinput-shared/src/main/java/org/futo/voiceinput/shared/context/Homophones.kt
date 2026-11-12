package org.futo.voiceinput.shared.context

/**
 * Words that sound exactly alike ("meat" / "meet", "their" / "there"), as opposed to the
 * lexicon's near sound-alikes ("pool" / "poll", "kite" / "cat"). A voice model mixes up exact
 * homophones all the time, but a near one it heard differently, so swapping it needs much more
 * evidence (see [ContextRescoreConfig.nearMargin]), and one that differs in a clearly different
 * sound ([confusable] false) is never swapped. Built by tools/context-bench/build_homophones.py
 * from the speech engine's pronunciations of the lexicon's pairs, and a word that is not listed has
 * no exact homophone.
 *
 * The lexicon only pairs words that pass its own filters (common enough, spelled alike, at most 6
 * per word), so it misses some homophones voice models do write ("High Jordan" for "Hi Jordan").
 * Those are listed by hand in soundalike-extra-en.txt, in the same format, and are candidates of
 * their own ([extraCandidates]). A possessive and its plural ("egg's" / "eggs") also sound
 * exactly alike ([possessiveVariants]).
 */
object Homophones {
    private const val RESOURCE = "org/futo/voiceinput/shared/context/homophones-en.txt"
    private const val EXTRA_RESOURCE = "org/futo/voiceinput/shared/context/soundalike-extra-en.txt"

    private class Data(val groupOf: Map<String, Int>, val near: Set<String>, val extra: Map<String, List<String>>)

    private val data: Data by lazy {
        val groups = HashMap<String, Int>()
        val near = HashSet<String>()
        val extra = HashMap<String, MutableList<String>>()
        var g = 0
        fun read(resource: String, extraPairs: Boolean) {
            val stream = Homophones::class.java.classLoader?.getResourceAsStream(resource) ?: return
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val near1 = line.startsWith("~ ")
                    val ws = (if (near1) line.substring(2) else line).trim().split(' ').filter { it.isNotEmpty() }
                    if (near1) {
                        // a pair of near sound-alikes a voice model can confuse ("bulls" / "bowls")
                        if (ws.size == 2) near.add(key(ws[0], ws[1]))
                    } else {
                        // a group joins any group one of its words is in already
                        val joined = ws.mapNotNull { groups[it] }.toSet()
                        val id = joined.minOrNull() ?: g++
                        if (joined.size > 1) for (e in groups.entries) if (e.value in joined) e.setValue(id)
                        for (w in ws) groups[w] = id
                    }
                    if (extraPairs) for (w in ws) extra.getOrPut(w) { ArrayList() }.addAll(ws.filter { it != w })
                }
            }
        }
        read(RESOURCE, false)
        read(EXTRA_RESOURCE, true)
        Data(groups, near, extra)
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
        val na = TranscriptGuard.normWord(a)
        val nb = TranscriptGuard.normWord(b)
        if (na != nb && isPossessiveVariant(na, nb)) return true
        val ga = groupOf[na] ?: return false
        return ga == groupOf[nb]
    }

    /**
     * Sound-alikes of [word] (any case) the lexicon doesn't pair with it, lowercase: the hand-listed
     * homophones and near sound-alikes. The caller filters them like the lexicon's.
     */
    fun extraCandidates(word: String): List<String> = data.extra[TranscriptGuard.normWord(word)] ?: emptyList()

    /**
     * The plural a possessive sounds like ("egg's" -> "eggs", "kids'" -> "kids"), lowercase, which
     * the voice model wrote with an apostrophe it doesn't hear. Not for contractions ("it's",
     * "let's", "he's"), which are words of their own.
     */
    fun possessiveVariants(word: String): List<String> {
        val w = TranscriptGuard.normWord(word)
        if (w.count { it == '\'' } != 1 || w.length < 4) return emptyList()
        val plural = when {
            w.endsWith("'s") -> w.dropLast(2) + "s"
            w.endsWith("s'") -> w.dropLast(1)
            else -> return emptyList()
        }
        if (plural.length < 4 || plural.endsWith("ss") || !plural.all { it.isLetter() }) return emptyList()
        if (w in CONTRACTIONS) return emptyList()
        return listOf(plural)
    }

    private fun isPossessiveVariant(a: String, b: String) = b in possessiveVariants(a) || a in possessiveVariants(b)

    /** Words ending in 's that are contractions, not possessives. */
    private val CONTRACTIONS = setOf(
        "it's", "let's", "he's", "she's", "that's", "what's", "there's", "here's", "who's", "where's", "when's",
        "how's", "why's", "one's", "this's", "everyone's", "everybody's", "someone's", "somebody's", "nobody's",
        "nothing's", "something's", "everything's", "today's", "tonight's", "tomorrow's",
    )
}
