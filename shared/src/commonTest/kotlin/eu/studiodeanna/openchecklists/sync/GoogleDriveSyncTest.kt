package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.google.GoogleSignInRequired
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.store.MemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleDriveSyncTest {
    private var clock = 1_000L
    private val drive = FakeGoogleDrive()

    private suspend fun device(auth: FakeGoogleAuth, scope: CoroutineScope) =
        ChecklistRepository(MemoryFileStore(), scope, drive.http, google = auth, now = { clock++ }).also { it.load() }

    private fun linkTo(id: String) = ShareLink("https://drive.google.com/file/d/$id/view?usp=sharing")

    @Test
    fun ownerCreatesALinkAndAFriendEditsThroughIt() = runTest {
        val anna = device(FakeGoogleAuth(drive, "anna"), backgroundScope)
        val list = anna.createList("Groceries")
        anna.edit(list) { addItem(it, null, "Milk") }
        anna.shareViaGoogleDrive(list)

        val link = anna.entry(list)!!.link!!
        val fileId = (ShareLinks.parse(link.url) as ParsedLink.GoogleDrive).fileId
        assertEquals("writer", drive.files[fileId]!!.anyoneRole)
        assertEquals("anna", drive.files[fileId]!!.owner)

        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val bens = ben.openShared(link)
        assertEquals(listOf("Milk"), ben.entry(bens)!!.doc.liveItems().map { it.text })

        ben.edit(bens) { addItem(it, null, "Bread") }
        ben.sync(bens)
        anna.sync(list)
        assertEquals(setOf("Milk", "Bread"), anna.entry(list)!!.doc.liveItems().map { it.text }.toSet())
        assertEquals(anna.entry(list)!!.doc, ListCodec.decode(drive.files[fileId]!!.content))
    }

    @Test
    fun driveFilesAreNamedAfterTheListAndNumbered() = runTest {
        val anna = device(FakeGoogleAuth(drive, "anna"), backgroundScope)
        drive.addFile("ben", name = "Groceries.json") // someone else's file does not count
        val names = listOf("Groceries", "Groceries", "Tools").map { title ->
            val id = anna.createList(title)
            anna.shareViaGoogleDrive(id)
            val fileId = (ShareLinks.parse(anna.entry(id)!!.link!!.url) as ParsedLink.GoogleDrive).fileId
            drive.files[fileId]!!.name
        }
        assertEquals(listOf("Groceries.json", "Groceries (2).json", "Tools.json"), names)
    }

    @Test
    fun openingALinkSignedOutAsksForSignIn() = runTest {
        val id = drive.addFile("anna")
        val auth = FakeGoogleAuth(drive, "ben", signedIn = false)
        val ben = device(auth, backgroundScope)
        assertFailsWith<GoogleSignInRequired> { ben.openShared(linkTo(id)) }
        ben.signInToGoogle()
        ben.openShared(linkTo(id))
        assertEquals(1, ben.lists.value.size)
    }

    @Test
    fun signingOutPausesSyncUntilSignedInAgain() = runTest {
        val id = drive.addFile("anna")
        val auth = FakeGoogleAuth(drive, "ben")
        val ben = device(auth, backgroundScope)
        val list = ben.openShared(linkTo(id))
        ben.signOutOfGoogle()
        ben.edit(list) { addItem(it, null, "Eggs") }
        ben.sync(list)
        val failed = assertIs<SyncState.Failed>(ben.entry(list)!!.sync)
        assertTrue(failed.needsSignIn)

        ben.signInToGoogle()
        ben.sync(list)
        assertIs<SyncState.Synced>(ben.entry(list)!!.sync)
        assertEquals(listOf("Eggs"), ListCodec.decode(drive.files[id]!!.content).liveItems().map { it.text })
    }

    @Test
    fun anExpiredTokenIsRefreshedTransparently() = runTest {
        val id = drive.addFile("anna")
        val auth = FakeGoogleAuth(drive, "ben")
        val ben = device(auth, backgroundScope)
        val list = ben.openShared(linkTo(id))
        auth.expireToken()
        ben.edit(list) { addItem(it, null, "Tea") }
        ben.sync(list)
        assertIs<SyncState.Synced>(ben.entry(list)!!.sync)
    }

    @Test
    fun aSaveInBetweenIsMergedNotLost() = runTest {
        val id = drive.addFile("anna")
        val anna = device(FakeGoogleAuth(drive, "anna"), backgroundScope)
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val a = anna.openShared(linkTo(id))
        val b = ben.openShared(linkTo(id))

        anna.edit(a) { addItem(it, null, "Cake") }
        ben.edit(b) { addItem(it, null, "Candles") }
        drive.beforeNextUpload = { anna.sync(a) }
        ben.sync(b)
        // Whichever upload landed last, one more round each brings everything together.
        anna.sync(a)
        ben.sync(b)
        val texts = ListCodec.decode(drive.files[id]!!.content).liveItems().map { it.text }.toSet()
        assertEquals(setOf("Cake", "Candles"), texts)
    }

    @Test
    fun linksWithAResourceKeySendIt() = runTest {
        val id = drive.addFile("anna")
        drive.files[id]!!.resourceKey = "0-AbCd"
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        assertFailsWith<RemoteException> { ben.openShared(linkTo(id)) }
        ben.openShared(ShareLink("https://drive.google.com/file/d/$id/view?usp=sharing&resourcekey=0-AbCd"))
    }

    @Test
    fun viewOnlyFilesExplainWhatIsMissing() = runTest {
        val id = drive.addFile("anna", ListCodec.encode(eu.studiodeanna.openchecklists.model.ListDocument(title = "Shop")), anyoneRole = "reader")
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val list = ben.openShared(linkTo(id))
        ben.edit(list) { addItem(it, null, "Soap") }
        ben.sync(list)
        val failed = assertIs<SyncState.Failed>(ben.entry(list)!!.sync)
        assertTrue("view the file but not edit" in failed.message, failed.message)
    }

    @Test
    fun privateFilesAndGoogleDocsAreRefused() = runTest {
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val private = drive.addFile("anna", anyoneRole = null)
        assertTrue("Anyone with the link" in assertFailsWith<RemoteException> { ben.openShared(linkTo(private)) }.message!!)

        val doc = drive.addFile("anna")
        drive.files[doc]!!.mimeType = "application/vnd.google-apps.document"
        assertTrue("Google Docs" in assertFailsWith<RemoteException> { ben.openShared(linkTo(doc)) }.message!!)
        assertTrue(ben.lists.value.isEmpty())
    }

    @Test
    fun buildsWithoutGoogleExplainThatDriveIsUnavailable() = runTest {
        val repo = ChecklistRepository(MemoryFileStore(), backgroundScope, drive.http, now = { clock++ }).also { it.load() }
        val error = assertFailsWith<RemoteException> { repo.openShared(linkTo("x")) }
        assertTrue("not set up" in error.message!!)
    }
}
