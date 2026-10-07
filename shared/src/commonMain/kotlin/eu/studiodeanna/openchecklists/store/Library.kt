package eu.studiodeanna.openchecklists.store

import eu.studiodeanna.openchecklists.sync.ShareLink
import kotlinx.serialization.Serializable

/** The lists on this device, and for each one where it is shared. Never leaves the device. */
@Serializable
data class Library(
    val device: String,
    val lists: List<LibraryEntry> = emptyList(),
)

@Serializable
data class LibraryEntry(
    val id: String,
    val link: ShareLink? = null,
    val lastSyncedAt: Long? = null,
    /** Sections folded away on this device. */
    val collapsed: Set<String> = emptySet(),
)
