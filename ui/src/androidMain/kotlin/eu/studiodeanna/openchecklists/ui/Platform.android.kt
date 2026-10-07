package eu.studiodeanna.openchecklists.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext

internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))

@Composable
internal actual fun rememberShareSheet(): ((text: String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
}
