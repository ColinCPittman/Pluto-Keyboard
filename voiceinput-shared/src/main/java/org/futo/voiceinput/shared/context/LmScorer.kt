package org.futo.voiceinput.shared.context

/**
 * Port of ctxrun's scoring server (tools/context-bench/ctxrun/ctxrun.cpp, --score) on top
 * of [ContextLm], where every text is scored after a fixed prompt [prefix].
 *
 *  * [detail] is one full pass over a text, which becomes the base. Returns per-token pieces
 *    and log-probs, the log-probs of [alts] tokens at every position (e.g. "?" vs "."), and
 *    "probes", the log-probs of candidate words' first tokens where a word starts. Tokens
 *    shared with the previous base are not decoded again (unless a probe needs them).
 *  * [score] gives total log-probs of variants of the base, and only the tokens after the first
 *    difference are decoded, all variants together.
 */
class LmScorer(private val lm: ContextLm, val prefix: String) {
    private val prefixTokens = lm.tokenize(prefix, true)
    private val p = prefixTokens.size

    private var baseText = IntArray(0)  // text tokens of the base (without the prefix)
    private var baseLp = FloatArray(0)
    private var baseAlt: Array<FloatArray> = emptyArray()
    private var baseAltIds: List<Int>? = null

    /** Milliseconds of every call, in order (like rescore.py's per-item "phone_ms"). */
    val timings = ArrayList<Double>()

    class Detail(
        val text: String,
        val pieces: List<ByteArray>,
        /** Byte offset of each piece in the concatenation of all pieces. */
        val pieceByteOffsets: IntArray,
        val lps: FloatArray,
        /** Per position, the log-probs of the alts tokens. */
        val alts: Array<FloatArray>,
        /** Per probe (same order as requested), the candidates' log-probs then the original token's (empty if not found). */
        val probes: List<FloatArray>,
    ) {
        private val textBytes = text.toByteArray(Charsets.UTF_8)
        private val byteToChar: IntArray = run {
            val map = IntArray(textBytes.size + 1)
            var b = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                for (k in 0 until len) if (b + k < map.size) map[b + k] = i
                b += len
                i += Character.charCount(cp)
            }
            map[textBytes.size] = text.length
            map
        }

        // rescore.py notes the decoded text may start differently, so align on the first 20 characters
        private val shift: Int = run {
            val joined = java.io.ByteArrayOutputStream().also { o -> pieces.forEach { o.write(it) } }.toByteArray()
            val head = text.take(20).toByteArray(Charsets.UTF_8)
            if (startsWith(joined, head, 0)) 0 else maxOf(0, indexOf(joined, head))
        }

        /** Character offset in [text] where piece [i] starts, after its leading whitespace (rescore.py's `c`). */
        fun charOffset(i: Int): Int {
            val b = pieceByteOffsets[i] - shift + leadingWhitespace(pieces[i])
            return when {
                b < 0 -> b  // before the text (cannot happen for the transcript itself)
                b >= textBytes.size -> text.length + (b - textBytes.size)
                else -> byteToChar[b]
            }
        }

        /** The piece without surrounding whitespace, as a string (for "," / "." tests). */
        fun strippedPiece(i: Int): String = String(pieces[i], Charsets.UTF_8).trim { it.isWhitespace() }
    }

    private fun tokens(text: String) = lm.tokenize(text, false)

    /** Number of tokens of [text] (without the prompt). */
    fun tokenCount(text: String): Int = if (text.isEmpty()) 0 else tokens(text).size

    fun detail(text: String, alts: List<String>, probes: List<Pair<Int, List<String>>> = emptyList()): Detail {
        val t0 = System.nanoTime()
        val textToks = tokens(text)
        val n = textToks.size
        val all = prefixTokens + textToks
        lm.ensureCapacity(all.size)
        val altIds = alts.map { a -> tokens(a).firstOrNull() ?: 0 }

        val pieces = textToks.map { lm.pieceBytes(it) }
        val pieceOff = IntArray(n)
        run {
            var off = 0
            for (i in 0 until n) {
                pieceOff[i] = off
                off += pieces[i].size
            }
        }
        // probes as [char offset, words] -> token index starting there (after leading spaces)
        val probeTokens = Array(n) { ArrayList<Int>() }
        val probeWhere = ArrayList<Pair<Int, Int>>()
        for ((c, words) in probes) {
            val cb = text.substring(0, c.coerceIn(0, text.length)).toByteArray(Charsets.UTF_8).size
            var ti = -1
            for (i in 0 until n) {
                if (pieceOff[i] + leadingSpaces(pieces[i]) == cb) { ti = i; break }
            }
            var cnt = 0
            if (ti >= 0) {
                val sp = pieces[ti].isNotEmpty() && pieces[ti][0] == ' '.code.toByte()
                for (w in words) {
                    probeTokens[ti].add(tokens((if (sp) " " else "") + w).firstOrNull() ?: 0)
                    cnt++
                }
            }
            probeWhere.add(ti to cnt)
        }
        // reuse the part shared with the previous base, but probes need fresh logits
        var l = 0
        if (altIds == baseAltIds) {
            while (l < n && l < baseText.size && textToks[l] == baseText[l]) l++
        }
        for ((ti, _) in probeWhere) if (ti >= 0) l = minOf(l, ti)

        val wantOff = IntArray(all.size + 1)
        val wantIds = ArrayList<Int>()
        for (i in all.indices) {
            if (i >= p) {
                wantIds.addAll(altIds)
                wantIds.addAll(probeTokens[i - p])
            }
            wantOff[i + 1] = wantIds.size
        }
        val r = lm.evalBase(all, p + l - 1, p, wantOff, wantIds.toIntArray())
        val first = r.firstReturned - p  // text index of the first fresh entry
        if (first < 0 || first > l) throw ContextLmException("unexpected evalBase layout")

        val lp = FloatArray(n)
        val alt = Array(n) { FloatArray(altIds.size) }
        val probeLp = Array(n) { FloatArray(0) }
        for (i in 0 until first) {
            lp[i] = baseLp[i]
            alt[i] = baseAlt[i]
        }
        var k = 0
        for (i in first until n) {
            lp[i] = r.values[k++]
            for (a in altIds.indices) alt[i][a] = r.values[k++]
            probeLp[i] = FloatArray(probeTokens[i].size) { r.values[k + it] }
            k += probeTokens[i].size
        }
        if (k != r.values.size) throw ContextLmException("unexpected evalBase size")

        val used = IntArray(n)
        val probeOut = probeWhere.map { (ti, cnt) ->
            if (ti < 0) FloatArray(0) else {
                val out = FloatArray(cnt + 1)
                for (q in 0 until cnt) out[q] = probeLp[ti][used[ti]++]
                out[cnt] = lp[ti]  // the last entry is the original token
                out
            }
        }
        baseText = textToks
        baseLp = lp
        baseAlt = alt
        baseAltIds = altIds
        timings.add((System.nanoTime() - t0) / 1e6)
        return Detail(text, pieces, pieceOff, lp, alt, probeOut)
    }

    /** A scored variant, with its text tokens, their log-probs and the total. */
    class Scored(val tokens: IntArray, val lps: FloatArray, val total: Double)

    /** Total log-prob of each variant (all of which must share the prompt with the base). */
    fun score(variants: List<String>): DoubleArray = scoreDetailed(variants).map { it.total }.toDoubleArray()

    /** Like [score], with every variant's tokens and per-token log-probs. */
    fun scoreDetailed(variants: List<String>): List<Scored> {
        val t0 = System.nanoTime()
        val toks = ArrayList<IntArray>()
        val lps = ArrayList<FloatArray>()
        val reqTokens = ArrayList<IntArray>()
        val reqStarts = ArrayList<Int>()
        val reqOf = IntArray(variants.size) { -1 }
        val starts = IntArray(variants.size)
        for ((v, text) in variants.withIndex()) {
            val t = tokens(text)
            toks.add(t)
            var l = 0
            while (l < t.size && l < baseText.size && t[l] == baseText[l]) l++
            val lp = FloatArray(t.size)
            for (i in 0 until l) lp[i] = baseLp[i]
            lps.add(lp)
            starts[v] = l
            if (l < t.size) {
                reqOf[v] = reqTokens.size
                reqTokens.add(prefixTokens + t)
                reqStarts.add(p + l)
            }
        }
        val res = if (reqTokens.isEmpty()) emptyList() else lm.evalVariants(reqTokens, reqStarts.toIntArray())
        val out = ArrayList<Scored>(variants.size)
        for (v in variants.indices) {
            val lp = lps[v]
            if (reqOf[v] >= 0) {
                val tail = res[reqOf[v]]
                for (i in starts[v] until lp.size) lp[i] = tail[i - starts[v]]
            }
            var total = 0.0
            for (x in lp) total += x
            out.add(Scored(toks[v], lp, total))
        }
        timings.add((System.nanoTime() - t0) / 1e6)
        return out
    }

    companion object {
        internal fun leadingSpaces(b: ByteArray): Int {
            var k = 0
            while (k < b.size && b[k] == ' '.code.toByte()) k++
            return k
        }

        /** Python str.lstrip() on the piece (ASCII whitespace). */
        internal fun leadingWhitespace(b: ByteArray): Int {
            var k = 0
            while (k < b.size && (b[k].toInt() and 0xff).let { it == 0x20 || it in 0x09..0x0d || it in 0x1c..0x1f }) k++
            return k
        }

        internal fun startsWith(a: ByteArray, b: ByteArray, at: Int): Boolean {
            if (at + b.size > a.size) return false
            for (k in b.indices) if (a[at + k] != b[k]) return false
            return true
        }

        internal fun indexOf(a: ByteArray, b: ByteArray): Int {
            for (i in 0..a.size - b.size) if (startsWith(a, b, i)) return i
            return -1
        }
    }
}
