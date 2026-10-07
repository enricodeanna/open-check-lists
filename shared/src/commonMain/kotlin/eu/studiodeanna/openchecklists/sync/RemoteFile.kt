package eu.studiodeanna.openchecklists.sync

/** A single file somewhere else that a list is kept in step with. */
interface RemoteFile {
    suspend fun read(): RemoteContent

    /**
     * Replaces the file, but only if it is still at [expected]; otherwise returns [WriteResult.Conflict]
     * and the caller re-reads and merges.
     */
    suspend fun write(content: String, expected: RemoteVersion): WriteResult
}

/** [text] is null when the file does not exist yet; [version] identifies what was read. */
data class RemoteContent(val text: String?, val version: RemoteVersion)

sealed interface RemoteVersion {
    data class Tag(val etag: String) : RemoteVersion
    data object Missing : RemoteVersion
    /** The server gave no version; writes are unconditional. */
    data object Unknown : RemoteVersion
}

sealed interface WriteResult {
    data object Written : WriteResult
    data object Conflict : WriteResult
}

/** A sync failure worded for the user. */
open class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)
