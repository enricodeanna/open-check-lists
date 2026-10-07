package eu.studiodeanna.openchecklists.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64

/**
 * Behaves like a Nextcloud public share, as far as Open Check Lists uses it: a single shared file, or
 * a folder of files. By default it is a current server (29 and later): the share lives at
 * `/public.php/dav/files/<token>`, and the old `/public.php/webdav` answers with a generic root where
 * nothing may be created, as Nextcloud 34 does. [legacyOnly] makes it an old server that only has
 * `/public.php/webdav`.
 */
class FakeNextcloud(
    private val token: String = "AbC123",
    private val password: String? = null,
    private val folder: Boolean = false,
    var readOnly: Boolean = false,
    /** Nextcloud share permission bits: 1 read, 2 update, 4 create, 8 delete. */
    private val permissions: Int = 15,
    /** Report permissions only as ownCloud letters, as some servers do on public links. */
    private val lettersOnly: Boolean = false,
    private val legacyOnly: Boolean = false,
) {
    val link = ShareLink("https://cloud.example.com/s/$token", password)

    private class Stored(var text: String, var version: Int = 1)

    /** The share's files by name; a file share is the single entry "". */
    private val files = linkedMapOf<String, Stored>().apply { if (!folder) put("", Stored("")) }

    val fileNames: Set<String> get() = files.keys

    /** The shared file, or in a folder the only file there; null otherwise. */
    val content: String? get() = if (folder) files.values.singleOrNull()?.text else files[""]?.text

    fun file(name: String): String? = files[name]?.text

    fun overwrite(text: String, name: String = "") {
        files.getOrPut(name) { Stored(text, 0) }.apply {
            this.text = text
            version++
        }
    }

    var writes = 0

    /** Refuses the next write although the share allows it, as a server-side rule might. */
    var denyNextWrite = false

    /** Runs just before a write is applied, to simulate someone else saving at that moment. */
    var beforeNextWrite: (suspend () -> Unit)? = null

    val http = HttpClient(MockEngine { handle(it) })

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val path = request.url.encodedPath.removeSuffix("/")
        val modernRoot = "/public.php/dav/files/$token"
        val legacyRoot = "/public.php/webdav"
        val auth = request.headers[HttpHeaders.Authorization]
        val root = when {
            path.startsWith(modernRoot) && !legacyOnly -> {
                // Current servers take the share password as the basic-auth password, any user name.
                val passwordGiven = auth?.removePrefix("Basic ")?.let { Base64.decode(it).decodeToString().substringAfter(':') }
                if (password != null && passwordGiven != password) return@run respond("", HttpStatusCode.Unauthorized)
                modernRoot
            }
            path.startsWith(legacyRoot) -> {
                val expected = "Basic " + Base64.encode("$token:${password.orEmpty()}".encodeToByteArray())
                if (auth != expected) return@run respond("", HttpStatusCode.Unauthorized)
                if (!legacyOnly) return@run genericLegacyRoot(request)
                legacyRoot
            }
            else -> return@run respond("", HttpStatusCode.NotFound)
        }
        val name = path.removePrefix(root).removePrefix("/").decodeURLPart()
        when {
            request.method.value == "PROPFIND" && name.isEmpty() -> respond(
                propfind(root, depth = request.headers["Depth"]?.toIntOrNull() ?: 0),
                HttpStatusCode.MultiStatus,
            )
            folder && name.isEmpty() -> respond("", HttpStatusCode.MethodNotAllowed)
            !folder && name.isNotEmpty() -> respond("", HttpStatusCode.NotFound)
            request.method.value == "GET" -> files[name]?.let {
                respond(it.text, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag(it)))
            } ?: respond("", HttpStatusCode.NotFound)
            request.method.value == "PUT" -> put(request, name)
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun etag(stored: Stored) = "\"v${stored.version}\""

    private fun propfind(root: String, depth: Int): String {
        val self = "<d:response><d:href>$root/</d:href><d:propstat><d:prop><d:resourcetype>" +
            (if (folder) "<d:collection/>" else "") + "</d:resourcetype>" + permissionProps() +
            "</d:prop></d:propstat></d:response>"
        val children = if (folder && depth >= 1) {
            files.keys.joinToString("") { name ->
                "<d:response><d:href>$root/${name.encodeURLPathPart()}</d:href><d:propstat><d:prop>" +
                    "<d:resourcetype/></d:prop></d:propstat></d:response>"
            }
        } else {
            ""
        }
        return """<d:multistatus xmlns:d="DAV:">$self$children</d:multistatus>"""
    }

    /** What Nextcloud 34 does with the old endpoint: a folder that is not the share, with no create right. */
    private fun MockRequestHandleScope.genericLegacyRoot(request: HttpRequestData) = when (request.method.value) {
        "PROPFIND" -> respond(
            """<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:resourcetype><d:collection/>""" +
                "</d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>",
            HttpStatusCode.MultiStatus,
        )
        "PUT" -> respond(
            "<d:error xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\"><s:message>Permission denied to create file " +
                "(filename open-check-list.json)</s:message></d:error>",
            HttpStatusCode.Forbidden,
        )
        else -> respond("", HttpStatusCode.NotFound)
    }

    private fun permissionProps(): String {
        val bits = if (readOnly) 1 else permissions
        val letters = "RG" + (if (bits and 2 != 0) "W" else "") + (if (bits and 4 != 0) "CK" else "") +
            (if (bits and 8 != 0) "D" else "")
        val ocs = "<x1:share-permissions xmlns:x1=\"http://open-collaboration-services.org/ns\">$bits</x1:share-permissions>"
        val oc = "<oc:permissions xmlns:oc=\"http://owncloud.org/ns\">$letters</oc:permissions>"
        return if (lettersOnly) oc else ocs + oc
    }

    private suspend fun MockRequestHandleScope.put(request: HttpRequestData, name: String) = run {
        beforeNextWrite?.let { beforeNextWrite = null; it() }
        val ifMatch = request.headers[HttpHeaders.IfMatch]
        val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
        val existing = files[name]
        val refused = denyNextWrite.also { denyNextWrite = false } || readOnly ||
            (existing == null && permissions and 4 == 0) || (existing != null && permissions and 2 == 0)
        when {
            refused -> respond(
                "<d:error xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\"><s:exception>Sabre\\DAV\\Exception\\Forbidden" +
                    "</s:exception><s:message>Access to this resource has been denied</s:message></d:error>",
                HttpStatusCode.Forbidden,
            )
            ifMatch != null && (existing == null || ifMatch != etag(existing)) -> respond("", HttpStatusCode.PreconditionFailed)
            ifNoneMatch == "*" && existing != null -> respond("", HttpStatusCode.PreconditionFailed)
            else -> {
                val stored = existing ?: Stored("", 0).also { files[name] = it }
                stored.text = request.body.toByteArray().decodeToString()
                stored.version++
                writes++
                respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, etag(stored)))
            }
        }
    }
}
