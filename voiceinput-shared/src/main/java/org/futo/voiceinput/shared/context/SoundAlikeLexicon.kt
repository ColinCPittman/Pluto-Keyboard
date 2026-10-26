package org.futo.voiceinput.shared.context

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Sound-alike candidates for the context stage. For a word, up to 6 common words with the
 * same number of phonemes and at most one phoneme different (CMUdict), best first. The
 * table is precomputed by tools/context-bench/build_lexicon.py (it reproduces rescore.py's
 * Lexicon.candidates() exactly) and memory-mapped here, so loading costs nothing and only
 * the pages that lookups touch are read.
 *
 * Format version 2 ("FCXLEX02") adds these optional tables after the sound-alike words.
 *  * word boundaries ([boundaryCandidates] finds "into" -> "in to", "in two" and "every day" -> "everyday"),
 *  * case data ([capitalized] finds "jordan" -> "Jordan", and [isLowercaseWord] finds "toaster").
 * A version 1 file ("FCXLEX01", packages made before these options) has only the sound-alike
 * words, and the other lookups then find nothing ([hasBoundaries] / [hasCaseData] are false).
 *
 * Table layout (little-endian):
 *   u32 nVocab, u32 nKeys, u32 nCands, u32 vocabBytes, u32 keyBytes,
 *   u32 vocabOff[nVocab + 1], vocab UTF-8, u32 keyOff[nKeys + 1], key UTF-8 (sorted by bytes),
 *   u32 candOff[nKeys + 1], u16 cand[nCands] (vocab indices)
 * Version 1 is "FCXLEX01" + one table. Version 2 is "FCXLEX02", u32 nSections,
 *   nSections x (4-byte tag, u32 offset, u32 length), sections (tables) SNDL, BNDR, CAPS, LOWR.
 */
class SoundAlikeLexicon private constructor(buf: ByteBuffer) {
    /** One memory-mapped table of sorted keys with candidate lists. */
    private class Table(private val buf: ByteBuffer) {
        val vocabSize: Int
        val size: Int
        private val vocabOffPos: Int
        private val vocabBlobPos: Int
        private val keyOffPos: Int
        private val keyBlobPos: Int
        private val candOffPos: Int
        private val candPos: Int

        init {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            if (buf.capacity() < 20) throw IOException("corrupt sound-alike lexicon")
            vocabSize = buf.getInt(0)
            size = buf.getInt(4)
            val nCands = buf.getInt(8)
            val vocabBytes = buf.getInt(12)
            val keyBytes = buf.getInt(16)
            vocabOffPos = 20
            vocabBlobPos = vocabOffPos + 4 * (vocabSize + 1)
            keyOffPos = vocabBlobPos + vocabBytes
            keyBlobPos = keyOffPos + 4 * (size + 1)
            candOffPos = keyBlobPos + keyBytes
            candPos = candOffPos + 4 * (size + 1)
            val end = candPos.toLong() + 2L * nCands
            if (vocabSize < 0 || size < 0 || nCands < 0 || vocabBytes < 0 || keyBytes < 0 || end != buf.capacity().toLong()) {
                throw IOException("corrupt sound-alike lexicon")
            }
        }

        private fun bytesAt(offPos: Int, blobPos: Int, i: Int): Pair<Int, Int> {
            val a = buf.getInt(offPos + 4 * i)
            val b = buf.getInt(offPos + 4 * (i + 1))
            return (blobPos + a) to (b - a)
        }

        private fun string(offPos: Int, blobPos: Int, i: Int): String {
            val (start, len) = bytesAt(offPos, blobPos, i)
            val out = ByteArray(len)
            for (k in 0 until len) out[k] = buf.get(start + k)
            return String(out, Charsets.UTF_8)
        }

        /** Index of [key] (UTF-8 bytes) among the keys, or -1. */
        fun find(key: ByteArray): Int {
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val (start, len) = bytesAt(keyOffPos, keyBlobPos, mid)
                var cmp = 0
                val n = minOf(len, key.size)
                var k = 0
                while (k < n) {
                    val x = buf.get(start + k).toInt() and 0xff
                    val y = key[k].toInt() and 0xff
                    if (x != y) { cmp = x - y; break }
                    k++
                }
                if (cmp == 0) cmp = len - key.size
                when {
                    cmp < 0 -> lo = mid + 1
                    cmp > 0 -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        fun find(key: String) = find(key.toByteArray(Charsets.UTF_8))

        fun candidatesAt(i: Int): List<String> {
            val a = buf.getInt(candOffPos + 4 * i)
            val b = buf.getInt(candOffPos + 4 * (i + 1))
            return (a until b).map { string(vocabOffPos, vocabBlobPos, buf.getShort(candPos + 2 * it).toInt() and 0xffff) }
        }

        fun candidates(key: String): List<String> {
            val i = find(key)
            return if (i < 0) emptyList() else candidatesAt(i)
        }

        fun keyAt(i: Int): String = string(keyOffPos, keyBlobPos, i)
    }

    private val words: Table
    private val boundaries: Table?
    private val caps: Table?
    private val lower: Table?

    /** Format version of the file (1 or 2). */
    val version: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (buf.capacity() < 8) throw IOException("not a sound-alike lexicon")
        val magic = ByteArray(8)
        buf.position(0)
        buf.get(magic)
        when (String(magic, Charsets.US_ASCII)) {
            MAGIC -> {
                version = 1
                words = Table(slice(buf, 8, buf.capacity() - 8))
                boundaries = null
                caps = null
                lower = null
            }
            MAGIC_V2 -> {
                version = 2
                val n = buf.getInt(8)
                if (n < 1 || n > 64 || 12 + 12L * n > buf.capacity()) throw IOException("corrupt sound-alike lexicon")
                val sections = HashMap<String, Table>()
                for (s in 0 until n) {
                    val tag = ByteArray(4)
                    for (k in 0 until 4) tag[k] = buf.get(12 + 12 * s + k)
                    val off = buf.getInt(12 + 12 * s + 4)
                    val len = buf.getInt(12 + 12 * s + 8)
                    if (off < 0 || len < 0 || off.toLong() + len > buf.capacity()) throw IOException("corrupt sound-alike lexicon")
                    sections[String(tag, Charsets.US_ASCII)] = Table(slice(buf, off, len))
                }
                words = sections[TAG_WORDS] ?: throw IOException("sound-alike lexicon without words")
                boundaries = sections[TAG_BOUNDARIES]
                caps = sections[TAG_CAPS]
                lower = sections[TAG_LOWER]
            }
            else -> throw IOException("not a sound-alike lexicon")
        }
    }

    val vocabSize: Int get() = words.vocabSize
    val size: Int get() = words.size

    /** Whether the file has word-boundary data (version 2). */
    val hasBoundaries: Boolean get() = boundaries != null && boundaries.size > 0

    /** Whether the file has case data (version 2). */
    val hasCaseData: Boolean get() = caps != null || lower != null

    /**
     * Candidates for [word] (any case), lowercase, best first, and empty for function words, words
     * with digits and words without sound-alikes (same rules as rescore.py's Lexicon).
     */
    fun candidates(word: String): List<String> {
        val w = TranscriptGuard.normWord(word)
        if (w in TranscriptGuard.FUNCTION_LOCK || w.length < 2 || w.any { it.isDigit() }) return emptyList()
        return words.candidates(w)
    }

    /**
     * Word-boundary alternatives of [phrase] (one word, or two words separated by one space, in any
     * case), lowercase, best first, for example "into" -> ["in to", "in two", ...] and "every day" -> ["everyday"].
     * Empty without boundary data.
     */
    fun boundaryCandidates(phrase: String): List<String> {
        val b = boundaries ?: return emptyList()
        val key = phrase.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { TranscriptGuard.normWord(it) }
        if (key.isEmpty() || key.any { it.isDigit() }) return emptyList()
        return b.candidates(key)
    }

    /** The usual capitalized form of [word] if it is mostly written capitalized ("jordan" -> "Jordan"), else null. */
    fun capitalized(word: String): String? = caps?.candidates(TranscriptGuard.normWord(word))?.firstOrNull()

    /** Whether [word] is mostly written in lowercase ("toaster", and not "jordan", "mark" or "may"). */
    fun isLowercaseWord(word: String): Boolean = (lower?.find(TranscriptGuard.normWord(word)) ?: -1) >= 0

    /** Every mostly-lowercase word, in byte order, which are the candidates for a misspelled word (loaded on first use). */
    val lowercaseWords: List<String> by lazy { lower?.let { t -> List(t.size) { t.keyAt(it) } } ?: emptyList() }

    /** Whether [word] is a word the lexicon knows in any case. */
    fun knows(word: String): Boolean {
        val w = TranscriptGuard.normWord(word)
        return isLowercaseWord(w) || capitalized(w) != null || words.find(w) >= 0
    }

    /** The lookup word with index [i] (for tests and tools). */
    fun keyAt(i: Int): String = words.keyAt(i)

    /** Number of word-boundary keys (tools). */
    val boundarySize: Int get() = boundaries?.size ?: 0

    /** The boundary key with index [i] (tools). */
    fun boundaryKeyAt(i: Int): String = boundaries!!.keyAt(i)

    companion object {
        const val MAGIC = "FCXLEX01"
        const val MAGIC_V2 = "FCXLEX02"
        private const val TAG_WORDS = "SNDL"
        private const val TAG_BOUNDARIES = "BNDR"
        private const val TAG_CAPS = "CAPS"
        private const val TAG_LOWER = "LOWR"

        /** Whether [header] (the first 8 bytes of a file) is a lexicon this app can read. */
        fun isLexiconMagic(header: String) = header == MAGIC || header == MAGIC_V2

        private fun slice(buf: ByteBuffer, off: Int, len: Int): ByteBuffer {
            val d = buf.duplicate()
            d.position(off)
            d.limit(off + len)
            return d.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        /** Memory-maps [file] (the mapping stays valid after the file is closed). */
        fun open(file: File): SoundAlikeLexicon = RandomAccessFile(file, "r").use { raf ->
            SoundAlikeLexicon(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
        }

        fun fromBytes(bytes: ByteArray): SoundAlikeLexicon = SoundAlikeLexicon(ByteBuffer.wrap(bytes))

        private fun tableBytes(table: Map<String, List<String>>): ByteArray {
            val vocab = table.values.flatten().toSortedSet().toList()
            val vidx = vocab.withIndex().associate { it.value to it.index }
            val keys = table.keys.sortedWith { a, b -> compareBytes(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8)) }
            fun blob(words: List<String>): Pair<IntArray, ByteArray> {
                val offs = IntArray(words.size + 1)
                val out = java.io.ByteArrayOutputStream()
                for ((i, w) in words.withIndex()) {
                    out.write(w.toByteArray(Charsets.UTF_8))
                    offs[i + 1] = out.size()
                }
                return offs to out.toByteArray()
            }
            val (voff, vblob) = blob(vocab)
            val (koff, kblob) = blob(keys)
            val coff = IntArray(keys.size + 1)
            val cands = ArrayList<Int>()
            for ((i, k) in keys.withIndex()) {
                table.getValue(k).forEach { cands.add(vidx.getValue(it)) }
                coff[i + 1] = cands.size
            }
            val size = 20 + 4 * voff.size + vblob.size + 4 * koff.size + kblob.size + 4 * coff.size + 2 * cands.size
            val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(vocab.size).putInt(keys.size).putInt(cands.size).putInt(vblob.size).putInt(kblob.size)
            voff.forEach { bb.putInt(it) }; bb.put(vblob)
            koff.forEach { bb.putInt(it) }; bb.put(kblob)
            coff.forEach { bb.putInt(it) }
            cands.forEach { bb.putShort(it.toShort()) }
            return bb.array()
        }

        /** Builds a version 1 lexicon file in memory from word -> candidates (tests, tools). */
        fun build(table: Map<String, List<String>>): ByteArray =
            MAGIC.toByteArray(Charsets.US_ASCII) + tableBytes(table)

        /** Builds a version 2 lexicon file in memory (tests, tools). [lowercase] is a set of words. */
        fun buildV2(
            table: Map<String, List<String>>,
            boundaries: Map<String, List<String>> = emptyMap(),
            capitalized: Map<String, String> = emptyMap(),
            lowercase: Set<String> = emptySet(),
        ): ByteArray {
            val sections = listOf(
                TAG_WORDS to tableBytes(table),
                TAG_BOUNDARIES to tableBytes(boundaries),
                TAG_CAPS to tableBytes(capitalized.mapValues { listOf(it.value) }),
                TAG_LOWER to tableBytes(lowercase.associateWith { emptyList() }),
            )
            val header = 8 + 4 + 12 * sections.size
            val total = header + sections.sumOf { it.second.size }
            val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_V2.toByteArray(Charsets.US_ASCII))
            bb.putInt(sections.size)
            var off = header
            for ((tag, body) in sections) {
                bb.put(tag.toByteArray(Charsets.US_ASCII)).putInt(off).putInt(body.size)
                off += body.size
            }
            for ((_, body) in sections) bb.put(body)
            return bb.array()
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (k in 0 until minOf(a.size, b.size)) {
                val x = a[k].toInt() and 0xff
                val y = b[k].toInt() and 0xff
                if (x != y) return x - y
            }
            return a.size - b.size
        }
    }
}
