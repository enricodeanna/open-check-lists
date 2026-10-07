package eu.studiodeanna.openchecklists.model

/**
 * Combines two copies of a list. Per section and per item the most recent change wins, and the title
 * likewise. The result does not depend on argument order, and merging with a copy already contained
 * in the other changes nothing, so it is safe to merge repeatedly.
 */
fun merge(a: ListDocument, b: ListDocument): ListDocument {
    val order = compareStamps(a.titleAt, a.titleBy, b.titleAt, b.titleBy)
    // Equal stamps with different titles only happen with hand-made files; any fixed rule keeps merge symmetric.
    val titleFromA = if (order != 0) order > 0 else a.title >= b.title
    val title = if (titleFromA) a else b
    return ListDocument(
        format = maxOf(a.format, b.format),
        title = title.title,
        titleAt = title.titleAt,
        titleBy = title.titleBy,
        sections = mergeEntities(a.sections, b.sections),
        items = mergeEntities(a.items, b.items),
    )
}

/** The canonical form of a document: what [merge] would produce, used to compare copies. */
fun ListDocument.normalized(): ListDocument = merge(this, this)

private fun <T : Stamped> mergeEntities(a: List<T>, b: List<T>): List<T> {
    val byId = LinkedHashMap<String, T>()
    for (entity in a + b) {
        val existing = byId[entity.id]
        if (existing == null || newer(entity, existing)) byId[entity.id] = entity
    }
    return byId.values.sortedBy { it.id }
}

private fun newer(candidate: Stamped, current: Stamped): Boolean {
    val order = compareStamps(candidate.at, candidate.by, current.at, current.by)
    if (order != 0) return order > 0
    // Same stamp should mean the same change; prefer the tombstone so a delete is never lost.
    return candidate.deleted && !current.deleted
}

private fun compareStamps(atA: Long, byA: String, atB: Long, byB: String): Int =
    compareValuesBy(atA to byA, atB to byB, { it.first }, { it.second })

/** Drops tombstones older than [maxAgeMillis]; a stale offline copy may then resurrect them. */
fun ListDocument.pruned(now: Long, maxAgeMillis: Long = 90L * 24 * 60 * 60 * 1000): ListDocument {
    val cutoff = now - maxAgeMillis
    return copy(
        sections = sections.filterNot { it.deleted && it.at < cutoff },
        items = items.filterNot { it.deleted && it.at < cutoff },
    )
}
