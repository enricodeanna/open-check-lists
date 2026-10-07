package eu.studiodeanna.openchecklists.ui

import androidx.compose.ui.window.ComposeUIViewController
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.defaultHttpClient
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig
import eu.studiodeanna.openchecklists.google.WebSessionGoogleAuth
import eu.studiodeanna.openchecklists.store.DocumentsFileStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

private val repository by lazy {
    val files = DocumentsFileStore()
    val http = defaultHttpClient()
    val google = GoogleOAuthConfig.IOS_CLIENT_ID.takeIf { it.isNotEmpty() }?.let { WebSessionGoogleAuth(it, files, http) }
    val scope = MainScope()
    google?.let { scope.launch { it.restore() } }
    ChecklistRepository(files, scope, http, google)
}

/** Entry point for the Swift app. */
fun MainViewController(): UIViewController = ComposeUIViewController { OpenCheckListsApp(repository) }
