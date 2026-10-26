package org.futo.voiceinput.shared.context

/** One entry of an editable phrase list (filler words, self-corrections, quoted speech), holding a phrase switched on or off. */
data class PhraseEntry(val phrase: String, val enabled: Boolean = true)

/**
 * A built-in phrase list and your changes to it (the "Filler words", "Self-corrections" and
 * "Quoted speech" pages). Only your changes are saved, meaning phrases you added, on or off, and built-in
 * phrases you switched off. So the built-in list can grow, and the "Built-in phrases" switch can
 * leave them out (only your own phrases count) without touching what you saved.
 */
abstract class PhraseList {
    /** The built-in phrases. */
    abstract val DEFAULTS: List<String>

    /**
     * The built-in lists of the versions that saved the whole list (before only changes were
     * saved), oldest first. Frozen, so [DEFAULTS] may grow but these never change.
     */
    abstract val SAVED_DEFAULTS: List<List<String>>

    /** Whether [phrase] can be in the list. */
    open fun isValidPhrase(phrase: String): Boolean = VoiceCommands.isValidPhrase(phrase)

    private val builtInWords by lazy { DEFAULTS.map { VoiceCommands.phraseWords(it) }.toSet() }

    /** Whether [phrase] is a built-in phrase. */
    fun isBuiltIn(phrase: String): Boolean = VoiceCommands.phraseWords(phrase) in builtInWords

    /** The first line of a saved list of changes, and older saves held the whole list without it. */
    private val HEADER = "\tchanges"

    /** One phrase per line, a switched-off one ending in a tab and "0", and no changes gives "". */
    fun serialize(changes: List<PhraseEntry>): String =
        if (changes.isEmpty()) "" else HEADER + "\n" + changes.joinToString("\n") { it.phrase.trim() + if (it.enabled) "" else "\t0" }

    /**
     * Your changes, from [serialize]'s format (blank means none). Invalid or repeated phrases are
     * skipped. A list saved before only changes were saved held the whole list, so its built-in
     * phrases that are on are dropped, and a built-in phrase left out was deleted, so it is off
     * (but not phrases the built-in list gained after that list was saved).
     */
    fun parse(text: String): List<PhraseEntry> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val out = ArrayList<PhraseEntry>()
        val seen = HashSet<List<String>>()
        for (line in lines) {
            val parts = line.split('\t')
            val p = parts[0].trim()
            if (!isValidPhrase(p) || !seen.add(VoiceCommands.phraseWords(p))) continue
            out.add(PhraseEntry(p, enabled = parts.getOrNull(1) != "0"))
        }
        if (lines.first() == HEADER) return out
        // an old save (the version it was saved with is the newest whose new phrases are in it)
        val versions = SAVED_DEFAULTS.map { v -> v.map { VoiceCommands.phraseWords(it) }.toSet() }
        val saved = versions.indices.lastOrNull { i -> i == 0 || (versions[i] - versions[i - 1]).any { it in seen } } ?: return out
        val deleted = SAVED_DEFAULTS[saved].filter { VoiceCommands.phraseWords(it) !in seen }.map { PhraseEntry(it, enabled = false) }
        return out.filter { !(it.enabled && VoiceCommands.phraseWords(it.phrase) in versions[saved]) } + deleted
    }

    /** Your own phrases in [changes], on or off (built-in phrases you switched off are not). */
    fun own(changes: List<PhraseEntry>): List<PhraseEntry> = changes.filter { it.enabled || !isBuiltIn(it.phrase) }

    /** Whether built-in [phrase] is switched on in [changes]. */
    fun isOn(changes: List<PhraseEntry>, phrase: String): Boolean {
        val w = VoiceCommands.phraseWords(phrase)
        return changes.none { !it.enabled && VoiceCommands.phraseWords(it.phrase) == w }
    }

    /** [changes] with built-in [phrase] switched [on]. */
    fun withBuiltIn(changes: List<PhraseEntry>, phrase: String, on: Boolean): List<PhraseEntry> {
        val w = VoiceCommands.phraseWords(phrase)
        val rest = changes.filter { VoiceCommands.phraseWords(it.phrase) != w }
        return if (on) rest else rest + PhraseEntry(phrase, enabled = false)
    }

    /**
     * The switched-on phrases are the built-in ones (unless [builtIn] is off) without those you
     * switched off, and your own.
     */
    fun enabledPhrases(changes: List<PhraseEntry>, builtIn: Boolean = true): List<String> {
        val mine = changes.filter { it.enabled }.map { it.phrase }
        if (!builtIn) return mine
        val off = changes.filter { !it.enabled }.map { VoiceCommands.phraseWords(it.phrase) }.toSet()
        val defaults = DEFAULTS.filter { VoiceCommands.phraseWords(it) !in off }
        return defaults + mine.filter { !isBuiltIn(it) }
    }
}
