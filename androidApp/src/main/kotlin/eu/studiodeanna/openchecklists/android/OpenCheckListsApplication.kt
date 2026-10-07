package eu.studiodeanna.openchecklists.android

import android.app.Application
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.defaultHttpClient
import eu.studiodeanna.openchecklists.google.GoogleOAuthConfig
import eu.studiodeanna.openchecklists.store.DirectoryFileStore
import kotlinx.coroutines.MainScope
import java.io.File

class OpenCheckListsApplication : Application() {
    val google by lazy { PlayServicesGoogleAuth(this) }

    /** Outlives activities, so a rotation neither reloads the lists nor interrupts a sync. */
    val repository by lazy {
        ChecklistRepository(
            DirectoryFileStore(File(filesDir, "lists")),
            MainScope(),
            defaultHttpClient(),
            google.takeIf { GoogleOAuthConfig.ANDROID_CLIENT_REGISTERED },
        )
    }
}
