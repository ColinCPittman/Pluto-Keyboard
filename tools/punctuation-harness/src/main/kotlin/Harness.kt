package punctuation.harness

import org.futo.voiceinput.shared.punctuation.PunctuationRestorers
import org.futo.voiceinput.shared.punctuation.RestoreOptions
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI:
 *   restore --engine pcs|horizon --model-dir DIR [--pure] [--no-lowercase] "text ..."   (" | " separates ASR segments)
 *   batch   --engine ... --model-dir DIR --input FILE [--output FILE] [--pure]   (one input per line)
 *   bench   --engine ... --model-dir DIR --input FILE [--runs N]                 (timing per line)
 * Every output is checked: its word sequence (ignoring . , ? ! ; : and case) must equal the input's.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val cmd = args[0]
    var engine = "pcs"
    var modelDir: String? = null
    var input: String? = null
    var output: String? = null
    var runs = 5
    var opts = RestoreOptions()
    val rest = ArrayList<String>()
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--engine" -> engine = args[++i]
            "--model-dir" -> modelDir = args[++i]
            "--input" -> input = args[++i]
            "--output" -> output = args[++i]
            "--runs" -> runs = args[++i].toInt()
            "--pure" -> opts = opts.copy(preserveAsrTerminators = false, keepAsrCapitals = false)
            "--no-lowercase" -> opts = opts.copy(horizonLowercaseInput = false)
            else -> rest.add(args[i])
        }
        i++
    }
    val dir = File(modelDir ?: usage())
    val t0 = System.nanoTime()
    val restorer = PunctuationRestorers.load(engine, dir, null, opts)
    System.err.println("loaded $engine in ${(System.nanoTime() - t0) / 1_000_000} ms")
    restorer.use {
        when (cmd) {
            "restore" -> {
                val text = rest.joinToString(" ")
                // " | " marks ASR segment boundaries (pauses), as Moonshine reports them
                val segments = text.split(" | ")
                val out = if (segments.size > 1) restorer.restoreSegments(segments) else restorer.restore(text)
                println(out)
                if (!sameWords(segments.joinToString(" "), out)) { System.err.println("WORD MISMATCH"); exitProcess(2) }
            }
            "batch" -> {
                val lines = File(input ?: usage()).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                val outLines = ArrayList<String>()
                var bad = 0
                for (l in lines) {
                    val o = restorer.restore(l)
                    outLines.add(o)
                    if (!sameWords(l, o)) { bad++; System.err.println("WORD MISMATCH:\n  in:  $l\n  out: $o") }
                }
                if (output != null) File(output).writeText(outLines.joinToString("\n") + "\n", Charsets.UTF_8)
                else outLines.forEach { println(it) }
                System.err.println("${lines.size} inputs, $bad word-sequence mismatches")
                if (bad > 0) exitProcess(2)
            }
            "bench" -> {
                val lines = File(input ?: usage()).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                for (l in lines) {
                    val wc = l.trim().split(WS).size
                    restorer.restore(l) // warm-up
                    val times = ArrayList<Double>()
                    repeat(runs) {
                        val s = System.nanoTime()
                        restorer.restore(l)
                        times.add((System.nanoTime() - s) / 1e6)
                    }
                    times.sort()
                    println("words=$wc  median=%.1f ms  min=%.1f ms  max=%.1f ms".format(times[times.size / 2], times.first(), times.last()))
                }
            }
            else -> usage()
        }
    }
}

private fun usage(): Nothing {
    System.err.println("usage: restore|batch|bench --engine pcs|horizon --model-dir DIR [--input FILE] [--output FILE] [--pure] [--no-lowercase] [--runs N] [text...]")
    exitProcess(64)
}

private val WS = Regex("\\s+")

private fun words(s: String): List<String> =
    s.split(WS).map { w -> w.filter { it !in ".,?!;:…\"'“”‘’" }.lowercase() }.filter { it.isNotEmpty() }

fun sameWords(a: String, b: String) = words(a) == words(b)
