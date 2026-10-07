package eu.studiodeanna.openchecklists.model

/**
 * What one edit changed: each section and item it touched as it was before (null for one it created)
 * and after, and the title likewise. [ListEditor.revert] takes it back as a new edit, so an undo syncs
 * like any other change.
 */
data class Change(
    val title: Pair<String, String>? = null,
    val sections: List<Pair<Section?, Section>> = emptyList(),
    val items: List<Pair<Item?, Item>> = emptyList(),
)

/** What [before] became in [after], or null if nothing reads differently; new stamps alone do not count. */
fun changeBetween(before: ListDocument, after: ListDocument): Change? {
    val change = Change(
        title = (before.title to after.title).takeIf { it.first != it.second },
        sections = changed(before.sections, after.sections),
        items = changed(before.items, after.items),
    )
    return change.takeUnless { it.title == null && it.sections.isEmpty() && it.items.isEmpty() }
}

private fun <T : Stamped> changed(before: List<T>, after: List<T>): List<Pair<T?, T>> {
    val old = before.associateBy { it.id }
    return after.filterNot { it.sameAs(old[it.id]) }.map { old[it.id] to it }
}

/** Whether [other] is this section or item saying the same, whoever stamped it and when. */
internal fun Stamped.sameAs(other: Stamped?): Boolean = when (this) {
    is Section -> other is Section && copy(at = other.at, by = other.by) == other
    is Item -> other is Item && copy(at = other.at, by = other.by) == other
}

/** What the user did in one edit, enough to name it in a message ("Undone: checked “Milk”"). */
sealed interface Action {
    data object RenamedList : Action
    data class AddedSection(val name: String) : Action
    data class RenamedSection(val name: String) : Action
    data class MovedSection(val name: String) : Action
    data class DeletedSection(val name: String) : Action
    data class AddedItem(val text: String) : Action
    data class EditedItem(val text: String) : Action
    data class MovedItem(val text: String) : Action
    data class CheckedItem(val text: String) : Action
    data class UncheckedItem(val text: String) : Action
    data class DeletedItem(val text: String) : Action
    data class RemovedChecked(val count: Int) : Action
    data class UncheckedItems(val count: Int) : Action
    /** Anything the editor does not do in one step today. */
    data object Other : Action
}

/** Names the edit that made this change. */
fun Change.action(): Action {
    if (title != null) return Action.RenamedList
    val section = sections.singleOrNull()
    if (section != null) {
        val (old, new) = section
        return when {
            old == null -> Action.AddedSection(new.name)
            new.deleted -> Action.DeletedSection(new.name)
            old.name != new.name -> Action.RenamedSection(new.name)
            else -> Action.MovedSection(new.name)
        }
    }
    val item = items.singleOrNull()
    if (sections.isEmpty() && item != null) {
        val (old, new) = item
        return when {
            old == null -> Action.AddedItem(new.text)
            new.deleted -> Action.DeletedItem(new.text)
            old.text != new.text -> Action.EditedItem(new.text)
            old.done != new.done -> if (new.done) Action.CheckedItem(new.text) else Action.UncheckedItem(new.text)
            else -> Action.MovedItem(new.text)
        }
    }
    return when {
        sections.isNotEmpty() || items.isEmpty() -> Action.Other
        items.all { (_, new) -> new.deleted } -> Action.RemovedChecked(items.size)
        items.all { (_, new) -> !new.done } -> Action.UncheckedItems(items.size)
        else -> Action.Other
    }
}

/** An edit made on this device that can be taken back: what it changed, and what it was. */
data class Step(val change: Change, val action: Action)

/** This device's latest edits to one list, newest last, for undo and redo. Kept only while the app runs. */
data class History(val undo: List<Step> = emptyList(), val redo: List<Step> = emptyList()) {
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** After a new edit: it can be undone, and what was undone before it can no longer be redone. */
    fun record(change: Change): History = History((undo + Step(change, change.action())).takeLast(LIMIT), emptyList())

    /** The step an undo ([redo] false) or a redo would take back next. */
    fun next(redo: Boolean): Step? = (if (redo) this.redo else undo).lastOrNull()

    /**
     * After [next] was taken back by the edit [back]: it moves to the other list, so it can be taken
     * back in turn. With [back] null (everything in it was changed again since) it is just dropped.
     */
    fun took(redo: Boolean, back: Change?): History {
        val moved = back?.let { listOf(Step(it, next(redo)!!.action)) }.orEmpty()
        return if (redo) History((undo + moved).takeLast(LIMIT), this.redo.dropLast(1))
        else History(undo.dropLast(1), this.redo + moved)
    }

    companion object {
        const val LIMIT = 10
    }
}
