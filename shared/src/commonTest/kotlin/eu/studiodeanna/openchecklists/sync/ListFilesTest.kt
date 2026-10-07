package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.ListChoiceNeeded
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.ListDocument
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.store.MemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every list in a shared folder has a file of its own, named after the list. */
class ListFilesTest {
    private var clock = 1_000L

    private suspend fun device(server: FakeNextcloud, scope: CoroutineScope) =
        ChecklistRepository(MemoryFileStore(), scope, server.http, now = { clock++ }).also { it.load() }

    @Test
    fun fileNamesFollowTheListNameAndNumberDuplicates() {
        assertEquals("Groceries.json", listFileName("Groceries"))
        assertEquals("Groceries (2).json", listFileName("Groceries", 2))
        assertEquals("Tools - screws- nails.json", listFileName("Tools / screws: nails"))
        assertEquals("List.json", listFileName("  "))
        assertEquals("hidden.json", listFileName(".hidden"))
    }

    @Test
    fun listsInOneFolderGetTheirOwnNumberedFiles() = runTest {
        val server = FakeNextcloud(folder = true)
        server.overwrite("notes", name = "Groceries (2).json") // someone's unrelated file with the next name
        val repo = device(server, backgroundScope)
        val ids = listOf("Groceries", "Hardware", "Groceries", "Groceries").map { title ->
            repo.createList(title).also { repo.attachLink(it, server.link) }
        }
        assertEquals(
            listOf("Groceries.json", "Hardware.json", "Groceries (3).json", "Groceries (4).json"),
            ids.map { repo.entry(it)!!.link!!.fileName },
        )
        assertEquals("Hardware", ListCodec.decode(server.file("Hardware.json")!!).title)
        assertEquals("notes", server.file("Groceries (2).json"))
    }

    @Test
    fun aNameTakenAtTheSameMomentMovesToTheNextNumber() = runTest {
        val server = FakeNextcloud(folder = true)
        val repo = device(server, backgroundScope)
        val list = repo.createList("Party")
        server.beforeNextWrite = { server.overwrite(ListCodec.encode(ListDocument(title = "Party")), name = "Party.json") }
        repo.attachLink(list, server.link)
        assertEquals("Party (2).json", repo.entry(list)!!.link!!.fileName)
    }

    @Test
    fun openingAFolderWithSeveralListsAsksWhichOne() = runTest {
        val server = FakeNextcloud(folder = true)
        val anna = device(server, backgroundScope)
        val groceries = anna.createList("Groceries")
        anna.edit(groceries) { addItem(it, null, "Milk") }
        anna.attachLink(groceries, server.link)
        anna.createList("Hardware").also { anna.attachLink(it, server.link) }
        server.overwrite("not a list", name = "notes.json")

        val ben = device(server, backgroundScope)
        val choice = assertFailsWith<ListChoiceNeeded> { ben.openShared(server.link) }
        assertEquals(listOf("Groceries", "Hardware"), choice.lists.map { it.title })
        val opened = ben.openShared(choice.link.copy(fileName = choice.lists.first().fileName))
        assertEquals(listOf("Milk"), ben.entry(opened)!!.doc.liveItems().map { it.text })
        // Opening it again does not add a second copy.
        assertEquals(opened, ben.openShared(ShareLink(ShareLinks.shareable(ben.entry(opened)!!.link!!))))
        assertEquals(1, ben.lists.value.size)
    }

    @Test
    fun theSharedAddressLeadsStraightToTheList() = runTest {
        val server = FakeNextcloud(folder = true)
        val anna = device(server, backgroundScope)
        val a = anna.createList("Fish & chips")
        anna.attachLink(a, server.link)
        anna.createList("Other").also { anna.attachLink(it, server.link) }
        val address = ShareLinks.shareable(anna.entry(a)!!.link!!)
        assertEquals("https://cloud.example.com/s/AbC123?file=Fish%20%26%20chips.json", address)

        val ben = device(server, backgroundScope)
        val opened = ben.openShared(ShareLink(address))
        assertEquals("Fish & chips", ben.entry(opened)!!.doc.title)
        assertEquals("Fish & chips.json", ben.entry(opened)!!.link!!.fileName)
    }

    @Test
    fun aFolderWithOneListOpensItAndAnEmptyFolderSaysSo() = runTest {
        val server = FakeNextcloud(folder = true)
        val ben = device(server, backgroundScope)
        assertTrue("no lists" in assertFailsWith<RemoteException> { ben.openShared(server.link) }.message!!)
        val anna = device(server, backgroundScope)
        anna.createList("Only one").also { anna.attachLink(it, server.link) }
        assertEquals("Only one", ben.entry(ben.openShared(server.link))!!.doc.title)
    }

    @Test
    fun listsSharedBeforeNamedFilesKeepTheirFile() = runTest {
        val server = FakeNextcloud(folder = true)
        server.overwrite(ListCodec.encode(ListDocument(title = "Old", titleAt = 1, titleBy = "x")), name = NextcloudShare.LEGACY_FOLDER_FILE)
        val repo = device(server, backgroundScope)
        val id = repo.openShared(server.link)
        assertEquals(NextcloudShare.LEGACY_FOLDER_FILE, repo.entry(id)!!.link!!.fileName)
        repo.edit(id) { addItem(it, null, "Still here") }
        repo.sync(id)
        assertEquals(listOf("Still here"), ListCodec.decode(server.file(NextcloudShare.LEGACY_FOLDER_FILE)!!).liveItems().map { it.text })
    }
}
