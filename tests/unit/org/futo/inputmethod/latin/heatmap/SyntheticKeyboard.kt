package org.futo.inputmethod.latin.heatmap

/**
 * A synthetic QWERTY letter layout resembling what LayoutEngine produces, with 100x150px cells,
 * visual keys of 90x130px at the top-left of each cell, hitbox = whole cell, and the 'a'/'l'
 * hitboxes extended to the keyboard edges. Row 2 has (non-letter) shift and delete keys on the
 * sides, which are not part of the heatmap layout.
 */
object SyntheticKeyboard {
    const val CELL_W = 100
    const val CELL_H = 150
    const val KEY_W = 90
    const val KEY_H = 130

    class SynthKey(
        val code: Int, val x: Int, val y: Int,
        val hitLeft: Int, val hitTop: Int, val hitRight: Int, val hitBottom: Int
    ) {
        val centerX get() = x + KEY_W / 2f
        val centerY get() = y + KEY_H / 2f
        fun contains(px: Int, py: Int) = px >= hitLeft && px < hitRight && py >= hitTop && py < hitBottom
    }

    val keys: List<SynthKey> = buildList {
        "qwertyuiop".forEachIndexed { i, c ->
            val x = i * CELL_W
            add(SynthKey(c.code, x, 0, x, 0, x + CELL_W, CELL_H))
        }
        "asdfghjkl".forEachIndexed { i, c ->
            val x = 50 + i * CELL_W
            val left = if (i == 0) 0 else x
            val right = if (i == 8) 1000 else x + CELL_W
            add(SynthKey(c.code, x, CELL_H, left, CELL_H, right, 2 * CELL_H))
        }
        "zxcvbnm".forEachIndexed { i, c ->
            val x = 150 + i * CELL_W
            add(SynthKey(c.code, x, 2 * CELL_H, x, 2 * CELL_H, x + CELL_W, 3 * CELL_H))
        }
    }

    fun key(c: Char): SynthKey = keys.first { it.code == c.code }

    fun geometry(code: Int): KeyGeometry? = keys.firstOrNull { it.code == code }?.let {
        KeyGeometry(it.centerX, it.centerY, KEY_W.toFloat(), KEY_H.toFloat())
    }

    fun layout(params: (Int) -> EffectiveKeyParams?): HeatmapLayout = HeatmapLayout.build(
        keys.map { LayoutKey(it.code, it.hitLeft, it.hitTop, it.hitRight, it.hitBottom, KEY_W, KEY_H) },
        params
    )

    /** Stock detection, giving the letter key whose hitbox contains the point, or null. */
    fun stockKey(x: Int, y: Int): Char? = keys.firstOrNull { it.contains(x, y) }?.code?.toChar()

    /** Heatmap selection, mirroring HeatmapKeyboardBinding (all letter keys are candidates). */
    fun select(layout: HeatmapLayout, x: Int, y: Int, boostedKeys: Set<Char> = emptySet()): Char? {
        val slots = IntArray(layout.size) { it }
        val boosted = BooleanArray(layout.size) { layout.codeAt(it).toChar() in boostedKeys }
        val slot = layout.select(x + 0.5f, y + 0.5f, slots,
            if (boostedKeys.isEmpty()) null else boosted, slots.size)
        return if (slot < 0) null else layout.codeAt(slot).toChar()
    }
}
