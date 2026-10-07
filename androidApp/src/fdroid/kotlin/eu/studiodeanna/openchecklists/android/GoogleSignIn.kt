package eu.studiodeanna.openchecklists.android

import android.app.Application
import eu.studiodeanna.openchecklists.google.GoogleAuth

/** The F-Droid build has no Google Play services, so no Google sign-in and no Google Drive. */
@Suppress("UNUSED_PARAMETER")
fun googleAuth(app: Application): GoogleAuth? = null
