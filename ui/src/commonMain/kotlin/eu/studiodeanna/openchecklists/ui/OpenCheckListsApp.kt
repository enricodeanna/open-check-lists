package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.store.LanguageChoice

/** [openList] starts on that list instead of the overview, when it exists. */
@Composable
fun OpenCheckListsApp(repository: ChecklistRepository, openList: String? = null) {
    val settings by repository.settings.collectAsState()
    val strings = when (settings.language) {
        LanguageChoice.English -> EnglishStrings
        LanguageChoice.Italian -> ItalianStrings
        LanguageChoice.System -> if (Locale.current.language == "it") ItalianStrings else EnglishStrings
    }
    SideEffect { Messages.current = strings.messages }
    OpenCheckListsTheme(darkTheme = settings.theme.isDark()) {
        CompositionLocalProvider(LocalStrings provides strings) {
            Surface(Modifier.fillMaxSize()) {
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(repository) {
                    repository.load()
                    loaded = true
                    repository.syncAll()
                }
                if (!loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Surface
                }

                val nav = rememberNavController()
                LaunchedEffect(openList) {
                    if (openList != null && repository.entry(openList) != null) nav.navigate("list/$openList")
                }
                NavHost(nav, startDestination = "lists") {
                    composable("lists") {
                        ListsScreen(repository, onOpen = { nav.navigate("list/$it") })
                    }
                    composable("list/{id}") { entry ->
                        val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                        ListScreen(repository, id, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
