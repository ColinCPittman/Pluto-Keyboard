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
import org.futo.voiceinput.shared.engine.SmartFeaturePolicy
import org.futo.voiceinput.shared.engine.VoiceModelProfile
import java.io.File
import kotlin.math.round

private fun usage(): Nothing {
    System.err.println(
        """
        usage:
          context-harness run --lib <libfutoctxlm> (--package-dir <dir> | --model <gguf> --lexicon <lex>)
                              --testset <jsonl> --out <jsonl> [--threads N] [--no-repack] [--fresh]
                              [--options default|all|none|soundalike,names,questions,numbers,commands,fillers,selfcorrections,quotes,emoji] [--gemoji gemoji.json]
              --options: a "-name" entry switches that one off ("all,-fillers"), which also works for the two
              parts that aren't settings: -sentencebreaks and -lexiconcasing (both on unless switched off)
                              [--personal <file: one personal-dictionary word per line>] [--only-option <name>]
                              [--profile whisper|moonshine|nemotron|parakeet] [--punctuation-ran true|false]
              --profile: what runs on the phone for that voice model (SmartFeaturePolicy drops what the model does itself)
              --punctuation-ran: whether the testset's text went through the punctuation stage (default: as the app does for the profile)
              items may carry "left" (text already in the field before the dictation), "segments" (ASR utterances), "personal" (personal-dictionary words)
              and "learned" (capitalized words learned from typing)
          context-harness typing --lib <libfutoctxlm> --model <gguf> --testset <jsonl> --out <jsonl> [--threads N]
                              [--margin X] [--opening-margin X] [--tail-min X] [--prompt P] [--own <file: your pairs, "typed<TAB>intended">] [--early] [--max-fixes N]
              Smart corrections for typing: each sentence of an item is checked in turn, as the
              keyboard does when it ends (at most --max-fixes swaps per sentence, default 2)
                              [--edits | --missing-words --leftover-words --word-endings --word-order --join-split]
                              [--add-margin X] [--remove-margin X] [--repeat-margin X] [--near-repeat-margin X] [--ending-margin X] [--order-margin X]
                              [--join-margin X] [--edit-budget N] [--edit-probe-drop X] [--add-min-probe X] [--first-letter-need X]
                              [--spike-log-prob X] [--spike-below-median X] [--spike-window N] [--ambiguity-gain X] [--trace-edits]
                              [--edit-window-words N] [--edit-window-slack X] [--edit-full-scores N]
              --edits: also fix slips made while editing (a missing, leftover or swapped word, an ending, a join)
              items may carry "left" (text before the input, scored as context and never changed)
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

/** [SmartCorrectionOptions.parse], plus "-name" entries that switch one option off (tools/voice-bench/ablate.py). */
private fun parseOptions(spec: String): SmartCorrectionOptions {
    val parts = spec.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val known = setOf("all", "default", "none", "soundalike", "names", "questions", "numbers", "commands", "fillers",
        "selfcorrections", "quotes", "emoji", "sentencebreaks", "lexiconcasing")
    for (p in parts) if (p.removePrefix("-") !in known) {
        System.err.println("unknown option '$p' in --options $spec")
        kotlin.system.exitProcess(2)
    }
    var o = SmartCorrectionOptions.parse(parts.filterNot { it.startsWith("-") }.joinToString(","))
    for (p in parts.filter { it.startsWith("-") }) {
        o = when (p.drop(1)) {
            "soundalike" -> o.copy(soundAlike = false)
            "names" -> o.copy(names = false)
            "questions" -> o.copy(questions = false)
            "numbers" -> o.copy(numbers = false)
            "commands" -> o.copy(commands = false)
            "fillers" -> o.copy(fillers = false)
            "selfcorrections" -> o.copy(selfCorrections = false)
            "quotes" -> o.copy(quotes = false)
            "emoji" -> o.copy(emoji = false)
            "sentencebreaks" -> o.copy(sentenceBreaks = false)
            "lexiconcasing" -> o.copy(lexiconCasing = false)
            else -> usage()
        }
    }
    return o
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
    var profileName: String? = null
    var punctuationRan: Boolean? = null
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
            "--options" -> { optionsName = args[++i]; options = parseOptions(optionsName) }
            "--personal" -> personalFile = args[++i]
            "--gemoji" -> gemojiFile = args[++i]  // java/res/raw/gemoji.json holds the emoji names for "emoji"
            "--only-option" -> onlyOption = args[++i]  // items whose "option" field is this (testset_holdout.jsonl)
            "--profile" -> profileName = args[++i]
            // whether the punctuation stage ran on the test set's text (default: as the app runs it for the profile)
            "--punctuation-ran" -> punctuationRan = args[++i].toBooleanStrict()
            else -> usage()
        }
        i++
    }
    if (lib == null || testset == null || out == null) usage()
    if (profileName != null) {
        val profile = when (profileName) {
            "whisper" -> VoiceModelProfile.WHISPER
            "moonshine" -> VoiceModelProfile.MOONSHINE
            "nemotron" -> VoiceModelProfile.NEMOTRON
            "parakeet" -> VoiceModelProfile.PARAKEET
            else -> usage()
        }
        // (the testset is the voice model's own text, so the punctuation stage ran only for a model that needs it)
        options = SmartFeaturePolicy.smartCorrections(options, profile, punctuationRan ?: SmartFeaturePolicy.punctuationStage(profile, true))
        optionsName += "+$profileName"
    }
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
            // edits for slips made while editing: --edits switches on all five kinds, or one at a time
            "--edits" -> config = config.copy(missingWords = true, leftoverWords = true, wordEndings = true, wordOrder = true, joinSplit = true)
            "--missing-words" -> config = config.copy(missingWords = true)
            "--leftover-words" -> config = config.copy(leftoverWords = true)
            "--word-endings" -> config = config.copy(wordEndings = true)
            "--word-order" -> config = config.copy(wordOrder = true)
            "--join-split" -> config = config.copy(joinSplit = true)
            "--add-margin" -> config = config.copy(addMargin = args[++i].toDouble())
            "--remove-margin" -> config = config.copy(removeMargin = args[++i].toDouble())
            "--repeat-margin" -> config = config.copy(repeatMargin = args[++i].toDouble())
            "--near-repeat-margin" -> config = config.copy(nearRepeatMargin = args[++i].toDouble())
            "--ending-margin" -> config = config.copy(endingMargin = args[++i].toDouble())
            "--order-margin" -> config = config.copy(orderMargin = args[++i].toDouble())
            "--join-margin" -> config = config.copy(joinMargin = args[++i].toDouble())
            "--edit-budget" -> config = config.copy(editBudget = args[++i].toInt())
            "--edit-probe-drop" -> config = config.copy(editProbeDrop = args[++i].toDouble())
            "--add-min-probe" -> config = config.copy(addMinProbe = args[++i].toDouble())
            "--spike-log-prob" -> config = config.copy(spikeLogProb = args[++i].toDouble())
            "--spike-below-median" -> config = config.copy(spikeBelowMedian = args[++i].toDouble())
            "--spike-window" -> config = config.copy(spikeWindow = args[++i].toInt())
            "--ambiguity-gain" -> config = config.copy(ambiguityGain = args[++i].toDouble())
            "--trace-edits" -> config = config.copy(traceEdits = true)
            "--edit-window-words" -> config = config.copy(editWindowWords = args[++i].toInt())
            "--edit-window-slack" -> config = config.copy(editWindowSlack = args[++i].toDouble())
            "--edit-full-scores" -> config = config.copy(editFullScores = args[++i].toInt())
            "--first-letter-need" -> config = config.copy(firstLetterNeed = args[++i].toDouble())
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
            // the text before the item ("left") is context: it is scored but never changed
            val left = (it["left"] as? JsonPrimitive)?.content ?: ""
            var text = left + input
            val trace = ArrayList<String>()
            val ms = ArrayList<Double>()
            val t0 = System.nanoTime()
            // the sentences that end in the input are checked (the first may start in "left", which stays as it is)
            val nLeft = org.futo.voiceinput.shared.context.TypingCorrector.sentences(text).count { s -> s.last < left.length }
            val nSentences = org.futo.voiceinput.shared.context.TypingCorrector.sentences(text).size
            val wordEnd = Regex("[A-Za-z']+(?=[\\s,;:]|$)")
            for (k in nLeft until nSentences) {
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
                        val from = maxOf(sent.first, left.length)
                        val ends = wordEnd.findAll(text.substring(from, sent.last + 1)).toList().dropLast(1)
                        if (w >= ends.size) break
                        val res = corrector.check(text, from, from + ends[w].range.last + 1, finished = false, skip = { it in fixedAt })
                        trace.addAll(res.trace.map { t -> "early $t" })
                        ms.addAll(res.timings)
                        val f = res.fix
                        if (f != null) apply(f) else w++
                    }
                }
                while (fixes < maxFixes) {
                    val sent = org.futo.voiceinput.shared.context.TypingCorrector.sentences(text)[k]
                    val res = corrector.check(text, maxOf(sent.first, left.length), sent.last + 1, skip = { it in fixedAt }, unknown = { true })
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
                put("guarded", text.substring(left.length))
                put("trace", JsonArray(trace.map { t -> JsonPrimitive(t) }))
                put("phone_ms", JsonArray(ms.map { x -> JsonPrimitive(r(x, 1)) }))
                put("secs", secs)
            }
            w.write(rec.toString())
            w.write("\n")
            System.err.println("%-8s %6.0f ms  %s".format(it["id"]!!.jsonPrimitive.content, secs * 1000, if (text.substring(left.length) != input) text.substring(left.length) else "(unchanged)"))
        }
    }
    System.err.println("total %.1f s for %d items (mean %.0f ms)".format(totalMs / 1000, items.size, totalMs / items.size))
    lm.close()
}
