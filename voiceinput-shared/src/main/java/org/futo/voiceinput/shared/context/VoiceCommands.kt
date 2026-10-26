package org.futo.voiceinput.shared.context

import java.util.Locale

/** How a command's [VoiceCommandAction.symbol] sits between the words around it. */
enum class CommandSpacing {
    /** A line break, and the next line starts with a capital letter. */
    LINE,
    /** After the previous word, then a space (like "word, next" or "word) next"). */
    AFTER_WORD,
    /** Before the next word, with a space in front (like "word (next" or "word $next"). */
    BEFORE_WORD,
    /** Joins the words around it (like "well-known", "and/or" or "snake_case"). */
    JOIN,
    /** With a space on both sides (like "rock & roll" or "a — b"). */
    SPACED,
    /** Removes the sentence (or utterance) said just before the command. */
    DELETE_SENTENCE,
    /** Removes the word said just before the command. */
    DELETE_WORD,
}

/**
 * What a spoken command does, in terms of the [symbol] it inserts and its [spacing]. [endsSentence] means the next
 * word starts with a capital letter.
 */
enum class VoiceCommandAction(
    val symbol: String,
    val spacing: CommandSpacing,
    val endsSentence: Boolean = false,
    /** [CommandSpacing.AFTER_WORD] with a space in front ("soon :)"). */
    val spaceBefore: Boolean = false,
) {
    NEW_PARAGRAPH("\n\n", CommandSpacing.LINE),
    NEW_LINE("\n", CommandSpacing.LINE),
    PERIOD(".", CommandSpacing.AFTER_WORD, endsSentence = true),
    COMMA(",", CommandSpacing.AFTER_WORD),
    QUESTION_MARK("?", CommandSpacing.AFTER_WORD, endsSentence = true),
    EXCLAMATION_MARK("!", CommandSpacing.AFTER_WORD, endsSentence = true),
    COLON(":", CommandSpacing.AFTER_WORD),
    SEMICOLON(";", CommandSpacing.AFTER_WORD),
    ELLIPSIS("…", CommandSpacing.AFTER_WORD),
    DASH("—", CommandSpacing.SPACED),
    HYPHEN("-", CommandSpacing.JOIN),
    OPEN_PARENTHESIS("(", CommandSpacing.BEFORE_WORD),
    CLOSE_PARENTHESIS(")", CommandSpacing.AFTER_WORD),
    OPEN_BRACKET("[", CommandSpacing.BEFORE_WORD),
    CLOSE_BRACKET("]", CommandSpacing.AFTER_WORD),
    OPEN_QUOTE("\"", CommandSpacing.BEFORE_WORD),
    CLOSE_QUOTE("\"", CommandSpacing.AFTER_WORD),
    AMPERSAND("&", CommandSpacing.SPACED),
    AT_SIGN("@", CommandSpacing.BEFORE_WORD),
    HASHTAG("#", CommandSpacing.BEFORE_WORD),
    DOLLAR_SIGN("$", CommandSpacing.BEFORE_WORD),
    PERCENT_SIGN("%", CommandSpacing.AFTER_WORD),
    ASTERISK("*", CommandSpacing.SPACED),
    PLUS_SIGN("+", CommandSpacing.SPACED),
    EQUALS_SIGN("=", CommandSpacing.SPACED),
    SLASH("/", CommandSpacing.JOIN),
    UNDERSCORE("_", CommandSpacing.JOIN),
    SMILEY(":)", CommandSpacing.AFTER_WORD, endsSentence = true, spaceBefore = true),
    FROWN(":(", CommandSpacing.AFTER_WORD, endsSentence = true, spaceBefore = true),
    DELETE_LAST_SENTENCE("", CommandSpacing.DELETE_SENTENCE),
    DELETE_LAST_WORD("", CommandSpacing.DELETE_WORD),
}

/** One spoken command, with the words to say, what they do, and whether it is switched on. */
data class VoiceCommand(val phrase: String, val action: VoiceCommandAction, val enabled: Boolean = true) {
    /** The phrase as normalized words ("New line." -> ["new", "line"]). */
    val words: List<String> = VoiceCommands.phraseWords(phrase)
}

/** The user's list of spoken commands (the "Voice commands" settings page), stored as text. */
object VoiceCommands {
    const val MAX_WORDS = 4

    val DEFAULTS: List<VoiceCommand> = listOf(
        VoiceCommand("new paragraph", VoiceCommandAction.NEW_PARAGRAPH),
        VoiceCommand("paragraph break", VoiceCommandAction.NEW_PARAGRAPH),
        VoiceCommand("new line", VoiceCommandAction.NEW_LINE),
        VoiceCommand("next line", VoiceCommandAction.NEW_LINE),
        VoiceCommand("line break", VoiceCommandAction.NEW_LINE),
        VoiceCommand("period", VoiceCommandAction.PERIOD),
        VoiceCommand("full stop", VoiceCommandAction.PERIOD),
        VoiceCommand("comma", VoiceCommandAction.COMMA),
        VoiceCommand("question mark", VoiceCommandAction.QUESTION_MARK),
        VoiceCommand("exclamation point", VoiceCommandAction.EXCLAMATION_MARK),
        VoiceCommand("exclamation mark", VoiceCommandAction.EXCLAMATION_MARK),
        VoiceCommand("colon", VoiceCommandAction.COLON),
        VoiceCommand("semicolon", VoiceCommandAction.SEMICOLON),
        VoiceCommand("ellipsis", VoiceCommandAction.ELLIPSIS),
        VoiceCommand("dot dot dot", VoiceCommandAction.ELLIPSIS),
        VoiceCommand("em dash", VoiceCommandAction.DASH),
        VoiceCommand("hyphen", VoiceCommandAction.HYPHEN),
        VoiceCommand("open parenthesis", VoiceCommandAction.OPEN_PARENTHESIS),
        VoiceCommand("close parenthesis", VoiceCommandAction.CLOSE_PARENTHESIS),
        VoiceCommand("open bracket", VoiceCommandAction.OPEN_BRACKET),
        VoiceCommand("close bracket", VoiceCommandAction.CLOSE_BRACKET),
        VoiceCommand("open quote", VoiceCommandAction.OPEN_QUOTE),
        VoiceCommand("open quotes", VoiceCommandAction.OPEN_QUOTE),
        VoiceCommand("close quote", VoiceCommandAction.CLOSE_QUOTE),
        VoiceCommand("close quotes", VoiceCommandAction.CLOSE_QUOTE),
        VoiceCommand("end quote", VoiceCommandAction.CLOSE_QUOTE),
        VoiceCommand("unquote", VoiceCommandAction.CLOSE_QUOTE),
        VoiceCommand("ampersand", VoiceCommandAction.AMPERSAND),
        VoiceCommand("at sign", VoiceCommandAction.AT_SIGN),
        VoiceCommand("hashtag", VoiceCommandAction.HASHTAG),
        VoiceCommand("dollar sign", VoiceCommandAction.DOLLAR_SIGN),
        VoiceCommand("percent sign", VoiceCommandAction.PERCENT_SIGN),
        VoiceCommand("asterisk", VoiceCommandAction.ASTERISK),
        VoiceCommand("plus sign", VoiceCommandAction.PLUS_SIGN),
        VoiceCommand("equals sign", VoiceCommandAction.EQUALS_SIGN),
        VoiceCommand("forward slash", VoiceCommandAction.SLASH),
        VoiceCommand("underscore", VoiceCommandAction.UNDERSCORE),
        VoiceCommand("smiley face", VoiceCommandAction.SMILEY),
        VoiceCommand("frowny face", VoiceCommandAction.FROWN),
        VoiceCommand("scratch that", VoiceCommandAction.DELETE_LAST_SENTENCE),
        VoiceCommand("delete that", VoiceCommandAction.DELETE_LAST_SENTENCE),
        VoiceCommand("delete last sentence", VoiceCommandAction.DELETE_LAST_SENTENCE),
        VoiceCommand("delete last word", VoiceCommandAction.DELETE_LAST_WORD),
    )

    /** Lowercase words of [phrase], without punctuation (apostrophes kept). */
    fun phraseWords(phrase: String): List<String> =
        phrase.lowercase(Locale.ROOT).replace('’', '\'')
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

    /** Whether [phrase] can be a command, which takes 1 to [MAX_WORDS] words. */
    fun isValidPhrase(phrase: String): Boolean = phraseWords(phrase).size in 1..MAX_WORDS

    /** The switched-on commands of [commands]. */
    fun enabled(commands: List<VoiceCommand>): List<VoiceCommand> = commands.filter { it.enabled }

    /** One command per line as `ACTION<TAB>phrase`, plus `<TAB>0` when it is switched off. */
    fun serialize(commands: List<VoiceCommand>): String =
        commands.joinToString("\n") {
            "${it.action.name}\t${phraseWords(it.phrase).joinToString(" ")}" + if (it.enabled) "" else "\t0"
        }

    /** Parses [serialize]'s format, and blank text means [DEFAULTS]. Unknown or invalid lines are skipped. */
    fun parse(text: String): List<VoiceCommand> {
        if (text.isBlank()) return DEFAULTS
        val out = ArrayList<VoiceCommand>()
        for (line in text.lines()) {
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val action = VoiceCommandAction.entries.firstOrNull { it.name == parts[0] } ?: continue
            val phrase = parts[1]
            if (!isValidPhrase(phrase)) continue
            if (out.any { it.words == phraseWords(phrase) }) continue
            out.add(VoiceCommand(phrase, action, enabled = parts.getOrNull(2) != "0"))
        }
        return out
    }
}
