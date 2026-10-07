package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.google.GoogleFileAccessRequired
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

    /** Opens [link] the way the app's dialog does: picking the file in Google's picker if asked to. */
    private suspend fun ChecklistRepository.openPicking(link: ShareLink): String = try {
        openShared(link)
    } catch (e: GoogleFileAccessRequired) {
        pickGoogleFile(e.fileId)
        openShared(link)
    }

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

        // Ben's app may not use Anna's file until he picks it in Google's picker.
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        assertEquals(fileId, assertFailsWith<GoogleFileAccessRequired> { ben.openShared(link) }.fileId)
        ben.pickGoogleFile(fileId)
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
        drive.addFile("anna", name = "Tools.json") // nor one the app may not see; Drive allows the same name twice
        val names = listOf("Groceries", "Groceries", "Tools").map { title ->
            val id = anna.createList(title)
            anna.shareViaGoogleDrive(id)
            val fileId = (ShareLinks.parse(anna.entry(id)!!.link!!.url) as ParsedLink.GoogleDrive).fileId
            drive.files[fileId]!!.name
        }
        assertEquals(listOf("Groceries.json", "Groceries (2).json", "Tools.json"), names)
    }

    @Test
    fun openingALinkSignedOutGoesStraightToThePicker() = runTest {
        val id = drive.addFile("anna")
        val auth = FakeGoogleAuth(drive, "ben", signedIn = false)
        val ben = device(auth, backgroundScope)
        ben.openPicking(linkTo(id))
        assertEquals(1, ben.lists.value.size)
        assertEquals(0, auth.signInCalls)
        assertEquals(1, auth.pickCalls)
        assertTrue(ben.googleSignedIn.value)
    }

    @Test
    fun aFileIsPickedOnceForAllOfTheUsersDevices() = runTest {
        val id = drive.addFile("anna")
        device(FakeGoogleAuth(drive, "ben"), backgroundScope).openPicking(linkTo(id))
        val bensTablet = FakeGoogleAuth(drive, "ben")
        device(bensTablet, backgroundScope).openPicking(linkTo(id))
        assertEquals(0, bensTablet.pickCalls)
    }

    @Test
    fun aListWhoseAccessWasRemovedSyncsAgainOncePicked() = runTest {
        val id = drive.addFile("anna")
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val list = ben.openPicking(linkTo(id))
        drive.revoke("ben", id)
        ben.edit(list) { addItem(it, null, "Jam") }
        ben.sync(list)
        assertEquals(id, assertIs<SyncState.Failed>(ben.entry(list)!!.sync).pickFile)

        ben.pickGoogleFile(id)
        ben.sync(list)
        assertIs<SyncState.Synced>(ben.entry(list)!!.sync)
        assertEquals(listOf("Jam"), ListCodec.decode(drive.files[id]!!.content).liveItems().map { it.text })
    }

    @Test
    fun signingOutPausesSyncUntilSignedInAgain() = runTest {
        val id = drive.addFile("anna")
        val auth = FakeGoogleAuth(drive, "ben")
        val ben = device(auth, backgroundScope)
        val list = ben.openPicking(linkTo(id))
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
        val list = ben.openPicking(linkTo(id))
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
        val a = anna.openPicking(linkTo(id))
        val b = ben.openPicking(linkTo(id))

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
        assertFailsWith<RemoteException> { ben.openPicking(linkTo(id)) }
        ben.openShared(ShareLink("https://drive.google.com/file/d/$id/view?usp=sharing&resourcekey=0-AbCd"))
    }

    @Test
    fun viewOnlyFilesExplainWhatIsMissing() = runTest {
        val id = drive.addFile("anna", ListCodec.encode(eu.studiodeanna.openchecklists.model.ListDocument(title = "Shop")), anyoneRole = "reader")
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val list = ben.openPicking(linkTo(id))
        ben.edit(list) { addItem(it, null, "Soap") }
        ben.sync(list)
        val failed = assertIs<SyncState.Failed>(ben.entry(list)!!.sync)
        assertTrue("view the file but not edit" in failed.message, failed.message)
    }

    @Test
    fun privateFilesAndGoogleDocsAreRefused() = runTest {
        val ben = device(FakeGoogleAuth(drive, "ben"), backgroundScope)
        val private = drive.addFile("anna", anyoneRole = null)
        assertTrue("Anyone with the link" in assertFailsWith<RemoteException> { ben.openPicking(linkTo(private)) }.message!!)

        val doc = drive.addFile("anna")
        drive.files[doc]!!.mimeType = "application/vnd.google-apps.document"
        assertTrue("Google Docs" in assertFailsWith<RemoteException> { ben.openPicking(linkTo(doc)) }.message!!)
        assertTrue(ben.lists.value.isEmpty())
    }

    @Test
    fun buildsWithoutGoogleExplainThatDriveIsUnavailable() = runTest {
        val repo = ChecklistRepository(MemoryFileStore(), backgroundScope, drive.http, now = { clock++ }).also { it.load() }
        val error = assertFailsWith<RemoteException> { repo.openShared(linkTo("x")) }
        assertTrue("not set up" in error.message!!)
    }
}
