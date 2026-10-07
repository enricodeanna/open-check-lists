package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.ChecklistRepository
import eu.studiodeanna.openchecklists.ListChoiceNeeded
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.defaultHttpClient
import eu.studiodeanna.openchecklists.model.liveItems
import eu.studiodeanna.openchecklists.store.MemoryFileStore
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Two lists with the same name, and two devices, through a real Nextcloud folder share; the test
 * deletes the files it made. Opt-in:
 * `OCL_NEXTCLOUD_TEST_LINK=https://…/s/<token> ./gradlew :shared:jvmTest --tests '*RealNextcloud*'`.
 * Use a throwaway folder; other files in it are left alone.
 */
class RealNextcloudTest {
    @Test
    fun listsGetTheirOwnFilesAndSyncThroughARealShare() = runBlocking {
        val url = System.getenv("OCL_NEXTCLOUD_TEST_LINK") ?: return@runBlocking
        // Real time, one thread: runTest would skip the HTTP timeouts' delays and time requests out at once.
        val background = Job()
        val scope = CoroutineScope(coroutineContext + background)
        val link = ShareLink(url, System.getenv("OCL_NEXTCLOUD_TEST_PASSWORD"))
        val http = defaultHttpClient()
        val created = mutableListOf<String>()
        try {
            val anna = ChecklistRepository(MemoryFileStore(), scope, http).also { it.load() }
            val first = anna.createList("Integration test")
            anna.edit(first) { addItem(it, null, "Apples") }
            anna.attachLink(first, link)
            val second = anna.createList("Integration test")
            anna.attachLink(second, link)
            created += listOfNotNull(anna.entry(first)!!.link!!.fileName, anna.entry(second)!!.link!!.fileName)
            assertEquals(listOf("Integration test.json", "Integration test (2).json"), created)

            val ben = ChecklistRepository(MemoryFileStore(), scope, http).also { it.load() }
            val choice = assertFailsWith<ListChoiceNeeded> { ben.openShared(link) }
            assertTrue(choice.lists.map { it.fileName }.containsAll(created), choice.lists.toString())
            val bens = ben.openShared(choice.link.copy(fileName = "Integration test.json"))
            assertEquals(listOf("Apples"), ben.entry(bens)!!.doc.liveItems().map { it.text })
            // The address shown for sharing leads straight to the list.
            val direct = ChecklistRepository(MemoryFileStore(), scope, http).also { it.load() }
            direct.openShared(ShareLink(ShareLinks.shareable(ben.entry(bens)!!.link!!)))

            ben.edit(bens) { addItem(it, null, "Bread") }
            anna.edit(first) { addItem(it, null, "Milk") }
            ben.sync(bens)
            anna.sync(first)
            ben.sync(bens)
            // Edits also start their own sync shortly after; let those finish before comparing.
            withTimeout(30_000) {
                while (listOf(anna.entry(first)!!, ben.entry(bens)!!).any { it.sync == SyncState.Syncing }) delay(200)
            }
            anna.sync(first)
            assertIs<SyncState.Synced>(anna.entry(first)!!.sync)
            assertIs<SyncState.Synced>(ben.entry(bens)!!.sync)
            assertEquals(setOf("Apples", "Bread", "Milk"), anna.entry(first)!!.doc.liveItems().map { it.text }.toSet())
            assertEquals(anna.entry(first)!!.doc, ben.entry(bens)!!.doc)
        } finally {
            background.cancel()
            val parsed = ShareLinks.parse(url) as ParsedLink.Nextcloud
            for (name in created) {
                http.request("${parsed.server}/public.php/dav/files/${parsed.token}/${name.encodeURLPathPart()}") {
                    method = HttpMethod.Delete
                    header("X-Requested-With", "XMLHttpRequest")
                }
            }
        }
    }
}
