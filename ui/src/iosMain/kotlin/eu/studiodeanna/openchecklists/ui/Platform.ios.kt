package eu.studiodeanna.openchecklists.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry.withPlainText(text)

@Composable
internal actual fun rememberShareSheet(): ((text: String) -> Unit)? = remember {
    { text ->
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        if (top != null) {
            val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            // iPads show the sheet as a popover, which needs a view to point at.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
    }
}
