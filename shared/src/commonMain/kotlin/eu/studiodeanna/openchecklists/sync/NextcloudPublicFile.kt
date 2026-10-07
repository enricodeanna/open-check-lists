package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.model.InvalidListFile
import eu.studiodeanna.openchecklists.model.ListCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** A list stored in a Nextcloud share, as [name] inside it; [title] is read from the file. */
data class SharedList(val fileName: String, val title: String)

/**
 * A Nextcloud public share, reached anonymously over WebDAV. Nextcloud 29 and later serve it at
 * `/public.php/dav/files/<token>` (share password as basic-auth password); older servers only at
 * `/public.php/webdav` (token as user name). The old path must not be used on newer servers:
 * Nextcloud 34 answers there with a folder that is not the share, where nothing can be created.
 *
 * A folder share holds any number of lists, one file each, named after the list; a file share is
 * one list.
 */
class NextcloudShare(
    private val link: ParsedLink.Nextcloud,
    private val password: String?,
    private val http: HttpClient,
) {
    private var root = "${link.server}/public.php/dav/files/${link.token}"
    private var legacy = false
    private var resolved = false

    var isFolder = false
        private set

    /** What the share allows, as Nextcloud reports it (bits: 2 update, 4 create); null if not reported. */
    private var permissions: Int? = null

    /** Finds out once which endpoint serves the share, and whether it is a folder. */
    suspend fun resolve() = guarded {
        if (resolved) return@guarded
        propfind()
        resolved = true
    }

    /** The URL of the list file: [fileName] in a folder share, the shared file itself otherwise. */
    fun fileUrl(fileName: String?): String =
        if (isFolder) "$root/${(fileName ?: LEGACY_FOLDER_FILE).encodeURLPathPart()}" else root

    /** The lists in a folder share, skipping files that are not lists. */
    suspend fun lists(): List<SharedList> = guarded {
        resolve()
        fileNames().filter { it.endsWith(".json", ignoreCase = true) }.mapNotNull { name ->
            val response = send(HttpMethod.Get, fileUrl(name))
            if (!response.status.isSuccess()) return@mapNotNull null
            val title = try {
                ListCodec.decode(response.bodyAsText()).title
            } catch (_: InvalidListFile) {
                return@mapNotNull null
            }
            SharedList(name, title.ifBlank { name.removeSuffix(".json") })
        }.sortedBy { it.title.lowercase() }
    }

    /**
     * Creates a new file for a list called [title] in a folder share and returns its name: the title,
     * or the title with the next free number. The name is claimed with a create-only write, so two
     * people adding a list of the same name at once end up with two files.
     */
    suspend fun createListFile(title: String, content: String): String = guarded {
        resolve()
        val taken = fileNames().mapTo(HashSet()) { it.lowercase() }
        var n = 1
        while (n <= MAX_NUMBER) {
            val name = listFileName(title, n++)
            if (name.lowercase() in taken) continue
            val response = send(HttpMethod.Put, fileUrl(name)) {
                header(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(content)
            }
            when {
                response.status.isSuccess() -> return@guarded name
                response.status == HttpStatusCode.PreconditionFailed -> taken += name.lowercase()
                else -> {
                    if (response.status == HttpStatusCode.Forbidden) runCatching { propfind() }
                    fail(response, writing = true, creating = true)
                }
            }
        }
        throw RemoteException(Messages.current.folderNameTaken(title))
    }

    private suspend fun fileNames(): List<String> {
        val body = propfindAt(root, depth = 1).also { if (it.status.value != 207) fail(it, writing = false) }.bodyAsText()
        return RESPONSE.findAll(body).mapNotNull { match ->
            val entry = match.groupValues[1]
            if (COLLECTION.containsMatchIn(entry)) return@mapNotNull null
            HREF.find(entry)?.groupValues?.get(1)?.trimEnd('/')?.substringAfterLast('/')?.decodeURLPart()
        }.toList()
    }

    internal suspend fun send(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = http.request(url) {
        this.method = method
        auth()
        configure()
    }

    internal suspend fun propfind() {
        var response = propfindAt(root, depth = 0)
        if (!legacy && !resolved && response.status.value in OLD_SERVER) {
            legacy = true
            root = "${link.server}/public.php/webdav"
            response = propfindAt(root, depth = 0)
        }
        if (response.status.value != 207 && !response.status.isSuccess()) fail(response, writing = false)
        val body = response.bodyAsText()
        isFolder = COLLECTION.containsMatchIn(body)
        // Public links report either a share-permission number or ownCloud-style letters (C create, W write).
        permissions = SHARE_PERMISSIONS.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: OC_PERMISSIONS.find(body)?.groupValues?.get(1)?.let { letters ->
                1 or (if ('W' in letters) UPDATE else 0) or (if ('C' in letters || 'K' in letters) CREATE else 0)
            }
    }

    private suspend fun propfindAt(url: String, depth: Int): HttpResponse = send(HttpMethod("PROPFIND"), "$url/") {
        header("Depth", depth.toString())
        contentType(ContentType.Application.Xml)
        setBody(PROPFIND_PROPS)
    }

    private fun HttpRequestBuilder.auth() {
        when {
            legacy -> basicAuth(link.token, password.orEmpty())
            password != null -> basicAuth("anonymous", password)
        }
        header("X-Requested-With", "XMLHttpRequest")
    }

    internal suspend fun fail(response: HttpResponse, writing: Boolean, creating: Boolean = false): Nothing {
        // Nextcloud explains refusals in a Sabre error document; pass that on, it names the real cause.
        val detail = runCatching { SERVER_MESSAGE.find(response.bodyAsText())?.groupValues?.get(1)?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        val perms = permissions
        val message = when {
            response.status == HttpStatusCode.Unauthorized -> Messages.current.shareNeedsPassword
            response.status == HttpStatusCode.NotFound -> Messages.current.shareNotFound
            response.status == HttpStatusCode.Forbidden && writing && perms != null && perms and UPDATE == 0 ->
                Messages.current.shareReadOnly
            response.status == HttpStatusCode.Forbidden && creating -> Messages.current.cannotCreate
            response.status == HttpStatusCode.Forbidden && writing -> Messages.current.nextcloudRefusedSave(detail)
            response.status == HttpStatusCode.Forbidden -> Messages.current.nextcloudRefusedAccess
            else -> Messages.current.nextcloudAnswered("${response.status.value} ${response.status.description}")
        }
        throw RemoteException(if (detail != null && detail !in message) Messages.current.withNextcloudDetail(message, detail) else message)
    }

    internal inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException(Messages.current.cantReach(link.server), e)
    }

    companion object {
        /** Where lists shared into a folder before lists had their own names are kept. */
        const val LEGACY_FOLDER_FILE = "open-check-list.json"

        private const val MAX_NUMBER = 99

        /** Answers to the current endpoint that mean the server predates it (Nextcloud 28 and older). */
        private val OLD_SERVER = setOf(404, 405, 501)

        private const val UPDATE = 2
        private const val CREATE = 4

        private val RESPONSE = Regex("""<(?:\w+:)?response>(.*?)</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("""<(?:\w+:)?href>([^<]*)</(?:\w+:)?href>""")
        private val COLLECTION = Regex("""<(?:\w+:)?collection\s*/>""")
        private val SHARE_PERMISSIONS = Regex("""<(?:\w+:)?share-permissions[^>]*>(\d+)<""")
        private val OC_PERMISSIONS = Regex("""<(?:\w+:)?permissions[^>]*>([A-Z]*)<""")
        private val SERVER_MESSAGE = Regex("""<(?:\w+:)?message>(.*?)</(?:\w+:)?message>""", RegexOption.DOT_MATCHES_ALL)
        private const val PROPFIND_PROPS =
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" """ +
                """xmlns:ocs="http://open-collaboration-services.org/ns"><d:prop><d:resourcetype/>""" +
                """<oc:permissions/><ocs:share-permissions/></d:prop></d:propfind>"""
    }
}

/** One list's file in a Nextcloud share: [fileName] in a folder share, or the shared file itself. */
class NextcloudPublicFile(
    private val share: NextcloudShare,
    private val fileName: String?,
) : RemoteFile {
    override suspend fun read(): RemoteContent = share.guarded {
        share.resolve()
        val response = share.send(HttpMethod.Get, share.fileUrl(fileName))
        when {
            response.status.isSuccess() -> RemoteContent(response.bodyAsText(), response.version())
            response.status == HttpStatusCode.NotFound && share.isFolder -> RemoteContent(null, RemoteVersion.Missing)
            else -> share.fail(response, writing = false)
        }
    }

    override suspend fun write(content: String, expected: RemoteVersion): WriteResult = share.guarded {
        share.resolve()
        val response = share.send(HttpMethod.Put, share.fileUrl(fileName)) {
            when (expected) {
                is RemoteVersion.Tag -> header(HttpHeaders.IfMatch, expected.etag)
                RemoteVersion.Missing -> header(HttpHeaders.IfNoneMatch, "*")
                RemoteVersion.Unknown -> Unit
            }
            contentType(ContentType.Application.Json)
            setBody(content)
        }
        when {
            response.status.isSuccess() -> WriteResult.Written
            response.status == HttpStatusCode.PreconditionFailed -> WriteResult.Conflict
            else -> {
                // The share may have been changed since it was opened; explain with what it allows now.
                if (response.status == HttpStatusCode.Forbidden) runCatching { share.propfind() }
                share.fail(response, writing = true, creating = expected == RemoteVersion.Missing)
            }
        }
    }

    private fun HttpResponse.version(): RemoteVersion =
        (headers["OC-ETag"] ?: headers[HttpHeaders.ETag])?.let { RemoteVersion.Tag(it) } ?: RemoteVersion.Unknown
}
