package org.futo.voiceinput.shared.context

/**
 * The language model operations the context stage needs (implemented natively by
 * [LlamaContextLm], and tests use a fake). Token sequences always start with the prompt prefix.
 *
 * The implementation keeps one "base" sequence (sequence 0) in its KV cache:
 *  * [evalBase] makes the base [tokens], and the first [keep] tokens may be reused from the
 *    previous base if they are the same, the rest is decoded. It returns, for every index i
 *    in [firstReturned, n), `log P(tokens[i] | tokens[<i])` followed by the log-probs of the
 *    extra ids `wantIds[wantOff[i] until wantOff[i + 1]]` at position i, where firstReturned
 *    is the first returned entry (>= max(keep + 1, needFrom), and also returned).
 *  * [evalVariants] scores sequences that share their first `starts[v]` tokens with the
 *    base and returns the log-probs of tokens `[starts[v], n)` of each.
 */
interface ContextLm : AutoCloseable {
    fun tokenize(text: String, addSpecial: Boolean): IntArray

    /** The raw bytes a token stands for (may be part of a UTF-8 sequence). */
    fun pieceBytes(token: Int): ByteArray

    /** Makes sure sequences of [nTokens] tokens (base + one variant tail) fit. */
    fun ensureCapacity(nTokens: Int)

    class BaseEval(val firstReturned: Int, val values: FloatArray)

    fun evalBase(tokens: IntArray, keep: Int, needFrom: Int, wantOff: IntArray, wantIds: IntArray): BaseEval

    fun evalVariants(variants: List<IntArray>, starts: IntArray): List<FloatArray>

    /** Asks a running evaluation to stop (it then throws). Set false again to allow new work. */
    fun setAbort(abort: Boolean) {}
}

class ContextLmException(message: String) : RuntimeException(message)
