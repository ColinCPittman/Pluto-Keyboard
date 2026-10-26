package org.futo.voiceinput.shared.context

/**
 * How likely a typed word is a slip of a real word on a QWERTY keyboard, using an edit distance where
 * a neighbouring key costs less than a key across the keyboard ("ststy" is three neighbour slips
 * from "start"). [TypingCorrector] adds the LM's view of each candidate in its sentence, so the
 * letters typed and the words around them decide together.
 */
object KeyboardTypos {
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** Key -> (row, column), columns shifted by half a key per row like a real keyboard. */
    private val pos: Map<Char, Pair<Int, Double>> = buildMap {
        ROWS.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> put(ch, r to c + r * 0.5) } }
    }

    /** Whether [a] and [b] are next to each other on the keyboard. */
    fun adjacent(a: Char, b: Char): Boolean {
        val pa = pos[a] ?: return false
        val pb = pos[b] ?: return false
        return a != b && kotlin.math.abs(pa.first - pb.first) <= 1 && kotlin.math.abs(pa.second - pb.second) <= 1.0
    }

    const val NEIGHBOUR = 1.0
    const val FAR = 2.5
    /** A key pressed twice, or a neighbour of a letter next to it pressed too. */
    const val EXTRA_NEAR = 1.0
    const val EXTRA = 2.0
    const val MISSED = 1.5
    const val SWAPPED = 1.0

    /**
     * The cost of typing [typed] when [meant] was meant (both lowercase), or [Double.POSITIVE_INFINITY]
     * once it is over [max].
     */
    fun cost(typed: String, meant: String, max: Double = Double.MAX_VALUE): Double {
        val n = typed.length
        val m = meant.length
        if (kotlin.math.abs(n - m) * minOf(MISSED, EXTRA_NEAR) > max) return Double.POSITIVE_INFINITY
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + extra(typed, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + MISSED
        for (i in 1..n) {
            var rowMin = Double.POSITIVE_INFINITY
            for (j in 1..m) {
                val a = typed[i - 1]
                val b = meant[j - 1]
                var v = d[i - 1][j - 1] + if (a == b) 0.0 else if (adjacent(a, b)) NEIGHBOUR else FAR
                v = minOf(v, d[i - 1][j] + extra(typed, i - 1), d[i][j - 1] + MISSED)
                if (i > 1 && j > 1 && a == meant[j - 2] && typed[i - 2] == b) v = minOf(v, d[i - 2][j - 2] + SWAPPED)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Double.POSITIVE_INFINITY
        }
        return d[n][m].takeIf { it <= max } ?: Double.POSITIVE_INFINITY
    }

    /** Cost of the extra letter typed at [i] of [typed], which is cheap when it repeats or neighbours a letter next to it. */
    private fun extra(typed: String, i: Int): Double {
        val c = typed[i]
        val near = (i > 0 && (typed[i - 1] == c || adjacent(typed[i - 1], c))) ||
            (i + 1 < typed.length && (typed[i + 1] == c || adjacent(typed[i + 1], c)))
        return if (near) EXTRA_NEAR else EXTRA
    }

    /**
     * Up to [limit] words of [words] that [typed] may be a slip of, cheapest first, at most [max]
     * apart. The first letter is rarely wrong, so a different first letter must be a neighbour.
     */
    fun candidates(typed: String, words: List<String>, max: Double, limit: Int): List<Pair<String, Double>> {
        val t = typed.lowercase()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, Double>>()
        for (w in words) {
            if (w == t || kotlin.math.abs(w.length - t.length) > 2 || w.length < 2) continue
            if (w[0] != t[0] && !adjacent(w[0], t[0]) && !(t.length > 1 && w[0] == t[1])) continue
            val c = cost(t, w, max)
            if (c <= max) out.add(w to c)
        }
        return out.sortedWith(compareBy({ it.second }, { it.first.length })).take(limit)
    }
}
