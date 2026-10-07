package eu.studiodeanna.openchecklists.android

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * A Google sign-in that shows Google's screens through the activity: the activity hands it a
 * [launcher] for them and passes back what they return.
 */
interface GoogleScreens {
    var launcher: ActivityResultLauncher<IntentSenderRequest>?

    fun onResult(result: ActivityResult)
}
