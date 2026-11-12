package org.futo.inputmethod.latin.uix

import org.futo.voiceinput.shared.context.TypingCorrector

/**
 * Finding text again after edits, by the characters around it. Typing fixes, words that are left
 * alone and words that were checked are followed this way, so editing earlier text never makes
 * them point at the wrong place.
 */
object TypingFind {
    /** Finding a fix again: the bonus for still being where it was, and the least matching context otherwise. */
    const val POSITION_BONUS = 4
    const val MIN_CONTEXT = 4

    /** Characters of context that must match on each side to tell a word changed in place ([wordBetween]). */
    const val CHANGED_CONTEXT = 6

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''

    /** Whether [word] (one or more words) is at [i] of [text], not as part of a longer word. */
    fun isWordAt(text: String, word: String, i: Int): Boolean {
        val len = word.length
        return len > 0 && i >= 0 && i + len <= text.length && text.regionMatches(i, word, 0, len) &&
            (i == 0 || !text[i - 1].isLetterOrDigit()) && (i + len == text.length || !text[i + len].isLetterOrDigit())
    }

    /** How many characters right around [i] (with [len] characters there) still match [before] and [after]. */
    fun score(text: String, i: Int, len: Int, before: String, after: String): Int {
        var a = 0
        while (a < before.length && i - 1 - a >= 0 && text[i - 1 - a] == before[before.length - 1 - a]) a++
        var b = 0
        while (b < after.length && i + len + b < text.length && text[i + len + b] == after[b]) b++
        return a + b
    }

    /**
     * The best place of [word] in [text] and its score: at [expected] if it's still there (with a
     * bonus), else wherever the characters around it best match [before] / [after]. Null when it
     * isn't there or two places match equally well.
     */
    fun best(text: String, word: String, expected: Int, before: String, after: String): Pair<Int, Int>? {
        if (word.isEmpty()) return null
        var best = -1
        var bestScore = -1
        var tie = false
        if (isWordAt(text, word, expected)) {
            best = expected
            bestScore = score(text, expected, word.length, before, after) + POSITION_BONUS
        }
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) break
            from = i + 1
            if (i == expected || !isWordAt(text, word, i)) continue
            val sc = score(text, i, word.length, before, after)
            if (sc > bestScore) { best = i; bestScore = sc; tie = false } else if (sc == bestScore) tie = true
        }
        return if (best < 0 || tie) null else best to bestScore
    }

    /** Where [word] is in [text] now (see [best]), or null when it can't be told for sure. */
    fun locate(text: String, word: String, expected: Int, before: String, after: String): Int? =
        best(text, word, expected, before, after)?.takeIf { it.second >= MIN_CONTEXT }?.first

    /**
     * Where [region] is in [text] exactly: at [expected] if it's there, else its only other place.
     * A checked sentence is found again this way before its fix goes in.
     */
    fun findRegion(text: String, region: String, expected: Int): Int? {
        if (region.isEmpty()) return null
        if (expected >= 0 && text.regionMatches(expected, region, 0, region.length)) return expected
        val i = text.indexOf(region)
        if (i < 0 || text.indexOf(region, i + 1) >= 0) return null
        return i
    }

    /**
     * The word (letters and apostrophes) that is now between [before] and [after], nearest to
     * [expected], when the last [CHANGED_CONTEXT] characters of [before] and the first of [after]
     * are still there around one word (an empty [before] means the start of [text]). Null when
     * that spot can't be found for sure.
     */
    fun wordBetween(text: String, expected: Int, before: String, after: String): IntRange? {
        val pre = before.takeLast(CHANGED_CONTEXT)
        val post = after.take(CHANGED_CONTEXT)
        var best: IntRange? = null
        var bestDist = Int.MAX_VALUE
        var tie = false
        var i = 0
        while (i <= text.length) {
            val ok = if (pre.isEmpty()) i == 0 && before.isEmpty() else i >= pre.length && text.regionMatches(i - pre.length, pre, 0, pre.length)
            if (ok && i < text.length && text[i].isLetter() && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i
                while (j < text.length && isWordChar(text[j])) j++
                while (j > i && text[j - 1] == '\'') j--
                // (nothing was after it then, so whatever is after it now is fine)
                val endOk = post.isEmpty() || text.regionMatches(j, post, 0, post.length)
                if (endOk) {
                    val d = kotlin.math.abs(i - expected)
                    if (d < bestDist) { best = i until j; bestDist = d; tie = false } else if (d == bestDist) tie = true
                }
            }
            if (pre.isEmpty()) break
            i++
        }
        return if (tie) null else best
    }
}

/**
 * A word (or words) in the text found again by the characters around it, like a word you picked
 * from the suggestions or a fix you undid, which are left alone, or a checked word whose later
 * change by hand counts as a missed fix.
 */
data class TypingMark(val start: Int, val word: String, val before: String, val after: String) {
    val end: Int get() = start + word.length

    /** This mark where it is now in [text] (whose first character is at [base]), with its surroundings refreshed, or null. */
    fun located(text: String, base: Int, context: Int): TypingMark? {
        val i = TypingFind.locate(text, word, start - base, before, after) ?: return null
        return copy(
            start = base + i,
            before = text.substring(maxOf(0, i - context), i),
            after = text.substring(i + word.length, minOf(text.length, i + word.length + context)),
        )
    }
}

/**
 * A fix that is no longer in the text because the original is back where it was (you changed it
 * back by hand), with where the original starts in [text] (whose first character is at [base]).
 * Null when the fix is still there, or neither is found for sure.
 */
fun undoneByHand(fix: TypingFix, text: String, base: Int): Int? {
    val expected = fix.start - base
    val orig = TypingFind.best(text, fix.original, expected, fix.before, fix.after) ?: return null
    if (orig.second < TypingFind.MIN_CONTEXT) return null
    // the fixed text may still be there too ("to" inside "to to"), so whichever fits its surroundings better wins
    val now = TypingFind.best(text, fix.replacement, expected, fix.before, fix.after)
    if (now != null && now.second >= orig.second) return null
    return orig.first
}

/**
 * A sentence of the text near the cursor to check, [from]..[to] of the window's text.
 * [early]: the unfinished sentence you're typing at the end of, checked early only when some word
 * of it may be a slip. [editing]: the cursor is inside it with text after the cursor, so it waits
 * until you have stopped for a moment. [distance]: characters between it and the cursor.
 */
data class TypingTarget(
    val from: Int,
    val to: Int,
    val finished: Boolean,
    val early: Boolean,
    val editing: Boolean,
    val distance: Int,
)

/** Which sentences of the text around the cursor are checked, in which order, and with which words left alone. */
object TypingPlan {
    private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    private val END_MARK = Regex("[.?!][\"')]*$")

    /** Characters before a sentence that count as its context in [key]. */
    const val KEY_LEFT_CHARS = 80

    /** Apostrophe-like marks that can be part of a word ("that’s"). */
    private const val APOSTROPHES = "'’ʼ‘`´′"

    private fun joins(c: Char) = c.isLetterOrDigit() || c in APOSTROPHES

    /** Marks that make the run of characters they're in (up to the spaces around it) an address, a name or code, not words. */
    private const val CODE_MARKS = "@#/\\_=&~|<>^"

    /**
     * Whether the word [s]..[e] is in a run of characters without spaces that isn't prose: a link
     * or a path ("site.com/teh-best"), an email address, an @name, a #tag, a file name ("notes.txt")
     * or code. It has one of the [CODE_MARKS], or a "." or ":" right between two letters or digits.
     */
    private fun inCode(text: String, s: Int, e: Int): Boolean {
        var a = s
        while (a > 0 && !text[a - 1].isWhitespace()) a--
        var b = e
        while (b < text.length && !text[b].isWhitespace()) b++
        for (i in a until b) {
            val c = text[i]
            if (c in CODE_MARKS) return true
            if ((c == '.' || c == ':') && i > a && i + 1 < b && text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()) return true
        }
        return false
    }

    /**
     * Starts of the words the engine sees (plain A-Z letters) that are really part of something
     * longer: a word with a letter it doesn't know ("café" would be "caf", "jalapeño" "jalape" and
     * "o"), a digit, or an apostrophe it doesn't take ("that’s" would be "that" and "s"), or a
     * piece of a link, an address, an @name or a #tag ([inCode]). They're never changed, and never
     * taken for a missed typo.
     */
    fun foreignStarts(text: String): Set<Int> = WORD.findAll(text).filter { m ->
        val s = m.range.first
        val e = m.range.last + 1
        (s > 0 && joins(text[s - 1])) || (e < text.length && joins(text[e])) || inCode(text, s, e)
    }.map { it.range.first }.toSet()

    /** [s]..[e] (a word the engine sees) widened over the letters, digits and apostrophes around it, which is the word as you see it. */
    private fun wholeWord(text: String, s: Int, e: Int): IntRange {
        var a = s
        while (a > 0 && joins(text[a - 1])) a--
        var b = e
        while (b < text.length && joins(text[b])) b++
        return a until b
    }

    /** Whether the line that [from] (a sentence start) is on is quoted mail ("> ..."). */
    private fun quoted(text: String, from: Int): Boolean {
        var i = from
        while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
        if (i < text.length && text[i] == '\n') i++
        val lineStart = text.lastIndexOf('\n', i - 1) + 1
        var j = lineStart
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return j < text.length && text[j] == '>'
    }

    /** A number for the words of [from]..[to] of [text] (spaces around them don't count), for [baseline]s. */
    fun sentenceHash(text: String, from: Int, to: Int): Long {
        var a = from
        var b = to
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        var h = -0x340d631b7bdddcdbL
        for (i in a until b) {
            h = h xor text[i].code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * A number for the sentence of [text] that [start]..[end] is in, as it reads with that range
     * replaced by [replacement]. Two fixes that leave the sentence the same get the same number,
     * so an undone fix isn't made again in another form.
     */
    fun resultHash(text: String, start: Int, end: Int, replacement: String): Long {
        val r = TypingCorrector.sentences(text).firstOrNull { start >= it.first && start <= it.last } ?: (0 until text.length)
        val to = maxOf(r.last + 1, end)
        val changed = text.substring(r.first, start) + replacement + text.substring(end, to)
        return sentenceHash(changed, 0, changed.length)
    }

    /**
     * The sentences of [text] as they were when the field opened (a cut-off one at either end left
     * out). Text that was already there isn't yours to fix (a quoted mail, a signature, a template,
     * an old note), so it's left alone ([targets]) until you change it.
     */
    fun baseline(text: String, cutStart: Boolean, cutEnd: Boolean): Set<Long> {
        val out = HashSet<Long>()
        for ((i, r) in TypingCorrector.sentences(text).withIndex()) {
            if (i == 0 && cutStart) continue
            if (r.last + 1 == text.length && cutEnd) continue
            out.add(sentenceHash(text, r.first, r.last + 1))
        }
        return out
    }

    /**
     * Whether a fix ending at [fixEnd] may go in now with the cursor at [cursor]. Right after
     * autocorrect changed a word, Backspace would put back what you typed by its old length
     * ([revertArmed]), so a change before the cursor waits for the next key, which ends that.
     */
    fun mayApplyNow(fixEnd: Int, cursor: Int, revertArmed: Boolean): Boolean = !(revertArmed && fixEnd <= cursor)

    /**
     * The sentences of [text] to check, nearest the cursor first (the one it's in, then the others,
     * those before it first on a tie). [cursor] is where the cursor is in [text], and
     * [composeStart]..[composeEnd] the word being typed (-1 when none). A sentence cut off by the
     * start ([cutStart]) or the end ([cutEnd]) of the text read is left out, and so is one too close
     * to a cut start to have its whole [key] context (the read start moves with every key, which
     * would make its key change every time). A sentence still as it was when the field opened
     * ([baseline], see [sentenceHash]) and a quoted mail line ("> ...") are left out too.
     */
    fun targets(
        text: String, cursor: Int, composeStart: Int, composeEnd: Int, cutStart: Boolean, cutEnd: Boolean,
        baseline: Set<Long> = emptySet(),
    ): List<TypingTarget> {
        val sentences = TypingCorrector.sentences(text)
        val out = ArrayList<TypingTarget>()
        for ((i, r) in sentences.withIndex()) {
            if (i == 0 && cutStart) continue
            if (cutStart && r.first < KEY_LEFT_CHARS) continue
            val end = r.last + 1
            if (end == text.length && cutEnd) continue
            if (quoted(text, r.first)) continue
            val region = text.substring(r.first, end)
            val core = region.trimEnd(' ', '\t')
            val marked = core.endsWith('\n') || END_MARK.containsMatchIn(core)
            val inIt = (cursor > r.first || (cursor == 0 && r.first == 0)) && cursor <= end
            if (!inIt) {
                val distance = if (end <= cursor) cursor - end else r.first - cursor
                out.add(TypingTarget(r.first, end, finished = true, early = false, editing = false, distance = distance))
                continue
            }
            val editing = text.substring(cursor, end).isNotBlank()
            when {
                editing -> out.add(TypingTarget(r.first, end, finished = true, early = false, editing = true, distance = 0))
                marked -> out.add(TypingTarget(r.first, end, finished = true, early = false, editing = false, distance = 0))
                else -> {
                    // typing at its end: what's before the word being typed, once a word has ended
                    val stop = if (composeStart in r.first..cursor) composeStart else cursor
                    val before = text.substring(r.first, stop)
                    if (before.isEmpty() || !(before.last().isWhitespace() || before.last() in ".?!,;:")) continue
                    val to = r.first + before.trimEnd().length
                    if (text.substring(r.first, to).none { it.isLetter() }) continue
                    out.add(TypingTarget(r.first, to, finished = false, early = true, editing = false, distance = 0))
                }
            }
        }
        return out.filter { baseline.isEmpty() || sentenceHash(text, it.from, it.to) !in baseline }
            .sortedWith(compareBy<TypingTarget>({ it.distance }, { if (it.from >= cursor) 1 else 0 }))
    }

    /**
     * Starts of the words of [text] that must not change: the word the cursor is in or next to
     * (only spaces between), the word being typed ([composeStart]..[composeEnd]) and the words next
     * to it, every word overlapping one of [kept] (fixes already made, words you picked or undid),
     * and the [foreignStarts]. Nearness is judged on the whole word as you see it ("café", "that’s").
     */
    fun protectedStarts(text: String, cursor: Int, composeStart: Int, composeEnd: Int, kept: List<IntRange>): Set<Int> {
        val out = HashSet<Int>(foreignStarts(text))
        val words = WORD.findAll(text).toList()
        val points = if (composeStart >= 0) listOf(cursor, composeStart, composeEnd) else listOf(cursor)
        fun spaces(a: Int, b: Int): Boolean {
            for (i in a until b) if (text[i] != ' ') return false
            return true
        }
        for (m in words) {
            val whole = wholeWord(text, m.range.first, m.range.last + 1)
            val s = whole.first
            val e = whole.last + 1
            val near = points.any { p -> (p in s..e) || (e <= p && spaces(e, p)) || (s >= p && spaces(p, s)) }
            if (near || kept.any { k -> k.first < e && s <= k.last }) out.add(m.range.first)
        }
        return out
    }

    /**
     * What a check of [from]..[to] of [text] depends on, as a number: the sentence, [KEY_LEFT_CHARS]
     * before it, whether it's [finished], which of its words are left alone ([skip]) and [salt] (the
     * settings). A check that found nothing isn't made again while this stays the same.
     */
    fun key(text: String, from: Int, to: Int, finished: Boolean, skip: Set<Int>, salt: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        fun add(c: Char) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        for (i in maxOf(0, from - KEY_LEFT_CHARS) until to) add(text[i])
        add('\u0000')
        add(if (finished) 'F' else 'E')
        for (s in skip.filter { it in from until to }.sorted()) {
            for (c in (s - from).toString()) add(c)
            add(',')
        }
        add('\u0000')
        for (c in salt) add(c)
        return h
    }
}

/** Checks that found nothing to fix, remembered by [TypingPlan.key] (the oldest go first). */
class CheckedSentences(private val capacity: Int = 256) {
    private val keys = object : LinkedHashMap<Long, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > capacity
    }

    operator fun contains(key: Long): Boolean = keys.containsKey(key).also { if (it) keys[key] }
    fun add(key: Long) { keys[key] = Unit }
    val size: Int get() = keys.size
    fun clear() = keys.clear()
}
