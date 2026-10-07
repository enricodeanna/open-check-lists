package eu.studiodeanna.openchecklists.google

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.sync.RemoteException
import kotlinx.coroutines.flow.StateFlow

/**
 * Google sign-in, one implementation per platform. Only Drive lists need it; every editor of a Drive
 * list signs in with their own Google account, because Google does not allow anonymous edits.
 *
 * The app asks only for the `drive.file` scope: it may use the files it created, and the files the
 * user picked for it in Google's file picker. A list someone else shared is therefore picked once,
 * through [pickFile], before this user's app may open it.
 */
interface GoogleAuth {
    val signedIn: StateFlow<Boolean>

    /** A current access token, without showing anything; null when the user has to sign in first. */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    /** Shows Google's sign-in. Throws [RemoteException] if it fails or is cancelled. */
    suspend fun signIn()

    /**
     * Shows Google's file picker showing only [fileId], which signs in too. Returns once the user has
     * picked it; throws [RemoteException] if they cancel or the picker could not show the file.
     */
    suspend fun pickFile(fileId: String)

    suspend fun signOut()
}

/** Thrown when a Drive list cannot sync until the user signs in; the UI offers a sign-in button. */
class GoogleSignInRequired : RemoteException(Messages.current.signInToSync)

/**
 * Thrown when this user has not picked a Drive list's file for the app yet, so Drive will not let the
 * app use it; the UI then shows Google's file picker for [fileId].
 */
class GoogleFileAccessRequired(val fileId: String) : RemoteException(Messages.current.driveChooseFile)

/**
 * OAuth clients registered in the Google Cloud project (see README). Client ids are not secret, and
 * Google treats the desktop client's secret as non-confidential too. Android needs no id here: its
 * client is matched by package name and signing certificate.
 */
object GoogleOAuthConfig {
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    /** Set to true once the Android OAuth client (package + SHA-1) is registered; until then Drive stays hidden. */
    const val ANDROID_CLIENT_REGISTERED = true

    const val DESKTOP_CLIENT_ID = ""
    const val DESKTOP_CLIENT_SECRET = ""
    const val IOS_CLIENT_ID = ""
    const val WEB_CLIENT_ID = ""

    /** The web app's file picker also needs an API key and the Cloud project's number. */
    const val WEB_API_KEY = ""
    const val CLOUD_PROJECT_NUMBER = ""
}
