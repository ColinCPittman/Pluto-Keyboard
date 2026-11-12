package org.futo.voiceinput.transcribe

import org.futo.voiceinput.shared.engine.FastCores
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.modelpackage.ModelPackageManifest
import org.futo.voiceinput.shared.transcribe.PauseSegments
import org.futo.voiceinput.shared.transcribe.TranscribeModelFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Pause segments from word times, fast cores, and transcribe.cpp packages (Nemotron, Parakeet). */
class TranscribeTest {
    @Test
    fun splitsAtPauses() {
        val words = listOf("Hi", "John,", "new", "line", "how", "are", "you?")
        // a 700 ms pause before "new", and one of 600 ms before "how"
        val times = longArrayOf(0, 200, 250, 600, 1300, 1500, 1520, 1800, 2400, 2550, 2560, 2700, 2710, 2900)
        assertEquals(listOf("Hi John,", "new line", "how are you?"),
            PauseSegments.split("Hi John, new line how are you?", words, times))
    }

    @Test
    fun withoutTimesTheWholeTextIsOneSegment() {
        val words = listOf("Hello", "there.")
        assertEquals(listOf("Hello there."), PauseSegments.split(" Hello there. ", words, LongArray(4)))
        assertEquals(listOf("Hello there."), PauseSegments.split("Hello there.", words, longArrayOf(0, 1)))
        assertEquals(emptyList<String>(), PauseSegments.split("", emptyList(), LongArray(0)))
    }

    @Test
    fun wordsWithSpacesAreTrimmed() {
        assertEquals(listOf("one two", "three"),
            PauseSegments.split("one two three", listOf(" one", " two", " three"), longArrayOf(0, 100, 150, 300, 900, 1000)))
    }

    @Test
    fun aPauseIsMeasuredFromTheLastRealWord() {
        // the empty word has no real times, so the gap is from "Hi" (300 ms), not from 0
        assertEquals(listOf("Hi there"),
            PauseSegments.split("Hi there", listOf("Hi", "", "there"), longArrayOf(0, 300, 0, 0, 600, 800)))
    }

    @Test
    fun parsesKernelCoreLists() {
        assertEquals(listOf(0, 1, 2, 3, 6, 7), FastCores.parseCoreList("0-3,6-7"))
        assertEquals(listOf(5), FastCores.parseCoreList("\t5 "))
        assertNull(FastCores.parseCoreList(""))
        assertNull(FastCores.parseCoreList("3-1"))
        assertNull(FastCores.parseCoreList("0-3,x"))
    }

    @Test
    fun picksTheFastCores() {
        // Dimensity 1200 class: 6 slow cores at 2.0 GHz, 2 fast at 2.6 GHz
        val dimensity = (0..5).associateWith { 2_000_000L } + (6..7).associateWith { 2_600_000L }
        assertArrayEquals(intArrayOf(6, 7), FastCores.fastTier(dimensity))
        assertArrayEquals(intArrayOf(6, 7), FastCores.fastest(dimensity, 2))
        assertArrayEquals(intArrayOf(5, 6, 7), FastCores.fastest(dimensity, 3))
        assertNull(FastCores.fastest(dimensity, 0))
        assertNull(FastCores.fastest(dimensity, 8))
        // 3 little, 4 big and a prime core: the prime and 3 big ones (at most 4)
        val triCluster = (0..2).associateWith { 400L } + (3..6).associateWith { 870L } + mapOf(7 to 1024L)
        assertArrayEquals(intArrayOf(3, 4, 5, 7), FastCores.fastTier(triCluster))
        // one cluster, or cores too close to call slow: nothing to pin to
        assertNull(FastCores.fastTier((0..7).associateWith { 1024L }))
        assertNull(FastCores.fastTier(mapOf(0 to 1000L, 1 to 900L)))
        // core ids with gaps (some cores off limits to the app)
        assertArrayEquals(intArrayOf(7), FastCores.fastTier(mapOf(0 to 500L, 1 to 500L, 7 to 1024L)))
    }

    private fun manifest(id: String, caps: String? = null) = ModelPackageManifest.parse(
        """{"format": 1, "kind": "transcribe", "id": "$id", "name": "x"${caps?.let { ", \"capabilities\": $it" } ?: ""}}""")

    @Test
    fun profilesByFamily() {
        assertEquals(VoiceModelProfile.NEMOTRON, TranscribeModelFiles.profile(manifest("nemotron-speech-streaming-en-0.6b")))
        assertEquals(VoiceModelProfile.PARAKEET, TranscribeModelFiles.profile(manifest("parakeet-tdt_ctc-110m")))
        // an unknown family gets every fix
        assertTrue(!TranscribeModelFiles.profile(manifest("someday-asr")).punctuatesItself)
        // a known family keeps the app's tuned profile (old packages said "casing": "good")
        assertEquals(VoiceModelProfile.NEMOTRON,
            TranscribeModelFiles.profile(manifest("nemotron-speech-streaming-en-0.6b", """{"casing": "good"}""")))
        // the manifest only describes a family the app doesn't know
        assertTrue(TranscribeModelFiles.profile(manifest("someday-asr", """{"punctuation": "good"}""")).punctuatesItself)
    }

    @Test
    fun validatesTheModelFile() {
        val dir = Files.createTempDirectory("transcribe-pkg").toFile()
        try {
            assertEquals(1, TranscribeModelFiles.validate(dir).size)
            dir.resolve("model.gguf").writeBytes("GGUF....".toByteArray())
            assertEquals(emptyList<String>(), TranscribeModelFiles.validate(dir))
            assertEquals("model.gguf", TranscribeModelFiles.modelFile(dir)?.name)
            dir.resolve("model.gguf").writeBytes("nope".toByteArray())
            assertEquals(1, TranscribeModelFiles.validate(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
