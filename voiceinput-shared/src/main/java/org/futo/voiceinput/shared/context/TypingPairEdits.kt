package org.futo.voiceinput.shared.context

/** A typed word with the words you meant by it ("teh" -> "the") as your own pair, switched on or off. */
data class OwnTypingPair(val typed: String, val intended: List<String>, val enabled: Boolean = true)

/**
 * Your changes to the typing corrections list (the "Typing corrections" settings page), meaning your own
 * pairs, built-in pairs you switched off and words that are never changed. Stored one per line:
 * `typed<TAB>intended intended` (a switched-off one ends in a tab and "0"), `-typed<TAB>intended` for
 * a switched-off built-in pair, `=word` for a word that is never changed.
 */
data class TypingPairEdits(
    val own: List<OwnTypingPair> = emptyList(),
    val offBuiltIn: List<Pair<String, String>> = emptyList(),
    val never: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = own.isEmpty() && offBuiltIn.isEmpty() && never.isEmpty()

    /** Whether one of your pairs (switched on or off) already has [typed] -> [intended]. */
    fun hasOwn(typed: String, intended: String, except: Int? = null): Boolean =
        own.withIndex().any { (i, o) -> i != except && o.typed == typed && intended in o.intended }

    /**
     * These edits with [pair] saved, in place of your pair at [replacing] (null means added). A typed
     * word has one pair, so when another of yours has it, [pair]'s words are merged into that one.
     */
    fun withOwn(pair: OwnTypingPair, replacing: Int? = null): TypingPairEdits {
        val at = own.withIndex().firstOrNull { (i, o) -> i != replacing && o.typed == pair.typed }?.index
        val list = own.toMutableList()
        when {
            at != null -> {
                list[at] = mergeOwn(list[at], pair)
                if (replacing != null && replacing in list.indices) list.removeAt(replacing)
            }
            replacing != null && replacing in list.indices -> list[replacing] = pair
            else -> list.add(pair)
        }
        return copy(own = list)
    }

    /**
     * These edits with the fix [typed] -> [meant] switched off ("Stop this fix" after an undo), where it
     * leaves your own pair for [typed] (the pair goes when it had no other word), and a built-in
     * pair in [builtIn] is switched off. [typed] may still be fixed into its other words.
     */
    fun withoutPair(typed: String, meant: String, builtIn: TypingConfusions): TypingPairEdits {
        val t = word(typed) ?: return this
        val m = word(meant) ?: return this
        val own2 = own.mapNotNull { o ->
            if (o.typed != t || m !in o.intended) o
            else o.copy(intended = o.intended - m).takeIf { it.intended.isNotEmpty() }
        }
        val off2 = if (m in builtIn.candidates(t) && (t to m) !in offBuiltIn) offBuiltIn + (t to m) else offBuiltIn
        return copy(own = own2, offBuiltIn = off2)
    }

    fun serialize(): String = buildString {
        for (o in own) append(o.typed).append('\t').append(o.intended.joinToString(" ")).append(if (o.enabled) "" else "\t0").append('\n')
        for ((t, i) in offBuiltIn) append('-').append(t).append('\t').append(i).append('\n')
        for (w in never) append('=').append(w).append('\n')
    }

    companion object {
        private val WORD = Regex("[a-z]+(?:'[a-z]+)?")

        /** [text] as one lowercase word, or null when it isn't one. */
        fun word(text: String): String? = text.trim().lowercase().takeIf { WORD.matches(it) }

        /** The words of [text] (separated by spaces or commas), lowercase, without repeats or invalid ones. */
        fun words(text: String): List<String> =
            text.split(' ', ',', ';').mapNotNull { word(it) }.distinct()

        fun parse(stored: String): TypingPairEdits {
            val own = ArrayList<OwnTypingPair>()
            val off = ArrayList<Pair<String, String>>()
            val never = ArrayList<String>()
            for (line in stored.lines()) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                when {
                    line.startsWith("=") -> word(line.drop(1))?.let { if (it !in never) never.add(it) }
                    line.startsWith("-") && p.size >= 2 -> {
                        val t = word(p[0].drop(1)); val i = word(p[1])
                        if (t != null && i != null && (t to i) !in off) off.add(t to i)
                    }
                    p.size >= 2 -> {
                        val t = word(p[0]); val i = words(p[1])
                        if (t != null && i.isNotEmpty()) {
                            // a typed word saved twice (older versions allowed it) becomes one pair
                            val pair = OwnTypingPair(t, i, enabled = p.getOrNull(2) != "0")
                            val at = own.indexOfFirst { it.typed == t }
                            if (at < 0) own.add(pair) else own[at] = mergeOwn(own[at], pair)
                        }
                    }
                }
            }
            return TypingPairEdits(own, off, never)
        }

        private fun mergeOwn(a: OwnTypingPair, b: OwnTypingPair) =
            a.copy(intended = (a.intended + b.intended).distinct(), enabled = a.enabled || b.enabled)

        /** A typed word found by [search], with its built-in pairs (with whether each is on) and your own pairs. */
        data class Row(
            val typed: String,
            /** Built-in intended words, in order, each with whether it is switched on. */
            val builtIn: List<Pair<String, Boolean>>,
            /** Indices into [TypingPairEdits.own] of your pairs for this word. */
            val own: List<Int>,
            val never: Boolean,
        )

        /**
         * The typed words starting with [query] from the built-in pairs (unless [builtInOn] is off)
         * and your own pairs, exact match first, at most [limit] + 1 rows.
         */
        fun search(builtIn: TypingConfusions, edits: TypingPairEdits, builtInOn: Boolean, query: String, limit: Int): List<Row> {
            val q = word(query) ?: return emptyList()
            val words = LinkedHashSet<String>()
            if (builtInOn) builtIn.pairs.keys.filterTo(words) { it.startsWith(q) }
            edits.own.map { it.typed }.filterTo(words) { it.startsWith(q) }
            return words.sortedWith(compareBy({ it != q }, { it.length }, { it })).take(limit + 1).map { t ->
                val off = edits.offBuiltIn.filter { it.first == t }.map { it.second }
                Row(
                    t,
                    if (builtInOn) builtIn.candidates(t).map { it to (it !in off) } else emptyList(),
                    edits.own.indices.filter { edits.own[it].typed == t },
                    t in edits.never,
                )
            }
        }

        /**
         * The pairs in use are [builtIn] (unless [builtInOn] is off) without the pairs you switched off,
         * plus your switched-on own pairs, without the words that are never changed.
         */
        fun merge(builtIn: TypingConfusions, edits: TypingPairEdits, builtInOn: Boolean = true): TypingConfusions {
            if (edits.isEmpty && builtInOn) return builtIn
            val out = LinkedHashMap<String, List<String>>()
            if (builtInOn) {
                val off = edits.offBuiltIn.groupBy({ it.first }, { it.second })
                for ((t, c) in builtIn.pairs) {
                    val keep = c.filter { it !in (off[t] ?: emptyList()) }
                    if (keep.isNotEmpty()) out[t] = keep
                }
            }
            val own = HashSet<Pair<String, String>>()
            for (o in edits.own) {
                if (!o.enabled || o.typed in edits.never) continue
                val words = o.intended.filter { it != o.typed }
                out[o.typed] = ((out[o.typed] ?: emptyList()) + words).distinct()
                words.forEach { own.add(o.typed to it) }
            }
            for (w in edits.never) out.remove(w)
            out.values.removeAll { it.isEmpty() }
            return TypingConfusions(out, own, edits.never.toSet())
        }
    }
}
