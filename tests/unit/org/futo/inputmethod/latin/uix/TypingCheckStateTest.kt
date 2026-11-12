package org.futo.inputmethod.latin.uix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Smart corrections for typing that judge the text as it is (not as keys come in):
 * finding fixes and words again after edits, fixes changed back by hand, which sentences are
 * checked in which order, and the content-keyed memory of checks that found nothing.
 */
class TypingCheckStateTest {
    private fun fixAt(text: String, original: String, replacement: String, at: Int, kind: String = "pair", id: Long = 1) =
        TypingFix(id, at, original, replacement,
            before = text.substring(maxOf(0, at - 20), at),
            after = text.substring(at + replacement.length, minOf(text.length, at + replacement.length + 20)),
            kind = kind)

    @Test
    fun multiWordFixIsFoundAgainAfterAnEarlierEdit() {
        val fixed = "I want to go home now. See you soon."
        val fix = fixAt(fixed, "to to", "to", fixed.indexOf("to go"), kind = "remove")
        val edited = "Well, I really want to go home now. See you soon."
        assertEquals(edited.indexOf("to go"), TypingFind.locate(edited, fix.replacement, fix.start, fix.before, fix.after))
        // an added word ("would" -> "I would") is found as both words
        val added = "So I would go there tomorrow with you."
        val add = fixAt(added, "would", "I would", added.indexOf("I would"), kind = "add")
        val moved = "Yes. $added"
        assertEquals(moved.indexOf("I would"), TypingFind.locate(moved, add.replacement, add.start, add.before, add.after))
        // gone when the words themselves were changed
        assertNull(TypingFind.locate("So we would go there tomorrow with you.", add.replacement, add.start, add.before, add.after))
    }

    @Test
    fun shiftFixesMovesOnlyLaterFixesForMultiWordChanges() {
        val a = TypingFix(1, 10, "would", "I would", "", "", kind = "add")
        val b = TypingFix(2, 30, "to to", "to", "", "", kind = "remove")
        val c = TypingFix(3, 2, "an", "and", "", "")
        // a later fix adding two characters at 10 moves b only
        val shifted = shiftFixes(listOf(a, b, c), at = 10, delta = 2)
        assertEquals(listOf(10, 32, 2), shifted.map { it.start })
        assertEquals(17, shifted[0].end)
        // removing "to " (3 characters) at 30 moves nothing before it
        assertEquals(listOf(10, 30, 2), shiftFixes(listOf(a, b, c), at = 30, delta = -3).map { it.start })
    }

    @Test
    fun fixChangedBackByHandIsNoticed() {
        // a doubled word put back
        val fixed = "I want to go home now."
        val remove = fixAt(fixed, "to to", "to", fixed.indexOf("to go"), kind = "remove")
        assertNull(undoneByHand(remove, fixed, 0))
        val back = "I want to to go home now."
        assertEquals(back.indexOf("to to"), undoneByHand(remove, back, 0))

        // an added word deleted again
        val added = "So I would go there."
        val add = fixAt(added, "would", "I would", added.indexOf("I would"), kind = "add")
        assertNull(undoneByHand(add, added, 0))
        val gone = "So would go there."
        assertEquals(gone.indexOf("would"), undoneByHand(add, gone, 0))

        // a swap typed back, with the field starting later (base)
        val pair = "Some new salt and pepper shakers."
        val swap = fixAt(pair, "an", "and", pair.indexOf("and"))
        val typedBack = "Some new salt an pepper shakers."
        assertEquals(typedBack.indexOf("an "), undoneByHand(swap.copy(start = swap.start + 100), typedBack, 100))

        // an ending
        val ending = "She has a long list of things."
        val e = fixAt(ending, "lists", "list", ending.indexOf("list"), kind = "ending")
        assertNull(undoneByHand(e, ending, 0))
        assertEquals(ending.indexOf("list"), undoneByHand(e, "She has a long lists of things.", 0))

        // the whole thing deleted is not an undo
        assertNull(undoneByHand(swap, "Something else entirely.", 0))
    }

    @Test
    fun sentenceAtTheCursorComesFirstThenTheNearest() {
        val text = "One two three four. Five six seven eight. Nine ten eleven twelve."
        val cursor = text.indexOf("six") + 1
        val t = TypingPlan.targets(text, cursor, -1, -1, cutStart = false, cutEnd = false)
        assertEquals(3, t.size)
        // the one being edited (text after the cursor in it), judged as a whole
        assertEquals(text.indexOf(" Five"), t[0].from)
        assertTrue(t[0].editing)
        assertTrue(t[0].finished)
        // then the one before it (closest), then the one after
        assertEquals(0, t[1].from)
        assertEquals(text.indexOf(" Nine"), t[2].from)
        assertFalse(t[1].editing || t[2].editing)
    }

    @Test
    fun unfinishedSentenceAtTheEndIsAnEarlyCheckOfWholeWords() {
        val text = "Hello there my friend. How are you doing yo"
        val composeStart = text.length - 2
        val t = TypingPlan.targets(text, text.length, composeStart, text.length, cutStart = false, cutEnd = false)
        val early = t.single { it.early }
        assertFalse(early.finished)
        assertEquals("How are you doing", text.substring(early.from, early.to).trim())
        // the finished one before it is there too
        assertTrue(t.any { it.from == 0 && it.finished })
        // in the middle of a word with nothing composing, there's no early check
        assertTrue(TypingPlan.targets("How are you doi", 15, -1, -1, false, false).none { it.early })
        // a sentence just ended is finished
        val ended = "Salt an pepper please."
        assertTrue(TypingPlan.targets(ended, ended.length, -1, -1, false, false).single().finished)
    }

    @Test
    fun aSentenceWithoutAMarkIsFinishedOnceTheCursorIsElsewhere() {
        val text = "see you at the game\nok sounds good to me"
        val t = TypingPlan.targets(text, 3, -1, -1, cutStart = false, cutEnd = false)
        val last = t.single { it.from > 3 }
        assertTrue(last.finished)
        assertFalse(last.early)
    }

    @Test
    fun sentencesCutOffByTheWindowAreLeftOut() {
        // (a sentence starting within 80 characters of a cut start would be left out too)
        val text = "the middle of one, with a long run of words before its end comes along at long last. A whole sentence here. And the start of anoth"
        val t = TypingPlan.targets(text, text.indexOf("whole"), -1, -1, cutStart = true, cutEnd = true)
        assertEquals(listOf(text.indexOf(" A whole")), t.map { it.from })
    }

    @Test
    fun wordsAtTheCursorAndKeptWordsAreLeftAlone() {
        val text = "I want to go home now"
        val cursor = text.indexOf("to") // "I want |to go"
        val skip = TypingPlan.protectedStarts(text, cursor, -1, -1, emptyList())
        assertEquals(setOf(text.indexOf("want"), text.indexOf("to")), skip)
        // a punctuation mark between keeps the word before it free
        val ended = "Salt an pepper. "
        assertTrue(TypingPlan.protectedStarts(ended, ended.length, -1, -1, emptyList()).isEmpty())
        // a kept range covers every word it overlaps
        val kept = listOf(text.indexOf("go") until text.indexOf("go") + "go home".length)
        val far = TypingPlan.protectedStarts(text, 0, -1, -1, kept)
        assertTrue(text.indexOf("go") in far && text.indexOf("home") in far && text.indexOf("now") !in far)
    }

    @Test
    fun checkKeyFollowsTheSentenceAndItsNearContextOnly() {
        val pad = "x".repeat(200) + ". "
        val text = pad + "Earlier words here. Some new salt an pepper shakers. After it."
        val from = text.indexOf(" Some")
        val to = text.indexOf(" After")
        val k = TypingPlan.key(text, from, to, true, emptySet(), "s")
        // the same sentence elsewhere in the field, same context: the same key
        val moved = "Hi. $text"
        assertEquals(k, TypingPlan.key(moved, from + 4, to + 4, true, emptySet(), "s"))
        // text after it, or far before it, doesn't matter
        assertEquals(k, TypingPlan.key(text.replace("After it.", "Later."), from, to, true, emptySet(), "s"))
        assertEquals(k, TypingPlan.key(text.replaceFirst("xxxx", "yyyy"), from, to, true, emptySet(), "s"))
        // the sentence itself, the context just before it, finished or not, the words left alone and the settings do
        assertNotEquals(k, TypingPlan.key(text.replace("salt an", "salt and"), from, to + 1, true, emptySet(), "s"))
        assertNotEquals(k, TypingPlan.key(text.replace("Earlier", "Former!"), from, to, true, emptySet(), "s"))
        assertNotEquals(k, TypingPlan.key(text, from, to, false, emptySet(), "s"))
        assertNotEquals(k, TypingPlan.key(text, from, to, true, setOf(text.indexOf("an ")), "s"))
        assertEquals(k, TypingPlan.key(text, from, to, true, setOf(5), "s"))
        assertNotEquals(k, TypingPlan.key(text, from, to, true, emptySet(), "t"))
    }

    @Test
    fun checkedSentencesForgetTheOldestBeyondCapacity() {
        val c = CheckedSentences(capacity = 3)
        c.add(1); c.add(2); c.add(3)
        assertTrue(1L in c) // used again, so 2 is now the oldest
        c.add(4)
        assertEquals(3, c.size)
        assertTrue(1L in c && 3L in c && 4L in c)
        assertFalse(2L in c)
    }

    @Test
    fun aRegionIsFoundWhereItWasOrItsOnlyOtherPlace() {
        val region = "Some new salt an pepper."
        val text = "Hi. $region Bye."
        assertEquals(4, TypingFind.findRegion(text, region, 4))
        assertEquals(4, TypingFind.findRegion(text, region, 0))
        assertNull(TypingFind.findRegion("$region $region", region, 3))
        assertNull(TypingFind.findRegion("Some new salt and pepper.", region, 0))
    }

    @Test
    fun aWordChangedInPlaceIsFoundByTheTextAroundIt() {
        val text = "when you pour every morning"
        val at = text.indexOf("pour")
        val before = text.substring(0, at)
        val after = text.substring(at + 4)
        val changed = "Oh and " + text.replace("pour", "pout")
        val r = TypingFind.wordBetween(changed, at, before, after)!!
        assertEquals("pout", changed.substring(r.first, r.last + 1))
        // gone when the words around it changed too
        assertNull(TypingFind.wordBetween("when we pour each morning", at, before, after))
        // a word at the very start, with nothing after it then
        val start = TypingFind.wordBetween("Pout it out", 0, "", "")!!
        assertEquals(0 until 4, start)
    }

    @Test
    fun aKeptWordFollowsEditsAroundIt() {
        val text = "I picked this word myself today."
        val at = text.indexOf("word")
        val mark = TypingMark(at, "word", text.substring(0, at), text.substring(at + 4))
        val edited = "Yes, $text"
        val found = mark.located(edited, 0, 20)!!
        assertEquals(edited.indexOf("word"), found.start)
        assertEquals(edited.substring(maxOf(0, found.start - 20), found.start), found.before)
        assertNull(mark.located("I picked this one myself today.", 0, 20))
    }

    @Test
    fun onlySwapsOfferStop() {
        assertTrue(TypingFix(1, 0, "an", "and", "", "").canStop)
        assertTrue(TypingFix(1, 0, "ststy", "start", "", "", kind = "typo").canStop)
        for (k in listOf("add", "remove", "ending", "order", "join")) assertFalse(TypingFix(1, 0, "a", "b", "", "", kind = k).canStop)
    }

    @Test
    fun keyOfASentenceNearTheReadStartDoesntChangeAsTheReadMoves() {
        // more than 600 characters before the cursor, so the read starts one character later per key
        val full = (1..40).joinToString(" ") { "Sentence number $it is here." } + " "
        val cursor = full.length
        fun keys(cut: Int): Map<String, Long> {
            val text = full.substring(cut)
            return TypingPlan.targets(text, cursor - cut, -1, -1, cutStart = true, cutEnd = false)
                .associate { text.substring(it.from, it.to).trim() to TypingPlan.key(text, it.from, it.to, it.finished, emptySet(), "s") }
        }
        val a = keys(cursor - 600)
        val b = keys(cursor - 599)
        // every sentence checked from both reads has the same key in both
        val both = a.keys intersect b.keys
        assertTrue(both.size >= 15)
        for (s in both) assertEquals(s, a[s], b[s])
        // and one too close to the cut start to have its whole context isn't checked at all
        val text = full.substring(cursor - 600)
        assertTrue(TypingPlan.targets(text, 600, -1, -1, true, false).all { it.from >= TypingPlan.KEY_LEFT_CHARS })
    }

    @Test
    fun textAlreadyInTheFieldIsLeftAloneUntilChanged() {
        val opened = "Thanks for the email form yesterday.\n\nBest regards,\nSam Smith"
        val baseline = TypingPlan.baseline(opened, cutStart = false, cutEnd = false)
        // the field just opened with the cursor at the start: nothing is checked
        assertTrue(TypingPlan.targets(opened, 0, -1, -1, false, false, baseline).isEmpty())
        // you type a sentence of your own above it: only that one
        val typed = "See you at the game tonight. $opened"
        val cursor = "See you at the game tonight. ".length
        val t = TypingPlan.targets(typed, cursor, -1, -1, false, false, baseline)
        assertEquals(listOf("See you at the game tonight."), t.map { typed.substring(it.from, it.to).trim() })
        // you change an old sentence: then it's yours too
        val edited = opened.replace("yesterday", "today")
        assertTrue(TypingPlan.targets(edited, 0, -1, -1, false, false, baseline).any { edited.substring(it.from, it.to).contains("today") })
        // with no baseline (the same field came back) everything is checked as before
        assertTrue(TypingPlan.targets(opened, 0, -1, -1, false, false).isNotEmpty())
    }

    @Test
    fun quotedMailLinesAreNeverChecked() {
        val text = "Sounds good to me.\n> On Monday you wrote that an meeting is set. And more here.\n>> Older quote line here.\nBye for now then."
        val t = TypingPlan.targets(text, 0, -1, -1, false, false).map { text.substring(it.from, it.to).trim() }
        assertTrue(t.none { it.contains("Monday") || it.contains("Older") })
        assertTrue("Bye for now then." in t)
        // a sentence on a quoted line after its first is quoted too
        assertTrue(t.none { it.contains("And more") })
    }

    @Test
    fun wordsTheCorrectorCantSeeWholeAreLeftAlone() {
        fun foreign(text: String) = TypingPlan.foreignStarts(text).map { s -> Regex("[A-Za-z]+(?:'[A-Za-z]+)?").find(text, s)!!.value }.toSet()
        assertEquals(setOf("caf"), foreign("a café au lait"))
        assertEquals(setOf("jalape", "o"), foreign("one jalapeño please"))
        assertEquals(setOf("that", "s"), foreign("that’s fine"))
        // an emoji isn't a letter, so the word next to it is a word
        assertEquals(emptySet<String>(), foreign("hi😀 there"))
        // a word run into a digit is left alone
        assertEquals(setOf("dollars"), foreign("I paid 20dollars today"))
        // they're in the skip set wherever the cursor is
        val text = "a nice café au lait"
        val skip = TypingPlan.protectedStarts(text, 0, -1, -1, emptyList())
        assertTrue(text.indexOf("caf") in skip)
        // and the cursor right after "café" makes it the word at the cursor, and the word before it next to it
        val cur = text.indexOf("café") + 4
        val atCursor = TypingPlan.protectedStarts(text, cur, -1, -1, emptyList())
        assertTrue(text.indexOf("caf") in atCursor && text.indexOf("au") in atCursor)
        // but not a word further away
        assertFalse(text.indexOf("nice") in atCursor)
    }

    @Test
    fun linksAddressesNamesAndTagsAreLeftAlone() {
        fun foreign(text: String) = TypingPlan.foreignStarts(text).map { s -> Regex("[A-Za-z]+(?:'[A-Za-z]+)?").find(text, s)!!.value }.toSet()
        // every piece of a link, and none of the words around it
        assertEquals(setOf("site", "com", "teh", "best", "recipies"), foreign("please see site.com/teh-best-recipies today"))
        assertEquals(setOf("https", "www", "exmaple", "org"), foreign("go to https://www.exmaple.org now"))
        assertEquals(setOf("jon", "smiht", "gmail", "com"), foreign("mail jon.smiht@gmail.com about it"))
        assertEquals(setOf("jonsmiht"), foreign("ask @jonsmiht about it"))
        assertEquals(setOf("tbt"), foreign("so #tbt it is"))
        assertEquals(setOf("notes", "txt"), foreign("open notes.txt first"))
        assertEquals(setOf("my", "varible"), foreign("rename my_varible here"))
        // a sentence end, a comma, a hyphen, brackets and quotes are prose
        assertEquals(emptySet<String>(), foreign("That is it. Then (maybe) a well-known \"word\", ok... so: fine!"))
        // a time or an amount doesn't make its neighbours code
        assertEquals(emptySet<String>(), foreign("at 8:30 for \$4.50 each"))
    }
}
