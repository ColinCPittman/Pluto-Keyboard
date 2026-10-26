package org.futo.voiceinput.shared.context

/**
 * Spoken numbers -> digits for the "Numbers, times and amounts" option. Pure rules, no LM.
 * [candidates] finds spans of number words in a transcript and proposes written forms
 * ("twenty five dollars" -> "$25", "three thirty pm" -> "3:30 PM", "fifty percent" -> "50%",
 * "nineteen ninety five" -> "1995", "twenty first" -> "21st"). The context stage's LM then
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

    private fun hour(w: String): Int? = (UNITS[w] ?: TEENS[w])?.takeIf { it in 1..12 }

    private fun grouped(v: Long): String = "%,d".format(java.util.Locale.US, v)

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
            if (h != null && m != null && m in 1..59) out.add("$h:%02d".format(m))
        }
        // year, as in "nineteen ninety five", "twenty twenty six", "twenty oh five", "nineteen hundred"
        if (ws.size in 2..4) {
            for (split in 1..2) {
                if (split >= ws.size) continue
                val hi = twoDigit(ws.take(split)) ?: continue
                val rest = ws.drop(split)
                val lo = if (rest == listOf("hundred")) 0 else ohOrTwoDigit(rest)
                if (lo != null && hi in 15..20 && (lo >= 10 || rest[0] == "oh" || rest == listOf("hundred"))) {
                    out.add((hi * 100 + lo).toString())
                }
            }
        }
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
                if (d != null && pointAt < 0 && c != null && c in 1..99) out.add("$" + grouped(d) + ".%02d".format(c))
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
                    if (hh != null && m != null && m in 0..59) out.add("$hh:%02d $ampm".format(m))
                }
            }
        }
        return out.toList()
    }

    /** The guard's check, giving written forms of the words in [src] (any case/punctuation between words). */
    fun forms(src: String): List<String> {
        digitAmountForm(src)?.let { return listOf(it) }
        val words = TranscriptGuard.tokenize(src).words.map { TranscriptGuard.normWord(it) }
        return formsOfWords(words)
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
        var k = 0
        while (k < words.size) {
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
            // units after the run
            val units = ArrayList<Int>()  // candidate end indices (exclusive), longest first
            fun w(i: Int) = if (i < words.size && spaceBefore(i)) lw[i] else null
            fun dotGap(i: Int) = i < words.size && gaps[i] == "."
            when {
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
