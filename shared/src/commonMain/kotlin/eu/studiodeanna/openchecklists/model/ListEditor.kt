package eu.studiodeanna.openchecklists.model

import kotlin.random.Random

/**
 * Applies user edits to a document, stamping each change for this device.
 *
 * Stamps never go backwards relative to what the document already contains, so a device with a slow
 * clock still wins over changes it has already seen.
 */
class ListEditor(
    private val device: String,
    private val now: () -> Long,
    private val random: Random = Random.Default,
) {
    private fun ListDocument.stamp(): Long = maxOf(now(), latestStamp() + 1)

    fun newId(): String = buildString {
        repeat(12) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    fun rename(doc: ListDocument, title: String): ListDocument =
        doc.copy(title = title.trim(), titleAt = doc.stamp(), titleBy = device)

    fun addSection(doc: ListDocument, name: String): ListDocument {
        val pos = (doc.visibleSections().maxOfOrNull { it.pos } ?: 0.0) + 1.0
        val section = Section(newId(), name.trim(), pos, doc.stamp(), device)
        return doc.copy(sections = doc.sections + section)
    }

    fun renameSection(doc: ListDocument, id: String, name: String): ListDocument =
        doc.updateSection(id) { copy(name = name.trim()) }

    /** Moves a section one place up ([offset] = -1) or down (+1). */
    fun moveSection(doc: ListDocument, id: String, offset: Int): ListDocument {
        val ordered = doc.visibleSections()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + offset
        if (index < 0 || target !in ordered.indices) return doc
        val rest = ordered.filterIndexed { i, _ -> i != index }
        return placeSection(doc, id, rest.getOrNull(target - 1)?.id, rest.getOrNull(target)?.id)
    }

    /** Puts a section between [previous] and [next], either null at an end; where dragging drops it. */
    fun placeSection(doc: ListDocument, id: String, previous: String?, next: String?): ListDocument {
        val pos = between(doc.sections.firstOrNull { it.id == previous }?.pos, doc.sections.firstOrNull { it.id == next }?.pos)
        return doc.updateSection(id) { copy(pos = pos) }
    }

    /** Deletes a section together with the items in it. */
    fun deleteSection(doc: ListDocument, id: String): ListDocument {
        val at = doc.stamp()
        return doc.copy(
            sections = doc.sections.map { if (it.id == id) it.copy(deleted = true, at = at, by = device) else it },
            items = doc.items.map {
                if (it.section == id && !it.deleted) it.copy(deleted = true, at = at, by = device) else it
            },
        )
    }

    fun addItem(doc: ListDocument, section: String?, text: String): ListDocument {
        val siblings = doc.itemsBySection()[section].orEmpty()
        val pos = (siblings.maxOfOrNull { it.pos } ?: 0.0) + 1.0
        val item = Item(newId(), text.trim(), section, pos = pos, at = doc.stamp(), by = device)
        return doc.copy(items = doc.items + item)
    }

    fun editItem(doc: ListDocument, id: String, text: String): ListDocument =
        doc.updateItem(id) { copy(text = text.trim()) }

    fun setDone(doc: ListDocument, id: String, done: Boolean): ListDocument =
        doc.updateItem(id) { copy(done = done) }

    fun moveItem(doc: ListDocument, id: String, section: String?): ListDocument {
        val siblings = doc.itemsBySection()[section].orEmpty()
        val pos = (siblings.maxOfOrNull { it.pos } ?: 0.0) + 1.0
        return doc.updateItem(id) { copy(section = section, pos = pos) }
    }

    /** Puts an item between [previous] and [next] of its section, either null at an end; where dragging drops it. */
    fun placeItem(doc: ListDocument, id: String, previous: String?, next: String?): ListDocument {
        val pos = between(doc.items.firstOrNull { it.id == previous }?.pos, doc.items.firstOrNull { it.id == next }?.pos)
        return doc.updateItem(id) { copy(pos = pos) }
    }

    fun deleteItem(doc: ListDocument, id: String): ListDocument =
        doc.updateItem(id) { copy(deleted = true) }

    /** Removes every checked item. */
    fun clearDone(doc: ListDocument): ListDocument {
        val at = doc.stamp()
        return doc.copy(items = doc.items.map {
            if (it.done && !it.deleted) it.copy(deleted = true, at = at, by = device) else it
        })
    }

    /** Unchecks everything, for lists that are reused (the weekly shop). */
    fun uncheckAll(doc: ListDocument): ListDocument {
        val at = doc.stamp()
        return doc.copy(items = doc.items.map {
            if (it.done && !it.deleted) it.copy(done = false, at = at, by = device) else it
        })
    }

    /**
     * Takes back [change] as a new edit: what it touched reads again as before, and what it created is
     * deleted. Whatever has been changed again since, here or by others, is left as it now is.
     */
    fun revert(doc: ListDocument, change: Change): ListDocument {
        val at = doc.stamp()
        val sections = change.sections.associateBy { (_, new) -> new.id }
        val items = change.items.associateBy { (_, new) -> new.id }
        val title = change.title?.takeIf { (_, new) -> new == doc.title }?.first
        return doc.copy(
            title = title ?: doc.title,
            titleAt = if (title == null) doc.titleAt else at,
            titleBy = if (title == null) doc.titleBy else device,
            sections = doc.sections.map { current ->
                val (old, new) = sections[current.id] ?: return@map current
                if (!current.sameAs(new)) current else (old ?: new.copy(deleted = true)).copy(at = at, by = device)
            },
            items = doc.items.map { current ->
                val (old, new) = items[current.id] ?: return@map current
                if (!current.sameAs(new)) current else (old ?: new.copy(deleted = true)).copy(at = at, by = device)
            },
        )
    }

    private inline fun ListDocument.updateSection(id: String, change: Section.() -> Section): ListDocument {
        val at = stamp()
        return copy(sections = sections.map { if (it.id == id) it.change().copy(at = at, by = device) else it })
    }

    private inline fun ListDocument.updateItem(id: String, change: Item.() -> Item): ListDocument {
        val at = stamp()
        return copy(items = items.map { if (it.id == id) it.change().copy(at = at, by = device) else it })
    }

    private fun between(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 1.0
        before == null -> after!! - 1.0
        after == null -> before + 1.0
        else -> (before + after) / 2
    }

    private companion object {
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
