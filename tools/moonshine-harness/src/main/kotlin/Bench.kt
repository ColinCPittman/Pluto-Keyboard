// Spoken bench (tools/voice-bench): Moonshine on the bench's recordings the way the phone runs it,
// and the punctuation stage on any model's transcripts the way the phone runs it.

import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.futo.voiceinput.shared.engine.SmartFeaturePolicy
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import org.futo.voiceinput.shared.modelpackage.ModelPackageManifest
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingModel
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingTranscriber
import org.futo.voiceinput.shared.moonshine.core.OrtAccelerator
import org.futo.voiceinput.shared.moonshine.core.OrtRuntimeOptions
import org.futo.voiceinput.shared.punctuation.PunctuationRestorer
import org.futo.voiceinput.shared.punctuation.PunctuationRestorers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true }

private fun benchUsage(): Nothing {
    System.err.println(
        """
        usage:
          bench <work> <moonshine-model-dir> <name> [--threads N] [--accel cpu|xnnpack|nnapi] [--limit N]
              Moonshine on <work>/wav/<id>.wav for every line of <work>/items.json, fed like the phone
              (100 ms blocks, the app's default streaming options), writes <work>/testset-<name>.jsonl
          punct <work> <punctuation-model-dir> <from> <to> [--threads N]
              the punctuation stage on <work>/testset-<from>.jsonl, writes <work>/testset-<to>.jsonl
        """.trimIndent()
    )
    exitProcess(2)
}

private fun readJsonl(f: File): List<JsonObject> =
    f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

private fun str(o: JsonObject, key: String): String = o[key]!!.jsonPrimitive.content

private fun strings(o: JsonObject, key: String): List<String> =
    (o[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

/**
 * The WAV as AudioRecognizer hands it to the session: 16-bit samples divided by Short.MAX_VALUE
 * (not 32768 like [readWav16kMono]).
 */
private fun readWavLikePhone(file: File): FloatArray {
    val bytes = file.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file: $file" }
    var pos = 12
    var channels = 0
    var rate = 0
    var bits = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val size = bb.getInt(pos + 4)
        val body = pos + 8
        when (id) {
            "fmt " -> {
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            }
            "data" -> {
                require(channels == 1 && rate == 16000 && bits == 16) {
                    "$file: need 16 kHz mono 16-bit, got $rate Hz $channels ch $bits bit"
                }
                val n = minOf(size, bytes.size - body) / 2
                return FloatArray(n) { bb.getShort(body + it * 2) / Short.MAX_VALUE.toFloat() }
            }
        }
        pos = body + size + (size and 1)
    }
    error("no data chunk in $file")
}

private const val PHONE_BLOCK = 1600  // AudioRecognizer reads the microphone in 100 ms blocks

fun bench(args: List<String>) {
    if (args.size < 3) benchUsage()
    val work = File(args[0])
    val dir = File(args[1])
    val name = args[2]
    var threads = 4
    var accel = OrtAccelerator.CPU
    var limit = Int.MAX_VALUE
    var i = 3
    while (i < args.size) {
        when (args[i]) {
            "--threads" -> threads = args[++i].toInt()
            "--accel" -> accel = OrtAccelerator.valueOf(args[++i].uppercase())
            "--limit" -> limit = args[++i].toInt()
            else -> benchUsage()
        }
        i++
    }
    val items = json.parseToJsonElement(File(work, "items.json").readText(Charsets.UTF_8)).jsonArray.map { it.jsonObject }
    // VoiceInputEngines.resolve() with the settings' defaults (refresh 500 ms, live text on)
    val options = MoonshineStreamingTranscriber.Options(transcriptionInterval = 0.5f, decodeIncompleteLines = true)
    val model = MoonshineStreamingModel.load(dir, OrtRuntimeOptions(accel, threads)) { }
    println("model ${dir.name} loaded in ${model.loadTimeMs} ms, ${model.accelerator}, ${model.threads} threads")
    val outFile = File(work, "testset-$name.jsonl")
    var audioTotal = 0.0
    var computeTotal = 0.0
    var finalizeTotal = 0.0
    var wallTotal = 0.0
    var n = 0
    outFile.bufferedWriter(Charsets.UTF_8).use { out ->
        model.use {
            for (item in items.take(limit)) {
                val id = str(item, "id")
                val wav = File(work, "wav/$id.wav")
                if (!wav.isFile) {
                    System.err.println("recording not found: $wav")
                    exitProcess(1)
                }
                val audio = readWavLikePhone(wav)
                // MoonshineStreamingSession: a fresh VAD and transcriber per dictation, the model stays loaded
                val wall0 = System.nanoTime()
                val transcript = vadForPackage(dir).use { vad ->
                    MoonshineStreamingTranscriber(model, vad, options).use { t ->
                        t.start()
                        var pos = 0
                        while (pos < audio.size) {
                            val len = minOf(PHONE_BLOCK, audio.size - pos)
                            t.addAudio(audio, pos, len)
                            pos += len
                            // the session's worker runs an update after every block it takes
                            t.update()
                        }
                        val tr = t.stop()
                        val s = t.stats
                        audioTotal += s.audioSeconds
                        computeTotal += s.computeSeconds
                        finalizeTotal += s.finalizeSeconds
                        Triple(tr, s.computeSeconds, s.finalizeSeconds)
                    }
                }
                val wall = (System.nanoTime() - wall0) / 1e9
                wallTotal += wall
                val (tr, compute, finalize) = transcript
                // MoonshineStreamingSession.finish() and AudioRecognizer.runStreamingFinish()
                val text = tr.text.trim()
                val segments = tr.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
                val line = buildJsonObject {
                    put("id", id)
                    put("cat", str(item, "cat"))
                    put("say", str(item, "say"))
                    put("input", text)
                    putJsonArray("segments") { segments.forEach { add(JsonPrimitive(it)) } }
                    put("expected", str(item, "expected"))
                    // set 2's extra fields, as bench.py's runs keep them (scoring and the harness read them)
                    for (k in listOf("alt", "personal", "strict")) item[k]?.let { put(k, it) }
                    put("audio_s", "%.2f".format(audio.size / 16000.0).toDouble())
                    put("asr_ms", (compute * 1000).toLong())
                    put("finalize_ms", (finalize * 1000).toLong())
                }
                out.write(line.toString())
                out.write("\n")
                if (n % 25 == 0) println("$n $id ${text}   [${segments.joinToString(" | ")}]")
                n++
            }
        }
    }
    println(
        "%d clips -> %s\naudio %.1f s, compute %.1f s (RTF %.3f), %.0f ms per clip, finalize %.0f ms per clip (wall %.1f s)".format(
            n, outFile, audioTotal, computeTotal, computeTotal / audioTotal, computeTotal * 1000 / n,
            finalizeTotal * 1000 / n, wallTotal
        )
    )
}

private fun vadForPackage(dir: File) =
    File(dir, org.futo.voiceinput.shared.moonshine.core.SileroVad.FILE_NAME).let {
        if (it.isFile) org.futo.voiceinput.shared.moonshine.core.SileroVad(it)
        else org.futo.voiceinput.shared.moonshine.core.EnergySpeechProbability()
    }

fun punct(args: List<String>) {
    if (args.size < 4) benchUsage()
    val work = File(args[0])
    val dir = File(args[1])
    val from = args[2]
    val to = args[3]
    var threads = 4
    var i = 4
    while (i < args.size) {
        when (args[i]) {
            "--threads" -> threads = args[++i].toInt()
            else -> benchUsage()
        }
        i++
    }
    val manifest = File(dir, "futo-model.json").takeIf { it.isFile }
        ?.let { ModelPackageManifest.parse(it.readText(Charsets.UTF_8)) }
    // PunctuationModels.obtainLocked()
    val engine = manifest?.engine ?: PunctuationRestorers.ENGINE_PCS
    for (p in listOf(VoiceModelProfile.MOONSHINE, VoiceModelProfile.NEMOTRON, VoiceModelProfile.PARAKEET)) {
        if (from.startsWith(p.nameForBench()) && !SmartFeaturePolicy.punctuationStage(p, enabled = true)) {
            println("note: the app does not run the punctuation stage after ${p.nameForBench()} (it punctuates itself)")
        }
    }
    val sessionOptions = OrtSession.SessionOptions()
    sessionOptions.setIntraOpNumThreads(threads)
    sessionOptions.setInterOpNumThreads(1)
    sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
    val t0 = System.nanoTime()
    val restorer: PunctuationRestorer = PunctuationRestorers.load(engine, dir, sessionOptions)
    println("punctuation model $engine loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
    val items = readJsonl(File(work, "testset-$from.jsonl"))
    val outFile = File(work, "testset-$to.jsonl")
    var totalMs = 0.0
    var n = 0
    outFile.bufferedWriter(Charsets.UTF_8).use { out ->
        restorer.use {
            for (item in items) {
                val raw = str(item, "input")
                val segments = strings(item, "segments")
                val start = System.nanoTime()
                // AudioRecognizer.deliverResult() skips post-processing for an empty transcript,
                // PunctuationModels.restore() picks the call, VoiceInputEngines.chain() keeps a blank result's input
                val text = if (raw.isEmpty()) raw else {
                    val r = if (segments.size > 1) restorer.restoreSegments(segments) else restorer.restore(raw)
                    r.ifBlank { raw }
                }
                val ms = (System.nanoTime() - start) / 1e6
                totalMs += ms
                val line = buildJsonObject {
                    for ((k, v) in item) if (k != "input") put(k, v)
                    put("input", text)
                    put("asr", raw)
                    put("punct_ms", "%.1f".format(ms).toDouble())
                }
                out.write(line.toString())
                out.write("\n")
                n++
            }
        }
    }
    sessionOptions.close()
    println("%d lines -> %s, %.1f ms per line".format(n, outFile, totalMs / n))
}

private fun VoiceModelProfile.nameForBench() = when (this) {
    VoiceModelProfile.MOONSHINE -> "moonshine"
    VoiceModelProfile.NEMOTRON -> "nemotron"
    VoiceModelProfile.PARAKEET -> "parakeet"
    else -> "?"
}
