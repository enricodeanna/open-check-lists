package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.model.InvalidListFile
import eu.studiodeanna.openchecklists.model.ListCodec
import eu.studiodeanna.openchecklists.model.ListDocument
import eu.studiodeanna.openchecklists.model.merge
import eu.studiodeanna.openchecklists.model.normalized
import io.ktor.client.HttpClient

/**
 * Brings [local] and the remote file together: reads the file, merges, and writes the merge back only
 * if it adds something. A concurrent writer makes the conditional write fail, and the round repeats.
 * Returns the merged document, which the caller merges into whatever the user edited meanwhile.
 */
suspend fun syncWith(remote: RemoteFile, local: ListDocument, attempts: Int = 4): ListDocument {
    repeat(attempts) {
        val content = remote.read()
        val remoteDoc = content.text?.takeIf { it.isNotBlank() }?.let {
            try {
                ListCodec.decode(it)
            } catch (e: InvalidListFile) {
                throw RemoteException(e.message ?: Messages.current.notAList, e)
            }
        }
        val merged = if (remoteDoc == null) local.normalized() else merge(local, remoteDoc)
        if (remoteDoc != null && merged == remoteDoc.normalized()) return merged
        when (remote.write(ListCodec.encode(merged), content.version)) {
            WriteResult.Written -> return merged
            WriteResult.Conflict -> Unit
        }
    }
    throw RemoteException(Messages.current.keptChanging)
}

/** The remote file behind a link, or a user-facing error when the link is not usable. */
fun remoteFileFor(link: ShareLink, http: HttpClient, drive: GoogleDriveApi?): RemoteFile =
    when (val parsed = ShareLinks.parse(link.url)) {
        is ParsedLink.Nextcloud -> NextcloudPublicFile(NextcloudShare(parsed, link.password, http), link.fileName)
        is ParsedLink.GoogleDrive -> GoogleDriveFile(
            parsed,
            drive ?: throw RemoteException(Messages.current.driveNotSetUp),
        )
        ParsedLink.Unrecognized -> throw RemoteException(Messages.current.notAShareLink)
    }

/** The Nextcloud share behind a link, or null for other kinds of links. */
fun nextcloudShareFor(link: ShareLink, http: HttpClient): NextcloudShare? =
    (ShareLinks.parse(link.url) as? ParsedLink.Nextcloud)?.let { NextcloudShare(it, link.password, http) }
