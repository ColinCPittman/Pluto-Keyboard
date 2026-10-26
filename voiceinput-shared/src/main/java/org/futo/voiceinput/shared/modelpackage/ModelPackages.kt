package org.futo.voiceinput.shared.modelpackage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

/**
 * Android entry point for model packages (format described in ModelPackageStore.kt).
 *
 * Packages live in app-private storage, in `filesDir/models/<kind>/<id>/`.
 *
 * Typical use by a feature:
 * ```
 * val installed = ModelPackages.list(context, ModelPackageKinds.PUNCTUATION)
 * val dir = ModelPackages.dir(context, ModelPackageKinds.PUNCTUATION, installed.first().id)
 * ```
 * Importing is handled by ImportResourceActivity (the app's "import file"
 * flow), which calls [detect] and [install].
 */
object ModelPackages {
    private val _changes = MutableStateFlow(0)

    /** Incremented whenever a package is installed or deleted (for UI refresh). */
    val changes: StateFlow<Int> = _changes

    fun root(context: Context): File = File(context.filesDir, "models")

    fun store(context: Context): ModelPackageStore = ModelPackageStore(root(context))

    /** Installed packages of [kind] (e.g. [ModelPackageKinds.MOONSHINE_STREAMING]), sorted by name. */
    fun list(context: Context, kind: String): List<InstalledModelPackage> = store(context).list(kind)

    /** Directory of package [id] of [kind] (may not exist). */
    fun dir(context: Context, kind: String, id: String): File = store(context).dir(kind, id)

    /** The installed package, or null. */
    fun get(context: Context, kind: String, id: String): InstalledModelPackage? = store(context).get(kind, id)

    fun delete(context: Context, kind: String, id: String): Boolean =
        store(context).delete(kind, id).also { _changes.value++ }

    /** Returns the manifest if [input] is a model package zip, else null. Does not close [input]. */
    fun detect(input: InputStream): ModelPackageManifest? = ModelPackageStore.detect(input)

    /**
     * Extracts and validates a package (blocking, so call on Dispatchers.IO).
     * Throws [ModelPackageException] with a user-presentable message on failure.
     */
    fun install(
        context: Context,
        input: InputStream,
        sizeHint: Long = -1,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): InstalledModelPackage = store(context).install(input, sizeHint, onProgress).also { _changes.value++ }
}
