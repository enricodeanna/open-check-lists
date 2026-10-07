package eu.studiodeanna.openchecklists.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoTest {
    private var time = 1_000L
    private val alice = ListEditor("alice", { time++ })
    private val bob = ListEditor("bob", { time++ })

    private fun ListDocument.id(text: String) = items.single { it.text == text }.id

    /** This document after [edit], and the change it made. */
    private fun ListDocument.edited(edit: (ListDocument) -> ListDocument): Pair<ListDocument, Change> {
        val after = edit(this)
        return after to changeBetween(this, after)!!
    }

    @Test
    fun undoingAnAdditionDeletesItAndRedoingBringsItBack() {
        val (added, change) = ListDocument().edited { alice.addItem(it, null, "Milk") }
        val undone = alice.revert(added, change)
        assertTrue(undone.liveItems().isEmpty())
        val redone = alice.revert(undone, changeBetween(added, undone)!!)
        assertEquals(listOf("Milk"), redone.liveItems().map { it.text })
    }

    @Test
    fun undoingASectionDeletionBringsBackItsItemsButNotOnesDeletedBefore() {
        var doc = alice.addSection(ListDocument(), "Dairy")
        val dairy = doc.visibleSections().single().id
        doc = alice.addItem(doc, dairy, "Milk")
        doc = alice.addItem(doc, dairy, "Cream")
        doc = alice.deleteItem(doc, doc.id("Cream"))
        val (deleted, change) = doc.edited { alice.deleteSection(it, dairy) }
        val undone = alice.revert(deleted, change)
        assertEquals(listOf("Dairy"), undone.visibleSections().map { it.name })
        assertEquals(listOf("Milk"), undone.itemsBySection()[dairy]!!.map { it.text })
    }

    @Test
    fun undoingSeveralEditsInARowTakesBackEach() {
        val (added, adding) = ListDocument().edited { alice.addItem(it, null, "Milk") }
        val (checked, checking) = added.edited { alice.setDone(it, it.id("Milk"), true) }
        val unchecked = alice.revert(checked, checking)
        assertFalse(unchecked.liveItems().single().done)
        assertTrue(alice.revert(unchecked, adding).liveItems().isEmpty())
    }

    @Test
    fun anUndoWinsOverTheCopiesOthersAlreadyHave() {
        val start = alice.addItem(ListDocument(), null, "Milk")
        val (checked, change) = start.edited { alice.setDone(it, it.id("Milk"), true) }
        val bobsCopy = checked
        val undone = alice.revert(checked, change)
        assertFalse(merge(bobsCopy, undone).liveItems().single().done)
        assertFalse(merge(undone, bobsCopy).liveItems().single().done)
    }

    @Test
    fun undoLeavesWhatOthersChangedSinceAlone() {
        var doc = alice.addItem(ListDocument(), null, "Milk")
        doc = alice.addItem(doc, null, "Bread")
        doc = alice.setDone(doc, doc.id("Milk"), true)
        doc = alice.setDone(doc, doc.id("Bread"), true)
        val (cleared, change) = doc.edited { alice.clearDone(it) }
        // Bob, who had not seen the clearing yet, renames the bread; his later change wins the merge.
        val bobs = bob.editItem(doc, doc.id("Bread"), "Rye bread")
        val merged = merge(cleared, bobs)

        val undone = alice.revert(merged, change)
        assertEquals(listOf("Milk", "Rye bread"), undone.liveItems().map { it.text }.sorted())
        assertEquals(merged.items.single { it.text == "Rye bread" }, undone.items.single { it.text == "Rye bread" })
    }

    @Test
    fun anEditOthersHaveChangedEntirelyHasNothingLeftToUndo() {
        val start = alice.addItem(ListDocument(), null, "Milk")
        val (renamed, change) = start.edited { alice.editItem(it, it.id("Milk"), "Oat milk") }
        val bobs = bob.editItem(renamed, renamed.id("Oat milk"), "Soy milk")
        assertNull(changeBetween(bobs, alice.revert(bobs, change)))
    }

    @Test
    fun editsThatOnlyRestampAreNoChange() {
        val doc = alice.addSection(ListDocument(), "Dairy")
        assertNull(changeBetween(doc, alice.renameSection(doc, doc.sections.single().id, "Dairy")))
    }

    @Test
    fun actionsNameWhatWasDone() {
        var doc = alice.addSection(ListDocument(), "Dairy")
        val dairy = doc.sections.single().id
        doc = alice.addItem(doc, dairy, "Milk")
        doc = alice.addItem(doc, dairy, "Cream")
        fun action(edit: (ListDocument) -> ListDocument) = doc.edited(edit).second.action()

        assertEquals(Action.RenamedList, action { alice.rename(it, "Shop") })
        assertEquals(Action.AddedSection("Produce"), action { alice.addSection(it, "Produce") })
        assertEquals(Action.RenamedSection("Milk & co"), action { alice.renameSection(it, dairy, "Milk & co") })
        assertEquals(Action.DeletedSection("Dairy"), action { alice.deleteSection(it, dairy) })
        assertEquals(Action.AddedItem("Eggs"), action { alice.addItem(it, null, "Eggs") })
        assertEquals(Action.CheckedItem("Milk"), action { alice.setDone(it, it.id("Milk"), true) })
        assertEquals(Action.MovedItem("Milk"), action { alice.moveItem(it, it.id("Milk"), null) })
        assertEquals(Action.EditedItem("Oat milk"), action { alice.moveItem(alice.editItem(it, it.id("Milk"), "Oat milk"), it.id("Milk"), null) })
        assertEquals(Action.DeletedItem("Cream"), action { alice.deleteItem(it, it.id("Cream")) })

        doc = alice.setDone(alice.setDone(doc, doc.id("Milk"), true), doc.id("Cream"), true)
        assertEquals(Action.RemovedChecked(2), action { alice.clearDone(it) })
        assertEquals(Action.UncheckedItems(2), action { alice.uncheckAll(it) })
        assertEquals(Action.UncheckedItem("Milk"), action { alice.setDone(it, it.id("Milk"), false) })
    }

    @Test
    fun historyKeepsTheLastTenEditsAndANewEditEndsRedo() {
        val changes = (1..12).map { Change(title = "$it" to "${it + 1}") }
        var history = changes.fold(History()) { history, change -> history.record(change) }
        assertEquals(History.LIMIT, history.undo.size)
        assertEquals(changes.last(), history.next(redo = false)!!.change)

        history = history.took(redo = false, back = Change(title = "13" to "12"))
        assertEquals(Change(title = "13" to "12"), history.next(redo = true)!!.change)
        assertEquals(changes[10], history.next(redo = false)!!.change)

        history = history.record(Change(title = "a" to "b"))
        assertFalse(history.canRedo)
    }

    @Test
    fun aStepWithNothingLeftToTakeBackIsDropped() {
        val history = History().record(Change(title = "a" to "b")).took(redo = false, back = null)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
