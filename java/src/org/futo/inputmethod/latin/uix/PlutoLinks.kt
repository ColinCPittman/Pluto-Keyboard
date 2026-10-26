package org.futo.inputmethod.latin.uix

/** Pluto Keyboard's own web links. Bug reports and feedback go here, not to FUTO. */
@Suppress("HardCodedStringLiteral")
object PlutoLinks {
    const val PROJECT_PAGE = "https://github.com/ColinCPittman/Pluto-Keyboard"
    const val ISSUES = "https://github.com/ColinCPittman/Pluto-Keyboard/issues"

    /** The latest release, where Pluto's model packages (voice, punctuation, smart corrections) are attached. */
    const val RELEASES_LATEST = "https://github.com/ColinCPittman/Pluto-Keyboard/releases/latest"
    private const val RELEASE_DOWNLOAD = RELEASES_LATEST + "/download/"
    const val MODEL_VOICE_MOONSHINE_SMALL_EN = RELEASE_DOWNLOAD + "pluto-voice-moonshine-small-en.zip"
    const val MODEL_PUNCTUATION_EN = RELEASE_DOWNLOAD + "pluto-punctuation-en.zip"
    const val MODEL_SMART_CORRECTIONS_EN = RELEASE_DOWNLOAD + "pluto-smart-corrections-en.zip"

    /** The original app by FUTO that Pluto Keyboard is based on. */
    const val FUTO_KEYBOARD = "https://keyboard.futo.tech/"
    /** FUTO's documentation of the features Pluto Keyboard inherits from FUTO Keyboard. */
    const val FUTO_KEYBOARD_DOCS = "https://docs.keyboard.futo.tech/"
}
