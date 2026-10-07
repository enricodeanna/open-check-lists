package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.sync.NextcloudAccountApi
import eu.studiodeanna.openchecklists.sync.NextcloudLogin
import eu.studiodeanna.openchecklists.sync.RemoteException
import kotlinx.coroutines.launch

private sealed interface Setup {
    data object Server : Setup
    class Waiting(val login: NextcloudLogin) : Setup
    data object Folder : Setup
}

/**
 * Connects to Nextcloud: the user names their server and logs in in the browser, then confirms the
 * folder for shared lists, suggested as [NextcloudAccountApi.DEFAULT_FOLDER], or browses for
 * another. An account that only lacks a folder starts at the folder; logging in again to an account
 * that has one skips it.
 */
@Composable
fun NextcloudSetupDialog(repository: ChecklistRepository, onDismiss: () -> Unit, onDone: () -> Unit) {
    val existing = remember { repository.nextcloudAccount.value }
    var step by remember { mutableStateOf(if (existing != null && existing.folder == null) Setup.Folder else Setup.Server) }
    var server by remember { mutableStateOf(existing?.server?.removePrefix("https://").orEmpty()) }
    var folder by remember { mutableStateOf(existing?.folder ?: NextcloudAccountApi.DEFAULT_FOLDER) }
    var browsing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // Without a browser to open it, "Open again" is still there to retry.
    fun openInBrowser(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    fun logIn() {
        if (busy || server.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                val login = repository.startNextcloudSignIn(server)
                openInBrowser(login.loginUrl)
                step = Setup.Waiting(login)
            } catch (e: RemoteException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun saveFolder() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                repository.setNextcloudFolder(folder)
                onDone()
            } finally {
                busy = false
            }
        }
    }

    // Leaving the dialog stops the wait, as the effect is cancelled with it.
    (step as? Setup.Waiting)?.let { waiting ->
        LaunchedEffect(waiting) {
            try {
                val account = repository.finishNextcloudSignIn(waiting.login)
                if (account.folder == null) step = Setup.Folder else onDone()
            } catch (e: RemoteException) {
                error = e.message
                step = Setup.Server
            }
        }
    }

    if (browsing) {
        NextcloudFolderPicker(
            repository,
            initial = folder,
            onDismiss = { browsing = false },
            onPick = {
                folder = it
                browsing = false
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (step == Setup.Folder) strings.nextcloudFolder else strings.connectNextcloud) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    Setup.Server -> {
                        Text(strings.connectNextcloudIntro, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text(strings.nextcloudAddress) },
                            placeholder = { Text("cloud.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { logIn() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Setup.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(strings.logInInBrowser, style = MaterialTheme.typography.bodyMedium)
                    }
                    Setup.Folder -> {
                        Text(strings.nextcloudFolderIntro, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(FolderIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(folder.replace("/", " › "), style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = { browsing = true }, enabled = !busy) { Text(strings.otherFolder) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            when (val current = step) {
                Setup.Server -> TextButton(onClick = ::logIn, enabled = server.isNotBlank() && !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(strings.logIn)
                }
                is Setup.Waiting -> TextButton(onClick = { openInBrowser(current.login.loginUrl) }) { Text(strings.openAgain) }
                Setup.Folder -> TextButton(onClick = ::saveFolder, enabled = !busy) { Text(strings.save) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}

/**
 * Lets the user choose a folder in their Nextcloud by browsing it, starting at [initial], or the
 * nearest folder above it that exists; folders can be created on the way. The top of the user's
 * files cannot be chosen, so shared lists stay apart from everything else there.
 */
@Composable
fun NextcloudFolderPicker(
    repository: ChecklistRepository,
    initial: String,
    note: String? = null,
    onDismiss: () -> Unit,
    onPick: (path: String) -> Unit,
) {
    var location by remember { mutableStateOf(initial) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun inside(name: String) = if (location.isEmpty()) name else "$location/$name"

    LaunchedEffect(location) {
        folders = null
        error = null
        try {
            val found = repository.nextcloudFolders(location)
            // Not there (yet): show the folder above it instead.
            if (found == null && location.isNotEmpty()) location = location.substringBeforeLast('/', "") else folders = found.orEmpty()
        } catch (e: RemoteException) {
            error = e.message
            folders = emptyList()
        }
    }

    if (naming) {
        TextDialog(
            title = strings.newFolder,
            label = strings.name,
            confirm = strings.create,
            onDismiss = { naming = false },
        ) { name ->
            naming = false
            val path = NextcloudAccountApi.folderPath(name)?.let(::inside) ?: return@TextDialog
            scope.launch {
                try {
                    repository.createNextcloudFolder(path)
                    location = path
                } catch (e: RemoteException) {
                    error = e.message
                }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.chooseFolder) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp)) }
                Text(
                    (listOf("Nextcloud") + location.split('/').filter { it.isNotEmpty() }).joinToString(" › "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (location.isNotEmpty()) {
                    FolderRow(Icons.AutoMirrored.Filled.ArrowBack, strings.parentFolder) {
                        location = location.substringBeforeLast('/', "")
                    }
                }
                when (val list = folders) {
                    null -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
                    else -> {
                        if (list.isEmpty() && error == null) {
                            Text(
                                strings.noSubfolders,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        list.forEach { name -> FolderRow(FolderIcon, name, opens = true) { location = inside(name) } }
                    }
                }
                FolderRow(Icons.Default.Add, strings.newFolder) { naming = true }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (location.isEmpty()) {
                    Text(
                        strings.chooseFolderHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(location) }, enabled = location.isNotEmpty() && folders != null) {
                Text(strings.useThisFolder)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun FolderRow(icon: ImageVector, text: String, opens: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (opens) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** Material's "folder", which is not among the core icons. */
private val FolderIcon: ImageVector = materialIcon(name = "Filled.Folder") {
    materialPath {
        moveTo(10f, 4f)
        horizontalLineTo(4f)
        curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
        lineTo(2f, 18f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(16f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(8f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        horizontalLineToRelative(-8f)
        lineToRelative(-2f, -2f)
        close()
    }
}
