package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.NumberWords
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Spoken numbers as digits: dates, phone numbers, amounts in two parts (no models). */
class NumberWordsTest {
    private fun candidates(s: String): List<Pair<String, List<String>>> {
        val tk = TranscriptGuard.tokenize(s)
        return NumberWords.candidates(tk.words, tk.gaps) { it == 0 }.map { c ->
            tk.words.subList(c.start, c.end).joinToString(" ") to c.forms
        }
    }

    @Test
    fun formsDontUseTheLocalesDigits() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(listOf("3:05"), NumberWords.forms("three oh five"))
            assertEquals(listOf("7:05 PM"), NumberWords.forms("seven oh five pm"))
            assertEquals(listOf("$9.05"), NumberWords.forms("nine dollars and five cents"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun datesWithTheMonth() {
        assertEquals(listOf("march twelfth" to listOf("March 12th")), candidates("My birthday is on march twelfth."))
        assertEquals(listOf("June third" to listOf("June 3rd")), candidates("My flight is on June third."))
        assertEquals(listOf("twenty first of July" to listOf("21st of July")), candidates("It's on the twenty first of July."))
        // "may" and "march" are also ordinary words
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("You may first want to check."))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("They march third in line."))
        assertEquals(listOf("March 12th"), NumberWords.forms("march twelfth"))
        assertNull(NumberWords.dateForm(listOf("june", "thirty", "second")))
    }

    @Test
    fun yearsInEverySpokenForm() {
        assertEquals(2021, NumberWords.yearValue(listOf("twenty", "twenty", "one")))
        assertEquals(1907, NumberWords.yearValue(listOf("nineteen", "oh", "seven")))
        assertEquals(2005, NumberWords.yearValue(listOf("two", "thousand", "five")))
        assertEquals(2012, NumberWords.yearValue(listOf("two", "thousand", "and", "twelve")))
        assertNull(NumberWords.yearValue(listOf("twenty", "four")))
        // hyphenated and capitalized, as the voice models write them
        assertEquals(listOf("twenty twenty-one" to listOf("2021")), candidates("I think twenty twenty-one was strange."))
        assertEquals(listOf("Twenty twenty two" to listOf("2022")), candidates("Twenty twenty two was strange."))
    }

    @Test
    fun yearRanges() {
        // "to" and "through" become a hyphen, a short second year stays short
        assertEquals(listOf("twenty twenty one to twenty twenty four" to listOf("2021-2024")),
            candidates("We lived there from twenty twenty one to twenty twenty four."))
        assertEquals(listOf("twenty twenty-one through twenty twenty-four" to listOf("2021-2024")),
            candidates("It covers twenty twenty-one through twenty twenty-four."))
        assertEquals("2021-24", NumberWords.yearRangeForm("twenty twenty one to twenty four".split(" ")))
        assertEquals("1998-04", NumberWords.yearRangeForm("nineteen ninety eight to oh four".split(" ")))
        assertEquals("2008-2019", NumberWords.yearRangeForm("two thousand and eight to twenty nineteen".split(" ")))
        // "and" and "until" keep their words, and need two full years
        assertEquals(listOf("nineteen ninety nine and two thousand five" to listOf("1999 and 2005")),
            candidates("He worked there between nineteen ninety nine and two thousand five."))
        assertEquals("1985 until 1992", NumberWords.yearRangeForm("nineteen eighty five until nineteen ninety two".split(" ")))
        assertNull(NumberWords.yearRangeForm("twenty twenty one and twenty four".split(" ")))
        // both sides must be years
        assertNull(NumberWords.yearRangeForm("twenty one to twenty four".split(" ")))
        assertNull(NumberWords.yearRangeForm("five to ten".split(" ")))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("The shift runs from nine to five."))
        // years the voice model wrote as digits
        assertEquals(listOf("2021-2024"), NumberWords.digitYearRanges("We lived there from 2021 to 2024.").map { it.third })
        assertEquals("2019-2022", NumberWords.digitYearRangeForm("2019 through 2022"))
        assertTrue(NumberWords.digitYearRanges("From 2021 to now, and 5 to 10 people.").isEmpty())
        assertTrue(NumberWords.digitYearRanges("We moved to 2024 Elm Street.").isEmpty())
        assertTrue(NumberWords.digitYearRanges("They scored 1500 to 2000 points, 2024 to 2021 backwards.").isEmpty())
        assertTrue(NumberWords.digitYearRanges("It costs $2020 to $2024.").isEmpty())
    }

    @Test
    fun datesSaidAsNumbers() {
        assertEquals(listOf("eleven eleven twenty twenty six" to listOf("11/11/2026")),
            candidates("The invoice is due eleven eleven twenty twenty six."))
        assertEquals(listOf("eleven slash eleven slash twenty twenty six" to listOf("11/11/2026")),
            candidates("Write eleven slash eleven slash twenty twenty six here."))
        assertEquals(listOf("11 slash 11 slash 2026" to listOf("11/11/2026")), candidates("It says 11 slash 11 slash 2026."))
        assertEquals(listOf("twelve thirty one twenty twenty five" to listOf("12/31/2025")),
            candidates("The check is dated twelve thirty one twenty twenty five."))
        // a short year said bare needs a word like "on" or "due" before it
        assertEquals(listOf("eleven eleven twenty six" to listOf("11/11/26")), candidates("It's on eleven eleven twenty six."))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("I have eleven eleven twenty six."))
        assertEquals("9/30/26", NumberWords.numericDateForm("nine slash thirty slash twenty six".split(" "), shortYearBare = false))
        // a month above twelve, a time, a phone number or a count is no date
        assertNull(NumberWords.numericDateForm("thirteen eleven twenty twenty six".split(" "), shortYearBare = true))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("At ten fifteen twenty people showed up."))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("Dial nine one one now."))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("The kids counted down five four three two one."))
        assertEquals(listOf("11/11/2026"), NumberWords.forms("eleven eleven twenty twenty six").take(1))
    }

    @Test
    fun monthDatesWithAYear() {
        assertEquals(listOf("november eleventh twenty twenty six" to listOf("November 11th, 2026")),
            candidates("The wedding is on november eleventh twenty twenty six."))
        assertEquals(listOf("november eleven twenty twenty six" to listOf("November 11, 2026")),
            candidates("It moved to november eleven twenty twenty six."))
        assertEquals(listOf("eleventh of november twenty twenty six" to listOf("11th of November 2026")),
            candidates("It was the eleventh of november twenty twenty six."))
        assertEquals(listOf("november twenty twenty six" to listOf("November 2026")), candidates("It opens in november twenty twenty six."))
        assertEquals(listOf("March third twenty twenty two" to listOf("March 3rd, 2022")), candidates("She started on March third, twenty twenty two."))
        assertEquals("December 21st, 2020", NumberWords.dateForm("december twenty first twenty twenty".split(" ")))
        // a day said as a cardinal needs the year ("november eleven" alone is left as said)
        assertNull(NumberWords.dateForm(listOf("november", "eleven")))
    }

    @Test
    fun andAHalfBeforeAUnit() {
        assertEquals(listOf("four and a half" to listOf("4.5")), candidates("It weighs four and a half pounds."))
        assertEquals(emptyList<Pair<String, List<String>>>(), candidates("She is two and a half."))
    }

    @Test
    fun phoneNumbersSaidDigitByDigit() {
        assertEquals("555-1234", NumberWords.phoneForm("five five five one two three four"))
        assertEquals("808-555-1212", NumberWords.phoneForm("eight oh eight five five five one two one two"))
        assertNull(NumberWords.phoneForm("five five five five one two three four"))
        assertEquals(listOf("555-1234"), NumberWords.digitFormats("Call me at five five five, one two three four.").map { it.third })
        // eight digits are not a phone number, and none of them is taken as one
        assertTrue(NumberWords.digitFormats("Call me at five five five five one two three four.").isEmpty())
    }

    @Test
    fun clockTimesNotRoundNumbers() {
        assertEquals(listOf("3:15"), NumberWords.digitFormats("Meet me at 315.").map { it.third })
        assertTrue(NumberWords.digitFormats("We're at 100.").isEmpty())
    }

    @Test
    fun dollarsAndCentsInTwoParts() {
        assertEquals("$1.50", NumberWords.centsForm("a dollar fifty cents"))
        assertEquals("$45.99", NumberWords.centsForm("forty-five dollars, ninety-nine cents"))
        assertNull(NumberWords.centsForm("five dollars. Fifty cents"))
    }
}
