import org.futo.voiceinput.shared.modelpackage.ModelPackageStore
import org.futo.voiceinput.shared.moonshine.core.EnergySpeechProbability
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingModel
import org.futo.voiceinput.shared.moonshine.core.MoonshineStreamingTranscriber
import org.futo.voiceinput.shared.moonshine.core.OrtAccelerator
import org.futo.voiceinput.shared.moonshine.core.OrtRuntimeOptions
import org.futo.voiceinput.shared.moonshine.core.SileroVad
import org.futo.voiceinput.shared.moonshine.core.SpeechProbabilityModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

private fun usage(): Nothing {
    System.err.println(
        """
        usage:
          transcribe <model-dir> <wav> [--mode final|stream|both] [--chunk-ms 160]
                     [--accel cpu|xnnpack|nnapi] [--threads N] [--no-silero] [--realtime]
                     [--split pause|reference] [--split-overlap-ms N]
          package <zip> <models-root>     import a model package zip (detect, validate, extract, list)

        WAV input must be 16 kHz mono 16-bit PCM.
        """.trimIndent()
    )
    exitProcess(2)
}

fun readWav16kMono(file: File): FloatArray {
    val bytes = file.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
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
                    "need 16 kHz mono 16-bit, got $rate Hz $channels ch $bits bit"
                }
                val n = minOf(size, bytes.size - body) / 2
                return FloatArray(n) { bb.getShort(body + it * 2) / 32768.0f }
            }
        }
        pos = body + size + (size and 1)
    }
    error("no data chunk")
}

private fun vadFor(dir: File, useSilero: Boolean): SpeechProbabilityModel {
    val f = File(dir, SileroVad.FILE_NAME)
    return if (useSilero && f.isFile) SileroVad(f) else EnergySpeechProbability()
}

private fun transcribe(args: List<String>) {
    if (args.size < 2) usage()
    val dir = File(args[0])
    val wav = File(args[1])
    var mode = "both"
    var chunkMs = 160
    var accel = OrtAccelerator.CPU
    var threads = 0
    var silero = true
    var realtime = false
    var options = MoonshineStreamingTranscriber.Options()
    var i = 2
    while (i < args.size) {
        when (args[i]) {
            "--mode" -> mode = args[++i]
            "--chunk-ms" -> chunkMs = args[++i].toInt()
            "--accel" -> accel = OrtAccelerator.valueOf(args[++i].uppercase())
            "--threads" -> threads = args[++i].toInt()
            "--no-silero" -> silero = false
            "--realtime" -> realtime = true
            "--split" -> options = options.copy(vadSplitAtPause = args[++i] == "pause")
            "--split-overlap-ms" -> options = options.copy(vadSplitOverlapDuration = args[++i].toInt() / 1000f)
            else -> usage()
        }
        i++
    }

    val audio = readWav16kMono(wav)
    val audioSec = audio.size / 16000.0
    println("audio: ${wav.name} %.2f s".format(audioSec))

    val model = MoonshineStreamingModel.load(dir, OrtRuntimeOptions(accel, threads)) { println("[load] $it") }
    println(
        "model: ${dir.name} loaded in ${model.loadTimeMs} ms, accelerator=${model.accelerator}" +
            " threads=${model.threads}" + (model.fallbackReason?.let { " (fallback: $it)" } ?: "")
    )

    model.use {
        if (mode == "final" || mode == "both") {
            vadFor(dir, silero).use { vad ->
                MoonshineStreamingTranscriber(model, vad, options).use { t ->
                    model.timings.reset()
                    t.start()
                    t.addAudio(audio)
                    val transcript = t.stop()
                    val s = t.stats
                    println("== final (whole file, one decode per utterance)")
                    transcript.lines.forEach {
                        println("  [%6.2f +%5.2f] %s".format(it.startTime, it.endTime - it.startTime, it.text))
                    }
                    println("RAW:  ${transcript.rawText}")
                    println("TEXT: ${transcript.text}")
                    println("time %.2f s, audio %.2f s, RTF %.3f".format(s.computeSeconds, s.audioSeconds, s.realTimeFactor))
                    println("  ${model.timings}")
                }
            }
        }

        if (mode == "stream" || mode == "both") {
            vadFor(dir, silero).use { vad ->
                MoonshineStreamingTranscriber(model, vad, options).use { t ->
                    println("== streaming (${chunkMs} ms chunks${if (realtime) ", real-time pacing" else ""})")
                    model.timings.reset()
                    t.start()
                    val chunk = 16 * chunkMs
                    var last = ""
                    var maxUpdateMs = 0.0
                    val wallStart = System.nanoTime()
                    var pos = 0
                    while (pos < audio.size) {
                        val n = minOf(chunk, audio.size - pos)
                        t.addAudio(audio, pos, n)
                        pos += n
                        if (realtime) {
                            val due = wallStart + (pos / 16000.0 * 1e9).toLong()
                            val wait = due - System.nanoTime()
                            if (wait > 0) Thread.sleep(wait / 1_000_000)
                        }
                        val u0 = System.nanoTime()
                        val tr = t.update() ?: continue
                        maxUpdateMs = maxOf(maxUpdateMs, (System.nanoTime() - u0) / 1e6)
                        val text = tr.text
                        if (text != last) {
                            println("  %6.2fs | %s".format(pos / 16000.0, text))
                            last = text
                        }
                    }
                    val transcript = t.stop()
                    val s = t.stats
                    transcript.lines.forEach {
                        println("  [%6.2f +%5.2f] %s".format(it.startTime, it.endTime - it.startTime, it.text))
                    }
                    println("RAW:  ${transcript.rawText}")
                    println("TEXT: ${transcript.text}")
                    println(
                        "compute %.2f s over %d updates (max update %.0f ms), audio %.2f s, RTF %.3f, finalize %.0f ms".format(
                            s.computeSeconds, s.updates, maxUpdateMs, s.audioSeconds, s.realTimeFactor, s.finalizeSeconds * 1000
                        )
                    )
                    println("  ${model.timings}")
                }
            }
        }
    }
}

private fun packageTest(args: List<String>) {
    if (args.size < 2) usage()
    val zip = File(args[0])
    val store = ModelPackageStore(File(args[1]))
    val manifest = zip.inputStream().use { ModelPackageStore.detect(it) }
    println("detected: $manifest")
    if (manifest == null) exitProcess(1)
    val installed = zip.inputStream().use { input ->
        store.install(input) { done, total -> if (done == total) println("  extracted $done bytes") }
    }
    println("installed: ${installed.manifest.id} -> ${installed.dir}")
    store.list(manifest.kind).forEach { println("  ${it.manifest.kind} ${it.manifest.id} '${it.manifest.name}' ${it.sizeBytes} bytes") }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "transcribe" -> transcribe(args.drop(1))
        "package" -> packageTest(args.drop(1))
        else -> usage()
    }
}
