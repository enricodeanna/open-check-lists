package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.studiodeanna.openchecklists.store.ThemeChoice

private val Light = lightColorScheme(
    primary = Color(0xFF1F6A5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2E1),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    secondaryContainer = Color(0xFFCCE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFFB85C1B),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceContainerLow = Color(0xFFF1F5F3),
    surfaceContainer = Color(0xFFEBF0ED),
    surfaceContainerHigh = Color(0xFFE5EAE7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD5C5),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA7F2E1),
    secondary = Color(0xFFB1CCC4),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFFFB68A),
    background = Color(0xFF0F1513),
    surface = Color(0xFF0F1513),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
)

/** Whether this choice means dark colors right now. */
@Composable
fun ThemeChoice.isDark(): Boolean = when (this) {
    ThemeChoice.System -> isSystemInDarkTheme()
    ThemeChoice.Light -> false
    ThemeChoice.Dark -> true
}

@Composable
fun OpenCheckListsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
