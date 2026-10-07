package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.store.MemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudAccountTest {
    private var clock = 1_000L

    private suspend fun device(server: FakeNextcloudServer, scope: CoroutineScope, files: MemoryFileStore = MemoryFileStore()) =
        ChecklistRepository(files, scope, server.http, now = { clock++ }).also { it.load() }

    /** What the setup dialog does: log in, then confirm [folder] when the account has none yet. */
    private suspend fun ChecklistRepository.connect(folder: String? = NextcloudAccountApi.DEFAULT_FOLDER) {
        val account = finishNextcloudSignIn(startNextcloudSignIn("cloud.example.com"))
        if (account.folder == null && folder != null) setNextcloudFolder(folder)
    }

    private suspend fun ChecklistRepository.newList(title: String, vararg items: String): String =
        createList(title).also { id -> items.forEach { item -> edit(id) { addItem(it, null, item) } } }

    @Test
    fun eachSharedListGetsItsOwnFileAndLinkThatOthersEditWithoutAnAccount() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        anna.connect()
        val account = anna.nextcloudAccount.value!!
        assertEquals("anna", account.userId)
        assertEquals("OpenCheckList", account.folder)

        val groceries = anna.newList("Groceries", "Milk")
        anna.shareViaNextcloud(groceries)
        val tools = anna.newList("Tools")
        anna.shareViaNextcloud(tools)
        assertEquals(setOf("OpenCheckList/Groceries.json", "OpenCheckList/Tools.json"), server.filePaths)
        val link = anna.entry(groceries)!!.link!!
        assertEquals("https://cloud.example.com/s/Tok1", link.url)
        assertEquals("https://cloud.example.com/s/Tok2", anna.entry(tools)!!.link!!.url)
        assertNull(link.password)

        // Ben has no account; the public link is enough.
        val ben = device(server, backgroundScope)
        val bens = ben.openShared(ShareLink(ShareLinks.shareable(link)))
        assertEquals(listOf("Milk"), ben.entry(bens)!!.doc.liveItems().map { it.text })
        ben.edit(bens) { addItem(it, null, "Bread") }
        ben.sync(bens)
        anna.sync(groceries)
        assertEquals(setOf("Milk", "Bread"), anna.entry(groceries)!!.doc.liveItems().map { it.text }.toSet())
        assertEquals(anna.entry(groceries)!!.doc, ListCodec.decode(server.file("OpenCheckList/Groceries.json")!!))
    }

    @Test
    fun sharingAsksForTheAccountAndThenTheFolder() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        val list = anna.newList("Groceries")
        assertFailsWith<NextcloudSetupNeeded> { anna.shareViaNextcloud(list) }
        anna.connect(folder = null)
        assertNull(anna.nextcloudAccount.value!!.folder)
        assertFailsWith<NextcloudSetupNeeded> { anna.shareViaNextcloud(list) }
        anna.setNextcloudFolder("/Family/Lists/")
        anna.shareViaNextcloud(list)
        assertEquals(setOf("Family", "Family/Lists"), server.folders)
        assertEquals(setOf("Family/Lists/Groceries.json"), server.filePaths)
    }

    @Test
    fun listsOfTheSameNameGetNumberedFiles() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        anna.connect()
        repeat(2) { anna.shareViaNextcloud(anna.newList("Groceries")) }
        assertEquals(setOf("OpenCheckList/Groceries.json", "OpenCheckList/Groceries (2).json"), server.filePaths)
    }

    @Test
    fun changingTheFolderLeavesListsAlreadySharedWhereTheyAre() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        anna.connect()
        val first = anna.newList("Groceries")
        anna.shareViaNextcloud(first)
        anna.setNextcloudFolder("Work")
        anna.shareViaNextcloud(anna.newList("Tasks"))
        assertEquals(setOf("OpenCheckList/Groceries.json", "Work/Tasks.json"), server.filePaths)

        anna.edit(first) { addItem(it, null, "Eggs") }
        anna.sync(first)
        assertEquals(listOf("Eggs"), ListCodec.decode(server.file("OpenCheckList/Groceries.json")!!).liveItems().map { it.text })
    }

    @Test
    fun aServerThatRequiresPasswordsGetsAGeneratedOne() = runTest {
        val server = FakeNextcloudServer(passwordsEnforced = true)
        val anna = device(server, backgroundScope)
        anna.connect()
        val list = anna.newList("Groceries", "Milk")
        anna.shareViaNextcloud(list)
        val link = anna.entry(list)!!.link!!
        val password = assertNotNull(link.password)
        assertTrue(Regex("""[A-Za-z0-9]{4}(-[A-Za-z0-9]{4}){3}""").matches(password), password)

        val ben = device(server, backgroundScope)
        assertFailsWith<SharePasswordRequired> { ben.openShared(ShareLink(link.url)) }
        ben.openShared(ShareLink(link.url, password))
    }

    @Test
    fun generatedPasswordsHaveEveryKindOfCharacter() {
        repeat(200) {
            val password = NextcloudAccountApi.newSharePassword()
            assertEquals(19, password.length)
            assertTrue(password.any { it.isUpperCase() } && password.any { it.isLowerCase() } && password.any { it.isDigit() })
        }
    }

    @Test
    fun theDayTheServerEndsALinkIsKept() = runTest {
        val server = FakeNextcloudServer(linkExpiry = "2026-11-06")
        val anna = device(server, backgroundScope)
        anna.connect()
        val list = anna.newList("Groceries")
        anna.shareViaNextcloud(list)
        assertEquals("2026-11-06", anna.entry(list)!!.link!!.expires)
    }

    @Test
    fun aRefusedLinkLeavesNoFileBehind() = runTest {
        val server = FakeNextcloudServer(linksAllowed = false)
        val anna = device(server, backgroundScope)
        anna.connect()
        val list = anna.newList("Groceries")
        val error = assertFailsWith<RemoteException> { anna.shareViaNextcloud(list) }
        assertTrue("disabled by the administrator" in error.message!!, error.message)
        assertTrue(server.filePaths.isEmpty())
        assertNull(anna.entry(list)!!.link)
    }

    @Test
    fun disconnectingRemovesTheLoginAndSharedListsKeepSyncing() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        anna.connect()
        val list = anna.newList("Groceries")
        anna.shareViaNextcloud(list)
        val password = anna.nextcloudAccount.value!!.appPassword

        anna.signOutOfNextcloud()
        assertNull(anna.nextcloudAccount.value)
        assertEquals(listOf(password), server.revoked)
        anna.edit(list) { addItem(it, null, "Tea") }
        anna.sync(list)
        assertIs<SyncState.Synced>(anna.entry(list)!!.sync)
    }

    @Test
    fun aLoginRemovedInNextcloudAsksToLogInAgainAndKeepsTheFolder() = runTest {
        val server = FakeNextcloudServer()
        val anna = device(server, backgroundScope)
        anna.connect()
        anna.setNextcloudFolder("Family")
        server.appPasswords.clear()
        val list = anna.newList("Groceries")
        assertFailsWith<NextcloudSignInRequired> { anna.shareViaNextcloud(list) }

        anna.connect(folder = null)
        assertEquals("Family", anna.nextcloudAccount.value!!.folder)
        anna.shareViaNextcloud(list)
        assertEquals(setOf("Family/Groceries.json"), server.filePaths)
    }

    @Test
    fun theAccountIsRememberedOnTheDevice() = runTest {
        val server = FakeNextcloudServer()
        val files = MemoryFileStore()
        device(server, backgroundScope, files).connect()
        val again = device(server, backgroundScope, files)
        assertEquals("OpenCheckList", again.nextcloudAccount.value?.folder)
        again.shareViaNextcloud(again.newList("Groceries"))
    }

    @Test
    fun theLoginWaitsForTheBrowserButNotForever() = runTest {
        val slow = FakeNextcloudServer(pollsBeforeLogin = 5)
        device(slow, backgroundScope).connect()
        assertEquals(6, slow.polls)

        val never = FakeNextcloudServer(pollsBeforeLogin = null)
        val repo = device(never, backgroundScope)
        val login = repo.startNextcloudSignIn("cloud.example.com")
        val error = assertFailsWith<RemoteException> { repo.finishNextcloudSignIn(login) }
        assertTrue("in time" in error.message!!, error.message)
        assertNull(repo.nextcloudAccount.value)
    }

    @Test
    fun anAddressWithoutNextcloudIsReported() = runTest {
        val repo = device(FakeNextcloudServer(isNextcloud = false), backgroundScope)
        val error = assertFailsWith<RemoteException> { repo.startNextcloudSignIn("example.org") }
        assertTrue("No Nextcloud found at https://example.org" in error.message!!, error.message)
    }

    @Test
    fun foldersCanBeBrowsedAndCreated() = runTest {
        val server = FakeNextcloudServer()
        server.folders += setOf("Photos", "Documents", "Documents/Family lists", ".hidden")
        val anna = device(server, backgroundScope)
        anna.connect(folder = null)
        assertEquals(listOf("Documents", "Photos"), anna.nextcloudFolders(""))
        assertEquals(listOf("Family lists"), anna.nextcloudFolders("Documents"))
        assertEquals(emptyList(), anna.nextcloudFolders("Documents/Family lists"))
        assertNull(anna.nextcloudFolders("OpenCheckList"))

        anna.createNextcloudFolder("Documents/Family lists/Shop")
        assertEquals(listOf("Shop"), anna.nextcloudFolders("Documents/Family lists"))
        anna.setNextcloudFolder("Documents/Family lists/Shop")
        anna.shareViaNextcloud(anna.newList("Groceries"))
        assertEquals(setOf("Documents/Family lists/Shop/Groceries.json"), server.filePaths)
        // Files are not folders.
        assertEquals(emptyList(), anna.nextcloudFolders("Documents/Family lists/Shop"))
    }

    @Test
    fun serverAddressesAndFoldersAreCleanedUp() {
        val clean = NextcloudAccountApi::serverAddress
        assertEquals("https://cloud.example.com", clean(" cloud.example.com "))
        assertEquals("https://cloud.example.com", clean("https://cloud.example.com/index.php/apps/files/?dir=/"))
        assertEquals("https://cloud.example.com", clean("https://cloud.example.com/s/AbC123"))
        assertEquals("https://example.com/nextcloud", clean("example.com/nextcloud/"))
        assertEquals("http://192.168.1.5:8080", clean("http://192.168.1.5:8080/login"))

        assertEquals("Family/Lists", NextcloudAccountApi.folderPath(" /Family/ Lists /"))
        assertEquals("Lists", NextcloudAccountApi.folderPath("../Lists"))
        assertNull(NextcloudAccountApi.folderPath(" / "))
    }
}
