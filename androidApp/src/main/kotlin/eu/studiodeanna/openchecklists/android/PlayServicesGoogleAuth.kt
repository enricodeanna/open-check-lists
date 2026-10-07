package eu.studiodeanna.openchecklists.android

import eu.studiodeanna.openchecklists.Messages
import android.app.Application
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig
import eu.studiodeanna.openchecklists.sync.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Android sign-in through Google Play services. The OAuth client is matched by package name and
 * signing certificate, so no client id appears here. Play services caches and renews tokens itself.
 */
class PlayServicesGoogleAuth(private val app: Application) : GoogleAuth {
    private val client = Identity.getAuthorizationClient(app)
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GoogleOAuthConfig.DRIVE_SCOPE)))
        .build()
    private val prefs = app.getSharedPreferences("google", Context.MODE_PRIVATE)
    private var token: String? = null

    private val state = MutableStateFlow(prefs.getBoolean(SIGNED_IN, false))
    override val signedIn: StateFlow<Boolean> = state

    /** Set by the activity; Google's consent screen is launched through it. */
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null

    fun onResult(result: ActivityResult) {
        pending?.complete(result)
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        // After "sign out", stay signed out even though Play services would still hand out tokens.
        if (!state.value) return null
        if (forceRefresh) token?.let { clearToken(it) }
        if (!forceRefresh) token?.let { return it }
        val result = authorize()
        if (result.hasResolution()) {
            setSignedIn(false)
            return null
        }
        return result.accessToken.also { token = it }
    }

    override suspend fun signIn() {
        var result = authorize()
        if (result.hasResolution()) {
            val launcher = launcher ?: throw RemoteException(Messages.current.openAppToSignIn)
            val deferred = CompletableDeferred<ActivityResult>().also { pending = it }
            launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
            val reply = deferred.await()
            result = try {
                client.getAuthorizationResultFromIntent(reply.data)
            } catch (e: Exception) {
                throw RemoteException(Messages.current.signInCancelled, e)
            }
        }
        token = result.accessToken ?: throw RemoteException(Messages.current.signInRetry)
        setSignedIn(true)
    }

    override suspend fun signOut() {
        token?.let { clearToken(it) }
        token = null
        setSignedIn(false)
    }

    private suspend fun authorize(): AuthorizationResult = try {
        client.authorize(request).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException(Messages.current.signInUnavailable(e.message), e)
    }

    private suspend fun clearToken(value: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(value).build()).await() }
        token = null
    }

    private fun setSignedIn(value: Boolean) {
        state.value = value
        prefs.edit().putBoolean(SIGNED_IN, value).apply()
    }

    private companion object {
        const val SIGNED_IN = "signedIn"
    }
}
