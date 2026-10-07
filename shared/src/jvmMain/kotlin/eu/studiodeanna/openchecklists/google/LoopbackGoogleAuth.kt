package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
import com.sun.net.httpserver.HttpServer
import eu.studiodeanna.openchecklists.currentTimeMillis
import eu.studiodeanna.openchecklists.store.FileStore
import eu.studiodeanna.openchecklists.sync.RemoteException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * Desktop sign-in: opens Google's page in the default browser and receives the answer on a
 * one-off server at 127.0.0.1, as Google recommends for desktop apps.
 */
class LoopbackGoogleAuth(
    clientId: String,
    clientSecret: String,
    store: FileStore,
    http: HttpClient,
) : GoogleAuth {
    private val oauth = PkceOAuthClient(clientId, clientSecret, store, http, ::currentTimeMillis)
    private val state = MutableStateFlow(false)
    override val signedIn: StateFlow<Boolean> = state

    override suspend fun accessToken(forceRefresh: Boolean): String? =
        oauth.accessToken(forceRefresh).also { state.value = it != null }

    override suspend fun signIn() {
        val reply = CompletableDeferred<Map<String, String>>()
        val server = withContext(Dispatchers.IO) {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        }
        server.createContext("/") { exchange ->
            val params = PkceOAuthClient.queryParams("http://127.0.0.1${exchange.requestURI}")
            val page = REPLY_PAGE.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
            if ("code" in params || "error" in params) reply.complete(params)
        }
        server.start()
        try {
            val request = oauth.newRequest("http://127.0.0.1:${server.address.port}")
            openBrowser(request.url)
            val params = try {
                withTimeout(5 * 60_000) { reply.await() }
            } catch (e: TimeoutCancellationException) {
                throw RemoteException(Messages.current.signInTimedOut, e)
            }
            oauth.complete(request, params)
            state.value = oauth.accessToken(forceRefresh = false) != null
        } finally {
            server.stop(0)
        }
    }

    override suspend fun signOut() {
        oauth.clear()
        state.value = false
    }

    /** Remembers across restarts whether a session exists, without a network call. */
    suspend fun restore() {
        state.value = oauth.hasSession()
    }

    private suspend fun openBrowser(url: String) = withContext(Dispatchers.IO) {
        val desktop = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        if (desktop) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            // Linux desktops without AWT browse support.
            ProcessBuilder("xdg-open", url).start()
        }
    }

    private companion object {
        const val REPLY_PAGE = "<!doctype html><meta charset=utf-8><title>Open Check Lists</title>" +
            "<body style=\"font-family:sans-serif;padding:3em\"><h2>Signed in</h2>" +
            "<p>You can close this tab and go back to Open Check Lists.</p></body>"
    }
}
