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
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64

/**
 * A Nextcloud server with one user, as far as logging in and creating share links go: Login Flow v2,
 * the user's files over WebDAV, the OCS user, capability and share APIs, and the public links it
 * makes, served like [FakeNextcloud]'s single-file shares.
 */
class FakeNextcloudServer(
    private val userId: String = "anna",
    private val loginName: String = "anna@example.com",
    /** Polls answered "not yet" before the user has logged in in the browser; null: never logs in. */
    var pollsBeforeLogin: Int? = 1,
    var passwordsEnforced: Boolean = false,
    /** The day links end, when the server sets one. */
    var linkExpiry: String? = null,
    var linksAllowed: Boolean = true,
    private val isNextcloud: Boolean = true,
) {
    val server = "https://cloud.example.com"

    private class Stored(var text: String, var version: Int = 1)

    /** The user's files by path, like "OpenCheckList/Groceries.json"; folders are kept apart. */
    private val files = linkedMapOf<String, Stored>()
    val folders = mutableSetOf<String>()

    class Share(val path: String, val password: String?)

    val shares = linkedMapOf<String, Share>()
    val appPasswords = mutableSetOf<String>()
    val revoked = mutableListOf<String>()
    var polls = 0
        private set

    val filePaths: Set<String> get() = files.keys

    fun file(path: String): String? = files[path]?.text

    val http = HttpClient(MockEngine { handle(it) })

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val path = request.url.encodedPath
        val method = request.method.value
        when {
            path == "/index.php/login/v2" && method == "POST" ->
                if (!isNextcloud) respond("<html>Not here</html>", HttpStatusCode.NotFound)
                else json("""{"poll":{"token":"poll-token","endpoint":"$server/login/v2/poll"},"login":"$server/login/v2/flow/abc"}""")
            path == "/login/v2/poll" && method == "POST" -> poll(request)
            path.startsWith("/public.php/dav/files/") -> public(request, path.removePrefix("/public.php/dav/files/").trimEnd('/'))
            else -> {
                if (appPasswordOf(request) !in appPasswords) return@run respond("", HttpStatusCode.Unauthorized)
                when {
                    path == "/ocs/v2.php/cloud/user" -> ocs("""{"id":"$userId"}""")
                    path == "/ocs/v2.php/cloud/capabilities" ->
                        ocs("""{"capabilities":{"files_sharing":{"public":{"enabled":true,"password":{"enforced":$passwordsEnforced}}}}}""")
                    path == "/ocs/v2.php/apps/files_sharing/api/v1/shares" && method == "POST" -> createShare(request)
                    path == "/ocs/v2.php/core/apppassword" && method == "DELETE" -> {
                        val password = appPasswordOf(request)!!
                        appPasswords -= password
                        revoked += password
                        ocs("[]")
                    }
                    path.startsWith("/remote.php/dav/files/$userId/") ->
                        dav(request, path.removePrefix("/remote.php/dav/files/$userId/").trimEnd('/').decodeURLPart())
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        }
    }

    private fun MockRequestHandleScope.poll(request: HttpRequestData) = run {
        val before = pollsBeforeLogin
        if (before == null || polls++ < before) return@run respond("[]", HttpStatusCode.NotFound)
        val password = "app-password-${appPasswords.size + revoked.size + 1}"
        appPasswords += password
        json("""{"server":"$server","loginName":"$loginName","appPassword":"$password"}""")
    }

    private fun appPasswordOf(request: HttpRequestData): String? =
        request.headers[HttpHeaders.Authorization]?.removePrefix("Basic ")
            ?.let { Base64.decode(it).decodeToString() }
            ?.takeIf { it.substringBefore(':') == loginName }
            ?.substringAfter(':')

    private suspend fun MockRequestHandleScope.dav(request: HttpRequestData, path: String) = run {
        val parent = path.substringBeforeLast('/', "")
        when (request.method.value) {
            "MKCOL" -> when {
                path in folders || path in files -> respond("", HttpStatusCode.MethodNotAllowed)
                parent.isNotEmpty() && parent !in folders -> respond("", HttpStatusCode.Conflict)
                else -> respond("", HttpStatusCode.Created).also { folders += path }
            }
            "PUT" -> when {
                parent.isNotEmpty() && parent !in folders -> respond("", HttpStatusCode.Conflict)
                request.headers[HttpHeaders.IfNoneMatch] == "*" && path in files -> respond("", HttpStatusCode.PreconditionFailed)
                else -> {
                    files[path] = Stored(request.body.toByteArray().decodeToString())
                    respond("", HttpStatusCode.Created)
                }
            }
            "DELETE" -> respond("", if (files.remove(path) != null) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            "PROPFIND" -> when {
                path.isNotEmpty() && path !in folders -> respond("", HttpStatusCode.NotFound)
                else -> {
                    fun entry(at: String, folder: Boolean) =
                        "<d:response><d:href>/remote.php/dav/files/$userId/" +
                            at.split('/').joinToString("/") { it.encodeURLPathPart() } + (if (folder) "/" else "") +
                            "</d:href><d:propstat><d:prop><d:resourcetype>" + (if (folder) "<d:collection/>" else "") +
                            "</d:resourcetype></d:prop></d:propstat></d:response>"
                    fun isChild(of: String) = of.substringBeforeLast('/', "") == path
                    val children = folders.filter(::isChild).map { entry(it, true) } + files.keys.filter(::isChild).map { entry(it, false) }
                    respond(
                        """<d:multistatus xmlns:d="DAV:">${entry(path, true)}${children.joinToString("")}</d:multistatus>""",
                        HttpStatusCode.MultiStatus,
                    )
                }
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private suspend fun MockRequestHandleScope.createShare(request: HttpRequestData) = run {
        val form = parseQueryString(request.body.toByteArray().decodeToString())
        val path = form["path"].orEmpty().removePrefix("/")
        val password = form["password"]
        when {
            !linksAllowed -> ocsFailure(HttpStatusCode.Forbidden, "Public link sharing is disabled by the administrator")
            passwordsEnforced && password.isNullOrEmpty() -> ocsFailure(HttpStatusCode.Forbidden, "Passwords are enforced for link and mail shares")
            path !in files -> ocsFailure(HttpStatusCode.NotFound, "Wrong path, file/folder does not exist")
            form["shareType"] != "3" || form["permissions"] != "3" -> ocsFailure(HttpStatusCode.BadRequest, "Unexpected share")
            else -> {
                val token = "Tok${shares.size + 1}"
                shares[token] = Share(path, password)
                val expiration = linkExpiry?.let { "\"$it 00:00:00\"" } ?: "null"
                ocs("""{"id":"${shares.size}","token":"$token","url":"$server/s/$token","expiration":$expiration}""")
            }
        }
    }

    /** A public single-file share, reached anonymously; the share password is the basic-auth password. */
    private suspend fun MockRequestHandleScope.public(request: HttpRequestData, token: String) = run {
        val share = shares[token] ?: return@run respond("", HttpStatusCode.NotFound)
        val given = request.headers[HttpHeaders.Authorization]?.removePrefix("Basic ")
            ?.let { Base64.decode(it).decodeToString().substringAfter(':') }
        if (share.password != null && given != share.password) return@run respond("", HttpStatusCode.Unauthorized)
        val stored = files[share.path] ?: return@run respond("", HttpStatusCode.NotFound)
        when (request.method.value) {
            "PROPFIND" -> respond(
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/public.php/dav/files/$token/</d:href><d:propstat>""" +
                    """<d:prop><d:resourcetype/><x1:share-permissions xmlns:x1="http://open-collaboration-services.org/ns">3""" +
                    "</x1:share-permissions></d:prop></d:propstat></d:response></d:multistatus>",
                HttpStatusCode.MultiStatus,
            )
            "GET" -> respond(stored.text, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v${stored.version}\""))
            "PUT" -> {
                val ifMatch = request.headers[HttpHeaders.IfMatch]
                if (ifMatch != null && ifMatch != "\"v${stored.version}\"") {
                    respond("", HttpStatusCode.PreconditionFailed)
                } else {
                    stored.text = request.body.toByteArray().decodeToString()
                    stored.version++
                    respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, "\"v${stored.version}\""))
                }
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.ocs(data: String) =
        json("""{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}""")

    private fun MockRequestHandleScope.ocsFailure(status: HttpStatusCode, message: String) =
        json("""{"ocs":{"meta":{"status":"failure","statuscode":${status.value},"message":"$message"},"data":[]}}""", status)
}
