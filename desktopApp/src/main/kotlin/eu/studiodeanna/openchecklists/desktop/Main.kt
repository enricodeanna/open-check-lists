package eu.studiodeanna.openchecklists.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.defaultHttpClient
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig
import eu.studiodeanna.openchecklists.google.LoopbackGoogleAuth
import eu.studiodeanna.openchecklists.store.DirectoryFileStore
import eu.studiodeanna.openchecklists.ui.OpenCheckListsApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.io.File

fun main(args: Array<String>) {
    val dataDir = args.firstOrNull()
        ?: System.getenv("OPENCHECKLISTS_DATA")
        ?: File(System.getProperty("user.home"), ".local/share/open-check-lists").path
    val files = DirectoryFileStore(File(dataDir))
    val http = defaultHttpClient()
    // The environment wins over the built-in ids, to try a Google Cloud project without rebuilding.
    val clientId = System.getenv("OPENCHECKLISTS_GOOGLE_CLIENT_ID") ?: GoogleOAuthConfig.DESKTOP_CLIENT_ID
    val clientSecret = System.getenv("OPENCHECKLISTS_GOOGLE_CLIENT_SECRET") ?: GoogleOAuthConfig.DESKTOP_CLIENT_SECRET
    val google = clientId.takeIf { it.isNotEmpty() }?.let {
        LoopbackGoogleAuth(it, clientSecret, files, http)
            .also { auth -> runBlocking { auth.restore() } }
    }
    val repository = ChecklistRepository(files, MainScope(), http, google)
    val icon = object {}.javaClass.getResourceAsStream("/icon.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Open Check Lists",
            icon = icon,
            state = rememberWindowState(size = DpSize(440.dp, 820.dp)),
        ) {
            OpenCheckListsApp(repository, openList = System.getenv("OPENCHECKLISTS_OPEN"))
        }
    }
}
