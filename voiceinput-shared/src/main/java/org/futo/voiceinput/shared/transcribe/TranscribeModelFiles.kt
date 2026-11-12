package org.futo.voiceinput.shared.transcribe

import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.modelpackage.ModelPackageManifest
import java.io.File

/**
 * Files of a `transcribe` model package: one GGUF converted by transcribe.cpp
 * (huggingface.co/handy-computer), next to futo-model.json.
 */
object TranscribeModelFiles {
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** The package's model file, or null if there isn't exactly one. */
    fun modelFile(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }?.singleOrNull()

    /** Problems with the package in [dir] (empty if it looks usable). */
    fun validate(dir: File): List<String> {
        val ggufs = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }.orEmpty()
        if (ggufs.size != 1) return listOf("expected one .gguf model, found ${ggufs.size}")
        val head = ByteArray(4)
        val n = ggufs[0].inputStream().use { it.read(head) }
        if (n != 4 || !head.contentEquals(GGUF_MAGIC)) return listOf("${ggufs[0].name} is not a GGUF file")
        return emptyList()
    }

    /**
     * What the packaged model does well itself. A known family (from the package id, such as
     * "nemotron-speech-streaming-en-0.6b") gets the app's own profile, which is tuned on the
     * benchmarks and newer than any package already imported; the manifest's `capabilities` only
     * describe models the app doesn't know. Without them an unknown model is assumed to need every
     * fix, which is the safe side.
     */
    fun profile(manifest: ModelPackageManifest): VoiceModelProfile {
        val id = manifest.id.lowercase()
        return when {
            id.startsWith("nemotron") -> VoiceModelProfile.NEMOTRON
            id.startsWith("parakeet") -> VoiceModelProfile.PARAKEET
            id.startsWith("moonshine") -> VoiceModelProfile.MOONSHINE
            else -> VoiceModelProfile(VoiceModelProfile.Quality.WEAK, VoiceModelProfile.Quality.WEAK, pauseSegments = true, streaming = false)
                .withOverrides(manifest.capabilities)
        }
    }
}
