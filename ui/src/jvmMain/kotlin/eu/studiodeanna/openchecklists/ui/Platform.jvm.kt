package eu.studiodeanna.openchecklists.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(StringSelection(text))

/** Desktops have no share sheet; the link is copied instead. */
@Composable
internal actual fun rememberShareSheet(): ((text: String) -> Unit)? = null
