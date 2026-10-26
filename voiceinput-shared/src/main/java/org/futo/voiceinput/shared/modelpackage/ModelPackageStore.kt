package org.futo.voiceinput.shared.modelpackage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.futo.voiceinput.shared.context.ContextModelFiles
import org.futo.voiceinput.shared.moonshine.core.MoonshineModelFiles
import org.futo.voiceinput.shared.punctuation.PunctuationRestorers
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/*
 * Generic "model package" format shared by features that use imported models
 * (Moonshine speech recognition, punctuation restoration, ...).
 *
 * A model package is a .zip whose root contains `futo-model.json`:
 *
 *   {
 *     "format": 1,                                  // required, must be 1
 *     "kind": "moonshine-streaming",                // required, see ModelPackageKinds
 *     "id": "moonshine-small-streaming-en",         // required, [a-z0-9][a-z0-9._-]{0,63}
 *     "name": "Moonshine Small Streaming (English)",// required, display name
 *     "language": "en",                             // optional
 *     "engine": "onnxruntime",                      // optional, engine-specific
 *     "license": "MIT",                             // optional
 *     "source": "https://...",                      // optional
 *     "version": "..."                              // optional
 *   }
 *
 * plus the model files (flat or in sub-directories). Packages are extracted to
 * `<root>/<kind>/<id>/` (on Android `<root>` is `filesDir/models`, see
 * ModelPackages). Installing a package with an existing kind+id replaces it.
 */

object ModelPackageKinds {
    const val MOONSHINE_STREAMING = "moonshine-streaming"
    const val PUNCTUATION = "punctuation"
    /** LM that fixes misheard sound-alike words and question marks (see context.ContextModelFiles). */
    const val CONTEXT = "context"

    val ALL = setOf(MOONSHINE_STREAMING, PUNCTUATION, CONTEXT)
}

class ModelPackageException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ModelPackageManifest(
    val format: Int,
    val kind: String,
    val id: String,
    val name: String,
    val language: String? = null,
    val engine: String? = null,
    val license: String? = null,
    val source: String? = null,
    val version: String? = null
) {
    fun toJson(): String = buildJsonObject {
        put("format", format)
        put("kind", kind)
        put("id", id)
        put("name", name)
        language?.let { put("language", it) }
        engine?.let { put("engine", it) }
        license?.let { put("license", it) }
        source?.let { put("source", it) }
        version?.let { put("version", it) }
    }.toString()

    companion object {
        const val FILE_NAME = "futo-model.json"
        const val CURRENT_FORMAT = 1
        private val ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** Parses and checks a manifest. Throws [ModelPackageException] if invalid. */
        fun parse(json: String): ModelPackageManifest {
            val obj: JsonObject = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw ModelPackageException("Invalid $FILE_NAME: ${e.message}", e)
            }

            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

            val format = (obj["format"] as? JsonPrimitive)?.intOrNull
                ?: throw ModelPackageException("$FILE_NAME: missing format")
            if (format != CURRENT_FORMAT) throw ModelPackageException("Unsupported model package format $format")
            val kind = str("kind") ?: throw ModelPackageException("$FILE_NAME: missing kind")
            val id = str("id") ?: throw ModelPackageException("$FILE_NAME: missing id")
            if (!ID_REGEX.matches(id)) throw ModelPackageException("$FILE_NAME: invalid id '$id'")
            val name = str("name")?.takeIf { it.isNotBlank() } ?: id

            return ModelPackageManifest(
                format = format,
                kind = kind,
                id = id,
                name = name,
                language = str("language"),
                engine = str("engine"),
                license = str("license"),
                source = str("source"),
                version = str("version")
            )
        }
    }
}

data class InstalledModelPackage(
    val manifest: ModelPackageManifest,
    val dir: File,
    val sizeBytes: Long
) {
    val kind: String get() = manifest.kind
    val id: String get() = manifest.id
    val name: String get() = manifest.name
}

/** Filesystem part of model packages (pure JVM, and ModelPackages adds Android paths). */
class ModelPackageStore(val root: File) {
    fun kindDir(kind: String): File = File(root, kind)

    fun dir(kind: String, id: String): File = File(kindDir(kind), id)

    /** Installed packages of [kind], sorted by name. Broken directories are skipped. */
    fun list(kind: String): List<InstalledModelPackage> {
        val dirs = kindDir(kind).listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { readInstalled(it) }.filter { it.manifest.kind == kind }.sortedBy { it.manifest.name.lowercase() }
    }

    fun get(kind: String, id: String): InstalledModelPackage? =
        readInstalled(dir(kind, id))?.takeIf { it.manifest.kind == kind && it.manifest.id == id }

    fun delete(kind: String, id: String): Boolean {
        val d = dir(kind, id)
        return d.exists() && d.deleteRecursively()
    }

    private fun readInstalled(d: File): InstalledModelPackage? {
        val m = File(d, ModelPackageManifest.FILE_NAME)
        if (!m.isFile) return null
        return try {
            InstalledModelPackage(ModelPackageManifest.parse(m.readText()), d, d.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts a package zip from [input] (streamed, so the zip is never held in
     * memory), validates it and moves it into place, replacing an existing
     * package with the same kind and id. [onProgress] gets (bytes extracted,
     * total uncompressed bytes so far is unknown, so the second argument is
     * the [sizeHint] if given, else -1).
     */
    fun install(
        input: InputStream,
        sizeHint: Long = -1,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): InstalledModelPackage {
        root.mkdirs()
        val tmp = File(root, ".tmp-" + UUID.randomUUID().toString())
        if (!tmp.mkdirs()) throw ModelPackageException("Could not create ${tmp.path}")
        try {
            extractZip(input, tmp, sizeHint, onProgress)

            val manifestFile = File(tmp, ModelPackageManifest.FILE_NAME)
            if (!manifestFile.isFile) throw ModelPackageException("Not a model package (no ${ModelPackageManifest.FILE_NAME})")
            val manifest = ModelPackageManifest.parse(manifestFile.readText())
            val problems = validate(manifest, tmp)
            if (problems.isNotEmpty()) {
                throw ModelPackageException("Invalid ${manifest.kind} package: " + problems.joinToString())
            }

            val target = dir(manifest.kind, manifest.id)
            target.parentFile?.mkdirs()
            if (target.exists() && !target.deleteRecursively()) {
                throw ModelPackageException("Could not replace existing package ${manifest.id}")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyRecursively(target, overwrite = true)
            }
            return readInstalled(target) ?: throw ModelPackageException("Package disappeared after install")
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun extractZip(input: InputStream, dest: File, sizeHint: Long, onProgress: (Long, Long) -> Unit) {
        val canonicalDest = dest.canonicalFile
        val destPrefix = canonicalDest.path + File.separator
        var written = 0L
        var files = 0
        var lastReport = 0L
        val buffer = ByteArray(256 * 1024)

        ZipInputStream(BufferedInputStream(input, 256 * 1024)).use { zip ->
            var entry = zip.nextEntry
            if (entry == null) throw ModelPackageException("Empty or invalid zip file")
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                // Zip-slip protection means refusing anything resolving outside dest.
                val out = File(canonicalDest, name).canonicalFile
                if (!out.path.startsWith(destPrefix)) {
                    throw ModelPackageException("Illegal path in package: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    if (++files > MAX_FILES) throw ModelPackageException("Too many files in package")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            os.write(buffer, 0, n)
                            written += n
                            if (written > MAX_TOTAL_BYTES) throw ModelPackageException("Package is too large")
                            if (written - lastReport > 4 * 1024 * 1024) {
                                lastReport = written
                                onProgress(written, sizeHint)
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        onProgress(written, written)
    }

    companion object {
        private const val MAX_FILES = 256
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024

        /** Kind-specific validation that returns problems (empty if OK). */
        fun validate(manifest: ModelPackageManifest, dir: File): List<String> = when (manifest.kind) {
            ModelPackageKinds.MOONSHINE_STREAMING -> MoonshineModelFiles.validateDirectory(dir)
            ModelPackageKinds.PUNCTUATION -> PunctuationRestorers.validate(manifest.engine, dir)
            ModelPackageKinds.CONTEXT -> ContextModelFiles.validate(manifest.engine, dir)
            else -> listOf("unsupported kind '${manifest.kind}'")
        }

        /** True if [header] (at least 4 bytes) starts a zip file. */
        fun isZipHeader(header: ByteArray): Boolean =
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

        /**
         * Returns the manifest if [input] is a model package zip, else null.
         * Reads entries until `futo-model.json` is found at the zip root
         * (packages should store it first so this stays fast).
         */
        fun detect(input: InputStream): ModelPackageManifest? {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(8)
            val header = ByteArray(4)
            if (buffered.read(header) != 4 || !isZipHeader(header)) return null
            buffered.reset()
            return try {
                ZipInputStream(buffered).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == ModelPackageManifest.FILE_NAME) {
                            val bytes = readLimited(zip, MAX_MANIFEST_BYTES)
                            return ModelPackageManifest.parse(String(bytes, Charsets.UTF_8))
                        }
                        entry = zip.nextEntry
                    }
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw ModelPackageException("Manifest too large")
            }
            return out.toByteArray()
        }
    }
}
