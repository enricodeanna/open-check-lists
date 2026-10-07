package eu.studiodeanna.openchecklists.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/** A clipboard entry holding [text]; each platform builds one its own way. */
internal expect fun plainTextClip(text: String): ClipEntry

/** Opens the system's share sheet with a text, or null where the platform has none. */
@Composable
internal expect fun rememberShareSheet(): ((text: String) -> Unit)?
