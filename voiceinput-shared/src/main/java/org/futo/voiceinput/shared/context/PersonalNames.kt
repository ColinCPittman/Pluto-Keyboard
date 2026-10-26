package org.futo.voiceinput.shared.context

/**
 * The user's own words, for the "Names and your words" option, where capitalized entries ("Xiomara",
 * "McKenna") are names, and lowercase personal-dictionary entries that are not ordinary words
 * ("kubectl", "pho") count too ([isOrdinaryWord] filters the rest). [soundAlike] finds words that
 * sound like one to three transcript words ("see ya mara" -> "Xiomara", "Shawn" -> "Sean").
 * The LM then judges the candidate in context.
 */
class PersonalNames(words: Collection<String>, private val isOrdinaryWord: ((String) -> Boolean)? = null) {
    /** Lowercase -> the dictionary's spelling, for every entry (names or not). */
    private val entries: Map<String, String>

    /** Lowercase -> spelling, capitalized entries only. */
    private val names: Map<String, String>

    /** Double Metaphone code -> names. */
    private val byCode: Map<String, List<String>>

    init {
        val e = LinkedHashMap<String, String>()
        val n = LinkedHashMap<String, String>()
        for (raw in words) {
            val w = raw.trim()
            if (w.length < 2 || w.contains(' ') || !w.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }) continue
            val key = TranscriptGuard.normWord(w)
            e.putIfAbsent(key, w)
            val isName = w[0].isUpperCase() && !(w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() })
            val isOwnWord = w.none { it.isUpperCase() } && w.length >= 3 && key !in TranscriptGuard.FUNCTION_LOCK && isOrdinaryWord?.invoke(key) == false
            if (isName || isOwnWord) n.putIfAbsent(key, w)
        }
        entries = e
        names = n
        val codes = HashMap<String, MutableList<String>>()
        for (name in n.values) {
            for (c in TranscriptGuard.soundCodes(TranscriptGuard.normWord(name))) {
                if (c.length >= MIN_CODE) codes.getOrPut(c) { ArrayList() }.let { if (name !in it) it.add(name) }
            }
        }
        byCode = codes
    }

    val size: Int get() = names.size

    val isEmpty: Boolean get() = names.isEmpty()

    /** The personal dictionary's names, as spelled there. */
    fun allNames(): Collection<String> = names.values

    /** The dictionary spelling if [word] is one of the user's words (any case). */
    fun name(word: String): String? = names[TranscriptGuard.normWord(word)]

    /** Whether [word] is a capitalized name of the user's ("Xiomara", not "kubectl"). */
    fun isCapitalizedName(word: String): Boolean = name(word)?.firstOrNull()?.isUpperCase() == true

    /** Whether [word] is in the personal dictionary at all (any case). */
    fun contains(word: String): Boolean = TranscriptGuard.normWord(word) in entries

    /**
     * Names that sound like [span] (one to three words) without being spelled the same,
     * best first. Single words need a code of at least [MIN_CODE] sounds, several words at
     * least three.
     */
    fun soundAlike(span: List<String>): List<String> {
        if (span.isEmpty() || span.size > 3 || byCode.isEmpty()) return emptyList()
        val joined = span.joinToString("") { TranscriptGuard.normWord(it) }
        if (joined.any { it.isDigit() }) return emptyList()
        if (span.size == 1 && joined in TranscriptGuard.FUNCTION_LOCK) return emptyList()
        // only words that look like a misheard name, never a compound ("x-ray", "well-known"),
        // never a lowercase word the voice model knew ("x-ray" -> "Sarah"), never a common word
        // ("Will", "Grace" at a sentence start), and words split from a name need one that is
        // not a common word ("see ya mara")
        if (span.any { '-' in it }) return emptyList()
        val ordinary = span.map { isOrdinaryWord?.invoke(TranscriptGuard.normWord(it)) == true }
        val lowercase = span.size == 1 && span[0].none { it.isUpperCase() }
        if (isOrdinaryWord != null && ordinary.all { it }) return emptyList()
        val out = LinkedHashSet<String>()
        // words run together into a name need its main sound code ("see ya mara" ~ "Xiomara"), because the
        // alternate codes and the silent leading "h" made "oh darn" sound like "Hadorn"
        val strict = if (span.size > 1) DoubleMetaphone.encode(joined.replace("'", "")).first else null
        for (c in TranscriptGuard.soundCodes(joined)) {
            if (c.length < (if (span.size == 1) MIN_CODE else 3)) continue
            for (name in byCode[c].orEmpty()) {
                // a lowercase word becomes one of the user's lowercase words ("fo" -> "pho"), not a name
                if (lowercase && name[0].isUpperCase()) continue
                if (strict != null && DoubleMetaphone.encode(TranscriptGuard.normWord(name).replace("'", "")).first != strict) continue
                if (TranscriptGuard.normWord(name).replace("'", "") != joined.replace("'", "")) out.add(name)
            }
        }
        return out.toList()
    }

    /** The guard's check that [dst] is a personal-dictionary name that sounds like [src]. */
    fun isSoundAlikeName(src: List<String>, dst: String): Boolean = name(dst) == dst && dst in soundAlike(src)

    companion object {
        const val MIN_CODE = 2
        val EMPTY = PersonalNames(emptyList())

        /**
         * The words of [personal], meaning personal-dictionary names and uncommon words, plus the learned
         * names (capitalized words the user typed) that are not common lowercase words ("The" or
         * "Hello" typed at a sentence start, going by [lexicon] case data).
         */
        fun from(personal: PersonalDictionary, lexicon: SoundAlikeLexicon): PersonalNames {
            val learned = personal.learned().filter { w ->
                val n = TranscriptGuard.normWord(w)
                n !in TranscriptGuard.FUNCTION_LOCK && !lexicon.isLowercaseWord(n)
            }
            return PersonalNames(personal.words() + learned) { lexicon.isLowercaseWord(it) }
        }
    }
}
