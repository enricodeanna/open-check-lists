package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.model.Action
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.itemsBySection
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.model.visibleSections
import eu.studiodeanna.openchecklists.store.MemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTest {
    private var clock = 1_000L

    private suspend fun device(server: FakeNextcloud, scope: CoroutineScope, files: MemoryFileStore = MemoryFileStore()) =
        ChecklistRepository(files, scope, server.http, now = { clock++ }).also { it.load() }

    @Test
    fun twoPeopleEditingTheSameShareConverge() = runTest {
        val server = FakeNextcloud()
        val anna = device(server, backgroundScope)
        val list = anna.createList("Groceries")
        anna.edit(list) { addSection(it, "Produce") }
        anna.edit(list) { addSection(it, "Dairy") }
        val produce = anna.entry(list)!!.doc.visibleSections()[0].id
        anna.edit(list) { addItem(it, produce, "Apples") }
        anna.attachLink(list, server.link)

        val ben = device(server, backgroundScope)
        val bensList = ben.openShared(server.link)
        assertEquals("Groceries", ben.entry(bensList)!!.doc.title)

        // Both edit while apart, then sync in turn.
        val apples = ben.entry(bensList)!!.doc.liveItems().single().id
        ben.edit(bensList) { setDone(it, apples, true) }
        val dairy = anna.entry(list)!!.doc.visibleSections()[1].id
        anna.edit(list) { addItem(it, dairy, "Milk") }
        ben.sync(bensList)
        anna.sync(list)
        ben.sync(bensList)

        val a = anna.entry(list)!!.doc
        val b = ben.entry(bensList)!!.doc
        assertEquals(a, b)
        assertTrue(a.liveItems().single { it.text == "Apples" }.done)
        assertEquals(listOf("Milk"), a.itemsBySection()[dairy]!!.map { it.text })
        assertEquals(a, ListCodec.decode(server.content!!))
    }

    @Test
    fun completedSectionsFoldOnEveryDeviceAndStaySoAfterARestart() = runTest {
        val server = FakeNextcloud()
        val annasFiles = MemoryFileStore()
        val anna = device(server, backgroundScope, annasFiles)
        val list = anna.createList("Groceries")
        anna.edit(list) { addSection(it, "Produce") }
        val produce = anna.entry(list)!!.doc.visibleSections().single().id
        anna.edit(list) { addItem(it, produce, "Apples") }
        anna.attachLink(list, server.link)
        val ben = device(server, backgroundScope)
        val bensList = ben.openShared(server.link)

        val apples = anna.entry(list)!!.doc.liveItems().single().id
        anna.edit(list) { setDone(it, apples, true) }
        assertEquals(setOf(produce), anna.entry(list)!!.collapsed)

        // Ben gets the tick by syncing, and the section folds for him as well.
        anna.sync(list)
        ben.sync(bensList)
        assertEquals(setOf(produce), ben.entry(bensList)!!.collapsed)

        runCurrent()
        assertEquals(setOf(produce), device(server, backgroundScope, annasFiles).entry(list)!!.collapsed)
        // Opening it again by hand is kept too, and only on Anna's device.
        anna.setCollapsed(list, produce, false)
        runCurrent()
        assertEquals(emptySet(), device(server, backgroundScope, annasFiles).entry(list)!!.collapsed)
        assertEquals(setOf(produce), ben.entry(bensList)!!.collapsed)
    }

    @Test
    fun undoAndRedoReachEveryoneSharingTheList() = runTest {
        val server = FakeNextcloud()
        val anna = device(server, backgroundScope)
        val list = anna.createList("Groceries")
        anna.edit(list) { addItem(it, null, "Apples") }
        anna.attachLink(list, server.link)
        val ben = device(server, backgroundScope)
        val bensList = ben.openShared(server.link)
        val apples = anna.entry(list)!!.doc.liveItems().single().id
        anna.edit(list) { setDone(it, apples, true) }
        anna.sync(list)
        ben.sync(bensList)

        assertEquals(Action.CheckedItem("Apples"), anna.undo(list))
        anna.sync(list)
        ben.sync(bensList)
        assertFalse(ben.entry(bensList)!!.doc.liveItems().single().done)

        assertEquals(Action.CheckedItem("Apples"), anna.redo(list))
        anna.sync(list)
        ben.sync(bensList)
        assertTrue(ben.entry(bensList)!!.doc.liveItems().single().done)
    }

    @Test
    fun undoSkipsAnEditSomeoneElseChangedSince() = runTest {
        val server = FakeNextcloud()
        val anna = device(server, backgroundScope)
        val list = anna.createList("Groceries")
        anna.edit(list) { addItem(it, null, "Apples") }
        anna.edit(list) { addItem(it, null, "Pears") }
        anna.attachLink(list, server.link)
        val ben = device(server, backgroundScope)
        val bensList = ben.openShared(server.link)
        val apples = anna.entry(list)!!.doc.liveItems().single { it.text == "Apples" }.id
        anna.edit(list) { editItem(it, apples, "Green apples") }
        anna.sync(list)
        ben.sync(bensList)
        ben.edit(bensList) { editItem(it, apples, "Red apples") }
        ben.sync(bensList)
        anna.sync(list)

        // Ben has since renamed what Anna renamed, so her undo takes back the edit before: adding the pears.
        assertEquals(Action.AddedItem("Pears"), anna.undo(list))
        assertEquals(listOf("Red apples"), anna.entry(list)!!.doc.liveItems().map { it.text })
        // Adding the apples is skipped too: deleting them would throw away Ben's renaming.
        assertNull(anna.undo(list))
        assertFalse(anna.entry(list)!!.history.canUndo)
        assertEquals(Action.AddedItem("Pears"), anna.redo(list))
        assertEquals(listOf("Pears", "Red apples"), anna.entry(list)!!.doc.liveItems().map { it.text }.sorted())
    }

    @Test
    fun editsThatChangeNothingAreNeitherSavedNorUndoable() = runTest {
        val server = FakeNextcloud()
        val repo = device(server, backgroundScope)
        val list = repo.createList("Books")
        repo.attachLink(list, server.link)
        val before = repo.entry(list)!!
        repo.edit(list) { rename(it, "Books") }
        assertEquals(before, repo.entry(list))
        assertNull(repo.undo(list))
    }

    @Test
    fun folderShareGetsTheListFileCreated() = runTest {
        val server = FakeNextcloud(folder = true)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Hardware store")
        repo.attachLink(list, server.link)
        assertEquals("Hardware store", ListCodec.decode(server.content!!).title)
    }

    @Test
    fun aConcurrentSaveIsMergedNotOverwritten() = runTest {
        val server = FakeNextcloud()
        val anna = device(server, backgroundScope)
        val list = anna.createList("Party")
        anna.attachLink(list, server.link)
        val ben = device(server, backgroundScope)
        val bensList = ben.openShared(server.link)

        ben.edit(bensList) { addItem(it, null, "Cake") }
        anna.edit(list) { addItem(it, null, "Balloons") }
        server.beforeNextWrite = { ben.sync(bensList) }
        anna.sync(list)

        val texts = ListCodec.decode(server.content!!).liveItems().map { it.text }.toSet()
        assertEquals(setOf("Cake", "Balloons"), texts)
    }

    @Test
    fun wrongPasswordIsReportedAndAddsNothing() = runTest {
        val server = FakeNextcloud(password = "secret")
        val repo = device(server, backgroundScope)
        val error = assertFailsWith<RemoteException> { repo.openShared(server.link.copy(password = "nope")) }
        assertTrue("password" in error.message!!)
        assertTrue(repo.lists.value.isEmpty())
        repo.openShared(server.link)
        assertEquals(1, repo.lists.value.size)
    }

    @Test
    fun readOnlyShareShowsAsFailedSync() = runTest {
        val server = FakeNextcloud()
        val repo = device(server, backgroundScope)
        val list = repo.openShared(server.link)
        server.readOnly = true
        repo.edit(list) { addItem(it, null, "Eggs") }
        repo.sync(list)
        val state = assertIs<SyncState.Failed>(repo.entry(list)!!.sync)
        assertTrue("Allow editing" in state.message)
    }

    @Test
    fun folderThatAllowsEditingButNotCreatingIsExplained() = runTest {
        val server = FakeNextcloud(folder = true, permissions = 1 or 2 or 8)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Groceries")
        val error = assertFailsWith<RemoteException> { repo.attachLink(list, server.link) }
        assertTrue("allow upload and editing" in error.message!!, error.message)
        assertTrue("Access to this resource has been denied" in error.message!!, error.message)
    }

    @Test
    fun singleFileShareNeedsOnlyEditPermission() = runTest {
        // A text file made in Nextcloud's web UI: exists, empty, editable, but nothing can be created.
        val server = FakeNextcloud(permissions = 1 or 2)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Groceries")
        repo.attachLink(list, server.link)
        assertEquals("Groceries", ListCodec.decode(server.content!!).title)
    }

    @Test
    fun permissionLettersAreUnderstood() = runTest {
        val server = FakeNextcloud(folder = true, permissions = 1 or 2 or 8, lettersOnly = true)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Groceries")
        val error = assertFailsWith<RemoteException> { repo.attachLink(list, server.link) }
        assertTrue("allow upload and editing" in error.message!!, error.message)
    }

    @Test
    fun unexplainedRefusalsPassOnNextcloudsReason() = runTest {
        val server = FakeNextcloud(folder = true)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Groceries")
        repo.attachLink(list, server.link)
        server.denyNextWrite = true
        repo.edit(list) { addItem(it, null, "Salt") }
        repo.sync(list)
        val failed = assertIs<SyncState.Failed>(repo.entry(list)!!.sync)
        assertTrue("Nextcloud refused to save the list: Access to this resource has been denied" in failed.message, failed.message)
    }

    @Test
    fun oldServersAreReachedThroughTheOldEndpoint() = runTest {
        for (folder in listOf(true, false)) {
            val server = FakeNextcloud(folder = folder, password = "pw", legacyOnly = true)
            val repo = device(server, backgroundScope)
            val list = repo.createList("Groceries")
            repo.attachLink(list, server.link)
            assertEquals("Groceries", ListCodec.decode(server.content!!).title)
        }
    }

    @Test
    fun passwordProtectedFolderOnACurrentServer() = runTest {
        val server = FakeNextcloud(folder = true, password = "pw")
        val repo = device(server, backgroundScope)
        val list = repo.createList("Groceries")
        repo.attachLink(list, server.link)
        assertEquals("Groceries", ListCodec.decode(server.content!!).title)
        assertFailsWith<RemoteException> { device(server, backgroundScope).openShared(server.link.copy(password = "no")) }
    }

    @Test
    fun nothingIsWrittenWhenAlreadyInStep() = runTest {
        val server = FakeNextcloud()
        val repo = device(server, backgroundScope)
        val list = repo.createList("Books")
        repo.attachLink(list, server.link)
        val writes = server.writes
        repo.sync(list)
        repo.sync(list)
        assertEquals(writes, server.writes)
    }

    @Test
    fun listsAndLinksSurviveARestart() = runTest {
        val server = FakeNextcloud()
        val files = MemoryFileStore()
        val first = device(server, backgroundScope, files)
        val list = first.createList("Garden")
        first.attachLink(list, server.link)
        first.edit(list) { addItem(it, null, "Seeds") }
        runCurrent() // the save; the debounced sync is still pending

        val again = device(server, backgroundScope, files)
        val entry = again.entry(list)!!
        assertEquals(server.link, entry.link)
        assertEquals(listOf("Seeds"), entry.doc.liveItems().map { it.text })
    }

    @Test
    fun otherFilesAreRefused() = runTest {
        val server = FakeNextcloud()
        server.overwrite("# Shopping\n- milk")
        val repo = device(server, backgroundScope)
        val error = assertFailsWith<RemoteException> { repo.openShared(server.link) }
        assertTrue("not a list from Open Check Lists" in error.message!!)
        assertEquals("# Shopping\n- milk", server.content)
    }
}
