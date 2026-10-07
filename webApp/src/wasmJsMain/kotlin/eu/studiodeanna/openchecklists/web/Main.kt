package eu.studiodeanna.openchecklists.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.google.GisGoogleAuth
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig
import eu.studiodeanna.openchecklists.store.LocalStorageFileStore
import eu.studiodeanna.openchecklists.ui.OpenCheckListsApp
import kotlinx.coroutines.MainScope

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val google = GoogleOAuthConfig.WEB_CLIENT_ID.takeIf { it.isNotEmpty() }?.let {
        GisGoogleAuth(it, GoogleOAuthConfig.WEB_API_KEY, GoogleOAuthConfig.CLOUD_PROJECT_NUMBER)
    }
    val repository = ChecklistRepository(LocalStorageFileStore(), MainScope(), google = google)
    ComposeViewport { OpenCheckListsApp(repository) }
}
