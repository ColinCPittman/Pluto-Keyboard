package org.futo.voiceinput.shared.moonshine.core

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decoder for moonshine's `tokenizer.bin` vocabulary format (port of the
 * decoding half of core/bin-tokenizer/bin-tokenizer.cpp).
 *
 * The file is a sequence of entries, one per token id. Each entry is a length
 * followed by that many UTF-8 bytes. A length < 128 is a single byte. Otherwise
 * it is two bytes, computed as `(second * 128) + first - 128`. A zero length marks an
 * unused id.
 */
class BinTokenizer(data: ByteArray, private val spaceString: String = "▁") {
    private val tokens: List<ByteArray>

    init {
        val list = ArrayList<ByteArray>(32768)
        var offset = 0
        while (offset < data.size) {
            val first = data[offset].toInt() and 0xFF
            offset++
            if (first == 0) {
                list.add(ByteArray(0))
                continue
            }
            val count = if (first < 128) {
                first
            } else {
                if (offset >= data.size) throw MoonshineException("Truncated tokenizer data")
                val second = data[offset].toInt() and 0xFF
                offset++
                second * 128 + first - 128
            }
            if (count > data.size - offset) throw MoonshineException("Truncated tokenizer data")
            list.add(data.copyOfRange(offset, offset + count))
            offset += count
        }
        if (list.isEmpty()) throw MoonshineException("No tokens found in tokenizer data")
        tokens = list
    }

    val size: Int get() = tokens.size

    /**
     * Converts tokens to text. Like the C++ version, special tokens (`<...>`)
     * are skipped, U+2581 becomes a space and the result is trimmed.
     */
    fun tokensToText(ids: IntArray, skipSpecials: Boolean = true): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            val bytes = tokens.getOrNull(id) ?: continue
            if (bytes.isEmpty()) continue
            if (skipSpecials && bytes.size > 2 && bytes[0] == '<'.code.toByte() &&
                bytes[bytes.size - 1] == '>'.code.toByte()
            ) {
                continue
            }
            out.write(bytes)
        }
        return String(out.toByteArray(), Charsets.UTF_8).replace(spaceString, " ").trim()
    }

    companion object {
        fun load(file: File) = BinTokenizer(file.readBytes())
    }
}
