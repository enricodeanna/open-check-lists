package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.currentTimeMillis
import eu.studiodeanna.openchecklists.store.FileStore
import eu.studiodeanna.openchecklists.sync.RemoteException
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS sign-in through the system's web authentication sheet, with the iOS OAuth client's
 * reversed-id redirect scheme. The refresh token is kept in [store].
 */
class WebSessionGoogleAuth(clientId: String, store: FileStore, http: HttpClient) : GoogleAuth {
    private val scheme = "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
    private val oauth = PkceOAuthClient(clientId, null, store, http, ::currentTimeMillis)
    private val state = MutableStateFlow(false)
    override val signedIn: StateFlow<Boolean> = state

    /** Held while the sheet is up; the system does not retain the session itself. */
    private var session: ASWebAuthenticationSession? = null

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? =
        oauth.accessToken(forceRefresh).also { state.value = it != null }

    override suspend fun signIn() {
        val request = oauth.newRequest("$scheme:/oauth2redirect")
        val callback = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val sheet = ASWebAuthenticationSession(NSURL(string = request.url), scheme) { url, _ ->
                    session = null
                    val reply = url?.absoluteString
                    if (reply != null) cont.resume(reply) else cont.resumeWithException(RemoteException(Messages.current.signInCancelled))
                }
                sheet.presentationContextProvider = anchor
                session = sheet
                cont.invokeOnCancellation { sheet.cancel() }
                if (!sheet.start()) cont.resumeWithException(RemoteException(Messages.current.signInCouldNotStart))
            }
        }
        oauth.complete(request, PkceOAuthClient.queryParams(callback))
        state.value = oauth.accessToken(forceRefresh = false) != null
    }

    override suspend fun signOut() {
        oauth.clear()
        state.value = false
    }

    suspend fun restore() {
        state.value = oauth.hasSession()
    }
}
