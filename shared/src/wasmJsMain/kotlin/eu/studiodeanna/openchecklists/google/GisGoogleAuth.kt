@file:OptIn(ExperimentalWasmJsInterop::class)

package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.currentTimeMillis
import eu.studiodeanna.openchecklists.sync.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Browser sign-in through Google Identity Services (the `gsi/client` script in index.html). Google
 * gives browser apps no refresh token, so the token lasts about an hour and lives only in memory;
 * after that, or after a reload, the user taps "Sign in" again (usually a popup that closes itself).
 */
class GisGoogleAuth(private val clientId: String) : GoogleAuth {
    private var token: String? = null
    private var expiresAt = 0L
    private val state = MutableStateFlow(false)
    override val signedIn: StateFlow<Boolean> = state

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        val valid = token?.takeIf { !forceRefresh && currentTimeMillis() < expiresAt - 60_000 }
        if (valid == null) {
            token = null
            state.value = false
        }
        return valid
    }

    override suspend fun signIn() {
        suspendCancellableCoroutine { cont ->
            requestToken(
                clientId,
                GoogleOAuthConfig.DRIVE_SCOPE,
                onToken = { accessToken, expiresInSeconds ->
                    token = accessToken
                    expiresAt = currentTimeMillis() + expiresInSeconds * 1000L
                    state.value = true
                    cont.resume(Unit)
                },
                onError = { message -> cont.resumeWithException(RemoteException(message)) },
            )
        }
    }

    override suspend fun signOut() {
        token?.let(::revokeToken)
        token = null
        state.value = false
    }
}

private fun requestToken(
    clientId: String,
    scope: String,
    onToken: (String, Int) -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
    if (!(window.google && google.accounts && google.accounts.oauth2)) {
        onError('Google sign-in could not load. Check the connection and reload the page.');
        return;
    }
    google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: scope,
        callback: (r) => r.error ? onError(r.error_description || r.error) : onToken(r.access_token, Number(r.expires_in)),
        error_callback: (e) => onError(e.type === 'popup_closed' ? 'Sign-in was cancelled.' : (e.message || 'Google sign-in failed.')),
    }).requestAccessToken();
}""",
)

private fun revokeToken(token: String): Unit = js("{ if (window.google && google.accounts) google.accounts.oauth2.revoke(token, () => {}); }")
