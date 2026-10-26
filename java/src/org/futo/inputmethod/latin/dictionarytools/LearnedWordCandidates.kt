package org.futo.inputmethod.latin.dictionarytools

import java.io.File
import java.util.Locale

/** A word from the keyboard's learned words (user history) for one language. Pure data. */
data class LearnedWord(val word: String, val locale: String, val probability: Int)

/** The review list for "Copy learned words to dictionary". */
data class CopyCandidates(
    /** Words that look like passwords or keys. They are shown first and unchecked by default. */
    val sensitive: List<LearnedWord>,
    /** Everything else worth copying, checked by default. */
    val normal: List<LearnedWord>,
)

object LearnedWordCandidates {
    private fun key(word: String) = word.lowercase(Locale.ROOT)

    /**
     * Picks the learned words worth copying to the personal dictionary, such as names, slang and other
     * words the main dictionary doesn't know.
     *
     * Skips words that are too short, already known ([isKnownWord], so in the main dictionary of that
     * language), already in the personal dictionary ([inPersonalDictionary]) or hidden by the user
     * ([hidden], compared ignoring case). Each word appears once (the most probable spelling wins
     * when the same word was learned with different capitalisation or in several languages), most
     * probable first. Words flagged by [SensitiveWords.looksSensitive] go to [CopyCandidates.sensitive].
     */
    fun select(
        learned: List<LearnedWord>,
        isKnownWord: (LearnedWord) -> Boolean,
        inPersonalDictionary: (LearnedWord) -> Boolean,
        hidden: Set<String>,
        looksSensitive: (String) -> Boolean = SensitiveWords::looksSensitive,
    ): CopyCandidates {
        val hiddenKeys = hidden.map { key(it) }.toSet()
        val best = LinkedHashMap<String, LearnedWord>()
        learned.sortedByDescending { it.probability }.forEach { w ->
            val trimmed = w.word.trim()
            if (trimmed.length < 2 || trimmed != w.word) return@forEach
            if (trimmed.none { it.isLetterOrDigit() }) return@forEach
            val k = key(trimmed)
            if (k in hiddenKeys || k in best) return@forEach
            if (isKnownWord(w) || inPersonalDictionary(w)) return@forEach
            best[k] = w
        }
        val (sensitive, normal) = best.values.partition { looksSensitive(it.word) }
        return CopyCandidates(sensitive, normal)
    }
}

/**
 * Pluto-private list of learned words the user chose to "Never suggest" for copying to the
 * personal dictionary. One word per line in a UTF-8 text file in the app's private storage.
 * This is separate from FUTO's Word Blacklist (which hides typing suggestions) and from the
 * Android personal dictionary, and it only affects the "Copy learned words" list.
 */
class HiddenLearnedWords(private val file: File) {
    @Synchronized
    fun all(): List<String> = try {
        if (!file.exists()) emptyList()
        else file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun contains(word: String): Boolean = all().any { it.equals(word, ignoreCase = true) }

    @Synchronized
    fun add(word: String) {
        val w = word.trim()
        if (w.isEmpty() || w.contains('\n')) return
        val current = all()
        if (current.any { it.equals(w, ignoreCase = true) }) return
        write(current + w)
    }

    @Synchronized
    fun remove(word: String) {
        val current = all()
        val updated = current.filterNot { it.equals(word.trim(), ignoreCase = true) }
        if (updated.size != current.size) write(updated)
    }

    private fun write(words: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(words.joinToString("\n", postfix = if (words.isEmpty()) "" else "\n"), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        const val FILE_NAME = "pluto_hidden_learned_words.txt"
    }
}
