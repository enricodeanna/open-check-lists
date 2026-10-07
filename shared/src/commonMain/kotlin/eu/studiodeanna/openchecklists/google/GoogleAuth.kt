package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.sync.RemoteException
import kotlinx.coroutines.flow.StateFlow

/**
 * Google sign-in, one implementation per platform. Only Drive lists need it; every editor of a Drive
 * list signs in with their own Google account, because Google does not allow anonymous edits.
 */
interface GoogleAuth {
    val signedIn: StateFlow<Boolean>

    /** A current access token, without showing anything; null when the user has to sign in first. */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    /** Shows Google's sign-in. Throws [RemoteException] if it fails or is cancelled. */
    suspend fun signIn()

    suspend fun signOut()
}

/** Thrown when a Drive list cannot sync until the user signs in; the UI offers a sign-in button. */
class GoogleSignInRequired : RemoteException(Messages.current.signInToSync)

/**
 * OAuth clients registered in the Google Cloud project (see README). Client ids are not secret, and
 * Google treats the desktop client's secret as non-confidential too. Android needs no id here: its
 * client is matched by package name and signing certificate.
 */
object GoogleOAuthConfig {
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

    /** Set to true once the Android OAuth client (package + SHA-1) is registered; until then Drive stays hidden. */
    const val ANDROID_CLIENT_REGISTERED = false

    const val DESKTOP_CLIENT_ID = ""
    const val DESKTOP_CLIENT_SECRET = ""
    const val IOS_CLIENT_ID = ""
    const val WEB_CLIENT_ID = ""
}
