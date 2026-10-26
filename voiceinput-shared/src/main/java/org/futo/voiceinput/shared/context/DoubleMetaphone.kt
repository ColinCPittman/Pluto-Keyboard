package org.futo.voiceinput.shared.context

import java.text.Normalizer

/**
 * Double Metaphone, ported line by line from the Python `metaphone` package (0.6) that
 * tools/context-bench/guard.py uses, so that the app's sound-alike test gives exactly the
 * benchmark's answers. That includes its quirks, such as Python slice/index semantics, branches that
 * leave the previous step's result in place, and a secondary code that may contain a space.
 *
 * Original algorithm (c) 1998, 1999 Lawrence Philips; Python version
 * Copyright (c) 2007 Andrew Collins, Chris Leong; (c) 2009 Matthew Somerville;
 * (c) 2010 Maximillian Dornseif, Richard Barran; (c) 2012 Duncan McGreggor.
 * BSD license (see NOTICE of the context model package / THIRD_PARTY_NOTICES.md).
 */
object DoubleMetaphone {
    private const val VOWELS = "AEIOUY"
    private val SILENT_STARTERS = setOf("GN", "KN", "PN", "WR", "PS")

    /** Returns (primary, secondary), and secondary is "" when it equals primary. */
    fun encode(input: String): Pair<String, String> = Parser(input).parse()

    // A step's result is what to append to the primary / secondary code and how far to move.
    // `three` mirrors the Python tuple length (2 means same code for both, 3 means separate codes).
    private class Next(val p: String?, val s: String?, val adv: Int, val three: Boolean)

    private fun two(p: String?, adv: Int) = Next(p, null, adv, false)
    private fun three(p: String?, s: String?, adv: Int) = Next(p, s, adv, true)

    private class Parser(input: String) {
        val upper: String
        val length: Int
        val startIndex = 2
        val endIndex: Int
        val buffer: String

        init {
            val decoded = input.replace('Ç', 's').replace('ç', 's')
            val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            upper = normalized.uppercase()
            length = upper.length
            endIndex = startIndex + length - 1
            buffer = "--$upper------"
        }

        val isSlavoGermanic: Boolean =
            upper.contains('W') || upper.contains('K') || upper.contains("CZ") || upper.contains("WITZ")

        var position = 0
        var primary = StringBuilder()
        var secondary = StringBuilder()
        var next: Next = two(null, 1)

        /** Python buffer[i] (negative indices count from the end). */
        fun at(i: Int): Char {
            val k = if (i < 0) i + buffer.length else i
            return if (k in buffer.indices) buffer[k] else '\u0000'
        }

        /** Python buffer[a:b]. */
        fun sl(a: Int, b: Int): String {
            val n = buffer.length
            var x = if (a < 0) a + n else a
            var y = if (b < 0) b + n else b
            x = x.coerceIn(0, n)
            y = y.coerceIn(0, n)
            return if (x >= y) "" else buffer.substring(x, y)
        }

        fun isVowel(c: Char) = VOWELS.indexOf(c) >= 0 && c != '\u0000'

        fun checkWordStart() {
            if (sl(startIndex, startIndex + 2) in SILENT_STARTERS) position += 1
            if (sl(startIndex, startIndex + 1) == "X") {
                primary.append('S')
                secondary.append('S')
                position += 1
            }
        }

        fun processInitialVowels() {
            next = two(null, 1)
            if (position == startIndex) next = two("A", 1)
        }

        fun processB() {
            next = if (at(position + 1) == 'B') two("P", 2) else two("P", 1)
        }

        fun processC() {
            val pos = position
            val si = startIndex
            if (pos > si + 1 && !isVowel(at(pos - 2)) && sl(pos - 1, pos + 2) == "ACH" &&
                at(pos + 2) != 'I' && (at(pos + 2) != 'E' || sl(pos - 2, pos + 4) in setOf("BACHER", "MACHER"))
            ) {
                next = two("K", 2)
            } else if (pos == si && sl(si, si + 6) == "CAESAR") {
                next = two("S", 2)
            } else if (sl(pos, pos + 4) == "CHIA") {
                next = two("K", 2)
            } else if (sl(pos, pos + 2) == "CH") {
                if (pos > si && sl(pos, pos + 4) == "CHAE") {
                    next = three("K", "X", 2)
                } else if (pos == si &&
                    (sl(pos + 1, pos + 6) in setOf("HARAC", "HARIS") || sl(pos + 1, pos + 4) in setOf("HOR", "HYM", "HIA", "HEM")) &&
                    sl(si, si + 5) != "CHORE"
                ) {
                    next = two("K", 2)
                } else if (sl(si, si + 4) in setOf("VAN ", "VON ") || sl(si, si + 3) == "SCH" ||
                    sl(pos - 2, pos + 4) in setOf("ORCHES", "ARCHIT", "ORCHID") ||
                    at(pos + 2) in "TS" ||
                    ((at(pos - 1) in "AOUE" || pos == si) && at(pos + 2) in "LRNMBHFVW")
                ) {
                    next = two("K", 2)
                } else {
                    next = if (pos > si) {
                        if (sl(si, si + 2) == "MC") two("K", 2) else three("X", "K", 2)
                    } else {
                        two("X", 2)
                    }
                }
            } else if (sl(pos, pos + 2) == "CZ" && sl(pos - 2, pos + 2) != "WICZ") {
                next = three("S", "X", 2)
            } else if (sl(pos + 1, pos + 4) == "CIA") {
                next = two("X", 3)
            } else if (sl(pos, pos + 2) == "CC" && !(pos == si + 1 && at(si) == 'M')) {
                next = if (at(pos + 2) in "IEH" && sl(pos + 2, pos + 4) != "HU") {
                    if ((pos == si + 1 && at(si) == 'A') || sl(pos - 1, pos + 4) in setOf("UCCEE", "UCCES")) {
                        two("KS", 3)
                    } else {
                        two("X", 3)
                    }
                } else {
                    two("K", 2)
                }
            } else if (sl(pos, pos + 2) in setOf("CK", "CG", "CQ")) {
                next = two("K", 2)
            } else if (sl(pos, pos + 2) in setOf("CI", "CE", "CY")) {
                next = if (sl(pos, pos + 3) in setOf("CIO", "CIE", "CIA")) three("S", "X", 2) else two("S", 2)
            } else {
                next = if (sl(pos + 1, pos + 3) in setOf(" C", " Q", " G")) {
                    two("K", 3)
                } else if (at(pos + 1) in "CKQ" && sl(pos + 1, pos + 3) !in setOf("CE", "CI")) {
                    two("K", 2)
                } else {
                    two("K", 1)
                }
            }
        }

        fun processD() {
            next = if (sl(position, position + 2) == "DG") {
                if (at(position + 2) in "IEY") two("J", 3) else two("TK", 2)
            } else if (sl(position, position + 2) in setOf("DT", "DD")) {
                two("T", 2)
            } else {
                two("T", 1)
            }
        }

        fun processF() {
            next = if (at(position + 1) == 'F') two("F", 2) else two("F", 1)
        }

        fun processG() {
            val pos = position
            val si = startIndex
            if (at(pos + 1) == 'H') {
                if (pos > si && !isVowel(at(pos - 1))) {
                    next = two("K", 2)
                } else if (pos < si + 3) {
                    if (pos == si) {
                        next = if (at(pos + 2) == 'I') two("J", 2) else two("K", 2)
                    }
                } else if ((pos > si + 1 && at(pos - 2) in "BHD") ||
                    (pos > si + 2 && at(pos - 3) in "BHD") ||
                    (pos > si + 3 && at(pos - 4) in "BH")
                ) {
                    next = two(null, 2)
                } else {
                    if (pos > si + 2 && at(pos - 1) == 'U' && at(pos - 3) in "CGLRT") {
                        next = two("F", 2)
                    } else if (pos > si && at(pos - 1) != 'I') {
                        next = two("K", 2)
                    }
                }
            } else if (at(pos + 1) == 'N') {
                next = if (pos == si + 1 && isVowel(at(si)) && !isSlavoGermanic) {
                    three("KN", "N", 2)
                } else if (sl(pos + 2, pos + 4) != "EY" && at(pos + 1) != 'Y' && !isSlavoGermanic) {
                    three("N", "KN", 2)
                } else {
                    two("KN", 2)
                }
            } else if (sl(pos + 1, pos + 3) == "LI" && !isSlavoGermanic) {
                next = three("KL", "L", 2)
            } else if (pos == si && (at(pos + 1) == 'Y' ||
                    sl(pos + 1, pos + 3) in setOf("ES", "EP", "EB", "EL", "EY", "IB", "IL", "IN", "IE", "EI", "ER"))
            ) {
                next = three("K", "J", 2)
            } else if ((sl(pos + 1, pos + 3) == "ER" || at(pos + 1) == 'Y') &&
                sl(si, si + 6) !in setOf("DANGER", "RANGER", "MANGER") &&
                at(pos - 1) !in "EI" &&
                sl(pos - 1, pos + 2) !in setOf("RGY", "OGY")
            ) {
                next = three("K", "J", 2)
            } else if (at(pos + 1) in "EIY" || sl(pos - 1, pos + 3) in setOf("AGGI", "OGGI")) {
                next = if (sl(si, si + 4) in setOf("VON ", "VAN ") || sl(si, si + 3) == "SCH" || sl(pos + 1, pos + 3) == "ET") {
                    two("K", 2)
                } else if (sl(pos + 1, pos + 5) == "IER ") {
                    two("J", 2)
                } else {
                    three("J", "K", 2)
                }
            } else if (at(pos + 1) == 'G') {
                next = two("K", 2)
            } else {
                next = two("K", 1)
            }
        }

        fun processH() {
            next = if ((position == startIndex || isVowel(at(position - 1))) && isVowel(at(position + 1))) {
                two("H", 2)
            } else {
                two(null, 1)
            }
        }

        fun processJ() {
            val pos = position
            val si = startIndex
            // codes without the step length, and the length is added at the end like in Python
            val p: String?
            var s: String? = null
            var isThree = false
            if (sl(pos, pos + 4) == "JOSE" || sl(si, si + 4) == "SAN ") {
                if ((pos == si && at(pos + 4) == ' ') || sl(si, si + 4) == "SAN ") {
                    p = "H"
                } else {
                    p = "J"; s = "H"; isThree = true
                }
            } else if (pos == si && sl(pos, pos + 4) != "JOSE") {
                p = "J"; s = "A"; isThree = true
            } else if (isVowel(at(pos - 1)) && !isSlavoGermanic && at(pos + 1) in "AO") {
                p = "J"; s = "H"; isThree = true
            } else if (pos == endIndex) {
                p = "J"; s = " "; isThree = true
            } else if (at(pos + 1) !in "LTKSNMBZ" && at(pos - 1) !in "SKL") {
                p = "J"
            } else {
                p = null
            }
            val adv = if (at(pos + 1) == 'J') 2 else 1
            next = if (isThree) three(p, s, adv) else two(p, adv)
        }

        fun processK() {
            next = if (at(position + 1) == 'K') two("K", 2) else two("K", 1)
        }

        fun processL() {
            val pos = position
            val ei = endIndex
            next = if (at(pos + 1) == 'L') {
                if ((pos == ei - 2 && sl(pos - 1, pos + 3) in setOf("ILLO", "ILLA", "ALLE")) ||
                    ((sl(ei - 1, ei + 1) in setOf("AS", "OS") || at(ei) in "AO") && sl(pos - 1, pos + 3) == "ALLE")
                ) {
                    three("L", "", 2)
                } else {
                    two("L", 2)
                }
            } else {
                two("L", 1)
            }
        }

        fun processM() {
            val pos = position
            next = if ((sl(pos + 1, pos + 4) == "UMB" && (pos + 1 == endIndex || sl(pos + 2, pos + 4) == "ER")) ||
                at(pos + 1) == 'M'
            ) two("M", 2) else two("M", 1)
        }

        fun processN() {
            next = if (at(position + 1) == 'N') two("N", 2) else two("N", 1)
        }

        fun processP() {
            next = when {
                at(position + 1) == 'H' -> two("F", 2)
                at(position + 1) in "PB" -> two("P", 2)
                else -> two("P", 1)
            }
        }

        fun processQ() {
            next = if (at(position + 1) == 'Q') two("K", 2) else two("K", 1)
        }

        fun processR() {
            val pos = position
            val adv = if (at(pos + 1) == 'R') 2 else 1
            next = if (pos == endIndex && !isSlavoGermanic && sl(pos - 2, pos) == "IE" &&
                sl(pos - 4, pos - 2) !in setOf("ME", "MA")
            ) three("", "R", adv) else two("R", adv)
        }

        fun processS() {
            val pos = position
            val si = startIndex
            if (sl(pos - 1, pos + 2) in setOf("ISL", "YSL")) {
                next = two(null, 1)
            } else if (pos == si && sl(si, si + 5) == "SUGAR") {
                next = three("X", "S", 1)
            } else if (sl(pos, pos + 2) == "SH") {
                next = if (sl(pos + 1, pos + 5) in setOf("HEIM", "HOEK", "HOLM", "HOLZ")) two("S", 2) else two("X", 2)
            } else if (sl(pos, pos + 3) in setOf("SIO", "SIA") || sl(pos, pos + 4) == "SIAN") {
                next = if (!isSlavoGermanic) three("S", "X", 3) else two("S", 3)
            } else if ((pos == si && at(pos + 1) in "MNLW") || at(pos + 1) == 'Z') {
                next = three("S", "X", if (at(pos + 1) == 'Z') 2 else 1)
            } else if (sl(pos, pos + 2) == "SC") {
                next = if (at(pos + 2) == 'H') {
                    if (sl(pos + 3, pos + 5) in setOf("OO", "ER", "EN", "UY", "ED", "EM")) {
                        if (sl(pos + 3, pos + 5) in setOf("ER", "EN")) three("X", "SK", 3) else two("SK", 3)
                    } else {
                        if (pos == si && !isVowel(at(si + 3)) && at(si + 3) != 'W') three("X", "S", 3) else two("X", 3)
                    }
                } else if (at(pos + 2) in "IEY") {
                    two("S", 3)
                } else {
                    two("SK", 3)
                }
            } else if (pos == endIndex && sl(pos - 2, pos) in setOf("AI", "OI")) {
                next = three("", "S", 1)
            } else {
                next = two("S", if (at(pos + 1) in "SZ") 2 else 1)
            }
        }

        fun processT() {
            val pos = position
            val si = startIndex
            next = if (sl(pos, pos + 4) == "TION") {
                two("X", 3)
            } else if (sl(pos, pos + 3) in setOf("TIA", "TCH")) {
                two("X", 3)
            } else if (sl(pos, pos + 2) == "TH" || sl(pos, pos + 3) == "TTH") {
                if (sl(pos + 2, pos + 4) in setOf("OM", "AM") || sl(si, si + 4) in setOf("VON ", "VAN ") || sl(si, si + 3) == "SCH") {
                    two("T", 2)
                } else {
                    three("0", "T", 2)
                }
            } else if (at(pos + 1) in "TD") {
                two("T", 2)
            } else {
                two("T", 1)
            }
        }

        fun processV() {
            next = if (at(position + 1) == 'V') two("F", 2) else two("F", 1)
        }

        fun processW() {
            val pos = position
            val si = startIndex
            next = if (sl(pos, pos + 2) == "WR") {
                two("R", 2)
            } else if (pos == si && (isVowel(at(pos + 1)) || sl(pos, pos + 2) == "WH")) {
                if (isVowel(at(pos + 1))) three("A", "F", 1) else two("A", 1)
            } else if ((pos == endIndex && isVowel(at(pos - 1))) ||
                sl(pos - 1, pos + 4) in setOf("EWSKI", "EWSKY", "OWSKI", "OWSKY") ||
                sl(si, si + 3) == "SCH"
            ) {
                three("", "F", 1)
            } else if (sl(pos, pos + 4) in setOf("WICZ", "WITZ")) {
                three("TS", "FX", 4)
            } else {
                two(null, 1)
            }
        }

        fun processX() {
            val pos = position
            val p = if (!(pos == endIndex && (sl(pos - 3, pos) in setOf("IAU", "EAU") || sl(pos - 2, pos) in setOf("AU", "OU")))) {
                "KS"
            } else {
                null
            }
            next = two(p, if (at(pos + 1) in "CX") 2 else 1)
        }

        fun processZ() {
            val pos = position
            val adv = if (at(pos + 1) == 'Z' || at(pos + 1) == 'H') 2 else 1
            next = if (at(pos + 1) == 'H') {
                two("J", adv)
            } else if (sl(pos + 1, pos + 3) in setOf("ZO", "ZI", "ZA") ||
                (isSlavoGermanic && pos > startIndex && at(pos - 1) != 'T')
            ) {
                three("S", "TS", adv)
            } else {
                two("S", adv)
            }
        }

        fun parse(): Pair<String, String> {
            position = startIndex
            checkWordStart()
            while (position <= endIndex) {
                val c = buffer[position]
                when {
                    isVowel(c) -> processInitialVowels()
                    c == ' ' -> { position += 1; continue }
                    c == 'B' -> processB()
                    c == 'C' -> processC()
                    c == 'D' -> processD()
                    c == 'F' -> processF()
                    c == 'G' -> processG()
                    c == 'H' -> processH()
                    c == 'J' -> processJ()
                    c == 'K' -> processK()
                    c == 'L' -> processL()
                    c == 'M' -> processM()
                    c == 'N' -> processN()
                    c == 'P' -> processP()
                    c == 'Q' -> processQ()
                    c == 'R' -> processR()
                    c == 'S' -> processS()
                    c == 'T' -> processT()
                    c == 'V' -> processV()
                    c == 'W' -> processW()
                    c == 'X' -> processX()
                    c == 'Z' -> processZ()
                    // any other character makes Python leave `next` as it was (the previous step repeats)
                }
                val n = next
                if (!n.three) {
                    if (!n.p.isNullOrEmpty()) {
                        primary.append(n.p)
                        secondary.append(n.p)
                    }
                } else {
                    if (!n.p.isNullOrEmpty()) primary.append(n.p)
                    if (!n.s.isNullOrEmpty()) secondary.append(n.s)
                }
                position += n.adv
            }
            val p = primary.toString()
            val s = secondary.toString()
            return p to (if (p == s) "" else s)
        }
    }
}
