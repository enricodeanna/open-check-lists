package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.studiodeanna.openchecklists.ListChoiceNeeded
import eu.studiodeanna.openchecklists.model.Item
import eu.studiodeanna.openchecklists.model.Section
import eu.studiodeanna.openchecklists.store.LanguageChoice
import eu.studiodeanna.openchecklists.store.Settings
import eu.studiodeanna.openchecklists.store.ThemeChoice
import eu.studiodeanna.openchecklists.google.GoogleSignInRequired
import eu.studiodeanna.openchecklists.sync.RemoteException
import eu.studiodeanna.openchecklists.sync.ShareLink
import kotlinx.coroutines.launch

@Composable
fun TextDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }, enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/**
 * Asks for a share link and password, then runs [connect]; stays open showing the error if that
 * fails, and closes on success. [createDriveLink], when given, offers making a new Google Drive
 * file instead. Either action signs in to Google first if it has to.
 */
@Composable
fun LinkDialog(
    title: String,
    intro: String,
    onDismiss: () -> Unit,
    signIn: suspend () -> Unit,
    connect: suspend (ShareLink) -> Unit,
    createDriveLink: (suspend () -> Unit)? = null,
) {
    var url by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                try {
                    action()
                } catch (_: GoogleSignInRequired) {
                    signIn()
                    action()
                }
                onDismiss()
            } catch (e: RemoteException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (url.isNotBlank()) run { connect(ShareLink(url.trim(), password.ifEmpty { null })) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                if (createDriveLink != null) {
                    Button(onClick = { run(createDriveLink) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.createDriveLink)
                    }
                    Text(strings.orPasteLink, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(strings.shareLink) },
                    placeholder = { Text("https://cloud.example.com/s/…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.sharePassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = url.isNotBlank() && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(strings.connect)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}

/** Lets the user pick one of the lists in a shared folder. */
@Composable
fun ListChoiceDialog(choice: ListChoiceNeeded, onDismiss: () -> Unit, open: suspend (fileName: String) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.whichList) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(strings.severalLists, style = MaterialTheme.typography.bodyMedium)
                choice.lists.forEach { list ->
                    TextButton(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    open(list.fileName)
                                } catch (e: RemoteException) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(list.title, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}

/** Edits an item's text and moves it between sections. */
@Composable
fun ItemDialog(
    item: Item,
    sections: List<Section>,
    onDismiss: () -> Unit,
    onSave: (text: String, section: String?) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember { mutableStateOf(item.text) }
    var section by remember { mutableStateOf(item.section?.takeIf { id -> sections.any { it.id == id } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.editItem) },
        text = {
            // Scrolls so a list with many sections still fits; the buttons below stay in view.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sections.isNotEmpty()) {
                    Text(strings.section, style = MaterialTheme.typography.labelLarge)
                    FlowChips(sections, section, onSelect = { section = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, section) }, enabled = text.isNotBlank()) { Text(strings.save) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(strings.cancel) }
            }
        },
    )
}

@Composable
private fun FlowChips(sections: List<Section>, selected: String?, onSelect: (String?) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(strings.none) })
        sections.forEach { s ->
            FilterChip(selected = selected == s.id, onClick = { onSelect(s.id) }, label = { Text(s.name) })
        }
    }
}

/** Theme and language for this device; each change applies and is saved at once. */
@Composable
fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settings) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.theme, style = MaterialTheme.typography.labelLarge)
                ThemeChoice.entries.forEach { choice ->
                    val label = when (choice) {
                        ThemeChoice.System -> strings.systemDefault
                        ThemeChoice.Light -> strings.light
                        ThemeChoice.Dark -> strings.dark
                    }
                    ChoiceRow(label, settings.theme == choice) { onChange(settings.copy(theme = choice)) }
                }
                Text(strings.language, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                LanguageChoice.entries.forEach { choice ->
                    // Languages are named in themselves, so each can be found whatever is shown now.
                    val label = when (choice) {
                        LanguageChoice.System -> strings.systemDefault
                        LanguageChoice.English -> "English"
                        LanguageChoice.Italian -> "Italiano"
                    }
                    ChoiceRow(label, settings.language == choice) { onChange(settings.copy(language = choice)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
