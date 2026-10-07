package eu.studiodeanna.openchecklists.model

import kotlinx.serialization.Serializable

/**
 * One to-do list, exactly as it is stored in its file.
 *
 * Every section and item carries a stamp (`at`, `by`) of its last change, and deletions are kept as
 * tombstones, so two copies edited apart can always be merged with [merge].
 */
@Serializable
data class ListDocument(
    val format: Int = FORMAT,
    val title: String = "",
    val titleAt: Long = 0,
    val titleBy: String = "",
    val sections: List<Section> = emptyList(),
    val items: List<Item> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

sealed interface Stamped {
    val id: String
    val at: Long
    val by: String
    val deleted: Boolean
}

@Serializable
data class Section(
    override val id: String,
    val name: String,
    val pos: Double,
    override val at: Long,
    override val by: String,
    override val deleted: Boolean = false,
) : Stamped

@Serializable
data class Item(
    override val id: String,
    val text: String,
    /** Owning section, or null for items that sit above every section. */
    val section: String? = null,
    val done: Boolean = false,
    val pos: Double,
    override val at: Long,
    override val by: String,
    override val deleted: Boolean = false,
) : Stamped

/** Latest stamp time anywhere in the document; new edits are stamped after it. */
fun ListDocument.latestStamp(): Long =
    maxOf(titleAt, sections.maxOfOrNull { it.at } ?: 0, items.maxOfOrNull { it.at } ?: 0)

/** Live sections in display order. */
fun ListDocument.visibleSections(): List<Section> =
    sections.filterNot { it.deleted }.sortedWith(compareBy({ it.pos }, { it.id }))

/**
 * Live items grouped for display: the null key holds items without a (live) section, which happens
 * when a section is deleted on one device while an item is added to it on another.
 */
fun ListDocument.itemsBySection(): Map<String?, List<Item>> {
    val live = visibleSections().mapTo(HashSet()) { it.id }
    return items.filterNot { it.deleted }
        .sortedWith(compareBy({ it.pos }, { it.id }))
        .groupBy { if (it.section in live) it.section else null }
}

fun ListDocument.liveItems(): List<Item> = items.filterNot { it.deleted }

/** Live sections that have items and all of them checked. */
fun ListDocument.completeSections(): Set<String> =
    itemsBySection().entries
        .filter { (section, items) -> section != null && items.all { it.done } }
        .mapNotNullTo(HashSet()) { it.key }

/**
 * The sections folded away on a device after its copy of a list changed from [before] to [after]. A
 * section whose items have all been checked folds; one that stops being complete (an item unchecked or
 * added) opens again; otherwise the user's choice in [collapsed] stays, except for deleted sections.
 */
fun foldCompleted(collapsed: Set<String>, before: ListDocument, after: ListDocument): Set<String> {
    val was = before.completeSections()
    val now = after.completeSections()
    val live = after.visibleSections().mapTo(HashSet()) { it.id }
    return (collapsed - (was - now) + (now - was)).filterTo(HashSet()) { it in live }
}
