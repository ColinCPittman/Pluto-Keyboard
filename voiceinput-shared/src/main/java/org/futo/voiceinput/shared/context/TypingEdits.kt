package org.futo.voiceinput.shared.context

/**
 * A candidate edit for slips made while editing a message: [start]..[end] of the text becomes
 * [replacement]. A missing word replaces the next word with both ("would" -> "I would"), a leftover
 * word replaces it and its neighbour with the neighbour ("to to" -> "to"), a swap replaces both words
 * ("of idea" -> "idea of"), and an ending or join replaces the word or words it changes.
 */
class TypingEdit(
    /** "add", "remove", "ending", "order" or "join". */
    val kind: String,
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
    /** Index of the first and last word the edit touches (in the checked words). */
    val firstWord: Int,
    val lastWord: Int,
    /** Where the first word that differs starts, and that word, for the one-pass probe (null: no probe). */
    val probeAt: Int,
    val probeWord: String?,
    /** An exact repeat ("to to"), which is cheap to tell and always scored. */
    val repeat: Boolean = false,
    /** A word typed twice in two forms next to each other ("its it's", "photos photo"), which is good evidence too. */
    val nearRepeat: Boolean = false,
    /** A word typed again a few words later ("should probably should"), which is weaker evidence (small words repeat anyway). */
    val windowRepeat: Boolean = false,
    /** For a removal, the index of the word that goes (-1 for the other kinds). */
    val removed: Int = -1,
)

/**
 * Builds the candidate edits of [TypingCorrector] for the words of a sentence. Each kind follows a
 * pattern of editing slips, not particular sentences, and the LM decides which (if any) is meant.
 */
class TypingEdits(
    private val config: TypingCorrectionConfig,
    private val lexicon: SoundAlikeLexicon?,
) {
    /**
     * Edits of [words] (all words of [text], the checked ones start at [first]). [usable] tells
     * whether a word may change (not skipped, ready), [opens] whether the word at an offset opens its
     * sentence, and [unknown] whether a word start is a word no dictionary knows.
     */
    fun candidates(
        text: String,
        words: List<MatchResult>,
        first: Int,
        usable: (Int) -> Boolean,
        opens: (Int) -> Boolean,
        unknown: (Int) -> Boolean,
    ): List<TypingEdit> {
        val out = LinkedHashMap<Triple<Int, Int, String>, TypingEdit>()
        // the same edit found twice keeps its strongest evidence
        fun rank(e: TypingEdit) = if (e.repeat) 3 else if (e.nearRepeat) 2 else if (e.windowRepeat) 1 else 0
        fun add(e: TypingEdit) {
            if (e.replacement == e.original) return
            val key = Triple(e.start, e.end, e.replacement)
            val had = out[key]
            if (had == null || rank(e) > rank(had)) out[key] = e
        }
        // two words are neighbours when only spaces are between them (no comma or sentence end)
        fun spaced(i: Int): Boolean {
            if (i < first || i + 1 >= words.size) return false
            val gap = text.substring(words[i].range.last + 1, words[i + 1].range.first)
            return gap.isNotEmpty() && gap.all { it == ' ' }
        }
        fun w(i: Int) = words[i].value
        fun s(i: Int) = words[i].range.first
        fun e(i: Int) = words[i].range.last + 1
        for (i in first until words.size) {
            val m = words[i]
            val l = m.value.lowercase()
            val opening = opens(s(i))
            val lower = m.value.none { it.isUpperCase() } || (opening && m.value.drop(1).none { it.isUpperCase() }) || m.value == "I"

            // a small word is missing before this one (never before the first word of a sentence)
            if (config.missingWords && i > first && spaced(i - 1) && usable(i)) {
                val prev = w(i - 1).lowercase()
                for (x in ADD_WORDS) {
                    if (x.equals(prev, true) || x.equals(l, true)) continue
                    add(TypingEdit("add", s(i), e(i), m.value, "$x ${m.value}", i, i, s(i), x))
                }
            }

            // (words people double on purpose, "so so good", "bye bye", are not repeats)
            if (config.leftoverWords && usable(i) && !opening && l !in DOUBLED_ON_PURPOSE) {
                // an exact repeat of the word before it ("to to")
                if (i > first && spaced(i - 1) && usable(i - 1) && w(i - 1).equals(m.value, true)) {
                    add(TypingEdit("remove", s(i - 1), e(i), text.substring(s(i - 1), e(i)), w(i - 1), i - 1, i,
                        s(i), if (spaced(i)) w(i + 1) else null, repeat = true, removed = i))
                }
                // a near repeat of the word after it ("its it's", "photos photo"), either may go
                if (spaced(i) && usable(i + 1) && nearRepeat(m.value, w(i + 1))) {
                    val both = text.substring(s(i), e(i + 1))
                    add(TypingEdit("remove", s(i), e(i + 1), both, w(i + 1), i, i + 1, s(i), w(i + 1), nearRepeat = true, removed = i))
                    add(TypingEdit("remove", s(i), e(i + 1), both, m.value, i, i + 1,
                        s(i + 1), if (spaced(i + 1)) w(i + 2) else null, nearRepeat = true, removed = i + 1))
                }
                // any word may be left over from an edit ("will call text you"), and one repeated a little
                // later ("we should probably should") is better evidence
                val later = (i + 2..minOf(words.size - 1, i + REPEAT_WINDOW)).any { w(it).equals(m.value, true) }
                val earlier = (maxOf(first, i - REPEAT_WINDOW)..i - 2).any { w(it).equals(m.value, true) }
                if (spaced(i) && usable(i + 1)) {
                    add(TypingEdit("remove", s(i), e(i + 1), text.substring(s(i), e(i + 1)), w(i + 1), i, i + 1, s(i), w(i + 1),
                        windowRepeat = later || earlier, removed = i))
                }
            }

            // a wrong ending, or a word that stopped agreeing ("a long lists", "you friends")
            if (config.wordEndings && usable(i) && (lower || l.endsWith("'s"))) {
                for (f in forms(l)) {
                    val rep = if (f == "i") "I" else TypingCorrector.matchCase(m.value, f)
                    add(TypingEdit("ending", s(i), e(i), m.value, rep, i, i, s(i), rep))
                }
            }

            // two neighbouring words in the wrong order (not the first word of a sentence)
            if (config.wordOrder && spaced(i) && !opening && usable(i) && usable(i + 1)) {
                val a = m.value
                val b = w(i + 1)
                if (!a.equals(b, true) && isPlain(a) && isPlain(b)) {
                    val gap = text.substring(e(i), s(i + 1))
                    add(TypingEdit("order", s(i), e(i + 1), text.substring(s(i), e(i + 1)), b + gap + a, i, i + 1, s(i), b))
                }
            }

            if (config.joinSplit && usable(i)) {
                // two words that are one ("Every thing")
                if (spaced(i) && usable(i + 1) && lexicon != null && lower && isPlain(w(i + 1)) &&
                    m.value.all { it.isLetter() } && w(i + 1).all { it.isLetter() } && l.length >= 2 && w(i + 1).length >= 2
                ) {
                    val joined = l + w(i + 1).lowercase()
                    if (lexicon.isLowercaseWord(joined)) {
                        val rep = TypingCorrector.matchCase(m.value, joined)
                        add(TypingEdit("join", s(i), e(i + 1), text.substring(s(i), e(i + 1)), rep, i, i + 1, s(i), rep))
                    }
                }
                // one word that is two ("upto"), from the table or a word no dictionary knows
                if (lower && m.value.all { it.isLetter() }) {
                    for (sp in splits(l, unknown(s(i)))) {
                        val rep = TypingCorrector.matchCase(m.value, sp).replace(Regex("\\bi\\b"), "I")
                        add(TypingEdit("join", s(i), e(i), m.value, rep, i, i, s(i), rep.substringBefore(' ')))
                    }
                }
                // a missing (or extra) apostrophe ("thats", "Its", "it's tail")
                APOSTROPHES[l]?.let { c ->
                    val rep = if (c.startsWith("i'")) "I" + c.drop(1) else TypingCorrector.matchCase(m.value, c)
                    add(TypingEdit("join", s(i), e(i), m.value, rep, i, i, s(i), rep))
                }
            }
        }
        return out.values.toList()
    }

    /** Lowercase letters and apostrophes only (not a name or an acronym), or "I". */
    private fun isPlain(w: String) = w == "I" || w.none { it.isUpperCase() }

    /** Two different words that are the same word typed twice ("its it's", "photos photo", "you you're"). */
    private fun nearRepeat(a: String, b: String): Boolean {
        val x = a.lowercase().replace("'", "")
        val y = b.lowercase().replace("'", "")
        if (a.equals(b, true)) return false
        if (x == y) return true
        val (s, l) = if (x.length <= y.length) x to y else y to x
        return s.length >= 3 && l.startsWith(s) && l.length - s.length <= 2
    }

    /** Real words that differ from [l] in their ending (other number, tense or form), and the irregular pairs. */
    fun forms(l: String): List<String> {
        val out = LinkedHashSet<String>()
        IRREGULAR[l]?.let { out.addAll(it) }
        val lex = lexicon ?: return out.toList()
        if (l.endsWith("'s") && l.length > 3) {
            val base = l.dropLast(2)
            if (lex.knows(base)) out.add(base)
        }
        if (l.length < 3 || !l.all { it in 'a'..'z' } || l in NO_FORMS) return out.toList()
        val c = ArrayList<String>()
        // number, and the third person
        when {
            l.endsWith("ies") -> c.add(l.dropLast(3) + "y")
            l.endsWith("ss") -> c.add(l + "es")
            l.endsWith("s") -> { c.add(l.dropLast(1)); if (l.endsWith("es")) c.add(l.dropLast(2)) }
            else -> {
                c.add(l + "s"); c.add(l + "es")
                if (l.endsWith("y")) c.add(l.dropLast(1) + "ies")
            }
        }
        // tense
        if (l.endsWith("ed")) {
            c.add(l.dropLast(2)); c.add(l.dropLast(1))
            if (l.endsWith("ied")) c.add(l.dropLast(3) + "y")
            if (l.length > 4 && l[l.length - 3] == l[l.length - 4]) c.add(l.dropLast(3))
        } else {
            c.add(l + "ed"); c.add(l + "d")
            if (l.endsWith("y")) c.add(l.dropLast(1) + "ied")
            c.add(l + l.last() + "ed")
        }
        // the -ing form
        if (l.endsWith("ing")) {
            val stem = l.dropLast(3)
            c.add(stem); c.add(stem + "e"); c.add(stem + "s")
            if (stem.length > 2 && stem.last() == stem[stem.length - 2]) c.add(stem.dropLast(1))
        } else {
            c.add(l + "ing")
            if (l.endsWith("e")) c.add(l.dropLast(1) + "ing")
            c.add(l + l.last() + "ing")
        }
        // "different" / "difference"
        if (l.endsWith("nt")) c.add(l.dropLast(1) + "ce")
        if (l.endsWith("nce")) c.add(l.dropLast(2) + "t")
        for (f in c) if (f != l && f.length >= 2 && lex.isLowercaseWord(f)) out.add(f)
        return out.toList()
    }

    /** Ways to split [l] into two words: from the table, or (when no dictionary knows it) two real words. */
    private fun splits(l: String, unknownWord: Boolean): List<String> {
        SPLITS[l]?.let { return listOf(it) }
        val lex = lexicon ?: return emptyList()
        if (!unknownWord || l.length < 4 || lex.knows(l)) return emptyList()
        val out = ArrayList<String>()
        for (k in 1 until l.length) {
            val a = l.substring(0, k)
            val b = l.substring(k)
            val okA = (a.length >= 2 && lex.isLowercaseWord(a)) || a == "a" || a == "i"
            val okB = b.length >= 2 && lex.isLowercaseWord(b)
            if (okA && okB) out.add("$a $b")
        }
        return out.take(MAX_SPLITS)
    }

    companion object {
        /** A word repeated within this many words may be a leftover. */
        const val REPEAT_WINDOW = 3
        private const val MAX_SPLITS = 3

        /** Small words that may be missing. */
        val ADD_WORDS = listOf(
            "a", "an", "the", "to", "of", "I", "it", "is", "are", "was", "be", "in", "on", "at", "for", "with",
            "and", "you", "we", "have", "do", "not", "that", "about", "my", "will",
        )

        /** Words people double on purpose ("very very", "no no no", "bye bye"), which are never removed. */
        private val DOUBLED_ON_PURPOSE = setOf(
            "so", "very", "really", "yeah", "yes", "yep", "no", "nope", "bye", "ha", "haha", "hey", "wow", "oh", "ok",
            "okay", "please", "more", "much", "many", "too", "now", "well", "go", "never", "again", "over", "far",
            "little", "long", "round", "around", "out", "up", "down", "lol", "omg", "hi", "hello", "thanks",
            "sorry", "fast", "slowly", "louder", "better", "worse", "together", "high", "deep", "boo", "yay", "woo",
        )

        /** Words whose endings are not changed by the suffix rules (function words; the irregular table covers some). */
        private val NO_FORMS = setOf(
            "its", "his", "hers", "ours", "yours", "theirs", "this", "thus", "was", "has", "does", "yes", "less",
            "unless", "news", "always", "perhaps", "besides", "sometimes", "plus", "the", "and", "for", "you", "are",
            "were", "but", "not", "she", "her", "him", "they", "them", "their", "there", "then", "than", "that",
            "these", "those", "with", "from", "into", "onto", "about", "what", "when", "where", "who", "why", "how",
            "our", "your", "had", "have", "did", "can", "will", "would", "could", "should", "might", "must", "been",
            "being", "any", "all", "out", "off", "too", "very", "just", "also", "one", "two", "own", "few", "who's",
        )

        /** Irregular forms that agree differently. */
        private val IRREGULAR: Map<String, List<String>> = run {
            val pairs = listOf(
                "is" to "are", "was" to "were", "has" to "have", "do" to "does", "you" to "your", "this" to "these",
                "that" to "those", "isn't" to "aren't", "wasn't" to "weren't", "don't" to "doesn't", "hasn't" to "haven't",
            )
            val m = HashMap<String, MutableList<String>>()
            for ((a, b) in pairs) {
                m.getOrPut(a) { ArrayList() }.add(b)
                m.getOrPut(b) { ArrayList() }.add(a)
            }
            m
        }

        /** Words commonly typed together that are two. */
        private val SPLITS = mapOf(
            "upto" to "up to", "alot" to "a lot", "incase" to "in case", "atleast" to "at least",
            "eachother" to "each other", "infront" to "in front", "aswell" to "as well", "thankyou" to "thank you",
            "everytime" to "every time", "inorder" to "in order",
        )

        /** Contractions typed without the apostrophe, and "it's" for "its" the other way. */
        private val APOSTROPHES: Map<String, String> = run {
            val m = HashMap<String, String>()
            for (c in listOf(
                "that's", "don't", "didn't", "doesn't", "isn't", "wasn't", "weren't", "aren't", "wouldn't", "couldn't",
                "shouldn't", "haven't", "hasn't", "hadn't", "won't", "can't", "i'm", "i've", "you're", "you've",
                "you'll", "they're", "they've", "they'll", "what's", "where's", "there's", "here's", "who's", "she's",
                "he's", "it's", "let's",
            )) m[c.replace("'", "")] = c
            m["it's"] = "its"
            m
        }
    }
}
