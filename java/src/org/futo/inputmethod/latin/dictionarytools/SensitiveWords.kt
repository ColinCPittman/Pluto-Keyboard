package org.futo.inputmethod.latin.dictionarytools

/**
 * Pluto: decides whether a typed token looks like a password, key, code or address rather than a
 * word, so the keyboard does not learn it (and does not offer to copy it to the shared Android
 * personal dictionary).
 *
 * Pure Kotlin, no Android dependencies (unit-tested in tests/unit). The detector errs on the side
 * of flagging, since a false positive only means the word is not learned automatically (the user can tap
 * "Remember anyway"), while a false negative could put a password into the suggestion strip.
 *
 * A token is flagged when, after trimming surrounding punctuation and a leading '@' or '#':
 *  1. it starts with a well-known secret/key prefix (sk-, sk_live_, ghp_, gho_, github_pat_,
 *     glpat-, AKIA/ASIA access key ids, xoxb-/xoxp-, AIza, ya29., hf_, npm_, SG., shpat_, eyJ (JWT),
 *     ...) and is long enough to be a real key,
 *  2. it is an e-mail address (anything@domain.tld),
 *  3. it contains a run of 8 or more digits, or 10 or more digits in total (card, phone and
 *     account numbers, even when grouped with dashes),
 *  4. it is 16 or more hex characters (hashes, tokens),
 *  5. it mixes letters and digits in one chunk of 5 or more characters (Tr0ub4dor, a8f2k9,
 *     hunter2, password123, iPhone15) -- except ordinals (1st, 22nd), decades (1990s, 80s) and
 *     numbers with a unit (10am, 1080p, 64GB, 5km). Chunks of 4 or fewer characters (MP3, PS5,
 *     4K, B2B, H2O, x86, gr8) are allowed. Note "iPhone15"/"Windows11"-style names are flagged on
 *     purpose, since word + trailing digits is the most common password shape,
 *  6. it contains a symbol inside the word other than the ones normal words use: apostrophes
 *     (don't, O'Neil), hyphens (well-known, COVID-19, 21-year-old), dots in abbreviations, numbers
 *     and domains (e.g., U.S., 3.14, google.com), slashes between words or small numbers (and/or,
 *     24/7), '&' in short abbreviations (AT&T, R&D), ':' in times (10:30) and ',' in numbers
 *     (1,000). Anything else inside a word (P@ssw0rd, Pa$$word, user:pass, my_secret) is flagged,
 *  7. it looks random, meaning 10+ Latin letters without a vowel, or 12+ letters whose case flips
 *     unusually often (aBcDeFgHiJkL), or a 20+ character base64-like string with mixed case and
 *     '+', '/' or '='.
 * Tokens shorter than 4 characters are never flagged. Non-Latin scripts are only checked by the
 * script-independent rules (digits, symbols, e-mail).
 */
object SensitiveWords {
    private const val MIN_LENGTH = 4

    /** Prefix -> minimum total token length for it to count as a key. Case-sensitive. */
    private val KEY_PREFIXES: List<Pair<String, Int>> = listOf(
        "sk-" to 16, "sk_live_" to 16, "sk_test_" to 16, "pk_live_" to 16, "pk_test_" to 16,
        "rk_live_" to 16, "rk_test_" to 16, "whsec_" to 16,
        "ghp_" to 20, "gho_" to 20, "ghu_" to 20, "ghs_" to 20, "ghr_" to 20, "github_pat_" to 20,
        "glpat-" to 20,
        "xoxb-" to 15, "xoxp-" to 15, "xoxa-" to 15, "xoxr-" to 15, "xoxs-" to 15, "xapp-" to 15,
        "AIza" to 20, "ya29." to 20,
        "hf_" to 20, "npm_" to 20, "pypi-" to 20, "SG." to 20,
        "shpat_" to 20, "shpss_" to 20, "shpca_" to 20, "dop_v1_" to 20, "eyJ" to 20,
    )

    private val ORDINAL = Regex("^\\d+(st|nd|rd|th)$", RegexOption.IGNORE_CASE)
    private val DECADE = Regex("^'?\\d+s$", RegexOption.IGNORE_CASE)
    private val NUMBER_WITH_UNIT = Regex(
        "^\\d+(k|m|b|g|t|x|d|p|v|w|h|s|am|pm|kg|km|cm|mm|mg|ml|gb|mb|tb|kb|hz|khz|mhz|ghz|px|pt|mph|kph|" +
            "kmh|lb|lbs|oz|ft|yr|yrs|hr|hrs|min|mins|sec|secs|kw|kwh|mah|fps|dpi|gen)$",
        RegexOption.IGNORE_CASE
    )
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val TIME = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
    private val NUMBER_WITH_COMMAS = Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val SLASHED_NUMBERS = Regex("^\\d{1,4}(/\\d{1,4}){1,2}$")
    private val VERSION_PART = Regex("^[vV]\\d+$")

    private const val EDGE_PUNCTUATION = ".,!?;:\"'()[]{}<>«»“”‘’…%+*\$€£¥"
    private val APOSTROPHES = setOf('\'', '’', 'ʼ', '‘')
    private val HYPHENS = setOf('-', '‐', '‑')

    @JvmStatic
    fun looksSensitive(word: String?): Boolean {
        if (word == null) return false
        var token = word.trim().trim { EDGE_PUNCTUATION.indexOf(it) >= 0 }
        if (token.startsWith("@") || token.startsWith("#")) token = token.substring(1)
        token = token.trim { EDGE_PUNCTUATION.indexOf(it) >= 0 }
        if (token.length < MIN_LENGTH) return false
        // Emoji, symbols and punctuation on their own are not secrets.
        if (token.none { it.isLetterOrDigit() }) return false
        if (token.any { it.isWhitespace() }) return token.split(Regex("\\s+")).any { looksSensitive(it) }

        if (hasKeyPrefix(token)) return true
        if (EMAIL.matches(token)) return true
        if (hasLongDigits(token)) return true
        if (token.length >= 16 && token.all { it.isHexDigitChar() }) return true
        if (hasBadSymbols(token)) return true
        if (hasMixedChunk(token)) return true
        if (looksRandom(token)) return true
        return false
    }

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun hasKeyPrefix(token: String): Boolean {
        if (token.length == 20 && (token.startsWith("AKIA") || token.startsWith("ASIA"))
            && token.all { it in 'A'..'Z' || it in '0'..'9' }) return true
        return KEY_PREFIXES.any { (prefix, minLength) -> token.length >= minLength && token.startsWith(prefix) }
    }

    private fun hasLongDigits(token: String): Boolean {
        var run = 0
        var total = 0
        for (c in token) {
            if (c.isDigit()) {
                run++; total++
                if (run >= 8) return true
            } else {
                run = 0
            }
        }
        return total >= 10
    }

    /** True if the token contains a symbol inside it that normal words don't use. */
    private fun hasBadSymbols(token: String): Boolean {
        val symbols = token.filterNot { it.isLetterOrDigit() || it in APOSTROPHES || it in HYPHENS || isMarkOrEmoji(it) }
        if (symbols.isEmpty()) return false
        val distinct = symbols.toSet()
        // Only one kind of extra symbol is allowed, and only in its usual shapes.
        if (distinct.size > 1) return true
        val core = token.filterNot { it in APOSTROPHES }
        return when (distinct.first()) {
            '.' -> !dotsLookNormal(core)
            '/' -> !slashesLookNormal(core)
            '&' -> core.length > 6 || !core.all { it == '&' || it.isLetter() }
            ':' -> !TIME.matches(core)
            ',' -> !NUMBER_WITH_COMMAS.matches(core)
            else -> true
        }
    }

    /** Accents written as separate marks, and emoji (surrogates, symbols, joiners, variation selectors). */
    private fun isMarkOrEmoji(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
            || type == Character.ENCLOSING_MARK.toInt() || type == Character.SURROGATE.toInt()
            || type == Character.OTHER_SYMBOL.toInt() || c.code == 0x200D || c.code == 0xFE0F
    }

    private fun dotsLookNormal(token: String): Boolean {
        val parts = token.split('.')
        if (parts.any { it.isEmpty() }) return false
        return parts.all { part ->
            val p = part.filterNot { it in HYPHENS }
            p.isNotEmpty() && (p.all { it.isLetter() } || p.all { it.isDigit() } || VERSION_PART.matches(p))
        }
    }

    private fun slashesLookNormal(token: String): Boolean {
        if (SLASHED_NUMBERS.matches(token)) return true
        val parts = token.split('/')
        return parts.size <= 3 && parts.all { it.isNotEmpty() && it.length <= 8 && it.all { c -> c.isLetter() } }
    }

    /** True if some chunk (split at hyphens, dots, slashes...) mixes letters and digits like a password. */
    private fun hasMixedChunk(token: String): Boolean {
        val chunks = token.splitWhere { !it.isLetterOrDigit() && it !in APOSTROPHES }
        var shortMixed = 0
        for (raw in chunks) {
            val chunk = raw.filterNot { it in APOSTROPHES }
            if (chunk.isEmpty()) continue
            val hasLetter = chunk.any { it.isLetter() }
            val hasDigit = chunk.any { it.isDigit() }
            if (!hasLetter || !hasDigit) continue
            if (ORDINAL.matches(chunk) || DECADE.matches(chunk) || NUMBER_WITH_UNIT.matches(chunk)) continue
            if (chunk.length >= 5) return true
            shortMixed++
        }
        // "a8-f2-k9" has several short mixed chunks together, and that is just as random.
        return shortMixed >= 2
    }

    private fun String.splitWhere(predicate: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in this) {
            if (predicate(c)) {
                out.add(sb.toString()); sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun looksRandom(token: String): Boolean {
        val latinLetters = token.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (latinLetters) {
            if (token.length >= 10 && token.none { it.lowercaseChar() in "aeiouy" }) return true
            if (token.length >= 12) {
                var flips = 0
                for (i in 1 until token.length) {
                    if (token[i].isUpperCase() != token[i - 1].isUpperCase()) flips++
                }
                if (flips >= 5 && flips * 3 >= token.length) return true
            }
        }
        if (token.length >= 20 && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "+/=_-" }
            && token.any { it.isUpperCase() } && token.any { it.isLowerCase() } && token.any { it in "+/=" }) return true
        return false
    }
}
