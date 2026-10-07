package eu.studiodeanna.openchecklists.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FoldingTest {
    private val editor = ListEditor("alice", { 1_000L })
    private val start: ListDocument
    private val produce: String
    private val dairy: String

    init {
        var doc = editor.addSection(ListDocument(), "Produce")
        doc = editor.addSection(doc, "Dairy")
        produce = doc.visibleSections()[0].id
        dairy = doc.visibleSections()[1].id
        doc = editor.addItem(doc, produce, "Apples")
        doc = editor.addItem(doc, produce, "Pears")
        doc = editor.addItem(doc, dairy, "Milk")
        start = doc
    }

    private fun ListDocument.id(text: String) = liveItems().single { it.text == text }.id

    private fun ListDocument.check(vararg texts: String) =
        texts.fold(this) { doc, text -> editor.setDone(doc, doc.id(text), true) }

    @Test
    fun checkingTheLastItemFoldsTheSection() {
        val partly = start.check("Apples")
        assertEquals(emptySet(), foldCompleted(emptySet(), start, partly))
        val done = partly.check("Pears")
        assertEquals(setOf(produce), foldCompleted(emptySet(), partly, done))
    }

    @Test
    fun uncheckingOrAddingAnItemReopensIt() {
        val done = start.check("Apples", "Pears")
        assertEquals(emptySet(), foldCompleted(setOf(produce), done, editor.setDone(done, done.id("Pears"), false)))
        assertEquals(emptySet(), foldCompleted(setOf(produce), done, editor.addItem(done, produce, "Plums")))
        assertEquals(emptySet(), foldCompleted(setOf(produce), done, editor.uncheckAll(done)))
    }

    @Test
    fun otherChangesKeepWhatTheUserChose() {
        // Dairy was folded by hand while incomplete, Produce opened by hand once complete.
        val done = start.check("Apples", "Pears")
        assertEquals(setOf(dairy), foldCompleted(setOf(dairy), done, editor.addItem(done, dairy, "Butter")))
    }

    @Test
    fun emptySectionsAreNeverComplete() {
        assertEquals(emptySet(), editor.addSection(start, "Bakery").completeSections())
        // Removing the checked items empties Produce, which then opens instead of staying folded.
        val done = start.check("Apples", "Pears")
        assertEquals(emptySet(), foldCompleted(setOf(produce), done, editor.clearDone(done)))
    }

    @Test
    fun deletedSectionsAreForgotten() {
        assertEquals(emptySet(), foldCompleted(setOf(dairy), start, editor.deleteSection(start, dairy)))
    }
}
