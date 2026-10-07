package eu.studiodeanna.openchecklists.android

import eu.studiodeanna.openchecklists.Messages
import android.accounts.Account
import android.app.Application
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.edit
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
 *
 * The account picked at sign-in is remembered and asked for by name from then on, so a phone with
 * several Google accounts keeps using the one the user chose. Signed out, the next sign-in asks again;
 * otherwise Play services would quietly reuse the account this app had before.
 */
class PlayServicesGoogleAuth(private val app: Application) : GoogleAuth, GoogleScreens {
    private val client = Identity.getAuthorizationClient(app)
    private val prefs = app.getSharedPreferences("google", Context.MODE_PRIVATE)
    private var token: String? = null

    private val state = MutableStateFlow(prefs.getBoolean(SIGNED_IN, false))
    override val signedIn: StateFlow<Boolean> = state

    /** Set by the activity; Google's consent screen is launched through it. */
    override var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null

    override fun onResult(result: ActivityResult) {
        pending?.complete(result)
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        // After "sign out", stay signed out even though Play services would still hand out tokens.
        if (!state.value) return null
        if (forceRefresh) token?.let { clearToken(it) }
        if (!forceRefresh) token?.let { return it }
        val result = authorize(request())
        if (result.hasResolution()) {
            setSignedIn(false)
            return null
        }
        return result.accessToken.also { token = it }
    }

    override suspend fun signIn() {
        resolve(authorize(request(AuthorizationRequest.Prompt.NOT_SET)))
    }

    /** Google's consent screen followed by its file picker, which shows only [fileId]. */
    override suspend fun pickFile(fileId: String) {
        val picker = request(AuthorizationRequest.Prompt.CONSENT) {
            setOptOutIncludingGrantedScopes(true)
            addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_FILE_IDS, fileId)
        }
        val result = resolve(authorize(picker))
        val picked = result.tokenResponseParams?.getString("picked_file_ids").orEmpty().split(',')
        if (fileId !in picked) throw RemoteException(Messages.current.driveFileNotPicked)
    }

    /** Shows Google's screens if [first] needs them, and keeps the token it ends with. */
    private suspend fun resolve(first: AuthorizationResult): AuthorizationResult {
        var result = first
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
        result.toGoogleSignInAccount()?.account?.name?.let { prefs.edit { putString(ACCOUNT, it) } }
        setSignedIn(true)
        return result
    }

    /**
     * A request for the Drive scope, for the remembered account. With a [prompt] it may show Google's
     * screens; with no account remembered, those start by asking which account to use.
     */
    private fun request(prompt: Int? = null, configure: AuthorizationRequest.Builder.() -> Unit = {}): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GoogleOAuthConfig.DRIVE_SCOPE)))
        val account = prefs.getString(ACCOUNT, null)
        if (account != null) builder.setAccount(Account(account, GOOGLE_ACCOUNT_TYPE))
        if (prompt != null) {
            builder.setPrompt(if (account == null) prompt or AuthorizationRequest.Prompt.SELECT_ACCOUNT else prompt)
        }
        return builder.apply(configure).build()
    }

    override suspend fun signOut() {
        token?.let { clearToken(it) }
        token = null
        prefs.edit { remove(ACCOUNT) }
        setSignedIn(false)
    }

    private suspend fun authorize(with: AuthorizationRequest): AuthorizationResult = try {
        client.authorize(with).await()
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
        prefs.edit { putBoolean(SIGNED_IN, value) }
    }

    private companion object {
        const val SIGNED_IN = "signedIn"
        const val ACCOUNT = "account"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
