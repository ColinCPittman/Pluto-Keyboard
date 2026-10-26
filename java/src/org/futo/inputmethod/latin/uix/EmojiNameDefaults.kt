package org.futo.inputmethod.latin.uix

import android.content.Context
import org.futo.inputmethod.latin.R
import org.futo.voiceinput.shared.context.EmojiNameIndex
import org.futo.voiceinput.shared.context.EmojiNames

/** The built-in emoji names (nicknames and the names in the keyboard's emoji data), read once. */
object EmojiNameDefaults {
    @Volatile private var index: EmojiNameIndex? = null
    @Volatile private var lastMap: Pair<Pair<String, Boolean>, Map<List<String>, String>>? = null

    fun get(context: Context): EmojiNameIndex = index ?: synchronized(this) {
        index ?: EmojiNames.builtIn(
            context.applicationContext.resources.openRawResource(R.raw.gemoji).bufferedReader().use { it.readText() }
        ).also { index = it }
    }

    /**
     * The built-in names (unless [SMART_CORRECTIONS_EMOJI_BUILT_IN] is off) with your changes
     * ([SMART_CORRECTIONS_EMOJI_LIST]) as the name -> emoji map, kept until they change.
     */
    fun nameMap(context: Context, stored: String): Map<List<String>, String> = synchronized(this) {
        // (one build at a time, since a warm-up and a dictation may ask at once)
        val key = stored to context.getSetting(SMART_CORRECTIONS_EMOJI_BUILT_IN)
        lastMap?.let { (k, map) -> if (k == key) return map }
        val start = System.nanoTime()
        val index = get(context)
        val map = EmojiNames.nameMap(index, EmojiNames.parse(stored, index), builtIn = key.second)
        android.util.Log.i("EmojiNameDefaults", "Built ${map.size} emoji names in ${(System.nanoTime() - start) / 1_000_000} ms")
        lastMap = key to map
        return map
    }
}
