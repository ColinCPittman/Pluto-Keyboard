package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

val ENABLE_SOUND = SettingsKey(
    key = booleanPreferencesKey("enable_sounds"),
    default = true
)

val VERBOSE_PROGRESS = SettingsKey(
    key = booleanPreferencesKey("verbose_progress"),
    default = false
)

val ENABLE_ENGLISH = SettingsKey(
    key = booleanPreferencesKey("enable_english"),
    default = true
)

val ENABLE_MULTILINGUAL = SettingsKey(
    key = booleanPreferencesKey("enable_multilingual"),
    default = false
)

val DISALLOW_SYMBOLS = SettingsKey(
    key = booleanPreferencesKey("disallow_symbols"),
    default = true
)

val PREFER_BLUETOOTH = SettingsKey(
    key = booleanPreferencesKey("prefer_bluetooth_recording"),
    default = false
)

val CAN_EXPAND_SPACE = SettingsKey(
    key = booleanPreferencesKey("can_expand_space"),
    default = true
)

val AUDIO_FOCUS = SettingsKey(
    key = booleanPreferencesKey("request_audio_focus"),
    default = true
)

val USE_VAD_AUTOSTOP = SettingsKey(
    key = booleanPreferencesKey("use_vad_autostop"),
    default = true
)

val ENGLISH_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("english_model_index"),
    default = 0
)

val MULTILINGUAL_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("multilingual_model_index"),
    default = 1
)

val LANGUAGE_TOGGLES = SettingsKey(
    key = stringSetPreferencesKey("enabled_languages"),
    default = setOf()
)

val USE_PERSONAL_DICT = SettingsKey(
    key = booleanPreferencesKey("use_personal_dict_voice_input"),
    default = true
)

val ANIMATE_BUBBLE = SettingsKey(
    key = booleanPreferencesKey("animate_bubble"),
    default = true
)

/** Speech recognition engine for built-in voice input, either [VOICE_ENGINE_WHISPER] or [VOICE_ENGINE_MOONSHINE]. */
val VOICE_INPUT_ENGINE = SettingsKey(
    key = stringPreferencesKey("voice_input_engine"),
    default = VOICE_ENGINE_WHISPER
)

const val VOICE_ENGINE_WHISPER = "whisper"
const val VOICE_ENGINE_MOONSHINE = "moonshine"

/** Id of the selected `moonshine-streaming` model package (empty = first installed one). */
val MOONSHINE_MODEL_ID = SettingsKey(
    key = stringPreferencesKey("moonshine_model_id"),
    default = ""
)

/** ONNX Runtime execution provider for Moonshine, the name of an OrtAccelerator (CPU, XNNPACK, NNAPI). */
val MOONSHINE_ACCELERATOR = SettingsKey(
    key = stringPreferencesKey("moonshine_accelerator"),
    default = "CPU"
)

/** Moonshine CPU threads (0 = automatic). */
val MOONSHINE_THREADS = SettingsKey(
    key = intPreferencesKey("moonshine_threads"),
    default = 0
)

/** Moonshine shows the words while speaking (off means each phrase appears once you pause, which uses less battery). */
val MOONSHINE_LIVE_TEXT = SettingsKey(
    key = booleanPreferencesKey("moonshine_live_text"),
    default = true
)

/** Moonshine's setting for how often the live text is refreshed, in ms of new audio. */
val MOONSHINE_REFRESH_MS = SettingsKey(
    key = intPreferencesKey("moonshine_refresh_ms"),
    default = 500
)

/** Moonshine, with [USE_VAD_AUTOSTOP], decides how long a silence after speech ends the dictation, in ms. */
val MOONSHINE_AUTO_STOP_MS = SettingsKey(
    key = intPreferencesKey("moonshine_auto_stop_ms"),
    default = 2000
)

/** Show audio length / processing time / RTF / accelerator after each Moonshine transcription. */
val VOICE_INPUT_SHOW_TIMING = SettingsKey(
    key = booleanPreferencesKey("voice_input_show_timing"),
    default = false
)

/** Summary of the last Moonshine transcription timing (shown in settings when timing info is on). */
val MOONSHINE_LAST_TIMING = SettingsKey(
    key = stringPreferencesKey("moonshine_last_timing"),
    default = ""
)

/** Run a punctuation-restoration model over the finished transcript before inserting it. */
val PUNCTUATION_RESTORATION_ENABLED = SettingsKey(
    key = booleanPreferencesKey("punctuation_restoration_enabled"),
    default = false
)

/** Id of the selected `punctuation` model package (empty = first installed one). */
val PUNCTUATION_MODEL_ID = SettingsKey(
    key = stringPreferencesKey("punctuation_model_id"),
    default = ""
)

/** Run the context model over the finished transcript (fixes misheard sound-alike words and question marks). */
val CONTEXT_MODEL_ENABLED = SettingsKey(
    key = booleanPreferencesKey("context_model_enabled"),
    default = false
)

/** Id of the selected `context` model package (empty = first installed one). */
val CONTEXT_MODEL_ID = SettingsKey(
    key = stringPreferencesKey("context_model_id"),
    default = ""
)

// Smart corrections options (the "Smart corrections" page under Voice Input), see
// org.futo.voiceinput.shared.context.SmartCorrectionOptions

/** Misheard words, meaning sound-alike words ("bulls" -> "bowls") and word boundaries ("in to" -> "into"). */
val SMART_CORRECTIONS_SOUND_ALIKE = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_sound_alike"),
    default = true
)

/** Names from the personal dictionary / learned words, and capital letters. */
val SMART_CORRECTIONS_NAMES = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_names"),
    default = true
)

/** Spoken numbers, times and amounts as digits. */
val SMART_CORRECTIONS_NUMBERS = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_numbers"),
    default = false
)

/** Spoken commands ("new line", "comma", "scratch that") at pauses. */
val SMART_CORRECTIONS_COMMANDS = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_commands"),
    default = false
)

/** Remove filler words ("um", "uh") and accidental repeats. */
val SMART_CORRECTIONS_FILLERS = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_fillers"),
    default = false
)

/** Fix self-corrections ("the toaster oven, I mean, air fryer" -> "the air fryer"). */
val SMART_CORRECTIONS_SELF_CORRECTIONS = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_self_corrections"),
    default = false
)

/**
 * The user's voice commands, one per line as `ACTION<TAB>phrase`
 * (org.futo.voiceinput.shared.context.VoiceCommands), and empty means the built-in list.
 */
val SMART_CORRECTIONS_COMMAND_LIST = SettingsKey(
    key = stringPreferencesKey("smart_corrections_command_list"),
    default = ""
)

/**
 * Your changes to the self-correction phrases ("I mean", "no wait"), which are your own phrases and built-in
 * ones you switched off, one per line, a switched-off one ending in a tab and "0"
 * (org.futo.voiceinput.shared.context.CorrectionPhrases). Empty means no changes.
 */
val SMART_CORRECTIONS_CORRECTION_PHRASES = SettingsKey(
    key = stringPreferencesKey("smart_corrections_correction_phrases"),
    default = ""
)

/** Whether the built-in self-correction phrases count (off means only your own do, and your list is kept). */
val SMART_CORRECTIONS_CORRECTION_PHRASES_BUILT_IN = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_correction_phrases_built_in"),
    default = true
)

/**
 * Your changes to the filler words ("um", "uh"), meaning your own and built-in ones you switched off, one
 * per line, a switched-off one ending in a tab and "0" (org.futo.voiceinput.shared.context.FillerWords).
 * Empty means no changes.
 */
val SMART_CORRECTIONS_FILLER_LIST = SettingsKey(
    key = stringPreferencesKey("smart_corrections_filler_list"),
    default = ""
)

/** Whether the built-in filler words are removed (off means only your own are, and your list is kept). */
val SMART_CORRECTIONS_FILLER_BUILT_IN = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_filler_built_in"),
    default = true
)

/** Quoted speech, as in `I was like um what are you doing` -> `I was like "Um, what are you doing?"`. */
val SMART_CORRECTIONS_QUOTES = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_quotes"),
    default = true
)

/**
 * Your changes to the phrases that introduce quoted speech ("said", "was like"), meaning your own and
 * built-in ones you switched off, one per line, a switched-off one ending in a tab and "0"
 * (org.futo.voiceinput.shared.context.QuoteTriggers). Empty means no changes.
 */
val SMART_CORRECTIONS_QUOTE_TRIGGERS = SettingsKey(
    key = stringPreferencesKey("smart_corrections_quote_triggers"),
    default = ""
)

/** Whether the built-in quote phrases count (off means only your own do, and your list is kept). */
val SMART_CORRECTIONS_QUOTE_TRIGGERS_BUILT_IN = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_quote_triggers_built_in"),
    default = true
)

/** Emoji by name, as in `thumbs up emoji` -> 👍. */
val SMART_CORRECTIONS_EMOJI = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_emoji"),
    default = true
)

/**
 * Your changes to the emoji names, meaning your own names ("lol" -> 😂), one per line as name, tab, emoji, and
 * built-in names you switched off, ending
 * in a tab and "0" (org.futo.voiceinput.shared.context.EmojiNames). Empty means only the built-in names.
 */
val SMART_CORRECTIONS_EMOJI_LIST = SettingsKey(
    key = stringPreferencesKey("smart_corrections_emoji_list"),
    default = ""
)

/** Whether the built-in emoji names give emoji (off means only your own names do, and your list is kept). */
val SMART_CORRECTIONS_EMOJI_BUILT_IN = SettingsKey(
    key = booleanPreferencesKey("smart_corrections_emoji_built_in"),
    default = true
)

/** How readily each Smart corrections option changes the text, from -2 (fewest changes) to 2 (most), and 0 is the default. */
private fun smartCorrectionsSensitivity(option: String) = SettingsKey(
    key = intPreferencesKey("smart_corrections_sensitivity_$option"),
    default = 0
)

val SMART_CORRECTIONS_SENSITIVITY_SOUND_ALIKE = smartCorrectionsSensitivity("sound_alike")
val SMART_CORRECTIONS_SENSITIVITY_NAMES = smartCorrectionsSensitivity("names")
val SMART_CORRECTIONS_SENSITIVITY_QUESTIONS = smartCorrectionsSensitivity("questions")
val SMART_CORRECTIONS_SENSITIVITY_NUMBERS = smartCorrectionsSensitivity("numbers")
val SMART_CORRECTIONS_SENSITIVITY_COMMANDS = smartCorrectionsSensitivity("commands")
val SMART_CORRECTIONS_SENSITIVITY_FILLERS = smartCorrectionsSensitivity("fillers")
val SMART_CORRECTIONS_SENSITIVITY_SELF_CORRECTIONS = smartCorrectionsSensitivity("self_corrections")
val SMART_CORRECTIONS_SENSITIVITY_QUOTES = smartCorrectionsSensitivity("quotes")
val SMART_CORRECTIONS_SENSITIVITY_EMOJI = smartCorrectionsSensitivity("emoji")
