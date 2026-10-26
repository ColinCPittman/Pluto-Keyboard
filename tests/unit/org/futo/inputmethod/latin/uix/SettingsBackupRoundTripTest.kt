package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.heatmap.HeatmapModel
import org.futo.inputmethod.latin.heatmap.TapSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export -> import of the parts of a settings backup that Pluto adds, meaning its DataStore settings
 * (voice engine, punctuation, and Smart corrections switches and lists),
 * the typing heatmap and the hidden learned words. Entries are written and read the way
 * [SettingsExporter] does it.
 */
class SettingsBackupRoundTripTest {
    /** Every Pluto DataStore setting, each set to something other than its default. */
    private val plutoSettings: List<Preferences.Pair<*>> = listOf(
        VOICE_INPUT_ENGINE.key to VOICE_ENGINE_MOONSHINE,
        MOONSHINE_MODEL_ID.key to "moonshine-base-en",
        MOONSHINE_ACCELERATOR.key to "XNNPACK",
        MOONSHINE_THREADS.key to 3,
        VOICE_INPUT_SHOW_TIMING.key to true,
        PUNCTUATION_RESTORATION_ENABLED.key to true,
        PUNCTUATION_MODEL_ID.key to "punct-1",
        CONTEXT_MODEL_ENABLED.key to true,
        CONTEXT_MODEL_ID.key to "context-1",
        SMART_CORRECTIONS_SOUND_ALIKE.key to false,
        SMART_CORRECTIONS_NAMES.key to false,
        SMART_CORRECTIONS_NUMBERS.key to true,
        SMART_CORRECTIONS_COMMANDS.key to true,
        SMART_CORRECTIONS_FILLERS.key to true,
        SMART_CORRECTIONS_SELF_CORRECTIONS.key to true,
        SMART_CORRECTIONS_QUOTES.key to false,
        SMART_CORRECTIONS_EMOJI.key to false,
        SMART_CORRECTIONS_COMMAND_LIST.key to "NEW_LINE\tnext line\nPERIOD\tdot",
        SMART_CORRECTIONS_CORRECTION_PHRASES.key to "or rather\nno wait\t0",
        SMART_CORRECTIONS_CORRECTION_PHRASES_BUILT_IN.key to false,
        SMART_CORRECTIONS_FILLER_LIST.key to "basically\num\t0",
        SMART_CORRECTIONS_FILLER_BUILT_IN.key to false,
        SMART_CORRECTIONS_QUOTE_TRIGGERS.key to "goes\nsaid\t0",
        SMART_CORRECTIONS_QUOTE_TRIGGERS_BUILT_IN.key to false,
        SMART_CORRECTIONS_EMOJI_LIST.key to "lol\t😂\nthumbs up\t0",
        SMART_CORRECTIONS_EMOJI_BUILT_IN.key to false,
        TYPING_FIX_MISSED_TYPOS.key to false,
    )

    private fun plutoPreferences(): Preferences = mutablePreferencesOf(*plutoSettings.toTypedArray())

    private fun heatmapJson(): String = HeatmapModel().apply {
        addSamples("qwerty|portrait", listOf(TapSample('a'.code, 0.1f, 0.2f), TapSample('a'.code, 0.3f, 0.4f)))
    }.toJson()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipOut ->
            for ((name, bytes) in entries) {
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                entries[entry.name] = zipIn.readBytes()
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return entries
    }

    @Test
    fun plutoSettingsSurviveExportAndImport() = runBlocking {
        val backup = zip(mapOf(SettingsExporter.datastoreFileName to SettingsExporter.encodeDatastore(plutoPreferences())))
        val restored = SettingsExporter.decodeDatastore(unzip(backup)[SettingsExporter.datastoreFileName]!!)
        assertEquals(plutoSettings.size, restored.asMap().size)
        assertEquals(plutoPreferences(), restored)
        assertEquals("lol\t😂\nthumbs up\t0", restored[SMART_CORRECTIONS_EMOJI_LIST.key])
        assertEquals(false, restored[TYPING_FIX_MISSED_TYPOS.key])
    }

    @Test
    fun heatmapAndHiddenWordsSurviveExportAndImport() {
        val hidden = listOf("Pluto", "naïve", "xyzzy")
        val backup = unzip(zip(mapOf(
            SettingsExporter.typingHeatmapFileName to heatmapJson().toByteArray(),
            SettingsExporter.hiddenLearnedWordsFileName to SettingsExporter.encodeHiddenWords(hidden),
        )))

        val heatmap = HeatmapModel.fromJson(String(backup[SettingsExporter.typingHeatmapFileName]!!))
        val a = heatmap!!.profile("qwerty|portrait")!!.keys['a'.code]!!
        assertEquals(0.2f, a.meanX, 1e-4f)
        assertEquals(0.3f, a.meanY, 1e-4f)
        assertEquals(hidden, SettingsExporter.decodeHiddenWords(backup[SettingsExporter.hiddenLearnedWordsFileName]!!))
    }

    @Test
    fun olderBackupWithoutPlutoSettingsStillImports() = runBlocking {
        // A FUTO Keyboard backup has its own settings only, no heatmap or hidden-words entries.
        val futoPrefs = mutablePreferencesOf(
            booleanPreferencesKey("animate_bubble") to false,
            stringPreferencesKey("some_futo_setting") to "x",
        )
        val backup = unzip(zip(mapOf(SettingsExporter.datastoreFileName to SettingsExporter.encodeDatastore(futoPrefs))))
        val restored = SettingsExporter.decodeDatastore(backup[SettingsExporter.datastoreFileName]!!)

        assertEquals(false, restored[booleanPreferencesKey("animate_bubble")])
        // Pluto settings are absent, so they read as their defaults.
        assertNull(restored[VOICE_INPUT_ENGINE.key])
        assertNull(restored[SMART_CORRECTIONS_FILLER_LIST.key])
        assertNull(backup[SettingsExporter.typingHeatmapFileName])
        assertNull(backup[SettingsExporter.hiddenLearnedWordsFileName])
    }

    @Test
    fun emptyHiddenWordsDecodeToNothing() {
        assertEquals(emptyList<String>(), SettingsExporter.decodeHiddenWords(ByteArray(0)))
        assertNotNull(SettingsExporter.encodeHiddenWords(emptyList()))
    }
}
