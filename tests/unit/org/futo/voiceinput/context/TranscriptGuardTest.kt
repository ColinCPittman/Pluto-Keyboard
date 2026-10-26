package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.DoubleMetaphone
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.futo.voiceinput.shared.context.SpokenEdits
import org.junit.Test

/** Expected values come from the Python originals (metaphone 0.6, tools/context-bench/guard.py). */
class TranscriptGuardTest {
    private val metaphone = listOf(
        "bulls" to ("PLS" to ""), "bowls" to ("PLS" to ""), "hour" to ("HR" to ""), "our" to ("AR" to ""),
        "Smith" to ("SM0" to "XMT"), "Schmidt" to ("XMT" to "SMT"), "jose" to ("JS" to "HS"),
        "San Jacinto" to ("SNHSNT" to ""), "caesar" to ("SSR" to ""), "michael" to ("MKL" to "MXL"),
        "chianti" to ("KNT" to ""), "chorus" to ("KRS" to ""), "orchestra" to ("ARKSTR" to ""),
        "focaccia" to ("FKX" to ""), "bacchus" to ("PKS" to ""), "accident" to ("AKSTNT" to ""),
        "succeed" to ("SKST" to ""), "edge" to ("AJ" to ""), "edgar" to ("ATKR" to ""), "laugh" to ("LF" to ""),
        "hugh" to ("HH" to ""), "cough" to ("KF" to ""), "ghislane" to ("JLN" to ""), "cagney" to ("KKN" to ""),
        "tagliaro" to ("TKLR" to "TLR"), "gerald" to ("KRLT" to "JRLT"), "danger" to ("TNJR" to "TNKR"),
        "biaggi" to ("PJ" to "PK"), "rogier" to ("RJ" to "RKR"), "island" to ("ALNT" to ""),
        "sugar" to ("XKR" to "SKR"), "shoe" to ("X" to ""), "school" to ("SKL" to ""),
        "schermerhorn" to ("XRMRRN" to "SKRMRRN"), "resnais" to ("RSN" to "RSNS"), "nation" to ("NXN" to ""),
        "thomas" to ("TMS" to ""), "thumb" to ("0MP" to "TMP"), "wasserman" to ("ASRMN" to "FSRMN"),
        "arnow" to ("ARN" to "ARNF"), "filipowicz" to ("FLPTS" to "FLPFX"), "breaux" to ("PR" to ""),
        "zhao" to ("J" to ""), "pizza" to ("PS" to "PTS"), "xavier" to ("SF" to "SFR"), "knight" to ("NT" to ""),
        "wright" to ("RT" to ""), "psychology" to ("SXLJ" to "SKLK"), "gnome" to ("NM" to ""),
        "weather" to ("A0R" to "FTR"), "whether" to ("A0R" to "ATR"), "flower" to ("FLR" to ""),
        "flour" to ("FLR" to ""), "meat" to ("MT" to ""), "meet" to ("MT" to ""), "jim" to ("JM" to "AM"),
        "gym" to ("KM" to "JM"), "dumb" to ("TMP" to ""), "campbell" to ("KMPL" to ""), "raspberry" to ("RSPR" to ""),
        "yankelovich" to ("ANKLFX" to "ANKLFK"), "bajador" to ("PJTR" to "PHTR"), "hochmeier" to ("HKMR" to ""),
        // characters without a rule repeat the previous step (Python quirk, kept on purpose)
        "well-known" to ("ALLNN" to "FLLNN"), "bill's" to ("PLL" to ""),
        "they're" to ("0R" to "TR"), "naïve" to ("NF" to ""), "façade" to ("FST" to ""),
        "a" to ("A" to ""), "I" to ("A" to ""), "hmm" to ("M" to ""), "cabrillo" to ("KPRL" to "KPR"),
        "gallegos" to ("KLKS" to "KKS"), "mac caffrey" to ("MKFR" to ""), "czerny" to ("SRN" to "XRN"),
        "tichner" to ("TXNR" to "TKNR"), "matthew" to ("M0" to "MTF"), "van gogh" to ("FNKK" to ""),
        "gnocchi" to ("NX" to ""),
    )

    @Test
    fun doubleMetaphoneMatchesPython() {
        for ((word, codes) in metaphone) assertEquals(word, codes, DoubleMetaphone.encode(word))
    }

    @Test
    fun soundsAlike() {
        fun check(a: String, b: String, ok: Boolean, why: String) = assertEquals("$a/$b", ok to why, TranscriptGuard.soundsAlike(a, b))
        check("bulls", "bowls", true, "metaphone PLS")
        check("whether", "weather", true, "metaphone A0R")
        check("break", "brain", false, "codes PRK vs PRN")
        check("is", "was", false, "function-word swap")
        check("won't", "want", false, "negation")
        check("bagel", "bagels", false, "inflection")
        check("a", "I", false, "function-word swap")
        check("hour", "our", true, "metaphone AR")
        check("two", "too", true, "metaphone T")
        check("6", "six", false, "digits")
    }

    private data class Case(val input: String, val proposal: String, val expected: String, val decisions: List<String>)

    private val cases = listOf(
        Case("I think there would be a microwave at the clubhouse, right, and bulls and spoons and things.",
            "I think there would be a microwave at the clubhouse, right? And bowls and spoons and things?",
            "I think there would be a microwave at the clubhouse, right? And bowls and spoons and things?",
            listOf("punct ,>? +", "subst bulls>bowls +", "punct .>? +")),
        Case("We need bulls for the soup.", "We need bowls for the stew.", "We need bowls for the soup.",
            listOf("subst bulls>bowls +", "subst soup>stew -")),
        Case("I'll be there in an our.", "I'll be there in an hour.", "I'll be there in an hour.", listOf("subst our>hour +")),
        Case("I am going to the jim.", "I'm going to the gym.", "I am going to the gym.",
            listOf("subst I am>I'm -", "subst jim>gym +")),
        Case("I won't go.", "I want go.", "I won't go.", listOf("subst won't>want -")),
        Case("Call me at 6:45, okay.", "Call me at 6:45, okay?", "Call me at 6:45, okay?", listOf("punct .>? +")),
        Case("We can meet every day.", "We can meet everyday.", "We can meet everyday.", listOf("subst every day>everyday +")),
        Case("Bring the PDF tomorrow.", "Please bring the pdf to me tomorrow.", "Bring the PDF tomorrow.",
            listOf("insert >Please -", "insert >to me -", "case PDF>pdf -")),
        Case("Did you feed the cat.", "Did you feed the cat?", "Did you feed the cat?", listOf("punct .>? +")),
        Case("Did you feed the cat?", "Did you feed the cat.", "Did you feed the cat?", listOf("punct ?>. -")),
        Case("so we went home", "So we went home.", "So we went home.", listOf("punct _>. +")),
        Case("It is what it is.", "Totally different words here now and more and more words.", "It is what it is.",
            listOf("subst It is what it is>Totally different words here now and more and more words -", "bail > -")),
    )

    @Test
    fun guardMatchesPython() {
        for (c in cases) {
            val r = TranscriptGuard.guard(c.input, c.proposal)
            assertEquals(c.input, c.expected, r.text)
            assertEquals(c.input, c.decisions, r.decisions.map { "${it.op} ${it.src}>${it.dst} ${if (it.accepted) "+" else "-"}" })
        }
    }

    @Test
    fun tokenizeKeepsGaps() {
        val t = TranscriptGuard.tokenize("Hi, it's 6:45 -- well-known I-95.")
        assertEquals(listOf("Hi", "it's", "6", "45", "well-known", "I-95"), t.words)
        assertEquals(listOf("", ", ", " ", ":", " -- ", " ", "."), t.gaps)
    }

    @Test
    fun breakJoinOnlyDropsThePeriodAndTheCapital() {
        val t = "In a manner. That is easier."
        assertTrue(TranscriptGuard.checkBreakJoin(t, 11, 17, " that").accepted)
        assertTrue(TranscriptGuard.checkBreakJoin("Ask Marcus. Who left.", 10, 15, " Who").accepted)
        assertFalse(TranscriptGuard.checkBreakJoin("Ask now. I left.", 7, 10, " i").accepted)
        // the period may become a comma ("Marcus, who")
        assertTrue(TranscriptGuard.checkBreakJoin("Ask Marcus. Who left.", 10, 15, ", who").accepted)
        assertFalse(TranscriptGuard.checkBreakJoin("Ask Marcus. Who left.", 10, 15, "; who").accepted)
        assertFalse(TranscriptGuard.checkBreakJoin("Ask Marcus. Who left.", 10, 15, ", whom").accepted)
        assertFalse(TranscriptGuard.checkBreakJoin(t, 11, 17, " this").accepted)
        assertFalse(TranscriptGuard.checkBreakJoin("Wait... That is it.", 4, 12, " that").accepted)
    }

    @Test
    fun addedBreaksNeedAnUnbrokenSegment() {
        val text = "We left. Then it rained. Later we ate."
        val whole = SpokenEdits.addedBreaks(text, listOf("we left then it rained later we ate"))
        assertTrue(2 in whole && 5 in whole)
        val paused = SpokenEdits.addedBreaks(text, listOf("we left", "then it rained later we ate"))
        assertTrue(2 !in paused && 5 in paused)
        // the ASR's own marks
        assertTrue(2 !in SpokenEdits.addedBreaks(text, listOf("We left.", "then it rained later we ate")))
        val marked = SpokenEdits.addedBreaks(text, listOf("We left. Then it rained. Later we ate."))
        assertTrue(2 !in marked && 5 !in marked)
    }
}
