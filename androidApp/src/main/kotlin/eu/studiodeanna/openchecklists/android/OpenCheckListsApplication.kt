package eu.studiodeanna.openchecklists.android

import android.app.Application
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.defaultHttpClient
import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.store.DirectoryFileStore
import kotlinx.coroutines.MainScope
import java.io.File

class OpenCheckListsApplication : Application() {
    /** Google sign-in, in builds that have it: the Play build, not the F-Droid one. */
    val google: GoogleAuth? by lazy { googleAuth(this) }

    /** Outlives activities, so a rotation neither reloads the lists nor interrupts a sync. */
    val repository by lazy {
        ChecklistRepository(
            DirectoryFileStore(File(filesDir, "lists")),
            MainScope(),
            defaultHttpClient(),
            google,
        )
    }
}
