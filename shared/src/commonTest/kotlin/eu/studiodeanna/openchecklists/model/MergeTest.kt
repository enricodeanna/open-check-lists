package eu.studiodeanna.openchecklists.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeTest {
    private var time = 1_000L
    private val alice = ListEditor("alice", { time })
    private val bob = ListEditor("bob", { time })

    private fun base(): ListDocument {
        var doc = alice.rename(ListDocument(), "Groceries")
        doc = alice.addSection(doc, "Produce")
        doc = alice.addSection(doc, "Dairy")
        val produce = doc.visibleSections()[0].id
        doc = alice.addItem(doc, produce, "Apples")
        return doc
    }

    @Test
    fun mergeIsOrderIndependentAndIdempotent() {
        val start = base()
        val a = alice.addItem(start, null, "Bread")
        val b = bob.setDone(start, start.items.single().id, true)
        assertEquals(merge(a, b), merge(b, a))
        assertEquals(merge(a, b), merge(merge(a, b), b))
    }

    @Test
    fun titlesWithTheSameStampMergeTheSameEitherWay() {
        val named = ListDocument(title = "Shop")
        assertEquals(merge(named, ListDocument()), merge(ListDocument(), named))
        assertEquals("Shop", merge(ListDocument(), named).title)
    }

    @Test
    fun concurrentAdditionsFromBothSidesAreKept() {
        val start = base()
        val dairy = start.visibleSections()[1].id
        val a = alice.addItem(start, dairy, "Milk")
        val b = bob.addItem(start, dairy, "Butter")
        val merged = merge(a, b)
        assertEquals(setOf("Milk", "Butter"), merged.itemsBySection()[dairy]!!.map { it.text }.toSet())
    }

    @Test
    fun laterChangeToTheSameItemWins() {
        val start = base()
        val apples = start.items.single().id
        val a = alice.editItem(start, apples, "Green apples")
        time += 5
        val b = bob.setDone(start, apples, true)
        val item = merge(a, b).items.single()
        assertEquals("Apples", item.text)
        assertTrue(item.done)
    }

    @Test
    fun deletionSurvivesMergeWithAnOlderCopy() {
        val start = base()
        time += 1
        val deleted = alice.deleteItem(start, start.items.single().id)
        assertTrue(merge(start, deleted).liveItems().isEmpty())
    }

    @Test
    fun itemAddedToASectionDeletedElsewhereMovesToTheTop() {
        val start = base()
        val produce = start.visibleSections()[0].id
        time += 1
        val a = alice.deleteSection(start, produce)
        val b = bob.addItem(start, produce, "Pears")
        val grouped = merge(a, b).itemsBySection()
        assertEquals(listOf("Pears"), grouped[null]!!.map { it.text })
    }

    @Test
    fun deviceWithSlowClockStillOverridesWhatItHasSeen() {
        val start = base()
        val apples = start.items.single().id
        val slow = ListEditor("carol", { 0L })
        val edited = slow.editItem(start, apples, "Red apples")
        assertEquals("Red apples", merge(start, edited).items.single().text)
    }

    @Test
    fun movingASectionPlacesItBetweenNeighbours() {
        var doc = base()
        doc = alice.addSection(doc, "Bakery")
        val bakery = doc.visibleSections().last().id
        doc = alice.moveSection(doc, bakery, -1)
        assertEquals(listOf("Produce", "Bakery", "Dairy"), doc.visibleSections().map { it.name })
        doc = alice.moveSection(doc, bakery, -1)
        assertEquals(listOf("Bakery", "Produce", "Dairy"), doc.visibleSections().map { it.name })
    }

    @Test
    fun placingASectionPutsItBetweenNeighbours() {
        var doc = alice.addSection(base(), "Bakery")
        val (produce, dairy, bakery) = doc.visibleSections().map { it.id }
        doc = alice.placeSection(doc, bakery, null, produce)
        assertEquals(listOf("Bakery", "Produce", "Dairy"), doc.visibleSections().map { it.name })
        doc = alice.placeSection(doc, bakery, dairy, null)
        assertEquals(listOf("Produce", "Dairy", "Bakery"), doc.visibleSections().map { it.name })
    }

    @Test
    fun placingAnItemPutsItBetweenNeighbours() {
        var doc = base()
        val produce = doc.visibleSections()[0].id
        doc = alice.addItem(doc, produce, "Pears")
        doc = alice.addItem(doc, produce, "Plums")
        fun id(text: String) = doc.liveItems().single { it.text == text }.id
        fun texts() = doc.itemsBySection()[produce]!!.map { it.text }

        doc = alice.placeItem(doc, id("Plums"), null, id("Apples"))
        assertEquals(listOf("Plums", "Apples", "Pears"), texts())
        doc = alice.placeItem(doc, id("Plums"), id("Apples"), id("Pears"))
        assertEquals(listOf("Apples", "Plums", "Pears"), texts())
        doc = alice.placeItem(doc, id("Apples"), id("Pears"), null)
        assertEquals(listOf("Plums", "Pears", "Apples"), texts())
    }

    @Test
    fun itemsPlacedOnTwoDevicesMergeOneByOne() {
        var start = base()
        val produce = start.visibleSections()[0].id
        start = alice.addItem(start, produce, "Pears")
        start = alice.addItem(start, produce, "Plums")
        fun id(text: String) = start.liveItems().single { it.text == text }.id

        val a = alice.placeItem(start, id("Plums"), null, id("Apples"))
        time += 1
        val b = bob.placeItem(start, id("Apples"), id("Pears"), id("Plums"))
        val merged = merge(a, b).itemsBySection()[produce]!!.map { it.text }
        assertEquals(listOf("Plums", "Pears", "Apples"), merged)
    }

    @Test
    fun fileRoundTrips() {
        val doc = alice.clearDone(base())
        assertEquals(doc, ListCodec.decode(ListCodec.encode(doc)))
    }
}
