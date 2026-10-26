package org.futo.inputmethod.latin.dictionarytools

import android.content.Context
import android.provider.UserDictionary
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.DictionaryCollection
import org.futo.inputmethod.latin.DictionaryFactory
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.personalization.PersonalizationHelper
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import org.futo.inputmethod.latin.uix.getSettingFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

/**
 * Pluto doesn't learn words that look like passwords or keys ([SensitiveWords]). Default on.
 * Checked in GeneralIME.addToHistory, the one place typed words reach the learned words.
 */
val PROTECT_PASSWORD_LIKE_WORDS = SettingsKey(
    booleanPreferencesKey("pluto_protect_password_like_words"),
    true
)

/** Frequency used for words Pluto adds to the Android personal dictionary (same as the add-word dialog). */
private const val PERSONAL_DICTIONARY_FREQUENCY = 250

/**
 * Android glue for Pluto's dictionary tools. It reads the keyboard's learned words (user history
 * dictionaries), checks them against the main dictionaries and the Android personal dictionary,
 * and writes to the personal dictionary only when asked.
 */
object LearnedWords {
    private const val SNAPSHOT_TIMEOUT_MS = 5_000L
    private const val USER_HISTORY_FILE_PREFIX = "UserHistoryDictionary."

    fun hiddenWords(context: Context) = HiddenLearnedWords(File(context.filesDir, HiddenLearnedWords.FILE_NAME))

    /** Locales of the enabled keyboard languages that have a learned-words file. */
    suspend fun localesWithLearnedWords(context: Context): List<Locale> {
        val enabled = context.getSettingFlow(SubtypesSetting).first().map { Subtypes.getLocale(Subtypes.convertToSubtype(it)) }
        return enabled.distinctBy { it.toString() }.filter {
            File(context.filesDir, USER_HISTORY_FILE_PREFIX + it.toString() + ".dict").exists()
        }
    }

    /** All words the keyboard has learned (not just seen once) for [locales]. Slow, so call off the main thread. */
    fun load(context: Context, locales: List<Locale>): List<LearnedWord> {
        val out = ArrayList<LearnedWord>()
        for (locale in locales) {
            val dict = PersonalizationHelper.getUserHistoryDictionary(context, locale, null)
            for (p in dict.getWordPropertiesSnapshot(SNAPSHOT_TIMEOUT_MS)) {
                if (p.mIsNotAWord || p.mIsBeginningOfSentence || p.mIsPossiblyOffensive) continue
                val probability = p.mProbabilityInfo?.mProbability ?: continue
                // Entries the keyboard has only seen (typed once, never picked) have no probability yet.
                if (probability == BinaryDictionary.NOT_A_PROBABILITY || probability <= 0) continue
                out.add(LearnedWord(p.mWord, locale.toString(), probability))
            }
        }
        return out
    }

    /** Builds the "Copy learned words" review list. Slow, so call off the main thread. */
    suspend fun copyCandidates(context: Context): CopyCandidates {
        val locales = localesWithLearnedWords(context)
        val learned = load(context, locales)
        val personal = UserDictionaryIO(context).get()
        val mainDictionaries = HashMap<String, DictionaryCollection>()
        try {
            return LearnedWordCandidates.select(
                learned,
                isKnownWord = { w ->
                    val dict = mainDictionaries.getOrPut(w.locale) {
                        DictionaryFactory.createMainDictionaryFromManager(context, localeFromString(w.locale))
                    }
                    dict.isInDictionary(w.word) || dict.isInDictionary(w.word.lowercase(localeFromString(w.locale)))
                },
                inPersonalDictionary = { w ->
                    personal.any { it.word.equals(w.word, ignoreCase = true) && (it.locale == null || it.locale == w.locale) }
                },
                hidden = hiddenWords(context).all().toSet()
            )
        } finally {
            mainDictionaries.values.forEach { it.close() }
        }
    }

    /** True if [word] is already in the Android personal dictionary for [locale] (or for all languages). */
    fun isInPersonalDictionary(context: Context, word: String, locale: Locale?): Boolean =
        UserDictionaryIO(context).get().any {
            it.word.equals(word, ignoreCase = true) && (it.locale == null || locale == null || it.locale == locale.toString())
        }

    /** Adds [word] to the Android personal dictionary unless it is already there. Returns false if it was. */
    fun addToPersonalDictionary(context: Context, word: String, locale: Locale?): Boolean {
        if (isInPersonalDictionary(context, word, locale)) return false
        UserDictionary.Words.addWord(context, word, PERSONAL_DICTIONARY_FREQUENCY, null, locale)
        return true
    }

    /** Copies [words] to the Android personal dictionary, skipping ones already there. Returns how many were added. */
    fun copyToPersonalDictionary(context: Context, words: List<LearnedWord>): Int {
        val existing = UserDictionaryIO(context).get()
        var added = 0
        words.forEach { w ->
            val present = existing.any { it.word.equals(w.word, ignoreCase = true) && (it.locale == null || it.locale == w.locale) }
            if (!present) {
                UserDictionary.Words.addWord(context, w.word, PERSONAL_DICTIONARY_FREQUENCY, null, localeFromString(w.locale))
                added++
            }
        }
        return added
    }

    /**
     * Removes [word] from the keyboard's learned words for its language, so it stops appearing as a
     * typing suggestion. Doesn't add it to the Word Blacklist, so the keyboard may learn it again if
     * the user picks it again.
     */
    fun forget(context: Context, word: LearnedWord) {
        val locale = localeFromString(word.locale)
        val dict = PersonalizationHelper.getUserHistoryDictionary(context, locale, null)
        listOf(word.word, word.word.lowercase(locale)).distinct().forEach { dict.removeUnigramEntryDynamically(it) }
        dict.asyncFlushBinaryDictionary()
    }
}
