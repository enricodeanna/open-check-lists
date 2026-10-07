package eu.studiodeanna.openchecklists

import eu.studiodeanna.openchecklists.model.Action
import eu.studiodeanna.openchecklists.model.History
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.ListDocument
import eu.studiodeanna.openchecklists.model.ListEditor
import eu.studiodeanna.openchecklists.model.changeBetween
import eu.studiodeanna.openchecklists.model.completeSections
import eu.studiodeanna.openchecklists.model.foldCompleted
import eu.studiodeanna.openchecklists.model.merge
import eu.studiodeanna.openchecklists.model.normalized
import eu.studiodeanna.openchecklists.model.pruned
import eu.studiodeanna.openchecklists.store.FileStore
import eu.studiodeanna.openchecklists.store.Library
import eu.studiodeanna.openchecklists.store.LibraryEntry
import eu.studiodeanna.openchecklists.store.Settings
import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.google.GoogleFileAccessRequired
import eu.studiodeanna.openchecklists.google.GoogleSignInRequired
import eu.studiodeanna.openchecklists.sync.GoogleDriveApi
import eu.studiodeanna.openchecklists.sync.ParsedLink
import eu.studiodeanna.openchecklists.sync.RemoteException
import eu.studiodeanna.openchecklists.sync.ShareLinks
import eu.studiodeanna.openchecklists.sync.SharedList
import eu.studiodeanna.openchecklists.sync.nextcloudShareFor
import eu.studiodeanna.openchecklists.sync.RemoteFile
import eu.studiodeanna.openchecklists.sync.ShareLink
import eu.studiodeanna.openchecklists.sync.remoteFileFor
import eu.studiodeanna.openchecklists.sync.syncWith
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface SyncState {
    data object LocalOnly : SyncState
    data object Syncing : SyncState
    data class Synced(val at: Long) : SyncState
    /**
     * [needsSignIn]: a Google Drive list that syncs again once the user signs in. [pickFile]: one that
     * syncs once the user picks its file, with that id, in Google's file picker.
     */
    data class Failed(val message: String, val needsSignIn: Boolean = false, val pickFile: String? = null) : SyncState
}

/** [collapsed]: the sections folded away on this device. [history]: this device's edits, to undo and redo. */
data class ListEntry(
    val id: String,
    val doc: ListDocument,
    val link: ShareLink?,
    val sync: SyncState,
    val collapsed: Set<String> = emptySet(),
    val history: History = History(),
) {
    /** This entry holding [newDoc], with completed sections folded and reopened sections opened. */
    fun withDoc(newDoc: ListDocument): ListEntry = copy(doc = newDoc, collapsed = foldCompleted(collapsed, doc, newDoc))
}

/** Thrown by [ChecklistRepository.openShared] when a shared folder holds several lists to choose from. */
class ListChoiceNeeded(val link: ShareLink, val lists: List<SharedList>) : RemoteException(Messages.current.chooseList)

/**
 * The lists on this device. Edits are saved locally at once; lists with a share link are then
 * synced shortly after, and whenever [syncNow] is called.
 */
class ChecklistRepository(
    private val files: FileStore,
    private val scope: CoroutineScope,
    private val http: HttpClient = defaultHttpClient(),
    private val google: GoogleAuth? = null,
    private val now: () -> Long = ::currentTimeMillis,
    private val syncDelayMillis: Long = 800,
) {
    private val drive = google?.let { GoogleDriveApi(it, http) }
    private val remoteFor: (ShareLink) -> RemoteFile = { remoteFileFor(it, http, drive) }

    /** Whether this build can use Google Drive at all (it has an OAuth client configured). */
    val googleAvailable: Boolean get() = google != null
    val googleSignedIn: StateFlow<Boolean> = google?.signedIn ?: MutableStateFlow(false)

    private val _lists = MutableStateFlow<List<ListEntry>>(emptyList())
    val lists: StateFlow<List<ListEntry>> = _lists.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private var device = ""
    private lateinit var editor: ListEditor
    private val syncLocks = mutableMapOf<String, Mutex>()
    private val pendingSyncs = mutableMapOf<String, Job>()
    private val saveLock = Mutex()

    /** Reads the lists from disk; later calls (a recreated screen, say) do nothing. */
    suspend fun load() {
        if (device.isNotEmpty()) return
        files.read(SETTINGS)?.let { text ->
            runCatching { json.decodeFromString(Settings.serializer(), text) }.getOrNull()?.let { _settings.value = it }
        }
        val library = files.read(LIBRARY)?.let { json.decodeFromString(Library.serializer(), it) }
            ?: Library(device = newDeviceId()).also { files.write(LIBRARY, json.encodeToString(Library.serializer(), it)) }
        device = library.device
        editor = ListEditor(device, now)
        _lists.value = library.lists.mapNotNull { entry ->
            val doc = files.read(fileOf(entry.id))?.let {
                runCatching { ListCodec.decode(it) }.getOrNull()
            } ?: return@mapNotNull null
            ListEntry(
                id = entry.id,
                doc = doc,
                link = entry.link,
                sync = if (entry.link == null) SyncState.LocalOnly
                else entry.lastSyncedAt?.let { SyncState.Synced(it) } ?: SyncState.Syncing,
                collapsed = entry.collapsed,
            )
        }
    }

    suspend fun updateSettings(settings: Settings) {
        _settings.value = settings
        saveLock.withLock { files.write(SETTINGS, json.encodeToString(Settings.serializer(), settings)) }
    }

    fun entry(id: String): ListEntry? = _lists.value.firstOrNull { it.id == id }

    suspend fun createList(title: String): String {
        val id = editor.newId()
        val doc = editor.rename(ListDocument(), title.ifBlank { Messages.current.newList })
        _lists.update { it + ListEntry(id, doc, null, SyncState.LocalOnly) }
        saveDoc(id)
        saveLibrary()
        return id
    }

    suspend fun deleteList(id: String) {
        pendingSyncs.remove(id)?.cancel()
        _lists.update { lists -> lists.filterNot { it.id == id } }
        files.delete(fileOf(id))
        saveLibrary()
    }

    /**
     * Applies a change made on this device and saves it; linked lists sync right after. It can be
     * undone, unless it changed nothing, in which case nothing is saved either.
     */
    fun edit(id: String, change: ListEditor.(ListDocument) -> ListDocument) {
        val before = entry(id) ?: return
        var changed = false
        updateEntry(id) { entry ->
            val doc = editor.change(entry.doc)
            val diff = changeBetween(entry.doc, doc)
            changed = diff != null
            if (diff == null) entry else entry.withDoc(doc).copy(history = entry.history.record(diff))
        }
        if (changed) saveEdit(id, before)
    }

    /**
     * Takes back this device's latest edit to [id], as a new edit that syncs like any other. What
     * others have changed since is left alone; an edit they have changed entirely is skipped for the
     * one before it. Returns what was undone, or null if nothing could be.
     */
    fun undo(id: String): Action? = replay(id, redo = false)

    /** Makes the latest undone edit to [id] again, the way [undo] takes one back. */
    fun redo(id: String): Action? = replay(id, redo = true)

    private fun replay(id: String, redo: Boolean): Action? {
        val before = entry(id) ?: return null
        var action: Action? = null
        updateEntry(id) { entry ->
            var doc = entry.doc
            var history = entry.history
            action = null
            while (action == null) {
                val step = history.next(redo) ?: break
                val reverted = editor.revert(doc, step.change)
                val back = changeBetween(doc, reverted)
                history = history.took(redo, back)
                if (back != null) {
                    doc = reverted
                    action = step.action
                }
            }
            entry.withDoc(doc).copy(history = history)
        }
        if (action != null) saveEdit(id, before)
        return action
    }

    /** Saves an edit made on this device to [id], which was [before] it; linked lists sync right after. */
    private fun saveEdit(id: String, before: ListEntry) {
        val folded = entry(id)?.collapsed != before.collapsed
        scope.launch {
            saveDoc(id)
            if (folded) saveLibrary()
        }
        if (before.link != null) scheduleSync(id, syncDelayMillis)
    }

    /** Folds a section away on this device, or opens it; others sharing the list keep their own. */
    fun setCollapsed(id: String, section: String, collapsed: Boolean) {
        updateEntry(id) { it.copy(collapsed = if (collapsed) it.collapsed + section else it.collapsed - section) }
        scope.launch { saveLibrary() }
    }

    /**
     * Opens a list someone shared. Nothing is added unless the first sync works, so a mistyped link
     * or password is reported instead of leaving an empty list behind. A shared folder may hold
     * several lists: with one it is opened, with more [ListChoiceNeeded] lists them, and the caller
     * opens one by passing its [ShareLink.fileName]. A list that is already here is not added twice.
     * A Google Drive link throws [GoogleFileAccessRequired] until the user has picked its file.
     */
    suspend fun openShared(link: ShareLink): String {
        var target = ShareLinks.normalize(link)
        existingWith(target)?.let { return it }
        val driveLink = ShareLinks.parse(target.url) as? ParsedLink.GoogleDrive
        // Google's file picker signs in too, so a signed-out user sees one Google page, not two.
        if (driveLink != null && google != null && !google.signedIn.value) throw GoogleFileAccessRequired(driveLink.fileId)
        val share = nextcloudShareFor(target, http)
        if (share != null && target.fileName == null) {
            share.resolve()
            if (share.isFolder) {
                val lists = share.lists()
                target = when (lists.size) {
                    0 -> throw RemoteException(Messages.current.noListsInFolder)
                    1 -> target.copy(fileName = lists.single().fileName)
                    else -> throw ListChoiceNeeded(target, lists)
                }
                existingWith(target)?.let { return it }
            }
        }
        val doc = syncWith(remoteFor(target), ListDocument())
        val id = editor.newId()
        _lists.update { it + ListEntry(id, doc, target, SyncState.Synced(now()), collapsed = doc.completeSections()) }
        saveDoc(id)
        saveLibrary()
        return id
    }

    /**
     * Shares an existing list through [link]. In a shared folder the list gets a file of its own,
     * named after it (numbered if the name is taken); a link to a single file is merged with whatever
     * that file already holds.
     */
    suspend fun attachLink(id: String, link: ShareLink) {
        val local = entry(id)?.doc ?: return
        var target = ShareLinks.normalize(link)
        val share = nextcloudShareFor(target, http)
        if (share != null && target.fileName == null) {
            share.resolve()
            if (share.isFolder) {
                target = target.copy(fileName = share.createListFile(local.title, ListCodec.encode(local.normalized())))
            }
        }
        val merged = syncWith(remoteFor(target), local)
        updateEntry(id) { it.withDoc(merge(it.doc, merged)).copy(link = target, sync = SyncState.Synced(now())) }
        saveDoc(id)
        saveLibrary()
    }

    /** Stops syncing; the list stays on this device and the shared file is left as it is. */
    suspend fun detachLink(id: String) {
        pendingSyncs.remove(id)?.cancel()
        updateEntry(id) { it.copy(link = null, sync = SyncState.LocalOnly) }
        saveLibrary()
    }

    /**
     * Keeps [id] in a new Google Drive file that anyone with the link can edit. Throws
     * [GoogleSignInRequired] when the user has to sign in first.
     */
    suspend fun shareViaGoogleDrive(id: String) {
        val api = drive ?: throw RemoteException(Messages.current.driveNotSetUp)
        val doc = entry(id)?.doc ?: return
        val url = api.createSharedFile(doc.title, ListCodec.encode(doc))
        updateEntry(id) { it.copy(link = ShareLink(url), sync = SyncState.Synced(now())) }
        saveLibrary()
        syncNow(id)
    }

    /** Shows Google's sign-in, then retries the lists that were waiting for it. */
    suspend fun signInToGoogle() {
        val auth = google ?: throw RemoteException(Messages.current.driveNotSetUp)
        auth.signIn()
        _lists.value.filter { (it.sync as? SyncState.Failed)?.needsSignIn == true }.forEach { syncNow(it.id) }
    }

    /**
     * Shows Google's file picker for [fileId], so this user's app may use a file someone shared, then
     * retries the lists that were waiting for it or for a sign-in.
     */
    suspend fun pickGoogleFile(fileId: String) {
        val auth = google ?: throw RemoteException(Messages.current.driveNotSetUp)
        auth.pickFile(fileId)
        _lists.value.filter { entry ->
            (entry.sync as? SyncState.Failed)?.let { it.needsSignIn || it.pickFile == fileId } == true
        }.forEach { syncNow(it.id) }
    }

    suspend fun signOutOfGoogle() {
        google?.signOut()
    }

    fun syncNow(id: String) = scheduleSync(id, 0)

    fun syncAll() = _lists.value.filter { it.link != null }.forEach { syncNow(it.id) }

    private fun scheduleSync(id: String, delayMillis: Long) {
        pendingSyncs.remove(id)?.cancel()
        pendingSyncs[id] = scope.launch {
            delay(delayMillis)
            sync(id)
        }
    }

    /** Runs one sync of a linked list; failures are recorded on the list, not thrown. */
    suspend fun sync(id: String) {
        val lock = syncLocks.getOrPut(id) { Mutex() }
        lock.withLock {
            val entry = entry(id) ?: return
            val link = entry.link ?: return
            updateEntry(id) { it.copy(sync = SyncState.Syncing) }
            try {
                val merged = syncWith(remoteFor(link), entry.doc.pruned(now()))
                var changedMeanwhile = false
                updateEntry(id) {
                    val combined = merge(it.doc, merged)
                    changedMeanwhile = combined != merged
                    it.withDoc(combined).copy(sync = SyncState.Synced(now()))
                }
                saveDoc(id)
                saveLibrary()
                if (changedMeanwhile) scheduleSync(id, syncDelayMillis)
            } catch (e: RemoteException) {
                val failed = SyncState.Failed(
                    e.message ?: Messages.current.syncFailed,
                    needsSignIn = e is GoogleSignInRequired,
                    pickFile = (e as? GoogleFileAccessRequired)?.fileId,
                )
                updateEntry(id) { it.copy(sync = failed) }
            }
        }
    }

    private fun existingWith(link: ShareLink): String? =
        _lists.value.firstOrNull { it.link?.url == link.url && it.link.fileName == link.fileName }?.id

    private fun updateEntry(id: String, change: (ListEntry) -> ListEntry) =
        _lists.update { lists -> lists.map { if (it.id == id) change(it) else it } }

    private suspend fun saveDoc(id: String) {
        val doc = entry(id)?.doc ?: return
        saveLock.withLock { files.write(fileOf(id), ListCodec.encode(doc)) }
    }

    private suspend fun saveLibrary() {
        val library = Library(
            device = device,
            lists = _lists.value.map { entry ->
                LibraryEntry(entry.id, entry.link, (entry.sync as? SyncState.Synced)?.at, entry.collapsed)
            },
        )
        saveLock.withLock { files.write(LIBRARY, json.encodeToString(Library.serializer(), library)) }
    }

    private fun newDeviceId(): String = buildString {
        repeat(8) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private companion object {
        const val LIBRARY = "library.json"
        const val SETTINGS = "settings.json"
        fun fileOf(id: String) = "list-$id.json"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

fun defaultHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
    }
}

@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
