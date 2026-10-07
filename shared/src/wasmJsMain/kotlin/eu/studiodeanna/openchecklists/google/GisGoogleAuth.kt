@file:OptIn(ExperimentalWasmJsInterop::class)

package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
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
 * The file picker is Google's Picker API (the `api.js` script), which needs [apiKey] and the Cloud
 * project's number, [appId], so that a picked file is granted to this app.
 */
class GisGoogleAuth(private val clientId: String, private val apiKey: String, private val appId: String) : GoogleAuth {
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

    override suspend fun pickFile(fileId: String) {
        if (apiKey.isEmpty() || appId.isEmpty()) throw RemoteException(Messages.current.driveNotSetUp)
        val current = accessToken() ?: run {
            signIn()
            token ?: throw RemoteException(Messages.current.signInRetry)
        }
        val picked = suspendCancellableCoroutine { cont ->
            showPicker(
                apiKey,
                appId,
                current,
                fileId,
                onDone = { ids -> cont.resume(ids) },
                onError = { cont.resumeWithException(RemoteException(Messages.current.pickerCouldNotLoad)) },
            )
        }
        if (fileId !in picked.split(',')) throw RemoteException(Messages.current.driveFileNotPicked)
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

/** Shows the picker with only [fileId] in it; [onDone] gets the picked ids, comma-separated, or "" if cancelled. */
private fun showPicker(
    apiKey: String,
    appId: String,
    token: String,
    fileId: String,
    onDone: (String) -> Unit,
    onError: () -> Unit,
): Unit = js(
    """{
    if (!window.gapi) {
        onError();
        return;
    }
    gapi.load('picker', {
        callback: () => {
            const view = new google.picker.DocsView(google.picker.ViewId.DOCS).setFileIds(fileId);
            new google.picker.PickerBuilder()
                .addView(view)
                .setOAuthToken(token)
                .setDeveloperKey(apiKey)
                .setAppId(appId)
                .setCallback((data) => {
                    const action = data[google.picker.Response.ACTION];
                    if (action === google.picker.Action.PICKED) {
                        onDone(data[google.picker.Response.DOCUMENTS].map((d) => d[google.picker.Document.ID]).join(','));
                    } else if (action === google.picker.Action.CANCEL) {
                        onDone('');
                    }
                })
                .build()
                .setVisible(true);
        },
        onerror: () => onError(),
    });
}""",
)

private fun revokeToken(token: String): Unit = js("{ if (window.google && google.accounts) google.accounts.oauth2.revoke(token, () => {}); }")
