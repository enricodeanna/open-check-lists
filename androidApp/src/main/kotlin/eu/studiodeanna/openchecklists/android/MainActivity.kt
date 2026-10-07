package eu.studiodeanna.openchecklists.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.studiodeanna.openchecklists.ui.OpenCheckListsApp
import eu.studiodeanna.openchecklists.ui.isDark

class MainActivity : ComponentActivity() {
    private val app get() = application as OpenCheckListsApplication

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        app.google.onResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.google.launcher = googleConsent
        setContent {
            // The bars' icons follow the app's theme, which the settings may set apart from the system's.
            val settings by app.repository.settings.collectAsState()
            val dark = settings.theme.isDark()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            OpenCheckListsApp(app.repository)
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up what others changed while the app was in the background.
        app.repository.syncAll()
    }

    override fun onDestroy() {
        if (app.google.launcher === googleConsent) app.google.launcher = null
        super.onDestroy()
    }

    private companion object {
        // The scrims enableEdgeToEdge uses by default.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
