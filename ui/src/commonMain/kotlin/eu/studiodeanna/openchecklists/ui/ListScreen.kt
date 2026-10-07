package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.ListEntry
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.model.Item
import eu.studiodeanna.openchecklists.model.ListDocument
import eu.studiodeanna.openchecklists.model.ListEditor
import eu.studiodeanna.openchecklists.model.Section
import eu.studiodeanna.openchecklists.model.itemsBySection
import eu.studiodeanna.openchecklists.model.visibleSections
import eu.studiodeanna.openchecklists.sync.ShareLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_MILLIS = 15_000L

private sealed interface Dialog {
    data object RenameList : Dialog
    data object AddSection : Dialog
    data class RenameSection(val section: Section) : Dialog
    data class DeleteSection(val section: Section) : Dialog
    data class EditItem(val item: Item) : Dialog
    data object Share : Dialog
    data object DeleteList : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(repository: ChecklistRepository, id: String, onBack: () -> Unit) {
    val lists by repository.lists.collectAsState()
    val entry = lists.firstOrNull { it.id == id }
    if (entry == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val doc = entry.doc
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    /** The section whose "add item" field is open; [TOP] for items outside sections. */
    var adding by remember { mutableStateOf<String?>(null) }

    val linked = entry.link != null
    LaunchedEffect(id, linked) {
        while (linked) {
            repository.syncNow(id)
            delay(POLL_MILLIS)
        }
    }

    fun edit(change: ListEditor.(ListDocument) -> ListDocument) = repository.edit(id, change)

    val snackbars = remember { SnackbarHostState() }
    val texts = strings
    /** Says what an undo or redo did, replacing the previous message so quick taps don't queue up. */
    fun announce(message: String) {
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(message)
        }
    }
    fun undo() = repository.undo(id)?.let { announce(texts.undone(it)) }
    fun redo() = repository.redo(id)?.let { announce(texts.redone(it)) }

    // Holds the keyboard focus whenever no field or dialog has it, so Ctrl+Z and Ctrl+Y reach the list.
    val keys = remember { FocusRequester() }
    LaunchedEffect(adding, dialog) {
        if (adding == null && dialog == null) keys.requestFocus()
    }

    /** While a row is dragged: the order of the rows it can trade places with, as dragged so far. */
    var draft by remember { mutableStateOf<Draft?>(null) }
    /** The section an item was just ticked in, kept open for a moment so the tick shows before it folds. */
    var hold by remember { mutableStateOf<Hold?>(null) }
    LaunchedEffect(hold) {
        if (hold != null) {
            delay(FOLD_DELAY_MILLIS)
            hold = null
        }
    }

    fun toggle(item: Item) {
        hold = Hold(item.section, (hold?.tick ?: 0) + 1)
        edit { setDone(it, item.id, !item.done) }
    }

    val grouped = doc.itemsBySection()
    fun sectionsInOrder(d: Draft?) = doc.visibleSections().inDraft(d?.takeIf { it.section }) { it.id }
    /** A section's items as shown: unchecked ones first, so what is left to get stays together at the top. */
    fun itemsInOrder(section: String?, d: Draft?): List<Item> {
        val items = grouped[section].orEmpty()
        val itemDraft = d?.takeIf { !it.section }
        return items.filterNot { it.done }.inDraft(itemDraft) { it.id } + items.filter { it.done }.inDraft(itemDraft) { it.id }
    }
    /** Ids of the rows that row [key] can trade places with, its own included: the sections, or the items of its section ticked or not like it. */
    fun groupOf(key: String, d: Draft?): List<String> {
        if (key.startsWith(SECTION)) return sectionsInOrder(d).map { it.id }
        val (section, items) = grouped.entries.firstOrNull { (_, rows) -> rows.any { it.id == key } } ?: return emptyList()
        val done = items.first { it.id == key }.done
        return itemsInOrder(section, d).filter { it.done == done }.map { it.id }
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            from as String
            val ids = groupOf(from, draft)
            val moved = from.removePrefix(SECTION)
            val target = (to as? String)?.takeIf { it.startsWith(SECTION) == from.startsWith(SECTION) }?.removePrefix(SECTION)
            if (target == null || target !in ids) {
                false
            } else {
                val order = ids.toMutableList().apply { add(ids.indexOf(target), removeAt(ids.indexOf(moved))) }
                draft = Draft(moved, from.startsWith(SECTION), order)
                true
            }
        },
        canScroll = { key, direction ->
            key as String
            val ids = groupOf(key, draft)
            ids.indexOf(key.removePrefix(SECTION)) + direction in ids.indices
        },
        onDrop = { key ->
            key as String
            val moved = key.removePrefix(SECTION)
            val ids = groupOf(key, draft)
            val unchanged = ids == groupOf(key, null)
            draft = null
            if (!unchanged) {
                val at = ids.indexOf(moved)
                val previous = ids.getOrNull(at - 1)
                val next = ids.getOrNull(at + 1)
                if (key.startsWith(SECTION)) edit { placeSection(it, moved, previous, next) }
                else edit { placeItem(it, moved, previous, next) }
            }
        },
    )

    Scaffold(
        modifier = Modifier
            .onKeyEvent { event ->
                // Text fields take these first, for undoing typing.
                if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return@onKeyEvent false
                when {
                    event.key == Key.Z && !event.isShiftPressed -> undo()
                    event.key == Key.Z || event.key == Key.Y -> redo()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(keys)
            .focusable(),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                title = {
                    Column(Modifier.clickable { dialog = Dialog.RenameList }) {
                        Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (linked) {
                            Text(
                                describe(entry.sync, strings),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.sync is SyncState.Failed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { undo() }, enabled = entry.history.canUndo) {
                        Icon(UndoIcon, contentDescription = strings.undo)
                    }
                    IconButton(onClick = { redo() }, enabled = entry.history.canRedo) {
                        Icon(RedoIcon, contentDescription = strings.redo)
                    }
                    IconButton(onClick = { dialog = Dialog.Share }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = strings.sharing,
                            tint = if (linked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.more) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (linked) MenuItem(strings.syncNow) { menu = false; repository.syncNow(id) }
                            MenuItem(strings.renameList) { menu = false; dialog = Dialog.RenameList }
                            MenuItem(strings.addSection) { menu = false; dialog = Dialog.AddSection }
                            MenuItem(strings.uncheckAll) { menu = false; edit { uncheckAll(it) } }
                            MenuItem(strings.removeChecked) { menu = false; edit { clearDone(it) } }
                            HorizontalDivider()
                            MenuItem(strings.deleteList) { menu = false; dialog = Dialog.DeleteList }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val sections = sectionsInOrder(draft)
        // A section being dragged hides its items, so it moves as one short row.
        val draggedSection = (reorder.dragging as? String)?.takeIf { it.startsWith(SECTION) }?.removePrefix(SECTION)
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                (entry.sync as? SyncState.Failed)?.let { failed ->
                    item(key = "sync-error") {
                        val pick = failed.pickFile
                        SyncErrorBanner(
                            message = failed.message,
                            action = when {
                                failed.needsSignIn -> strings.signIn
                                pick != null -> strings.chooseFile
                                else -> strings.retry
                            },
                            onAction = {
                                when {
                                    failed.needsSignIn -> scope.launch { runCatching { repository.signInToGoogle() } }
                                    pick != null -> scope.launch { runCatching { repository.pickGoogleFile(pick) } }
                                    else -> repository.syncNow(id)
                                }
                            },
                        )
                    }
                }

                val topItems = itemsInOrder(null, draft)
                if (topItems.isNotEmpty() || sections.isEmpty()) {
                    itemRows(topItems, reorder, onToggle = { toggle(it) }) { dialog = Dialog.EditItem(it) }
                    item(key = "add-top") {
                        AddItemRow(
                            open = adding == TOP,
                            onOpen = { adding = TOP },
                            onClose = { if (adding == TOP) adding = null },
                            onAdd = { text -> edit { addItem(it, null, text) } },
                            modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = reorder.placement(), fadeOutSpec = null),
                        )
                    }
                }

                sections.forEachIndexed { index, section ->
                    val items = grouped[section.id].orEmpty()
                    val folded = section.id in entry.collapsed && hold?.section != section.id
                    item(key = SECTION + section.id) {
                        ReorderableRow(reorder, SECTION + section.id) {
                            SectionHeader(
                                section = section,
                                done = items.count { it.done },
                                total = items.size,
                                folded = folded,
                                canMoveUp = index > 0,
                                canMoveDown = index < sections.lastIndex,
                                onToggle = {
                                    if (hold?.section == section.id) hold = null
                                    if (!folded && adding == section.id) adding = null
                                    repository.setCollapsed(id, section.id, !folded)
                                },
                                onRename = { dialog = Dialog.RenameSection(section) },
                                onMove = { offset -> edit { moveSection(it, section.id, offset) } },
                                onDelete = {
                                    if (items.isEmpty()) edit { deleteSection(it, section.id) }
                                    else dialog = Dialog.DeleteSection(section)
                                },
                                handle = { DragHandle(reorder, SECTION + section.id) },
                            )
                        }
                    }
                    if (!folded && section.id != draggedSection) {
                        itemRows(itemsInOrder(section.id, draft), reorder, SECTION_INDENT, onToggle = { toggle(it) }) {
                            dialog = Dialog.EditItem(it)
                        }
                        item(key = "add-${section.id}") {
                            AddItemRow(
                                open = adding == section.id,
                                onOpen = { adding = section.id },
                                onClose = { if (adding == section.id) adding = null },
                                onAdd = { text -> edit { addItem(it, section.id, text) } },
                                modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = reorder.placement(), fadeOutSpec = null)
                                    .padding(start = SECTION_INDENT),
                            )
                        }
                    }
                }

                item(key = "add-section") {
                    OutlinedButton(
                        onClick = { dialog = Dialog.AddSection },
                        modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = reorder.placement(), fadeOutSpec = null)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(strings.addSection, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.RenameList -> TextDialog(strings.renameList, strings.name, strings.rename, doc.title, { dialog = null }) { name ->
            dialog = null
            edit { rename(it, name) }
        }
        Dialog.AddSection -> TextDialog(strings.addSection, strings.sectionNameHint, strings.add, onDismiss = { dialog = null }) { name ->
            dialog = null
            edit { addSection(it, name) }
        }
        is Dialog.RenameSection -> TextDialog(strings.renameSection, strings.name, strings.rename, d.section.name, { dialog = null }) { name ->
            dialog = null
            edit { renameSection(it, d.section.id, name) }
        }
        is Dialog.DeleteSection -> ConfirmDialog(
            title = strings.deleteTitle(d.section.name),
            message = strings.deleteSectionMessage,
            confirm = strings.delete,
            onDismiss = { dialog = null },
        ) {
            dialog = null
            edit { deleteSection(it, d.section.id) }
        }
        is Dialog.EditItem -> ItemDialog(
            item = d.item,
            sections = doc.visibleSections(),
            onDismiss = { dialog = null },
            onSave = { text, section ->
                dialog = null
                edit {
                    val renamed = if (text.trim() != d.item.text) editItem(it, d.item.id, text) else it
                    if (section != d.item.section) moveItem(renamed, d.item.id, section) else renamed
                }
            },
            onDelete = {
                dialog = null
                edit { deleteItem(it, d.item.id) }
            },
        )
        Dialog.Share -> if (linked) {
            SharedDialog(
                entry = entry,
                onDismiss = { dialog = null },
                onSync = { repository.syncNow(id) },
                onStop = {
                    dialog = null
                    scope.launch { repository.detachLink(id) }
                },
            )
        } else {
            LinkDialog(
                title = strings.shareThisList,
                intro = strings.shareHelp(repository.googleAvailable),
                onDismiss = { dialog = null },
                // Once linked, this same dialog shows the link to send.
                onDone = {},
                signIn = repository::signInToGoogle,
                pickFile = repository::pickGoogleFile,
                connect = { link -> repository.attachLink(id, link) },
                createDriveLink = if (repository.googleAvailable) {
                    { repository.shareViaGoogleDrive(id) }
                } else {
                    null
                },
                createNextcloudLink = { repository.shareViaNextcloud(id) },
                nextcloudSetup = { dismiss, done -> NextcloudSetupDialog(repository, dismiss, done) },
            )
        }
        Dialog.DeleteList -> ConfirmDialog(
            title = strings.deleteTitle(doc.title),
            message = if (linked) strings.deleteSharedListMessage else strings.cantBeUndone,
            confirm = strings.delete,
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch { repository.deleteList(id) }
        }
    }
}

private const val TOP = "\u0000top"

/** Prefix of a section header's key in the list; items use their bare id. */
private const val SECTION = "section-"

private const val FOLD_DELAY_MILLIS = 600L

/** How far a section's items sit to the right of its header, so they read as belonging to it. */
private val SECTION_INDENT = 24.dp

/** A drag in progress: the dragged section or item, and the order of its group so far. */
private data class Draft(val id: String, val section: Boolean, val order: List<String>)

/** Material's "undo" arrow, which is not among the core icons. */
private val UndoIcon: ImageVector = materialIcon(name = "AutoMirrored.Filled.Undo", autoMirror = true) {
    materialPath {
        moveTo(12.5f, 8f)
        curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
        lineTo(2f, 7f)
        verticalLineToRelative(9f)
        horizontalLineToRelative(9f)
        lineToRelative(-3.62f, -3.62f)
        curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
        curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
        lineToRelative(2.37f, -0.78f)
        curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
        close()
    }
}

/** Material's "redo" arrow, which is not among the core icons. */
private val RedoIcon: ImageVector = materialIcon(name = "AutoMirrored.Filled.Redo", autoMirror = true) {
    materialPath {
        moveTo(18.4f, 10.6f)
        curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
        curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f)
        lineTo(3.9f, 16f)
        curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
        curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
        lineTo(13f, 16f)
        horizontalLineToRelative(9f)
        verticalLineTo(7f)
        lineToRelative(-3.6f, 3.6f)
        close()
    }
}

/** [tick] makes each tick a new hold, restarting its delay. */
private data class Hold(val section: String?, val tick: Int)

/** This list in [draft]'s order, while one of its elements is the one being dragged. */
private fun <T> List<T>.inDraft(draft: Draft?, id: (T) -> String): List<T> {
    if (draft == null || none { id(it) == draft.id }) return this
    val byId = associateBy(id)
    return draft.order.mapNotNull { byId[it] } + filter { id(it) !in draft.order }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)

private fun LazyListScope.itemRows(
    items: List<Item>,
    reorder: ReorderState,
    indent: Dp = 0.dp,
    onToggle: (Item) -> Unit,
    onEdit: (Item) -> Unit,
) {
    items(items, key = { it.id }) { item ->
        ReorderableRow(reorder, item.id) {
            ItemRow(item, indent, onToggle = { onToggle(item) }, onEdit = { onEdit(item) }) { DragHandle(reorder, item.id) }
        }
    }
}

@Composable
private fun ItemRow(item: Item, indent: Dp, onToggle: () -> Unit, onEdit: () -> Unit, handle: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp + indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.done, onCheckedChange = { onToggle() })
        Text(
            item.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        TextButton(onClick = onEdit) { Text(strings.edit, style = MaterialTheme.typography.labelMedium) }
        handle()
    }
}

@Composable
private fun SectionHeader(
    section: Section,
    done: Int,
    total: Int,
    folded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    handle: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (folded) strings.expandSection else strings.collapseSection, onClick = onToggle)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only shows whether everything in the section is checked; it is not a control.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Checkbox(checked = total > 0 && done == total, onCheckedChange = null)
            }
            Text(
                section.name,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text("$done/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (folded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.sectionOptions) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(strings.rename) { menu = false; onRename() }
                    if (canMoveUp) MenuItem(strings.moveUp) { menu = false; onMove(-1) }
                    if (canMoveDown) MenuItem(strings.moveDown) { menu = false; onMove(1) }
                    MenuItem(strings.deleteSection) { menu = false; onDelete() }
                }
            }
            handle()
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

/** A collapsed "Add item" button that opens into a field; Enter adds and keeps it open for the next one. */
@Composable
private fun AddItemRow(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) {
        TextButton(onClick = onOpen, modifier = modifier.padding(start = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(strings.addItem, Modifier.padding(start = 8.dp))
        }
        return
    }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    TextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(strings.addItem) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isBlank()) onClose() else submit() }),
        trailingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = strings.doneAdding) }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp).focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SyncErrorBanner(message: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) {
            Text(action, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SharedDialog(entry: ListEntry, onDismiss: () -> Unit, onSync: () -> Unit, onStop: () -> Unit) {
    val link = entry.link ?: return
    val shareable = ShareLinks.shareable(link)
    val clipboard = LocalClipboard.current
    val shareSheet = rememberShareSheet()
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val sendText = strings.sendText(shareable, link.password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.sharedList) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (link.url.contains("google.com")) {
                        strings.anyoneWithLinkGoogle
                    } else {
                        strings.anyoneWithLink
                    },
                )
                SelectionContainer {
                    Text(shareable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { clipboard.setClipEntry(plainTextClip(shareable)) }
                        copied = true
                    }) {
                        Text(if (copied) strings.copied else strings.copyLink)
                    }
                    shareSheet?.let { share ->
                        Button(onClick = { share(sendText) }) { Text(strings.send) }
                    }
                }
                link.fileName?.let {
                    Text(
                        strings.keptInFolder(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                link.password?.let { password ->
                    SelectionContainer { Text(strings.needsSharePassword(password), style = MaterialTheme.typography.bodySmall) }
                }
                link.expires?.let { Text(strings.linkExpires(it), style = MaterialTheme.typography.bodySmall) }
                Text(describe(entry.sync, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onStop) { Text(strings.stopSyncing, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
        dismissButton = { TextButton(onClick = onSync) { Text(strings.syncNow) } },
    )
}
