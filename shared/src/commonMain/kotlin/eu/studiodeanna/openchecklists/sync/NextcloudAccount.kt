package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.Messages
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A Nextcloud account this device signed in to. It is used only to create the public links lists are
 * shared by; lists then sync through those links like any pasted one, so others need no account.
 * [userId] names the user's files in WebDAV and may differ from [loginName], which can be an email
 * address. [folder] is where shared lists are kept: null until the user has confirmed one.
 */
@Serializable
data class NextcloudAccount(
    val server: String,
    val loginName: String,
    val appPassword: String,
    val userId: String,
    val folder: String? = null,
)

/** A Nextcloud login in progress: the user logs in at [loginUrl] in a browser while the app waits. */
class NextcloudLogin internal constructor(
    val server: String,
    val loginUrl: String,
    internal val endpoint: String,
    internal val token: String,
)

/** Thrown when a Nextcloud link is wanted before an account and folder are set up; the UI sets them up. */
class NextcloudSetupNeeded : RemoteException(Messages.current.nextcloudNotConnected)

/** Thrown when Nextcloud no longer accepts the app's login; the UI offers to log in again. */
class NextcloudSignInRequired : RemoteException(Messages.current.nextcloudSignInAgain)

/**
 * The calls made with a Nextcloud account: Nextcloud's Login Flow v2, which gives the app a password
 * of its own, and making a list's file with a public link to it.
 */
class NextcloudAccountApi(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Starts the login at the server the user typed; they log in at [NextcloudLogin.loginUrl]. */
    suspend fun startLogin(address: String): NextcloudLogin {
        val server = serverAddress(address)
        val response = guarded(server) {
            http.request("$server/index.php/login/v2") {
                method = HttpMethod.Post
                // Nextcloud names the app's password after it, in the user's security settings.
                header(HttpHeaders.UserAgent, APP_NAME)
            }
        }
        val body = if (response.status.isSuccess()) response.jsonOrNull() else null
        val poll = body?.get("poll") as? JsonObject
        val login = body?.string("login")
        val endpoint = poll?.string("endpoint")
        val token = poll?.string("token")
        if (login == null || endpoint == null || token == null) throw RemoteException(Messages.current.notANextcloud(server))
        return NextcloudLogin(server, login, endpoint, token)
    }

    /** Waits until the user has logged in, for as long as Nextcloud keeps the login open (20 minutes). */
    suspend fun awaitLogin(login: NextcloudLogin): NextcloudAccount {
        repeat(MAX_POLLS) {
            val response = guarded(login.server) {
                http.submitForm(login.endpoint, parameters { append("token", login.token) })
            }
            if (response.status.isSuccess()) {
                val body = response.jsonOrNull()
                val server = body?.string("server")?.trimEnd('/')
                val loginName = body?.string("loginName")
                val appPassword = body?.string("appPassword")
                if (server == null || loginName == null || appPassword == null) {
                    throw RemoteException(Messages.current.nextcloudUnexpected)
                }
                val account = NextcloudAccount(server, loginName, appPassword, userId = loginName)
                val id = guarded(server) { ocs(account, HttpMethod.Get, "/ocs/v2.php/cloud/user") }?.string("id")
                return account.copy(userId = id ?: loginName)
            }
            if (response.status != HttpStatusCode.NotFound) {
                throw RemoteException(Messages.current.nextcloudAnswered("${response.status.value} ${response.status.description}"))
            }
            delay(POLL_MILLIS)
        }
        throw RemoteException(Messages.current.nextcloudLoginTimedOut)
    }

    /**
     * Makes a file for a list called [title], holding [content], in the account's folder, named after
     * the list (numbered if the name is taken), and a public link that may edit it. A server that
     * requires passwords on links gets a generated one, kept in the returned link.
     */
    suspend fun share(account: NextcloudAccount, title: String, content: String): ShareLink = guarded(account.server) {
        val folder = account.folder ?: throw NextcloudSetupNeeded()
        createFolderPath(account, folder)
        val path = createFile(account, folder, title, content)
        try {
            val password = if (passwordsEnforced(account)) newSharePassword() else null
            val form = parameters {
                append("path", path)
                append("shareType", PUBLIC_LINK)
                append("permissions", READ_AND_UPDATE)
                password?.let { append("password", it) }
            }
            val data = ocs(account, HttpMethod.Post, "/ocs/v2.php/apps/files_sharing/api/v1/shares", form) {
                Messages.current.nextcloudRefusedShare(it)
            }
            val url = data?.string("url") ?: throw RemoteException(Messages.current.nextcloudUnexpected)
            ShareLinks.normalize(ShareLink(url, password, expires = data.string("expiration")?.take(10)))
        } catch (e: Exception) {
            // Leave no file behind that nothing links to.
            if (e !is CancellationException) runCatching { send(account, HttpMethod.Delete, davUrl(account, path)) }
            throw e
        }
    }

    /**
     * The names of the folders in [path] ("" for the top of the user's files), sorted; null when
     * [path] is not a folder there (any more).
     */
    suspend fun folders(account: NextcloudAccount, path: String): List<String>? = guarded(account.server) {
        val response = send(account, HttpMethod("PROPFIND"), davUrl(account, folderUrlPath(path)) + "/") {
            header("Depth", "1")
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND_TYPE)
        }
        if (response.status == HttpStatusCode.NotFound) return@guarded null
        if (response.status.value != 207) failDav(response) { Messages.current.nextcloudAnswered(it) }
        val self = "/remote.php/dav/files/${account.userId}${folderUrlPath(path)}"
        RESPONSE.findAll(response.bodyAsText()).mapNotNull { match ->
            val entry = match.groupValues[1]
            if (!COLLECTION.containsMatchIn(entry)) return@mapNotNull null
            val href = HREF.find(entry)?.groupValues?.get(1)?.decodeURLPart()?.trimEnd('/') ?: return@mapNotNull null
            href.substringAfterLast('/').takeIf { !href.endsWith(self) && !it.startsWith(".") }
        }.sortedBy { it.lowercase() }.toList()
    }

    /** Creates the folder [path], and its parents, in the user's files. */
    suspend fun createFolder(account: NextcloudAccount, path: String) = guarded(account.server) {
        createFolderPath(account, path)
    }

    /** Removes the app's password from the account, so the login no longer works anywhere. */
    suspend fun revoke(account: NextcloudAccount) {
        guarded(account.server) { ocs(account, HttpMethod.Delete, "/ocs/v2.php/core/apppassword") }
    }

    /** Creates [folder] and its parents in the user's files; folders already there are fine. */
    private suspend fun createFolderPath(account: NextcloudAccount, folder: String) {
        var path = ""
        for (segment in folder.split('/')) {
            path += "/$segment"
            val response = send(account, HttpMethod("MKCOL"), davUrl(account, path))
            // 405: it exists already.
            if (response.status != HttpStatusCode.Created && response.status != HttpStatusCode.MethodNotAllowed) failDav(response)
        }
    }

    /** Writes the list's file with a create-only write, so it never replaces a file; returns its path. */
    private suspend fun createFile(account: NextcloudAccount, folder: String, title: String, content: String): String {
        for (n in 1..MAX_NUMBER) {
            val path = "/$folder/${listFileName(title, n)}"
            val response = send(account, HttpMethod.Put, davUrl(account, path)) {
                header(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(content)
            }
            if (response.status.isSuccess()) return path
            if (response.status != HttpStatusCode.PreconditionFailed) failDav(response)
        }
        throw RemoteException(Messages.current.folderNameTaken(title))
    }

    private suspend fun passwordsEnforced(account: NextcloudAccount): Boolean {
        val capabilities = ocs(account, HttpMethod.Get, "/ocs/v2.php/cloud/capabilities")?.get("capabilities") as? JsonObject
        val password = capabilities?.path("files_sharing", "public")?.get("password") as? JsonObject
        return password?.get("enforced")?.jsonPrimitive?.booleanOrNull == true
    }

    /** An OCS call; returns its `data`, and words a refusal with [refused] and the server's reason. */
    private suspend fun ocs(
        account: NextcloudAccount,
        method: HttpMethod,
        path: String,
        form: Parameters? = null,
        refused: ((reason: String?) -> String)? = null,
    ): JsonObject? {
        val response = send(account, method, "${account.server}$path?format=json") {
            header("OCS-APIRequest", "true")
            if (form != null) setBody(FormDataContent(form))
        }
        val ocs = response.jsonOrNull()?.get("ocs") as? JsonObject
        if (response.status.isSuccess()) return ocs?.get("data") as? JsonObject
        if (response.status == HttpStatusCode.Unauthorized) throw NextcloudSignInRequired()
        val reason = (ocs?.get("meta") as? JsonObject)?.string("message")?.takeIf { it.isNotBlank() }
        if (refused != null) throw RemoteException(refused(reason))
        val status = Messages.current.nextcloudAnswered("${response.status.value} ${response.status.description}")
        throw RemoteException(reason?.let { Messages.current.withNextcloudDetail(status, it) } ?: status)
    }

    /** Words a WebDAV refusal with [refused], given the server's reason or else the status. */
    private suspend fun failDav(
        response: HttpResponse,
        refused: (String) -> String = { Messages.current.nextcloudRefusedSave(it) },
    ): Nothing {
        if (response.status == HttpStatusCode.Unauthorized) throw NextcloudSignInRequired()
        val detail = runCatching { SERVER_MESSAGE.find(response.bodyAsText())?.groupValues?.get(1)?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        throw RemoteException(refused(detail ?: "${response.status.value} ${response.status.description}"))
    }

    private fun folderUrlPath(path: String) = if (path.isEmpty()) "" else "/$path"

    private suspend fun send(
        account: NextcloudAccount,
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = http.request(url) {
        this.method = method
        basicAuth(account.loginName, account.appPassword)
        configure()
    }

    private fun davUrl(account: NextcloudAccount, path: String) =
        "${account.server}/remote.php/dav/files/${account.userId.encodeURLPathPart()}" +
            path.split('/').joinToString("/") { it.encodeURLPathPart() }

    private suspend fun HttpResponse.jsonOrNull(): JsonObject? =
        runCatching { json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.path(vararg keys: String): JsonObject? =
        keys.fold(this as JsonObject?) { obj, key -> obj?.get(key) as? JsonObject }

    private inline fun <T> guarded(server: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException(Messages.current.cantReach(server), e)
    }

    companion object {
        /** The folder suggested the first time; the user may pick another. */
        const val DEFAULT_FOLDER = "OpenCheckList"

        private const val APP_NAME = "Open Check Lists"
        private const val POLL_MILLIS = 2_000L
        private const val MAX_POLLS = 20 * 60 * 1000 / 2_000
        private const val MAX_NUMBER = 99
        private const val PUBLIC_LINK = "3"
        private const val READ_AND_UPDATE = "3"
        private const val PASSWORD_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val RESPONSE = Regex("""<(?:\w+:)?response>(.*?)</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("""<(?:\w+:)?href>([^<]*)</(?:\w+:)?href>""")
        private val COLLECTION = Regex("""<(?:\w+:)?collection\s*/>""")
        private const val PROPFIND_TYPE =
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        private val SERVER_MESSAGE = Regex("""<(?:\w+:)?message>(.*?)</(?:\w+:)?message>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * The server's address from what the user typed: "cloud.example.com", the address of a page
         * in Nextcloud, or a share link all give "https://cloud.example.com".
         */
        fun serverAddress(input: String): String {
            var address = input.trim().substringBefore('?').substringBefore('#')
            if ("://" !in address) address = "https://$address"
            val hostStart = address.indexOf("://") + 3
            for (marker in listOf("/index.php", "/apps/", "/s/", "/login", "/remote.php", "/ocs/")) {
                val at = address.indexOf(marker, hostStart)
                if (at >= 0) address = address.substring(0, at)
            }
            return address.trimEnd('/')
        }

        /** [input] as a folder path in the user's files, like "Family/Lists"; null if nothing is left. */
        fun folderPath(input: String): String? =
            input.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/").ifEmpty { null }

        /** Four groups of four letters and digits, with each kind in it, for servers that require one. */
        @OptIn(ExperimentalUuidApi::class)
        internal fun newSharePassword(): String {
            while (true) {
                // Uuid.random() is backed by a cryptographically secure generator on every platform.
                val bytes = Uuid.random().toByteArray() + Uuid.random().toByteArray()
                val limit = 256 - 256 % PASSWORD_CHARS.length
                val chars = bytes.map { it.toInt() and 0xff }.filter { it < limit }.map { PASSWORD_CHARS[it % PASSWORD_CHARS.length] }
                if (chars.size < 16) continue
                val password = chars.take(16).chunked(4).joinToString("-") { it.joinToString("") }
                if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() } && password.any { it.isDigit() }) {
                    return password
                }
            }
        }
    }
}
