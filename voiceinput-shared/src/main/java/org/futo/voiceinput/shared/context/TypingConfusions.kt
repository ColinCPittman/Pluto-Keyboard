package org.futo.voiceinput.shared.context

/**
 * Real words that are one key off from other common real words ("an" / "and", "or" / "of",
 * "form" / "from") are typos a keyboard can't catch while typing, because both are words. Built by
 * tools/context-bench/build_typing_confusions.py, with one line per typed word, then the words it
 * may be a slip of. Used by [TypingCorrector].
 */
class TypingConfusions(
    private val map: Map<String, List<String>>,
    /** The pairs (typed, intended) the user added themselves (see [TypingPairEdits.merge]). */
    private val own: Set<Pair<String, String>> = emptySet(),
    /** Words the user said never to change (missed typos skip them too). */
    private val never: Set<String> = emptySet(),
) {
    /** Whether [word] is on the user's never-change list. */
    fun isNever(word: String): Boolean = never.isNotEmpty() && word.lowercase() in never

    /** Words the user may have meant when typing [word] (lowercase, empty if none). */
    fun candidates(word: String): List<String> = map[word.lowercase()] ?: emptyList()

    /** Whether the user added the pair [typed] -> [intended] themselves. */
    fun isOwn(typed: String, intended: String): Boolean =
        own.isNotEmpty() && (typed.lowercase() to intended.lowercase()) in own

    val size: Int get() = map.size

    /** Every typed word with the words it may be a slip of. */
    val pairs: Map<String, List<String>> get() = map

    companion object {
        private const val RESOURCE = "org/futo/voiceinput/shared/context/typing-confusions-en.txt"

        fun parse(lines: Sequence<String>): TypingConfusions {
            val map = HashMap<String, List<String>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.trim().split(' ').filter { it.isNotEmpty() }
                if (p.size >= 2) map[p[0]] = p.drop(1)
            }
            return TypingConfusions(map)
        }

        /** The built-in English list. */
        val builtIn: TypingConfusions by lazy {
            val stream = TypingConfusions::class.java.classLoader?.getResourceAsStream(RESOURCE)
            stream?.bufferedReader(Charsets.UTF_8)?.useLines { parse(it) } ?: TypingConfusions(emptyMap())
        }
    }
}
