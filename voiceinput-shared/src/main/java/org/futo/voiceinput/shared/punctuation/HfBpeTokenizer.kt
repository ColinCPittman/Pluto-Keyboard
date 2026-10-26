package org.futo.voiceinput.shared.punctuation

import java.io.File
import java.io.InputStreamReader
import java.io.Reader

/**
 * Minimal loader/encoder for the Hugging Face `tokenizer.json` flavour used by
 * Horizon-Labs/punctuation-restoration-small (a Gemma-style BPE):
 *
 *  - normalizer: Replace(" " -> U+2581)
 *  - pre_tokenizer: Metaspace(replacement U+2581, prepend_scheme=always, split=true)
 *  - model: BPE with byte_fallback (`<0xXX>` pieces), no dropout, no continuing prefix / end-of-word suffix
 *
 * Because the pre-tokenizer splits on every U+2581 boundary, each whitespace-delimited word is
 * encoded independently as "▁" + word, which is what [encodeWord] does.
 *
 * The 34 MB JSON is parsed in a streaming fashion (only `model.vocab`, `model.merges` and a few small
 * fields are retained) so the whole file never sits in memory as a String.
 */
internal class HfBpeTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    private val mergeRanks: LongIntTable,
    val unkId: Int,
    private val byteFallback: Boolean,
) {
    val bosId: Int = vocab["<bos>"] ?: 2
    val eosId: Int = vocab["<eos>"] ?: 1

    /** Id of a vocabulary entry (e.g. "▁hello"), or null. Lower ids are generally more frequent pieces. */
    fun vocabId(piece: String): Int? = vocab[piece]

    private val byteTokenIds = IntArray(256) { vocab[byteTokenName(it)] ?: -1 }

    /** Encodes one whitespace-free word to token ids (the Metaspace prefix is added here). */
    fun encodeWord(word: String): IntArray {
        if (word.isEmpty()) return IntArray(0)
        val text = "▁$word"
        // initial symbols (one per code point, with byte fallback for characters not in the vocabulary)
        val sym = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val s = text.substring(i, i + len)
            val id = vocab[s]
            if (id != null) {
                sym.add(id)
            } else {
                var ok = byteFallback
                val bytes = s.toByteArray(Charsets.UTF_8)
                if (ok) for (b in bytes) if (byteTokenIds[b.toInt() and 0xff] < 0) { ok = false; break }
                if (ok) {
                    for (b in bytes) sym.add(byteTokenIds[b.toInt() and 0xff])
                } else if (!(sym.isNotEmpty() && sym.last() == unkId)) {
                    sym.add(unkId) // fuse_unk
                }
            }
            i += len
        }
        // BPE repeatedly merges the adjacent pair with the lowest rank
        // (a merged symbol must exist in the vocabulary, and ids are tracked while strings are rebuilt via pieceString)
        val strs = ArrayList<String>(sym.size)
        for (id in sym) strs.add(pieceOf(id))
        while (sym.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestPos = -1
            for (p in 0 until sym.size - 1) {
                val r = mergeRanks.get(pairKey(sym[p], sym[p + 1]))
                if (r >= 0 && r < bestRank) { bestRank = r; bestPos = p }
            }
            if (bestPos < 0) break
            val mergedStr = strs[bestPos] + strs[bestPos + 1]
            val mergedId = vocab[mergedStr] ?: break
            sym[bestPos] = mergedId
            strs[bestPos] = mergedStr
            sym.removeAt(bestPos + 1)
            strs.removeAt(bestPos + 1)
        }
        return sym.toIntArray()
    }

    private fun pieceOf(id: Int): String {
        // reverse (id -> piece) table, built lazily on first use
        if (reverse == null) {
            val r = arrayOfNulls<String>(vocab.size + 1024)
            var max = 0
            for ((k, v) in vocab) { if (v >= r.size) continue; r[v] = k; if (v > max) max = v }
            reverse = r
        }
        val r = reverse!!
        return if (id in r.indices) (r[id] ?: "") else ""
    }
    private var reverse: Array<String?>? = null

    companion object {
        fun byteTokenName(b: Int): String = "<0x" + "%02X".format(b) + ">"

        private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        fun load(file: File): HfBpeTokenizer =
            file.inputStream().buffered(1 shl 16).use { InputStreamReader(it, Charsets.UTF_8).use { r -> parse(r) } }

        fun parse(reader: Reader): HfBpeTokenizer {
            val js = JsonStream(reader.buffered(1 shl 16))
            var vocab: HashMap<String, Int>? = null
            var pendingMerges: ArrayList<String>? = null
            var ranks: LongIntTable? = null
            var unkToken = "<unk>"
            var byteFallback = false
            var modelType = ""
            var preTokType = ""
            var metaspaceOk = false

            js.beginObject()
            while (js.hasNextKey()) {
                when (js.nextKey()) {
                    "normalizer" -> {
                        @Suppress("UNCHECKED_CAST")
                        val v = js.readValue() as? Map<String, Any?>
                        if (v != null) {
                            val pat = (v["pattern"] as? Map<*, *>)?.get("String")
                            require(v["type"] == "Replace" && pat == " " && v["content"] == "▁") {
                                "Unsupported tokenizer normalizer: $v"
                            }
                        }
                    }
                    "pre_tokenizer" -> {
                        @Suppress("UNCHECKED_CAST")
                        val v = js.readValue() as? Map<String, Any?>
                        preTokType = (v?.get("type") as? String) ?: ""
                        metaspaceOk = preTokType == "Metaspace" && v?.get("replacement") == "▁" &&
                            v["prepend_scheme"] == "always" && v["split"] == true
                    }
                    "model" -> {
                        js.beginObject()
                        while (js.hasNextKey()) {
                            when (js.nextKey()) {
                                "type" -> modelType = js.readValue() as String
                                "unk_token" -> unkToken = (js.readValue() as? String) ?: unkToken
                                "byte_fallback" -> byteFallback = js.readValue() == true
                                "vocab" -> {
                                    val m = HashMap<String, Int>(1 shl 19)
                                    js.beginObject()
                                    while (js.hasNextKey()) {
                                        val k = js.nextKey()
                                        m[k] = js.readInt()
                                    }
                                    js.endObject()
                                    vocab = m
                                }
                                "merges" -> {
                                    val v = vocab
                                    if (v == null) {
                                        val list = ArrayList<String>()
                                        js.beginArray()
                                        while (js.hasNextElement()) list.add(readMergeAsString(js))
                                        js.endArray()
                                        pendingMerges = list
                                    } else {
                                        val t = LongIntTable(1 shl 20)
                                        js.beginArray()
                                        var rank = 0
                                        while (js.hasNextElement()) {
                                            addMerge(t, v, readMergeAsString(js), rank++)
                                        }
                                        js.endArray()
                                        ranks = t
                                    }
                                }
                                else -> js.skipValue()
                            }
                        }
                        js.endObject()
                    }
                    else -> js.skipValue()
                }
            }
            js.endObject()
            require(modelType == "BPE") { "Unsupported tokenizer model type '$modelType' (only BPE)" }
            require(metaspaceOk) { "Unsupported pre_tokenizer '$preTokType' (only Metaspace/always/split)" }
            val v = requireNotNull(vocab) { "tokenizer.json has no model.vocab" }
            if (ranks == null) {
                val t = LongIntTable(1 shl 20)
                val pm = pendingMerges
                if (pm != null) for ((i, s) in pm.withIndex()) addMerge(t, v, s, i)
                ranks = t
            }
            val unk = v[unkToken] ?: 3
            return HfBpeTokenizer(v, ranks!!, unk, byteFallback)
        }

        private fun readMergeAsString(js: JsonStream): String {
            val v = js.readValue()
            return if (v is String) v else {
                val l = v as List<*>
                "${l[0]} ${l[1]}"
            }
        }

        private fun addMerge(t: LongIntTable, vocab: HashMap<String, Int>, merge: String, rank: Int) {
            val sp = merge.indexOf(' ', 1)
            if (sp < 0) return
            val a = vocab[merge.substring(0, sp)] ?: return
            val b = vocab[merge.substring(sp + 1)] ?: return
            val key = pairKey(a, b)
            if (t.get(key) < 0) t.put(key, rank)
        }
    }
}

/** Open-addressing Long -> Int map (non-negative values), compact for ~600k entries. */
internal class LongIntTable(initialCapacity: Int) {
    private var cap = Integer.highestOneBit(maxOf(16, initialCapacity - 1)) shl 1
    private var keys = LongArray(cap)
    private var vals = IntArray(cap) { -1 }
    private var size = 0

    private fun slot(key: Long, mask: Int): Int {
        var h = key * -7046029254386353131L
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    fun get(key: Long): Int {
        val mask = cap - 1
        var i = slot(key, mask)
        while (vals[i] >= 0) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var i = slot(key, mask)
        while (vals[i] >= 0) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        keys[i] = key; vals[i] = value; size++
    }

    private fun grow() {
        val ok = keys; val ov = vals
        cap = cap shl 1
        keys = LongArray(cap); vals = IntArray(cap) { -1 }
        size = 0
        for (i in ok.indices) if (ov[i] >= 0) put(ok[i], ov[i])
    }
}

/** Small streaming JSON reader (enough for tokenizer.json). */
internal class JsonStream(private val r: Reader) {
    private var peeked = -2

    private fun peek(): Int {
        if (peeked == -2) peeked = r.read()
        return peeked
    }

    private fun next(): Int {
        val c = peek()
        peeked = -2
        return c
    }

    private fun skipWs() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return
        }
    }

    private fun expect(ch: Char) {
        skipWs()
        val c = next()
        require(c == ch.code) { "JSON: expected '$ch' but got ${if (c < 0) "EOF" else c.toChar().toString()}" }
    }

    private var firstInContainer = ArrayDeque<Boolean>()

    fun beginObject() { expect('{'); firstInContainer.addLast(true) }
    fun endObject() { expect('}'); firstInContainer.removeLast() }
    fun beginArray() { expect('['); firstInContainer.addLast(true) }
    fun endArray() { expect(']'); firstInContainer.removeLast() }

    private fun hasNext(close: Char): Boolean {
        skipWs()
        if (peek() == close.code) return false
        if (!firstInContainer.last()) {
            expect(',')
        } else {
            firstInContainer[firstInContainer.size - 1] = false
        }
        return true
    }

    fun hasNextKey() = hasNext('}')
    fun hasNextElement() = hasNext(']')

    fun nextKey(): String {
        val k = readString()
        expect(':')
        return k
    }

    fun readInt(): Int {
        skipWs()
        var v = 0L
        var neg = false
        if (peek() == '-'.code) { neg = true; next() }
        while (true) {
            val c = peek()
            if (c >= '0'.code && c <= '9'.code) { v = v * 10 + (c - '0'.code); next() } else break
        }
        return if (neg) (-v).toInt() else v.toInt()
    }

    fun readString(): String {
        skipWs()
        require(next() == '"'.code) { "JSON: expected string" }
        val sb = StringBuilder()
        while (true) {
            val c = next()
            require(c >= 0) { "JSON: unterminated string" }
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    when (val e = next()) {
                        'n'.code -> sb.append('\n')
                        't'.code -> sb.append('\t')
                        'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000c')
                        'u'.code -> {
                            var cp = 0
                            repeat(4) { cp = cp * 16 + Character.digit(next(), 16) }
                            sb.append(cp.toChar())
                        }
                        else -> sb.append(e.toChar()) // " \ /
                    }
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    /** Reads any value into Map / List / String / Double / Boolean / null. Use only for small values. */
    fun readValue(): Any? {
        skipWs()
        return when (val c = peek()) {
            '{'.code -> {
                val m = LinkedHashMap<String, Any?>()
                beginObject()
                while (hasNextKey()) { val k = nextKey(); m[k] = readValue() }
                endObject()
                m
            }
            '['.code -> {
                val l = ArrayList<Any?>()
                beginArray()
                while (hasNextElement()) l.add(readValue())
                endArray()
                l
            }
            '"'.code -> readString()
            't'.code -> { repeat(4) { next() }; true }
            'f'.code -> { repeat(5) { next() }; false }
            'n'.code -> { repeat(4) { next() }; null }
            else -> {
                require(c == '-'.code || (c >= '0'.code && c <= '9'.code)) { "JSON: unexpected character" }
                val sb = StringBuilder()
                while (true) {
                    val d = peek()
                    if (d >= 0 && (d == '-'.code || d == '+'.code || d == '.'.code || d == 'e'.code || d == 'E'.code ||
                            (d >= '0'.code && d <= '9'.code))) { sb.append(d.toChar()); next() } else break
                }
                sb.toString().toDouble()
            }
        }
    }

    fun skipValue() {
        skipWs()
        when (peek()) {
            '{'.code -> {
                beginObject()
                while (hasNextKey()) { nextKey(); skipValue() }
                endObject()
            }
            '['.code -> {
                beginArray()
                while (hasNextElement()) skipValue()
                endArray()
            }
            '"'.code -> readString()
            else -> readValue()
        }
    }
}
