package org.futo.inputmethod.latin.heatmap

/**
 * Recently committed words whose tap samples are held back, so that a later correction of one of
 * them relabels its taps with the word the user actually meant, instead of the model learning the
 * misinterpretation. Two kinds of correction are tracked:
 *  - going back to a word and changing it ("dent" -> "sent", see [relabel]).
 *  - deleting the last word(s) with backspace and typing the replacement(s) in their place
 *    (see [onDelete], [onWordTyped]). The deleted words are set aside and paired, in order, with
 *    the words typed next.
 *
 * Words become final (and are returned for learning) once they fall out of the buffer or it is
 * drained. Deleted words that are never replaced are dropped without learning. Whether a pair is a
 * plausible typo (same length, few substitutions...) is decided later by [TapAligner].
 * [C] is opaque per-word context (keyboard and profile) for the caller.
 */
class RecentWordBuffer<C>(private val capacity: Int) {
    class Entry<C>(
        val context: C,
        /** Letters produced by the original taps. */
        val typedWord: String,
        /** What is currently in the text field for this word, and it is updated on corrections. */
        var committedWord: String,
        val taps: List<RecordedTap>
    )

    private val entries = ArrayDeque<Entry<C>>()

    /** Words deleted with backspace, in text order, waiting for their replacements. */
    private val deleted = ArrayDeque<Entry<C>>()
    /** Characters deleted in the current run of backspaces, and how many of them the deleted words cover. */
    private var deletedChars = 0
    private var deletedBudget = 0

    val size: Int get() = entries.size
    val deletedCount: Int get() = deleted.size

    /** Adds a newly committed word and returns the entries pushed out (oldest first), which are now final. */
    fun add(entry: Entry<C>): List<Entry<C>> {
        entries.addLast(entry)
        return evict()
    }

    private fun evict(): List<Entry<C>> {
        if (entries.size <= capacity) return emptyList()
        val evicted = ArrayList<Entry<C>>(entries.size - capacity)
        while (entries.size > capacity) evicted.add(entries.removeFirst())
        return evicted
    }

    /**
     * One backspace. [startsRun] is true for the first backspace after a word was committed (nothing typed
     * since). Every word whose characters (plus one separator) are reached by the run is set aside.
     */
    fun onDelete(startsRun: Boolean) {
        if (startsRun) {
            deletedChars = 0
            deletedBudget = 0
        } else if (deletedBudget == 0) {
            return // backspaces inside a word being typed are not a deletion of committed words
        }
        deletedChars++
        // A word is set aside as soon as the deletion reaches it, and its separator (deleted first) and its letters
        // make up its share of the run. If one backspace deletes a whole word at once, the count runs behind
        // and fewer words are set aside, which only loses learning because unmatched pairs are rejected later.
        while (deletedChars > deletedBudget && entries.isNotEmpty()) {
            val word = entries.removeLast()
            deleted.addFirst(word)
            deletedBudget += word.committedWord.length + 1
        }
    }

    /**
     * A word that was never committed (a swiped word deleted with one backspace) was deleted. It is the newest
     * text, and the backspaces that follow continue the run past it (its separator, then the committed words
     * before it). Not added when other deleted words are still waiting, since the next word then replaces those.
     * Returns whether it was added.
     */
    fun addDeleted(entry: Entry<C>): Boolean {
        if (deleted.isNotEmpty()) return false
        deleted.addLast(entry)
        deletedChars = 0
        deletedBudget = 1
        return true
    }

    /** The user typed a letter, so the current run of backspaces (if any) is over. */
    fun onTypingResumed() {
        deletedChars = 0
        deletedBudget = 0
    }

    /**
     * A newly typed word was committed. If words were deleted before, the oldest deleted word is taken to
     * be the one this word replaces. It is relabeled and returned to the buffer (ahead of the new word).
     * Returns the entries that became final.
     */
    fun onWordTyped(word: String, entry: Entry<C>?): List<Entry<C>> {
        onTypingResumed()
        val replaced = deleted.removeFirstOrNull()
        if (replaced != null) {
            replaced.committedWord = word
            entries.addLast(replaced)
        }
        if (entry != null) entries.addLast(entry)
        return evict()
    }

    /**
     * The user replaced the existing word [from] with [to] (a resumed word was committed). A word that was
     * just partly deleted and fixed in place is found among the deleted words first, and otherwise the most recent
     * buffered word whose text is [from] (exact match preferred, else case-insensitive). Returns it, or null.
     */
    fun relabel(from: String, to: String): Entry<C>? {
        onTypingResumed()
        val fromDeleted = deleted.firstOrNull { it.committedWord.equals(from, ignoreCase = true) }
        if (fromDeleted != null) {
            deleted.remove(fromDeleted)
            fromDeleted.committedWord = to
            entries.addLast(fromDeleted)
            return fromDeleted
        }
        val entry = entries.lastOrNull { it.committedWord == from }
            ?: entries.lastOrNull { it.committedWord.equals(from, ignoreCase = true) }
            ?: return null
        entry.committedWord = to
        return entry
    }

    /**
     * An existing word was resumed for editing. Backspacing into a deleted word resumes it (keep the deleted
     * words), but resuming any other word means the user moved elsewhere (drop them).
     */
    fun onWordResumed(text: String) {
        if (deleted.none { it.committedWord.equals(text, ignoreCase = true) }) forgetDeleted()
    }

    /** The user moved elsewhere, so deleted words that were not replaced are dropped. */
    fun forgetDeleted() {
        deleted.clear()
        onTypingResumed()
    }

    /** Forgets the most recent word without learning from it. */
    fun dropNewest(): Entry<C>? = entries.removeLastOrNull()

    /** Removes and returns all buffered words (oldest first), and they are final. Deleted words are dropped. */
    fun drain(): List<Entry<C>> {
        val all = entries.toList()
        entries.clear()
        forgetDeleted()
        return all
    }

    fun clear() {
        entries.clear()
        forgetDeleted()
    }
}
