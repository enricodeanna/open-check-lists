package eu.studiodeanna.openchecklists.android

import android.app.Application
import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig

/** Google sign-in through Play services, once the Android OAuth client is registered (see README). */
fun googleAuth(app: Application): GoogleAuth? =
    if (GoogleOAuthConfig.ANDROID_CLIENT_REGISTERED) PlayServicesGoogleAuth(app) else null
