@file:OptIn(ExperimentalWasmJsInterop::class)

package eu.studiodeanna.openchecklists.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry.withPlainText(text)

/** The browser's share sheet (Web Share), which mostly phones and tablets have. */
@Composable
internal actual fun rememberShareSheet(): ((text: String) -> Unit)? = remember { if (canShare()) ::share else null }

private fun canShare(): Boolean = js("typeof navigator.share === 'function'")

private fun share(text: String): Unit = js("{ navigator.share({ text: text }).catch(() => {}); }")
