package org.futo.voiceinput.shared.context

/**
 * Spoken numbers -> digits for the "Numbers, times and amounts" option. Pure rules, no LM.
 * [candidates] finds spans of number words in a transcript and proposes written forms
 * ("twenty five dollars" -> "$25", "three thirty pm" -> "3:30 PM", "fifty percent" -> "50%",
 * "nineteen ninety five" -> "1995", "twenty first" -> "21st"), and dates: year ranges ("twenty twenty
 * one to twenty twenty four" -> "2021-2024"), dates said as numbers ("eleven eleven twenty twenty six"
 * -> "11/11/2026") and month dates with a year ("November 11th, 2026"). The context stage's LM then
 * judges whether a converted form fits. [forms] is the guard's check, and an edit is only allowed
 * if its replacement is one of the forms these rules produce for exactly the replaced words.
 *
 * Conservative by construction, so single number words below ten without a unit ("one of
 * them", "two or three") are never converted, a run of number words that is not one
 * grammatical number ("ten four", "twenty four seven") is left alone, and a run followed by
 * "eighties", "hundreds", ... ("nineteen eighties") or capitalized mid-sentence ("Ocean's
 * Eleven") is skipped.
 */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50,
        "sixtieth" to 60, "seventieth" to 70, "eightieth" to 80, "ninetieth" to 90,
    )

    /** Words after a run that mean it is not a plain number ("the nineteen eighties"). */
    private val BLOCKING_NEXT = setOf(
        "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties",
        "hundreds", "thousands", "millions", "billions", "teens", "ish", "something", "odd",
    )

    private fun isNumberWord(w: String) =
        w in UNITS || w in TEENS || w in TENS || w == "hundred" || w in SCALES || w in ORDINALS || w == "oh"

    /** [word] is a spoken number ("nine", "twenty-five", "second"), not counting "oh". */
    fun isSpokenNumber(word: String): Boolean {
        val p = parts(word)
        return p.isNotEmpty() && p.all { isNumberWord(it) && it != "oh" }
    }

    private fun parts(word: String): List<String> = TranscriptGuard.normWord(word).split('-').filter { it.isNotEmpty() }

    /** A candidate conversion of words [start, end) (word indices of the tokenized transcript). */
    class Candidate(val start: Int, val end: Int, val forms: List<String>)

    // ------------------------------------------------------------------------------------
    // Parsing

    private enum class T { START, UNIT, TEEN, TENS, HUNDRED, SCALE, AND }

    /** Value of a grammatical cardinal ("two hundred and fifty", "twenty five", "three thousand"), or null. */
    fun cardinal(ws: List<String>): Long? {
        if (ws.isEmpty() || ws.first() == "and" || ws.last() == "and") return null
        var total = 0L
        var cur = 0L
        var last = T.START
        var lastScale = Long.MAX_VALUE
        for (w in ws) {
            when {
                w in UNITS -> {
                    if (last !in setOf(T.START, T.TENS, T.HUNDRED, T.SCALE, T.AND)) return null
                    if (w == "zero" && (last != T.START || ws.size > 1)) return null
                    cur += UNITS.getValue(w)
                    last = T.UNIT
                }
                w in TEENS -> {
                    if (last !in setOf(T.START, T.HUNDRED, T.SCALE, T.AND)) return null
                    cur += TEENS.getValue(w)
                    last = T.TEEN
                }
                w in TENS -> {
                    if (last !in setOf(T.START, T.HUNDRED, T.SCALE, T.AND)) return null
                    cur += TENS.getValue(w)
                    last = T.TENS
                }
                w == "hundred" -> {
                    if (last !in setOf(T.UNIT, T.TEEN, T.TENS) || cur !in 1..99) return null
                    cur *= 100
                    last = T.HUNDRED
                }
                w in SCALES -> {
                    val s = SCALES.getValue(w)
                    if (last == T.START || last == T.AND || cur == 0L || s >= lastScale) return null
                    total += cur * s
                    cur = 0
                    lastScale = s
                    last = T.SCALE
                }
                w == "and" -> {
                    if (last != T.HUNDRED && last != T.SCALE) return null
                    last = T.AND
                }
                else -> return null
            }
        }
        return total + cur
    }

    /** 10..99 as one or two words ("fifteen", "forty", "forty five"), or null. */
    private fun twoDigit(ws: List<String>): Int? {
        if (ws.size == 1) return TEENS[ws[0]] ?: TENS[ws[0]]
        if (ws.size == 2 && ws[0] in TENS && ws[1] in UNITS && ws[1] != "zero") return TENS.getValue(ws[0]) + UNITS.getValue(ws[1])
        return null
    }

    /** Minutes / second year half (10..59 / 10..99), or "oh five" -> 5. */
    private fun ohOrTwoDigit(ws: List<String>): Int? {
        if (ws.size == 2 && ws[0] == "oh" && ws[1] in UNITS && ws[1] != "zero") return UNITS.getValue(ws[1])
        return twoDigit(ws)
    }

    /** A year said in two halves ("nineteen ninety five", "twenty twenty six", "twenty oh five", "nineteen hundred"), or null. */
    private fun pairYear(ws: List<String>): Int? {
        if (ws.size !in 2..4) return null
        val hi = twoDigit(ws.take(1))?.takeIf { it in 15..20 } ?: return null
        val rest = ws.drop(1)
        if (rest == listOf("hundred")) return hi * 100
        val lo = ohOrTwoDigit(rest) ?: return null
        return if (lo >= 10 || rest[0] == "oh") hi * 100 + lo else null
    }

    /**
     * A year from 1500 to 2099 in any common spoken form, from lowercase hyphen-split words: in two
     * halves ("twenty twenty one") or as a cardinal with "thousand" ("two thousand and five"), or null.
     */
    fun yearValue(ws: List<String>): Int? {
        pairYear(ws)?.let { return it }
        if ("thousand" !in ws || "oh" in ws) return null
        return cardinal(ws)?.takeIf { it in 2000..2099 }?.toInt()
    }

    /** The last two digits of a year said short ("twenty four", "oh four", "ninety"), or null. */
    private fun shortYear(ws: List<String>): Int? = ohOrTwoDigit(ws)

    /** A day of the month said as a cardinal ("eleven", "twenty two"), 1 to 31, or null. */
    private fun dayCardinal(ws: List<String>): Int? {
        val v = when (ws.size) {
            1 -> UNITS[ws[0]] ?: TEENS[ws[0]] ?: TENS[ws[0]]
            2 -> if (ws[0] in TENS && ws[1] in UNITS && ws[1] != "zero") TENS.getValue(ws[0]) + UNITS.getValue(ws[1]) else null
            else -> null
        }
        return v?.takeIf { it in 1..31 }
    }

    private fun hour(w: String): Int? = (UNITS[w] ?: TEENS[w])?.takeIf { it in 1..12 }

    private fun grouped(v: Long): String = "%,d".format(java.util.Locale.US, v)

    /** Two digits ("05"), the same in every locale (String.format would use the locale's digits). */
    private fun twoDigits(v: Number): String = v.toString().padStart(2, '0')

    private fun ordinalSuffix(v: Long): String {
        val r100 = v % 100
        if (r100 in 11..13) return "th"
        return when (v % 10) {
            1L -> "st"
            2L -> "nd"
            3L -> "rd"
            else -> "th"
        }
    }

    /** Written forms of a run of number words alone (no unit), best first (empty if none). */
    private fun bareForms(ws: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        // ordinal is cardinal words + an ordinal word ("twenty first", "hundredth")
        val lastOrd = ORDINALS[ws.last()]
        if (lastOrd != null) {
            val head = ws.dropLast(1)
            val v = if (head.isEmpty()) lastOrd.toLong() else {
                val c = cardinal(head)
                // "twenty first" is tens + unit ordinal, and "one hundred and fifth" is hundred(s) + ordinal
                if (c != null && (c % 10 == 0L) && (lastOrd < 10 || c % 100 == 0L)) c + lastOrd else null
            }
            if (v != null && v >= 10) out.add(grouped(v) + ordinalSuffix(v))
            return out.toList()
        }
        if (ws.any { it in ORDINALS }) return emptyList()
        // time as H MM ("three thirty", "eleven forty five", "three oh five")
        if (ws.size in 2..3) {
            val h = hour(ws[0])
            val m = ohOrTwoDigit(ws.drop(1))
            if (h != null && m != null && m in 1..59) out.add("$h:${twoDigits(m)}")
        }
        // year, as in "nineteen ninety five", "twenty twenty six", "twenty oh five", "nineteen hundred"
        pairYear(ws)?.let { out.add(it.toString()) }
        if ("oh" !in ws) {
            val c = cardinal(ws)
            if (c != null) {
                // 2000..2099 said as a cardinal ("two thousand twenty six") is usually a year
                if (c in 2000..2099 && ws.contains("thousand")) out.add(c.toString())
                if (c >= 10) out.add(grouped(c))
            }
        }
        return out.toList()
    }

    private val AM_PM = setOf("am", "pm")

    /** Unit words that make a number an amount or a time, not a year or a date. */
    private val UNIT_WORDS = setOf("dollars", "dollar", "euros", "euro", "percent", "per", "cents", "cent", "am", "pm")

    private val BARE_TIME = Regex("\\d{1,2}:\\d{2}")

    /** Words before a time said without am/pm ("at three thirty", "until six fifteen"). */
    private val TIME_PREPOSITIONS = setOf(
        "at", "by", "until", "till", "til", "around", "before", "after", "from", "since", "about", "past", "and", "or",
        "between", "starts", "start", "leaves", "leave", "lands", "arrives", "opens", "closes",
    )

    /**
     * Written forms of exactly these (lowercase, hyphen-split) words, best first, made of a number run
     * plus an optional unit ("dollars", "percent", "pm", "dollars and fifty cents").
     */
    fun formsOfWords(ws0: List<String>): List<String> {
        val ws = ws0.flatMap { it.split('-') }.filter { it.isNotEmpty() }
        if (ws.isEmpty() || !isNumberWord(ws[0])) return emptyList()
        // the leading number run
        var n = 0
        while (n < ws.size && (isNumberWord(ws[n]) || (ws[n] == "and" && n + 1 < ws.size && isNumberWord(ws[n + 1])) ||
                (ws[n] == "point" && n + 1 < ws.size && ws[n + 1] in UNITS))) n++
        val run = ws.take(n)
        val unit = ws.drop(n)
        val out = LinkedHashSet<String>()
        // decimals, as in "two point five" -> 2.5
        val pointAt = run.indexOf("point")
        val number: String? = if (pointAt >= 0) {
            val whole = cardinal(run.take(pointAt))
            val digits = run.drop(pointAt + 1)
            if (whole != null && digits.isNotEmpty() && digits.all { it in UNITS }) {
                grouped(whole) + "." + digits.joinToString("") { UNITS.getValue(it).toString() }
            } else null
        } else null
        when {
            unit.isEmpty() -> {
                if (pointAt >= 0) {
                    if (number != null) out.add(number)
                } else {
                    out.addAll(bareForms(run))
                }
            }
            unit == listOf("dollars") || unit == listOf("dollar") || unit == listOf("euros") || unit == listOf("euro") -> {
                val sym = if (unit[0].startsWith("dollar")) "$" else "€"
                val v = number ?: cardinal(run)?.let { grouped(it) }
                if (v != null && (unit[0].endsWith("s") || v == "1")) out.add(sym + v)
            }
            unit.size >= 4 && (unit[0] == "dollars" || unit[0] == "dollar") && unit[1] == "and" && unit.last() == "cents" -> {
                val d = cardinal(run)
                val c = cardinal(unit.subList(2, unit.size - 1))
                if (d != null && pointAt < 0 && c != null && c in 1..99) out.add("$" + grouped(d) + "." + twoDigits(c))
            }
            // "four and a half" -> 4.5
            unit == listOf("and", "a", "half") -> {
                val c = if (pointAt < 0) cardinal(run) else null
                if (c != null) out.add(grouped(c) + ".5")
            }
            unit == listOf("percent") || unit == listOf("per", "cent") -> {
                val v = number ?: cardinal(run)?.let { grouped(it) }
                if (v != null) out.add("$v%")
            }
            unit.size == 1 && unit[0] in AM_PM || unit.size == 2 && unit[0] in setOf("a", "p") && unit[1] == "m" -> {
                val ampm = (if (unit[0].startsWith("a")) "AM" else "PM")
                val h = if (run.size == 1) hour(run[0]) else null
                if (h != null) out.add("$h $ampm")
                if (run.size >= 2) {
                    val hh = hour(run[0])
                    val m = ohOrTwoDigit(run.drop(1))
                    if (hh != null && m != null && m in 0..59) out.add("$hh:${twoDigits(m)} $ampm")
                }
            }
        }
        return out.toList()
    }

    /** The guard's check, giving written forms of the words in [src] (any case/punctuation between words). */
    fun forms(src: String): List<String> {
        digitAmountForm(src)?.let { return listOf(it) }
        val words = TranscriptGuard.tokenize(src).words.map { TranscriptGuard.normWord(it) }
        dateForm(words)?.let { return listOf(it) }
        // ("/" is what the "forward slash" command writes)
        val slashed = TranscriptGuard.tokenize(src.replace("/", " slash ")).words.map { TranscriptGuard.normWord(it) }
        return listOfNotNull(numericDateForm(slashed, shortYearBare = true), yearRangeForm(words), digitYearRangeForm(src.trim())) +
            formsOfWords(words)
    }

    private fun flat(ws: List<String>): List<String> = ws.flatMap { it.split('-') }.filter { it.isNotEmpty() }

    /** Words between the two years of a range ("from 2021 to 2024", "between 1999 and 2005"). */
    private val RANGE_WORDS = setOf("to", "through", "thru", "until", "till", "and", "or")

    /** Of those, the ones a hyphen stands for ("2021 to 2024" -> "2021-2024"). */
    private val DASH_WORDS = setOf("to", "through", "thru")

    /**
     * A range of two years said as words, from lowercase words, or null. Joined by "to" or "through"
     * it is written with a hyphen ("twenty twenty one to twenty twenty four" -> "2021-2024"), and the
     * second year may be said short, which stays short ("twenty twenty one to twenty four" -> "2021-24").
     * Joined by "and", "or", "until" both years must be full and the word stays ("between 1999 and 2005").
     */
    fun yearRangeForm(ws: List<String>): String? {
        for (c in 1 until ws.size - 1) {
            if (ws[c] !in RANGE_WORDS) continue
            val first = flat(ws.subList(0, c))
            // (a round hundred is more often a count than a year: "between fifteen hundred and two thousand")
            if (first.lastOrNull() == "hundred") continue
            val y1 = yearValue(first) ?: continue
            val second = flat(ws.subList(c + 1, ws.size))
            val dash = ws[c] in DASH_WORDS
            yearValue(second)?.let { y2 -> return if (dash) "$y1-$y2" else "$y1 ${ws[c]} $y2" }
            // a short second year must come after the first ("2021-24", "1998-04", not "2021-19")
            val s = if (dash) shortYear(second) else null
            if (s != null && (s > y1 % 100 || y1 % 100 >= 90 && s < 10)) return "$y1-${twoDigits(s)}"
        }
        return null
    }

    /** Two years the voice model wrote as digits joined by "to" or "through" ("2021 to 2024"). */
    private val DIGIT_YEAR_RANGE = Regex("""(?<![\p{L}\p{N}$€.,/:-])(1[5-9]\d\d|20\d\d) (to|through|thru) (1[5-9]\d\d|20\d\d)(?![\p{L}\p{N}%:/-]|[.,]\d)""", RegexOption.IGNORE_CASE)

    private fun digitYearRangeOf(m: MatchResult): String? {
        val y1 = m.groupValues[1].toInt()
        val y2 = m.groupValues[3].toInt()
        // a later year, and not two round numbers, which are more often counts ("1500 to 2000 people")
        if (y2 <= y1 || (y1 % 100 == 0 && y2 % 100 == 0)) return null
        return "$y1-$y2"
    }

    /** "2021-2024" for exactly "2021 to 2024" (digits the voice model wrote), or null. */
    fun digitYearRangeForm(src: String): String? = DIGIT_YEAR_RANGE.matchEntire(src)?.let { digitYearRangeOf(it) }

    /** Spans (start, end, written form) of year ranges written as digits in [text] ("2021 to 2024" -> "2021-2024"). */
    fun digitYearRanges(text: String): List<Triple<Int, Int, String>> =
        DIGIT_YEAR_RANGE.findAll(text).mapNotNull { m -> digitYearRangeOf(m)?.let { Triple(m.range.first, m.range.last + 1, it) } }.toList()

    /** Before a date said as numbers, as in "due eleven eleven twenty six" (a short year needs one). */
    private val NUMERIC_DATE_CUES = setOf(
        "on", "date", "dated", "due", "born", "birthday", "expires", "expiry", "expiration", "deadline", "until", "till",
        "by", "since", "from", "before", "after", "of", "is", "was",
    )

    /** Before a run of numbers that is a time or a phone number, not a date. */
    private val NOT_DATE_BEFORE = setOf("at", "around", "about", "call", "text", "phone", "dial", "number", "extension", "room")

    private fun digitsValue(w: String): Int? = if (w.length in 1..4 && w.all { it in '0'..'9' }) w.toInt() else null

    /**
     * "11/11/2026" for a date said as numbers, month first ("eleven eleven twenty twenty six",
     * "eleven slash eleven slash twenty six", "11 slash 11 slash 2026"), from lowercase words, or null.
     * Without "slash" the words must be number words and a short year ("twenty six") only counts when
     * [shortYearBare], since three bare numbers are a date far less often than a slash says so. The
     * words must split into month (1 to 12), day (1 to 31) and year in exactly one way.
     */
    fun numericDateForm(ws: List<String>, shortYearBare: Boolean): String? {
        if (ws.size < 3) return null
        val slashes = ws.indices.filter { ws[it] == "slash" }
        val found = LinkedHashSet<String>()
        fun yearOf(y: List<String>, slash: Boolean): String? {
            if (y.isEmpty()) return null
            if (y.size == 1) digitsValue(y[0])?.let { v ->
                return when {
                    !slash -> null
                    y[0].length == 4 && v in 1500..2099 -> "$v"
                    y[0].length == 2 -> y[0]
                    else -> null
                }
            }
            val p = flat(y)
            if (p.any { digitsValue(it) != null }) return null
            yearValue(p)?.let { return "$it" }
            // a short year said bare must be two words ("twenty six", "oh five"), not "twenty"
            if (!slash && (!shortYearBare || p.size != 2)) return null
            return shortYear(p)?.let { twoDigits(it) }
        }
        fun dayOf(d: List<String>): Int? {
            if (d.size == 1) digitsValue(d[0])?.let { return it.takeIf { d[0].length <= 2 && it in 1..31 } }
            val p = flat(d)
            return if (p.any { digitsValue(it) != null }) null else dayCardinal(p)
        }
        val month = if (digitsValue(ws[0]) != null) digitsValue(ws[0])!!.takeIf { ws[0].length <= 2 }
            else flat(ws.take(1)).singleOrNull()?.let { UNITS[it] ?: TEENS[it] }
        if (month == null || month !in 1..12) return null
        when (slashes.size) {
            2 -> {
                val (s1, s2) = slashes
                if (s1 != 1) return null
                val d = dayOf(ws.subList(2, s2)) ?: return null
                val y = yearOf(ws.subList(s2 + 1, ws.size), slash = true) ?: return null
                found.add("$month/$d/$y")
            }
            0 -> {
                if (ws.any { digitsValue(it) != null }) return null
                for (n in 1..2) {
                    if (2 + n > ws.size) break
                    val d = dayOf(ws.subList(1, 1 + n)) ?: continue
                    val y = yearOf(ws.subList(1 + n, ws.size), slash = false) ?: continue
                    found.add("$month/$d/$y")
                }
            }
            else -> return null
        }
        return found.singleOrNull()
    }

    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december",
    )

    /** Months that are also common words ("you may first", "they march"), taken as months only after these. */
    private val AMBIGUOUS_MONTHS = setOf("may", "march")
    private val DATE_PREPOSITIONS = setOf("on", "by", "until", "till", "since", "before", "after", "from", "of", "through", "is", "was")

    /** "12th" for a day of the month said as an ordinal ("twelfth", "twenty first"), or null. */
    private fun dayOrdinal(ws: List<String>): String? {
        val p = ws.flatMap { it.split('-') }.filter { it.isNotEmpty() }
        val last = p.lastOrNull()?.let { ORDINALS[it] } ?: return null
        val v = when {
            p.size == 1 -> last
            p.size == 2 && (p[0] == "twenty" || p[0] == "thirty") && last < 10 -> TENS.getValue(p[0]) + last
            else -> return null
        }
        return if (v in 1..31) "$v" + ordinalSuffix(v.toLong()) else null
    }

    /**
     * A day of a month, maybe with a year, or a month and a year, from lowercase words, or null:
     * "march twelfth" -> "March 12th", "third of june" -> "3rd of June", "november eleventh twenty
     * twenty six" -> "November 11th, 2026", "november eleven twenty twenty six" -> "November 11, 2026",
     * "eleventh of november twenty twenty six" -> "11th of November 2026", "november twenty twenty six"
     * -> "November 2026". A day said as a cardinal ("november eleven") only counts with a year.
     */
    fun dateForm(ws: List<String>): String? {
        if (ws.size < 2) return null
        fun month(w: String) = w.takeIf { it in MONTHS }?.replaceFirstChar { it.uppercaseChar() }
        month(ws[0])?.let { m ->
            val rest = flat(ws.drop(1))
            yearValue(rest)?.let { return "$m $it" }
            dayOrdinal(rest)?.let { return "$m $it" }
            for (n in 1..2) {
                if (n >= rest.size) break
                val y = yearValue(rest.drop(n)) ?: continue
                val day = rest.take(n)
                (dayOrdinal(day) ?: dayCardinal(day)?.toString())?.let { return "$m $it, $y" }
            }
            return null
        }
        val of = ws.indexOf("of")
        if (of >= 1 && of + 1 < ws.size) month(ws[of + 1])?.let { m ->
            val day = dayOrdinal(ws.take(of)) ?: return null
            if (of + 2 == ws.size) return "$day of $m"
            return yearValue(flat(ws.drop(of + 2)))?.let { "$day of $m $it" }
        }
        return null
    }

    /** A form is an amount of money or a percentage ("$25", "€3.50", "50%"). */
    fun isAmount(form: String): Boolean = form.startsWith("$") || form.startsWith("€") || form.endsWith("%")

    /** Digits the voice model wrote, then the unit said as a word, as in "800 dollars", "1,200 euros", "30 percent". */
    private val DIGIT_AMOUNT = Regex(
        "(?<![\\p{L}\\p{N}$€.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?[ \\t]+(dollars?|euros?|percent|per cent)(?![\\p{L}\\p{N}'’-])",
        RegexOption.IGNORE_CASE,
    )

    /** "800 dollars" -> "$800", "3.50 euros" -> "€3.50", "30 percent" -> "30%" (exactly [src]), or null. */
    fun digitAmountForm(src: String): String? = DIGIT_AMOUNT.matchEntire(src)?.let { formOf(it) }

    private fun formOf(m: MatchResult): String? {
        val number = m.groupValues[1] + m.groupValues[2]
        val unit = m.groupValues[3].lowercase()
        return when {
            unit.startsWith("per") -> "$number%"
            // "1 dollar", but not "5 dollar bill" or "1 dollars"
            unit.endsWith("s") == (number == "1") -> null
            unit.startsWith("dollar") -> "$$number"
            else -> "€$number"
        }
    }

    /** Spans (start, end, written form) of digit amounts in [text] ("800 dollars"). */
    fun digitAmounts(text: String): List<Triple<Int, Int, String>> =
        DIGIT_AMOUNT.findAll(text).mapNotNull { m -> formOf(m)?.let { Triple(m.range.first, m.range.last + 1, it) } }.toList()

    /**
     * A clock time after a time word, as a voice model writes it ("at 3.30", "at 7 45", "at 315"),
     * followed by the sentence end or a word that goes with a time, not a unit ("at 100 percent").
     * Run together, "00" is a round number, not a time ("we're at 100.").
     */
    private val DIGIT_TIME = Regex("""(?i)\b(?:at|by|until|till|from|around|before|after|since)\s+(1[0-2]|0?[1-9])(?:[. ]([0-5]\d)|([0-5][1-9]|[1-5]0))(?=[.,!?]?$|[.,!?]\s|\s+(?:am|pm|a\.m|p\.m|on|today|tonight|tomorrow|this|sharp|and|or|with|for|at|in)\b)""")

    /**
     * Words a phone number follows: "call", "text", "phone", ..., and "number" only as someone's
     * ("my number is"), since an order or tracking number is no phone number.
     */
    private const val PHONE_CONTEXT = """\b(?:call|text|phone|cell|reach|dial|(?:my|his|her|their|your|our|new|home|work|mobile)\s+number)\b"""

    /** Seven or ten run-together digits near "call", "text", "my number", "phone" ("call me at 5551234"). */
    private val DIGIT_PHONE = Regex("""(?i)$PHONE_CONTEXT(?:\s+\S+){0,3}?\s+(\d{7}|\d{10})\b""")

    private const val DIGIT_WORD = "(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)"

    /** The same said digit by digit ("call me at five five five one two three four"). */
    private val SPOKEN_PHONE = Regex(
        """(?i)$PHONE_CONTEXT(?:\s+(?!$DIGIT_WORD\b)\S+){0,3}?\s+""" +
            """($DIGIT_WORD(?:[ ,-]+$DIGIT_WORD){6}(?:(?:[ ,-]+$DIGIT_WORD){3})?)\b(?![ ,-]+$DIGIT_WORD\b)"""
    )

    private fun spokenOrDigits(ws: List<String>): Long? =
        if (ws.size == 1 && ws[0].isNotEmpty() && ws[0].all { it.isDigit() }) ws[0].toLongOrNull()
        else cardinal(ws.flatMap { it.split('-').filter { p -> p.isNotEmpty() } })

    /**
     * "$45.99" for an amount said in dollars and cents, with or without the "and" a voice model
     * tends to drop ("forty five dollars ninety nine cents", "$45 ninety nine cents"), or null.
     */
    fun centsForm(src: String): String? {
        val tk = TranscriptGuard.tokenize(src)
        val ws = tk.words.map { TranscriptGuard.normWord(it) }
        if (ws.size < 3 || ws.last() !in setOf("cents", "cent")) return null
        // one amount, not "five dollars. Fifty cents for the bag"
        if ((1 until ws.size).any { SENTENCE_BREAK.containsMatchIn(tk.gaps[it]) }) return null
        val d = ws.indexOfFirst { it == "dollars" || it == "dollar" }
        val dollarWords: List<String>
        var centStart: Int
        if (d >= 1) {
            dollarWords = ws.subList(0, d)
            centStart = d + 1
        } else if (src.trimStart().startsWith("$") && ws[0].all { it.isDigit() }) {
            dollarWords = ws.subList(0, 1)
            centStart = 1
        } else return null
        if (ws.getOrNull(centStart) == "and") centStart++
        if (centStart >= ws.size - 1) return null
        // ("a dollar fifty cents")
        val dollars = (if (dollarWords == listOf("a")) 1L else spokenOrDigits(dollarWords)) ?: return null
        val cents = spokenOrDigits(ws.subList(centStart, ws.size - 1)) ?: return null
        if (cents !in 0..99) return null
        return "$" + String.format(java.util.Locale.US, "%,d.%02d", dollars, cents)
    }

    private val SENTENCE_BREAK = Regex("[.?!]\\s|\\n")

    /** Before these, numbers are said digit by digit ("room two oh four" -> "room 204"). */
    val DIGIT_NOUNS = setOf("room", "apartment", "apt", "suite", "gate", "flight", "platform", "unit", "extension", "ext", "bus", "route", "seat")

    private val DIGIT_WORDS = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
        "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9',
    )

    /** "204" for digits said one by one ("two oh four"), 2 to 5 of them, or null. */
    fun digitSequenceForm(src: String): String? {
        val ws = TranscriptGuard.tokenize(src).words.map { TranscriptGuard.normWord(it) }
        if (ws.size !in 2..5 || ws.any { it !in DIGIT_WORDS }) return null
        // "one one" or "two two" alone reads as words more often than a number
        if (ws.none { it == "oh" || it == "zero" } && ws.size == 2 && ws[0] == ws[1]) return null
        return ws.map { DIGIT_WORDS.getValue(it) }.joinToString("")
    }

    /** "555-1234" / "555-123-4567" for 7 or 10 digits, run together or said one by one ("five five five one ..."), or null. */
    fun phoneForm(src: String): String? {
        val ws = TranscriptGuard.tokenize(src).words.flatMap { TranscriptGuard.normWord(it).split('-') }.filter { it.isNotEmpty() }
        val v = when {
            ws.size == 1 && ws[0].all { it.isDigit() } -> ws[0]
            ws.isNotEmpty() && ws.all { it in DIGIT_WORDS } -> ws.map { DIGIT_WORDS.getValue(it) }.joinToString("")
            else -> return null
        }
        return when (v.length) {
            7 -> "${v.substring(0, 3)}-${v.substring(3)}"
            10 -> "${v.substring(0, 3)}-${v.substring(3, 6)}-${v.substring(6)}"
            else -> null
        }
    }

    /** Spans (start, end, written form) of clock times and phone numbers written as bare digits ("at 3.30" -> "3:30"). */
    fun digitFormats(text: String): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        for (m in DIGIT_TIME.findAll(text)) {
            val h = m.groups[1]!!
            val min = (m.groups[2] ?: m.groups[3])!!
            out.add(Triple(h.range.first, min.range.last + 1, "${h.value.trimStart('0')}:${min.value}"))
        }
        for (m in DIGIT_PHONE.findAll(text) + SPOKEN_PHONE.findAll(text)) {
            val d = m.groups[1]!!
            val form = phoneForm(d.value) ?: continue
            out.add(Triple(d.range.first, d.range.last + 1, form))
        }
        return out
    }

    /**
     * Conversion candidates in a tokenized transcript. [gaps] are the tokenizer's gaps
     * (gaps[k] precedes words[k]), and words in a run must be separated by plain spaces, except
     * "p.m."/"a.m.". [sentenceStart] tells whether word k starts a sentence (its capital
     * letter is then not a sign of a name).
     */
    fun candidates(words: List<String>, gaps: List<String>, sentenceStart: (Int) -> Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        val lw = words.map { TranscriptGuard.normWord(it) }
        fun spaceBefore(k: Int) = gaps[k].isNotEmpty() && gaps[k].all { it == ' ' }
        fun capitalizedMid(k: Int) = words[k][0].isUpperCase() && !sentenceStart(k)
        fun numberToken(i: Int) = parts(words[i]).let { p -> p.isNotEmpty() && p.all { isNumberWord(it) } }
        // end of the run of number words from i, separated by spaces
        fun runEnd(i: Int): Int {
            var j = i
            while (j < words.size && (j == i || spaceBefore(j)) && !capitalizedMid(j) && numberToken(j)) j++
            return j
        }
        // the run ends there: no number, decade ("nineteen eighties") or unit ("dollars") follows
        fun endsClean(j: Int) = j >= words.size || !spaceBefore(j) ||
            (lw[j] !in BLOCKING_NEXT && lw[j] !in UNIT_WORDS && !(lw[j] == "and" && j + 1 < words.size && numberToken(j + 1)))
        // a date said as numbers ("due eleven eleven twenty twenty six" -> "due 11/11/2026"), also with
        // "slash" between them (or "/", from the "forward slash" command), or the voice model's digits with
        // "slash" ("11 slash 11 slash 2026")
        fun numericDate(k: Int): Candidate? {
            val digitsOrNumber = { i: Int -> numberToken(i) || digitsValue(lw[i]) != null }
            if (!digitsOrNumber(k) || (k > 0 && spaceBefore(k) && digitsOrNumber(k - 1))) return null
            val ws = ArrayList<String>()
            var e = k
            while (e < words.size && e - k < 9 && !capitalizedMid(e)) {
                val written = e > k && gaps[e].trim() == "/"
                if (e > k && !spaceBefore(e) && !written) break
                if (!digitsOrNumber(e) && lw[e] != "slash") break
                if (written) ws.add("slash")
                ws.add(lw[e])
                e++
            }
            if (e == k || !endsClean(e) || lw[e - 1] == "slash") return null
            val slash = "slash" in ws
            if (!slash) {
                // not a time ("at ten fifteen twenty twenty") or a phone number ("call five five five ...")
                if ((maxOf(0, k - 4) until k).any { lw[it] in NOT_DATE_BEFORE }) return null
            }
            val cue = (maxOf(0, k - 3) until k).any { lw[it] in NUMERIC_DATE_CUES }
            val form = numericDateForm(ws, shortYearBare = cue) ?: return null
            return Candidate(k, e, listOf(form))
        }
        // a range of years ("from twenty twenty one to twenty twenty four" -> "from 2021 to 2024"),
        // as one edit, since the LM judges "2021 to twenty twenty four" alone as worse than both
        fun yearRange(k: Int): Candidate? {
            if (!numberToken(k) || (k > 0 && numberToken(k - 1))) return null
            for (c in k + 1 until minOf(words.size - 1, k + 7)) {
                if (!spaceBefore(c) || capitalizedMid(c)) return null
                if (lw[c] in RANGE_WORDS) {
                    if (!spaceBefore(c + 1)) return null
                    val e = runEnd(c + 1)
                    if (e == c + 1 || !endsClean(e)) continue
                    val form = yearRangeForm(lw.subList(k, e)) ?: continue
                    return Candidate(k, e, listOf(form))
                }
                if (!numberToken(c) && lw[c] != "and") return null
            }
            return null
        }
        var k = 0
        while (k < words.size) {
            if (!capitalizedMid(k) && (k == 0 || lw[k - 1] !in MONTHS)) {
                val c = numericDate(k) ?: yearRange(k)
                if (c != null) {
                    out.add(c)
                    k = c.end
                    continue
                }
            }
            val first = parts(words[k])
            if (first.isEmpty() || !isNumberWord(first[0]) || first[0] == "oh" || capitalizedMid(k)) {
                k++
                continue
            }
            // maximal run of number words (and "and"/"point" between them) separated by spaces
            var e = k + 1
            while (e < words.size && spaceBefore(e) && !capitalizedMid(e)) {
                val p = parts(words[e])
                val w = p.firstOrNull() ?: break
                val nextIsNumber = e + 1 < words.size && spaceBefore(e + 1) && parts(words[e + 1]).firstOrNull()?.let { isNumberWord(it) } == true
                if (p.all { isNumberWord(it) } || ((w == "and" || w == "point") && nextIsNumber)) e++ else break
            }
            if (e < words.size && spaceBefore(e) && lw[e] in BLOCKING_NEXT) {
                k = e
                continue
            }
            fun w(i: Int) = if (i < words.size && spaceBefore(i)) lw[i] else null
            // a day of the month ("on march twelfth" -> "on March 12th", "the third of June" -> "the 3rd of June")
            fun month(i: Int) = lw[i] in MONTHS &&
                (lw[i] !in AMBIGUOUS_MONTHS || words[i][0].isUpperCase() || (i > 0 && lw[i - 1] in DATE_PREPOSITIONS))
            // with a year after it, maybe after a comma ("march third, twenty twenty two" -> "March 3rd, 2022",
            // "the eleventh of november twenty twenty six" -> "the 11th of November 2026"), longest first
            val dates = when {
                k > 0 && spaceBefore(k) && month(k - 1) -> listOfNotNull(
                    (k - 1 until runEnd(e)).takeIf { e < words.size && gaps[e] == ", " && numberToken(e) && endsClean(runEnd(e)) },
                    k - 1 until e,
                )
                w(e) == "of" && w(e + 1) != null && month(e + 1) -> listOfNotNull(
                    (k until runEnd(e + 2)).takeIf { w(e + 2) != null && numberToken(e + 2) && endsClean(runEnd(e + 2)) },
                    k until e + 2,
                )
                else -> emptyList()
            }
            val date = dates.firstOrNull { d -> dateForm(lw.subList(d.first, d.last + 1)) != null }
            if (date != null) {
                out.add(Candidate(date.first, date.last + 1, listOf(dateForm(lw.subList(date.first, date.last + 1))!!)))
                k = date.last + 1
                continue
            }
            // units after the run
            val units = ArrayList<Int>()  // candidate end indices (exclusive), longest first
            fun dotGap(i: Int) = i < words.size && gaps[i] == "."
            when {
                // "four and a half pounds" (not alone, "she's two and a half")
                w(e) == "and" && w(e + 1) == "a" && w(e + 2) == "half" && w(e + 3) != null -> units.add(e + 3)
                w(e) == "dollars" && w(e + 1) == "and" -> {
                    // "dollars and fifty cents"
                    var c = e + 2
                    while (c < words.size && spaceBefore(c) && parts(words[c]).all { it in UNITS || it in TEENS || it in TENS }) c++
                    if (c > e + 2 && w(c) == "cents") units.add(c + 1)
                    units.add(e + 1)
                }
                w(e) in setOf("dollars", "dollar", "euros", "euro", "percent", "am", "pm") -> units.add(e + 1)
                w(e) == "per" && w(e + 1) == "cent" -> units.add(e + 2)
                w(e) in setOf("a", "p") && e + 1 < words.size && (dotGap(e + 1) || spaceBefore(e + 1)) && lw[e + 1] == "m" -> units.add(e + 2)
            }
            units.add(e)
            // a time without am/pm only after "at", "by", "until", ... (so "the oven to three fifty" is 350 degrees)
            val timeContext = k > 0 && lw[k - 1] in TIME_PREPOSITIONS
            for (end in units.distinct()) {
                val forms = formsOfWords(words.subList(k, end).map { TranscriptGuard.normWord(it) })
                    .filter { timeContext || !BARE_TIME.matches(it) }
                if (forms.isNotEmpty()) {
                    out.add(Candidate(k, end, forms))
                    break
                }
            }
            k = e
        }
        return out
    }
}
