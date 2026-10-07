package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.ListChoiceNeeded
import eu.studiodeanna.openchecklists.ListEntry
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.sync.NextcloudAccountApi
import kotlinx.coroutines.launch

/** The dialogs the settings open for the Nextcloud account; the settings step aside meanwhile. */
private enum class AccountDialog { Connect, Folder, Disconnect }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(repository: ChecklistRepository, onOpen: (String) -> Unit) {
    val lists by repository.lists.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var accountDialog by remember { mutableStateOf<AccountDialog?>(null) }
    var choosing by remember { mutableStateOf<ListChoiceNeeded?>(null) }
    val googleSignedIn by repository.googleSignedIn.collectAsState()
    val settings by repository.settings.collectAsState()
    val nextcloud by repository.nextcloudAccount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.appName) },
                actions = {
                    IconButton(onClick = { joining = true }) {
                        Icon(Icons.Default.Share, contentDescription = strings.openShared)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.more) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(strings.settings) },
                                onClick = {
                                    menu = false
                                    showSettings = true
                                },
                            )
                            if (googleSignedIn) {
                                DropdownMenuItem(
                                    text = { Text(strings.signOutOfGoogle) },
                                    onClick = {
                                        menu = false
                                        scope.launch { repository.signOutOfGoogle() }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(strings.newList) },
            )
        },
    ) { padding ->
        if (lists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    strings.noLists,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lists.sortedBy { it.doc.title.lowercase() }, key = { it.id }) { entry ->
                        ListCard(entry, onClick = { onOpen(entry.id) })
                    }
                }
            }
        }
    }

    if (showSettings && accountDialog == null) {
        SettingsDialog(
            settings = settings,
            nextcloud = nextcloud,
            onChange = { scope.launch { repository.updateSettings(it) } },
            onConnectNextcloud = { accountDialog = AccountDialog.Connect },
            onChangeFolder = { accountDialog = AccountDialog.Folder },
            onDisconnectNextcloud = { accountDialog = AccountDialog.Disconnect },
            onDismiss = { showSettings = false },
        )
    }
    when (accountDialog) {
        AccountDialog.Connect -> NextcloudSetupDialog(
            repository,
            onDismiss = { accountDialog = null },
            onDone = { accountDialog = null },
        )
        AccountDialog.Folder -> NextcloudFolderPicker(
            repository,
            initial = nextcloud?.folder ?: NextcloudAccountApi.DEFAULT_FOLDER,
            note = strings.nextcloudFolderIntro + " " + strings.folderChangeNote,
            onDismiss = { accountDialog = null },
            onPick = { folder ->
                accountDialog = null
                scope.launch { repository.setNextcloudFolder(folder) }
            },
        )
        AccountDialog.Disconnect -> ConfirmDialog(
            title = strings.disconnectTitle,
            message = strings.disconnectMessage,
            confirm = strings.disconnect,
            onDismiss = { accountDialog = null },
            onConfirm = {
                accountDialog = null
                scope.launch { repository.signOutOfNextcloud() }
            },
        )
        null -> Unit
    }
    if (creating) {
        TextDialog(
            title = strings.newList,
            label = strings.name,
            confirm = strings.create,
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch { onOpen(repository.createList(name)) }
            },
        )
    }
    if (joining) {
        LinkDialog(
            title = strings.openShared,
            intro = strings.openSharedIntro(repository.googleAvailable),
            onDismiss = { joining = false },
            signIn = repository::signInToGoogle,
            pickFile = repository::pickGoogleFile,
            connect = { link ->
                try {
                    onOpen(repository.openShared(link))
                } catch (choice: ListChoiceNeeded) {
                    choosing = choice
                }
            },
        )
    }
    choosing?.let { choice ->
        ListChoiceDialog(
            choice = choice,
            onDismiss = { choosing = null },
            open = { fileName ->
                onOpen(repository.openShared(choice.link.copy(fileName = fileName)))
                choosing = null
            },
        )
    }
}

@Composable
private fun ListCard(entry: ListEntry, onClick: () -> Unit) {
    val items = entry.doc.liveItems()
    val done = items.count { it.done }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.doc.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SyncBadge(entry)
            }
            Text(
                if (items.isEmpty()) strings.emptyList else strings.doneOf(done, items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
        }
    }
}
