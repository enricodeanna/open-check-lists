package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.store.FileStore
import eu.studiodeanna.openchecklists.sync.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Google's OAuth "installed app" flow with PKCE, shared by the desktop and iOS sign-ins: the platform
 * opens [AuthRequest.url] in a browser and hands back the redirect; this class does the rest. The
 * refresh token is kept in [store], so sign-in survives restarts.
 */
class PkceOAuthClient(
    private val clientId: String,
    private val clientSecret: String?,
    private val store: FileStore,
    private val http: HttpClient,
    private val now: () -> Long,
) {
    class AuthRequest(val url: String, val redirectUri: String, val state: String, val verifier: String)

    @Serializable
    private data class Saved(val refreshToken: String)

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Long = 3600,
        val refresh_token: String? = null,
    )

    private var accessToken: String? = null
    private var expiresAt = 0L
    private var refreshToken: String? = null
    private var loaded = false

    suspend fun hasSession(): Boolean {
        loadSaved()
        return refreshToken != null
    }

    @OptIn(ExperimentalUuidApi::class)
    fun newRequest(redirectUri: String): AuthRequest {
        // Uuid.random() is backed by a cryptographically secure generator on every platform.
        val verifier = Uuid.random().toHexString() + Uuid.random().toHexString()
        val state = Uuid.random().toHexString()
        val url = URLBuilder("https://accounts.google.com/o/oauth2/v2/auth").apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", GoogleOAuthConfig.DRIVE_SCOPE)
            parameters.append("code_challenge", codeChallenge(verifier))
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
        }.buildString()
        return AuthRequest(url, redirectUri, state, verifier)
    }

    /** Completes sign-in with the redirect's query parameters. */
    suspend fun complete(request: AuthRequest, redirectParams: Map<String, String>) {
        redirectParams["error"]?.let { throw RemoteException(if (it == "access_denied") Messages.current.signInCancelled else Messages.current.signInFailed(it)) }
        if (redirectParams["state"] != request.state) throw RemoteException(Messages.current.signInMismatch)
        val code = redirectParams["code"] ?: throw RemoteException(Messages.current.signInNoCode)
        val tokens = tokenCall(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to request.verifier,
            "redirect_uri" to request.redirectUri,
        ) ?: throw RemoteException(Messages.current.signInRetry)
        refreshToken = tokens.refresh_token ?: refreshToken
        refreshToken?.let { store.write(FILE, json.encodeToString(Saved.serializer(), Saved(it))) }
    }

    suspend fun accessToken(forceRefresh: Boolean): String? {
        loadSaved()
        if (!forceRefresh && accessToken != null && now() < expiresAt - 60_000) return accessToken
        val refresh = refreshToken ?: return null
        val tokens = tokenCall("grant_type" to "refresh_token", "refresh_token" to refresh)
        if (tokens == null) {
            // Revoked or expired: the user has to sign in again.
            clear()
            return null
        }
        return tokens.access_token
    }

    suspend fun clear() {
        accessToken = null
        refreshToken = null
        expiresAt = 0
        store.delete(FILE)
    }

    private suspend fun tokenCall(vararg params: Pair<String, String>): TokenResponse? {
        val response = try {
            http.submitForm(
                "https://oauth2.googleapis.com/token",
                parameters {
                    append("client_id", clientId)
                    clientSecret?.takeIf { it.isNotEmpty() }?.let { append("client_secret", it) }
                    params.forEach { (k, v) -> append(k, v) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(Messages.current.cantReachGoogle, e)
        }
        if (!response.status.isSuccess()) return null
        val tokens = json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        accessToken = tokens.access_token
        expiresAt = now() + tokens.expires_in * 1000
        return tokens
    }

    private suspend fun loadSaved() {
        if (loaded) return
        loaded = true
        refreshToken = store.read(FILE)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }?.refreshToken
    }

    companion object {
        private const val FILE = "google-session.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun codeChallenge(verifier: String): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(sha256(verifier.encodeToByteArray()))

        /** Query parameters of a redirect URL, decoded. */
        fun queryParams(url: String): Map<String, String> =
            parseQueryString(url.substringAfter('?', "").substringBefore('#'))
                .entries().associate { (k, v) -> k to v.first() }
    }
}
