package org.futo.voiceinput.shared.context

import java.io.File

/**
 * Files of a `context` model package (kind [org.futo.voiceinput.shared.modelpackage.ModelPackageKinds.CONTEXT]):
 *
 *   futo-model.json   kind "context", engine "qwen3-rescore", language "en"
 *   model.gguf        the judging LM (llama.cpp GGUF, and a single .gguf file is also accepted)
 *   soundalike-en.lex sound-alike lexicon (SoundAlikeLexicon format 1 or 2, and a single .lex file is accepted)
 *   context.json      optional thresholds (ContextRescoreConfig keys, with defaults = rescore.py)
 *   NOTICE            licenses of the model, lexicon and frequency data
 *
 * Built by tools/package-context-model.py.
 */
object ContextModelFiles {
    const val ENGINE_RESCORE = "qwen3-rescore"
    const val MODEL = "model.gguf"
    const val LEXICON = "soundalike-en.lex"
    const val CONFIG = "context.json"

    /** Engines this app can run (all use the LM-as-judge rescoring design). */
    fun supportsEngine(engine: String?): Boolean = engine == null || engine == ENGINE_RESCORE || engine.endsWith("-rescore")

    fun modelFile(dir: File): File? = find(dir, MODEL, ".gguf")

    fun lexiconFile(dir: File): File? = find(dir, LEXICON, ".lex")

    fun configFile(dir: File): File = File(dir, CONFIG)

    /** Problems with the package in [dir] (empty if it can be used). */
    fun validate(engine: String?, dir: File): List<String> {
        if (!supportsEngine(engine)) return listOf("unknown context engine '$engine' (expected '$ENGINE_RESCORE')")
        val problems = ArrayList<String>()
        val model = modelFile(dir)
        if (model == null) problems.add("missing $MODEL") else if (!hasMagic(model, 4) { it == "GGUF" }) problems.add("$MODEL is not a GGUF file")
        val lex = lexiconFile(dir)
        if (lex == null) problems.add("missing $LEXICON") else if (!hasMagic(lex, 8) { SoundAlikeLexicon.isLexiconMagic(it) }) problems.add("${lex.name} is not a sound-alike lexicon")
        val cfg = configFile(dir)
        if (cfg.isFile) {
            try {
                ContextRescoreConfig.parse(cfg.readText())
            } catch (e: Exception) {
                problems.add("invalid $CONFIG: ${e.message}")
            }
        }
        return problems
    }

    fun loadConfig(dir: File): ContextRescoreConfig {
        val cfg = configFile(dir)
        return if (cfg.isFile) ContextRescoreConfig.parse(cfg.readText()) else ContextRescoreConfig()
    }

    private fun find(dir: File, preferred: String, suffix: String): File? {
        File(dir, preferred).takeIf { it.isFile }?.let { return it }
        return dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) }?.singleOrNull()
    }

    private fun hasMagic(f: File, length: Int, ok: (String) -> Boolean): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(length)
            s.read(b) == b.size && ok(String(b, Charsets.US_ASCII))
        }
    } catch (e: Exception) {
        false
    }
}

/** A loaded context model (LM + lexicon + thresholds). Close it to free the model. */
class ContextModel private constructor(
    val lm: LlamaContextLm,
    val lexicon: SoundAlikeLexicon,
    val config: ContextRescoreConfig,
) : AutoCloseable {
    /** A fresh rescorer (one per transcript, since it keeps per-transcript KV state). */
    fun rescorer(
        options: SmartCorrectionOptions = SmartCorrectionOptions(),
        personal: PersonalDictionary = PersonalDictionary.EMPTY,
    ): ContextRescorer = ContextRescorer(lm, lexicon, config, options, personal)

    override fun close() = lm.close()

    companion object {
        /** Loads the package in [dir]. [threads] <= 0 uses the package's setting. Throws on failure. */
        fun load(dir: File, threads: Int = 0, cpus: IntArray? = null): ContextModel {
            val config = ContextModelFiles.loadConfig(dir)
            val model = ContextModelFiles.modelFile(dir) ?: throw ContextLmException("no model in $dir")
            val lexFile = ContextModelFiles.lexiconFile(dir) ?: throw ContextLmException("no lexicon in $dir")
            val lexicon = SoundAlikeLexicon.open(lexFile)
            val n = if (threads > 0) threads else config.threads
            val lm = LlamaContextLm.load(model, n, cpus, config.repack)
            return ContextModel(lm, lexicon, config)
        }
    }
}
