import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.futo.voiceinput.shared.context.ContextModelFiles
import org.futo.voiceinput.shared.context.EmojiNames
import org.futo.voiceinput.shared.context.ContextRescoreConfig
import org.futo.voiceinput.shared.context.ContextRescorer
import org.futo.voiceinput.shared.context.LlamaContextLm
import org.futo.voiceinput.shared.context.PersonalDictionary
import org.futo.voiceinput.shared.context.SmartCorrectionOptions
import org.futo.voiceinput.shared.context.SoundAlikeLexicon
import java.io.File
import kotlin.math.round

private fun usage(): Nothing {
    System.err.println(
        """
        usage:
          context-harness run --lib <libfutoctxlm> (--package-dir <dir> | --model <gguf> --lexicon <lex>)
                              --testset <jsonl> --out <jsonl> [--threads N] [--no-repack] [--fresh]
                              [--options default|all|none|soundalike,names,questions,numbers,commands,fillers,selfcorrections,quotes,emoji] [--gemoji gemoji.json]
                              [--personal <file: one personal-dictionary word per line>] [--only-option <name>]
              items may carry "left" (text already in the field before the dictation), "segments" (ASR utterances), "personal" (personal-dictionary words)
              and "learned" (capitalized words learned from typing)
          context-harness typing --lib <libfutoctxlm> --model <gguf> --testset <jsonl> --out <jsonl> [--threads N]
                              [--margin X] [--opening-margin X] [--tail-min X] [--prompt P] [--own <file: your pairs, "typed<TAB>intended">] [--early] [--max-fixes N]
              Smart corrections for typing: each sentence of an item is checked in turn, as the
              keyboard does when it ends (at most --max-fixes swaps per sentence, default 2)
          context-harness lexicon <lex> [words...]
          context-harness verify <lex> <metaphone-ref.tsv>   (Kotlin vs Python Double Metaphone; lexicon pairs sound alike)
        """.trimIndent()
    )
    kotlin.system.exitProcess(2)
}

private fun r(x: Double, digits: Int): Double {
    var m = 1.0
    repeat(digits) { m *= 10 }
    return round(x * m) / m
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "run" -> run(args.drop(1))
        "lexicon" -> lexicon(args.drop(1))
        "verify" -> verify(args.drop(1))
        "prior" -> prior(args.drop(1))
        "typing" -> typing(args.drop(1))
        "lexdump" -> {
            val lex = SoundAlikeLexicon.open(File(args[1]))
            for (i in 0 until lex.size) { val k = lex.keyAt(i); println(k + "	" + lex.candidates(k).joinToString(" ")) }
        }
        else -> usage()
    }
}

private fun lexicon(args: List<String>) {
    if (args.isEmpty()) usage()
    val t0 = System.nanoTime()
    val lex = SoundAlikeLexicon.open(File(args[0]))
    val tLoad = (System.nanoTime() - t0) / 1e6
    println("loaded ${lex.size} words / ${lex.vocabSize} candidate words in %.2f ms".format(tLoad))
    val words = args.drop(1).ifEmpty { listOf("bulls", "meat", "flower", "whether", "our", "jim") }
    for (w in words) println("$w -> ${lex.candidates(w)} lower=${lex.isLowercaseWord(w)} cap=${lex.capitalized(w)}")
    // lookup speed over all keys
    val t1 = System.nanoTime()
    var n = 0
    for (i in 0 until lex.size) n += lex.candidates(lex.keyAt(i)).size
    val per = (System.nanoTime() - t1) / 1e3 / lex.size
    println("looked up all ${lex.size} words ($n candidates): %.2f us per word".format(per))
}

private fun verify(args: List<String>) {
    if (args.size < 2) usage()
    var bad = 0
    var n = 0
    File(args[1]).forEachLine { line ->
        val parts = line.split("	")
        if (parts.size < 3) return@forEachLine
        n++
        val mine = org.futo.voiceinput.shared.context.DoubleMetaphone.encode(parts[0])
        if (mine != (parts[1] to parts[2])) {
            if (bad < 20) println("metaphone mismatch: ${parts[0]} py=${parts[1]}/${parts[2]} kt=${mine.first}/${mine.second}")
            bad++
        }
    }
    println("Double Metaphone: $n words, $bad mismatches")
    val lex = SoundAlikeLexicon.open(File(args[0]))
    var pairs = 0
    var rejected = 0
    for (i in 0 until lex.size) {
        val w = lex.keyAt(i)
        for (c in lex.candidates(w)) {
            pairs++
            if (!org.futo.voiceinput.shared.context.TranscriptGuard.soundsAlike(w, c).first) {
                if (rejected < 20) println("guard would reject lexicon pair $w -> $c")
                rejected++
            }
        }
    }
    println("lexicon: $pairs pairs, $rejected not sound-alike in Kotlin")
}

private fun run(args: List<String>) {
    var lib: String? = null
    var model: String? = null
    var lexPath: String? = null
    var pkgDir: String? = null
    var testset: String? = null
    var out: String? = null
    var threads = 8
    var repack = true
    var fresh = false
    var options = SmartCorrectionOptions()
    var optionsName = "default"
    var personalFile: String? = null
    var onlyOption: String? = null
    var gemojiFile: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--lib" -> lib = args[++i]
            "--model" -> model = args[++i]
            "--lexicon" -> lexPath = args[++i]
            "--package-dir" -> pkgDir = args[++i]
            "--testset" -> testset = args[++i]
            "--out" -> out = args[++i]
            "--threads" -> threads = args[++i].toInt()
            "--no-repack" -> repack = false
            "--fresh" -> fresh = true  // new rescorer per item (as in the app) instead of one for all
            "--options" -> { optionsName = args[++i]; options = SmartCorrectionOptions.parse(optionsName) }
            "--personal" -> personalFile = args[++i]
            "--gemoji" -> gemojiFile = args[++i]  // java/res/raw/gemoji.json holds the emoji names for "emoji"
            "--only-option" -> onlyOption = args[++i]  // items whose "option" field is this (testset_holdout.jsonl)
            else -> usage()
        }
        i++
    }
    if (lib == null || testset == null || out == null) usage()
    if (gemojiFile != null) options = options.copy(emojiNames = EmojiNames.builtIn(File(gemojiFile).readText()).names)
    var config = ContextRescoreConfig()
    if (pkgDir != null) {
        val d = File(pkgDir)
        model = ContextModelFiles.modelFile(d)!!.path
        lexPath = ContextModelFiles.lexiconFile(d)!!.path
        config = ContextModelFiles.loadConfig(d)
    }
    if (model == null || lexPath == null) usage()

    LlamaContextLm.loadLibraryFrom(File(lib).absolutePath)
    val tl = System.nanoTime()
    val lm = LlamaContextLm.load(File(model), threads, null, repack)
    val loadMs = (System.nanoTime() - tl) / 1e6
    val tx = System.nanoTime()
    val lex = SoundAlikeLexicon.open(File(lexPath))
    val lexMs = (System.nanoTime() - tx) / 1e6
    System.err.println("ready: ${lm.description}; model %.0f ms, lexicon %.1f ms".format(loadMs, lexMs))

    val items = File(testset).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        .filter { o -> onlyOption == null || (o["option"] as? JsonPrimitive)?.content == onlyOption }
    val globalPersonal = personalFile?.let { f -> File(f).readLines().map { it.trim() }.filter { it.isNotEmpty() } } ?: emptyList()
    fun strings(o: kotlinx.serialization.json.JsonObject, key: String) =
        (o[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    System.err.println("options: ${options.copy(emojiNames = emptyMap())} (${options.emojiNames.size} emoji names); lexicon v${lex.version}${if (lex.hasBoundaries) " with boundaries" else ""}${if (lex.hasCaseData) " and case data" else ""}")
    var rescorer = ContextRescorer(lm, lex, config, options, PersonalDictionary.of(globalPersonal))
    var totalMs = 0.0
    File(out).bufferedWriter().use { w ->
        for (it in items) {
            val personal = globalPersonal + strings(it, "personal")
            val segments = strings(it, "segments")
            val learned = strings(it, "learned")
            if (fresh || it.containsKey("personal") || it.containsKey("learned")) {
                rescorer = ContextRescorer(lm, lex, config, options, PersonalDictionary.of(personal, learned))
            }
            val input = it["input"]!!.jsonPrimitive.content
            val t0 = System.nanoTime()
            val res = rescorer.correctAfter(input, (it["left"] as? JsonPrimitive)?.content ?: "", segments)
            val secs = (System.nanoTime() - t0) / 1e9
            totalMs += secs * 1000
            val edits = buildJsonArray {
                for ((k, e) in res.edits.withIndex()) add(buildJsonArray {
                    add(JsonPrimitive(res.editSources[k])); add(JsonPrimitive(e.replacement))
                    add(JsonPrimitive(e.kind)); add(JsonPrimitive(r(e.delta, 2)))
                })
            }
            val rec = buildJsonObject {
                put("id", it["id"]!!)
                put("cat", it["cat"]!!)
                put("input", input)
                put("raw", edits.toString())
                put("candidate", res.candidate)
                put("guarded", res.text)
                put("decisions", buildJsonArray {
                    for (d in res.decisions) add(buildJsonObject {
                        put("op", d.op); put("src", d.src); put("dst", d.dst); put("accepted", d.accepted); put("reason", d.reason)
                    })
                })
                put("edits", edits)
                put("n_cand_words", res.nCandidateWords)
                put("options", optionsName)
                put("trace", JsonArray(res.trace.map { JsonPrimitive(it) }))
                put("phone_ms", JsonArray(res.timings.map { JsonPrimitive(r(it, 1)) }))
                put("secs", secs)
                put("surprisal", JsonArray(res.surprisal.map { (word, s) -> buildJsonArray { add(JsonPrimitive(word)); add(JsonPrimitive(r(s, 1))) } }))
            }
            w.write(rec.toString())
            w.write("\n")
            System.err.println("%-8s %6.0f ms  %s".format(it["id"]!!.jsonPrimitive.content, secs * 1000, if (res.text != input) res.text else "(unchanged)"))
        }
    }
    System.err.println("total %.1f s for %d items (mean %.0f ms)".format(totalMs / 1000, items.size, totalMs / items.size))
    lm.close()
}

/** prior <lib> <model> <frame|frame|...> word... gives log P(word | prompt + frame) per frame (experiments). */
private fun prior(args: List<String>) {
    LlamaContextLm.loadLibraryFrom(File(args[0]).absolutePath)
    val lm = LlamaContextLm.load(File(args[1]), 4, null, false)
    val scorer = org.futo.voiceinput.shared.context.LmScorer(lm, ContextRescoreConfig().prompt)
    val frames = args[2].split('|')
    scorer.detail("The word is a long enough base text for the variants here.", emptyList())
    for (w in args.drop(3)) {
        val vals = frames.map { f ->
            val base = scorer.scoreDetailed(listOf(f.trimEnd()))[0].total
            scorer.scoreDetailed(listOf(f + w))[0].total - (if (f.isBlank()) 0.0 else base)
        }
        println(w + "	" + vals.joinToString("	") { "%.2f".format(it) })
    }
    lm.close()
}

private fun typing(args: List<String>) {
    var lib: String? = null
    var model: String? = null
    var testset: String? = null
    var out: String? = null
    var threads = 8
    var config = org.futo.voiceinput.shared.context.TypingCorrectionConfig()
    var early = false
    var maxFixes = 2
    var lexPath: String? = null
    var confusions = org.futo.voiceinput.shared.context.TypingConfusions.builtIn
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--lib" -> lib = args[++i]
            "--model" -> model = args[++i]
            "--testset" -> testset = args[++i]
            "--out" -> out = args[++i]
            "--threads" -> threads = args[++i].toInt()
            "--margin" -> config = config.copy(margin = args[++i].toDouble())
            "--opening-margin" -> config = config.copy(openingMargin = args[++i].toDouble())
            "--tail-min" -> config = config.copy(tailMin = args[++i].toDouble())
            "--min-words" -> config = config.copy(minWords = args[++i].toInt())
            "--early" -> early = true  // also check unfinished sentences word by word, as typed
            "--max-fixes" -> maxFixes = args[++i].toInt()  // swaps per sentence (the keyboard uses 2)
            "--probe-drop" -> config = config.copy(probeDrop = args[++i].toDouble())
            "--early-words" -> config = config.copy(earlyWords = args[++i].toInt())
            // missed typos ("ststy" -> "start") take candidates from the lexicon's lowercase words, and a word
            // it doesn't know stands in for one the keyboard's dictionaries don't know
            "--missed" -> config = config.copy(missedTypos = true)
            "--no-real-words" -> config = config.copy(realWords = false)
            "--lexicon" -> lexPath = args[++i]
            "--sensitivity" -> config = config.withSensitivity(args[++i].toInt())
            "--typo-unit" -> config = config.copy(typoUnit = args[++i].toDouble())
            "--missed-need" -> config = config.copy(missedNeed = args[++i].toDouble())
            "--missed-margin" -> config = config.copy(missedMargin = args[++i].toDouble())
            "--confusions" -> confusions = org.futo.voiceinput.shared.context.TypingConfusions.parse(File(args[++i]).readLines().asSequence())
            // your own pairs, as the settings page stores them ("typed<TAB>intended intended")
            "--own" -> confusions = org.futo.voiceinput.shared.context.TypingPairEdits.merge(
                confusions, org.futo.voiceinput.shared.context.TypingPairEdits.parse(File(args[++i]).readText())
            )
            "--prompt" -> config = config.copy(prompt = args[++i].replace("\\n", "\n"))
            else -> usage()
        }
        i++
    }
    if (lib == null || model == null || testset == null || out == null) usage()
    LlamaContextLm.loadLibraryFrom(File(lib).absolutePath)
    val lm = LlamaContextLm.load(File(model), threads, null, false)
    val lexicon = lexPath?.let { org.futo.voiceinput.shared.context.SoundAlikeLexicon.open(File(it)) }
    val corrector = org.futo.voiceinput.shared.context.TypingCorrector(lm, confusions, config, lexicon)
    val items = File(testset).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    var totalMs = 0.0
    File(out).bufferedWriter().use { w ->
        for (it in items) {
            val input = it["input"]!!.jsonPrimitive.content
            var text = input
            val trace = ArrayList<String>()
            val ms = ArrayList<Double>()
            val t0 = System.nanoTime()
            val nSentences = org.futo.voiceinput.shared.context.TypingCorrector.sentences(input).size
            val wordEnd = Regex("[A-Za-z']+(?=[\\s,;:]|$)")
            for (k in 0 until nSentences) {
                // as the keyboard does (up to maxFixes swaps per sentence, and a fixed word is not changed again)
                val fixedAt = HashSet<Int>()
                var fixes = 0
                fun apply(f: org.futo.voiceinput.shared.context.TypingCorrector.Fix) {
                    val delta = f.replacement.length - f.original.length
                    val moved = fixedAt.map { if (it > f.start) it + delta else it }
                    fixedAt.clear(); fixedAt.addAll(moved); fixedAt.add(f.start)
                    text = text.substring(0, f.start) + f.replacement + text.substring(f.end)
                    fixes++
                }
                if (early) {
                    // after each word typed (a space or comma after it), as the keyboard does
                    var w = 0
                    while (fixes < maxFixes) {
                        // a swap never adds or removes a sentence end, but it moves the offsets after it
                        val sent = org.futo.voiceinput.shared.context.TypingCorrector.sentences(text)[k]
                        val ends = wordEnd.findAll(text.substring(sent.first, sent.last + 1)).toList().dropLast(1)
                        if (w >= ends.size) break
                        val res = corrector.check(text, sent.first, sent.first + ends[w].range.last + 1, finished = false, skip = { it in fixedAt })
                        trace.addAll(res.trace.map { t -> "early $t" })
                        ms.addAll(res.timings)
                        val f = res.fix
                        if (f != null) apply(f) else w++
                    }
                }
                while (fixes < maxFixes) {
                    val sent = org.futo.voiceinput.shared.context.TypingCorrector.sentences(text)[k]
                    val res = corrector.check(text, sent.first, sent.last + 1, skip = { it in fixedAt }, unknown = { true })
                    trace.addAll(res.trace)
                    ms.addAll(res.timings)
                    apply(res.fix ?: break)
                }
            }
            val secs = (System.nanoTime() - t0) / 1e9
            totalMs += secs * 1000
            val rec = buildJsonObject {
                put("id", it["id"]!!)
                put("cat", it["cat"]!!)
                put("input", input)
                put("guarded", text)
                put("trace", JsonArray(trace.map { t -> JsonPrimitive(t) }))
                put("phone_ms", JsonArray(ms.map { x -> JsonPrimitive(r(x, 1)) }))
                put("secs", secs)
            }
            w.write(rec.toString())
            w.write("\n")
            System.err.println("%-8s %6.0f ms  %s".format(it["id"]!!.jsonPrimitive.content, secs * 1000, if (text != input) text else "(unchanged)"))
        }
    }
    System.err.println("total %.1f s for %d items (mean %.0f ms)".format(totalMs / 1000, items.size, totalMs / items.size))
    lm.close()
}
