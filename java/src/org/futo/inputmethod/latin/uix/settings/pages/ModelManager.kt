package org.futo.inputmethod.latin.uix.settings.pages

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.uix.settings.settingsPage
import androidx.navigation.compose.dialog
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.FinetuneModelScreen
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.ModelDeleteConfirmScreen
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.ModelListScreen
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.ModelScreenNav
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.PrivateModelExportConfirmation
import org.futo.inputmethod.latin.uix.urlDecode
import java.io.File

fun NavGraphBuilder.addModelManagerNavigation(
    navController: NavHostController
) {
    settingsPage("models") { ModelListScreen(navController) }
    settingsPage("finetune/{modelPath}") {
        val path = it.arguments!!.getString("modelPath")!!.urlDecode()
        FinetuneModelScreen(
            File(path), navController
        )

    }
    settingsPage("finetune") {
        FinetuneModelScreen(file = null, navController = navController)
    }
    settingsPage("model/{modelPath}") {
        val path = it.arguments!!.getString("modelPath")!!.urlDecode()
        ModelScreenNav(
            File(path), navController
        )
    }
    dialog("modelExport/{modelPath}") {
        PrivateModelExportConfirmation(
            File(it.arguments!!.getString("modelPath")!!.urlDecode()),
            navController
        )
    }
    dialog("modelDelete/{modelPath}") {
        val path = it.arguments!!.getString("modelPath")!!.urlDecode()
        ModelDeleteConfirmScreen(File(path), navController)
    }
}