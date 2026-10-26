package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.moonshine.MoonshineModelManager
import org.futo.voiceinput.shared.types.ModelLoader


class ModelManager(
    val context: Context
) {
    private val loadedModels: HashMap<Any, WhisperGGML> = hashMapOf()

    /** Cache for the Moonshine streaming engine (same lifetime as the whisper models). */
    val moonshine = MoonshineModelManager()

    fun obtainModel(model: ModelLoader): WhisperGGML {
        val key = model.key(context)
        if (!loadedModels.contains(key)) {
            loadedModels[key] = model.loadGGML(context)
        }

        return loadedModels[key]!!
    }

    fun cancelAll() {
        loadedModels.forEach {
            it.value.cancel()
        }
    }

    suspend fun cleanUp() {
        for (model in loadedModels.entries) {
            model.value.cancel()
            model.value.close()
        }

        loadedModels.clear()

        moonshine.cleanUp()
    }
}
