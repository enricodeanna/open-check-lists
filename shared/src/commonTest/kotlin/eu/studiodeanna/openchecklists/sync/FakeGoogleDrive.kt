package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.sync.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The parts of the Drive v3 API the app uses, with per-user access. */
class FakeGoogleDrive {
    class File(var content: String, var mimeType: String = "application/json", val owner: String, var name: String = "") {
        var version = 1L
        var anyoneRole: String? = null
        var resourceKey: String? = null
    }

    val files = mutableMapOf<String, File>()
    /** access token → user */
    val tokens = mutableMapOf<String, String>()
    private var nextId = 1

    /** Runs just before an upload is applied, to simulate someone else saving at that moment. */
    var beforeNextUpload: (suspend () -> Unit)? = null

    val http = HttpClient(MockEngine { handle(it) })

    private companion object {
        val NAME_QUERY = Regex("""name = '((?:[^'\\]|\\.)*)'""")
        val NAME_FIELD = Regex(""""name"\s*:\s*"([^"]*)"""")
    }

    fun addFile(owner: String, content: String = "", anyoneRole: String? = "writer", name: String = ""): String {
        val id = "file${nextId++}"
        files[id] = File(content, owner = owner, name = name).also { it.anyoneRole = anyoneRole }
        return id
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val user = request.headers["Authorization"]?.removePrefix("Bearer ")?.let { tokens[it] }
            ?: return@run respond("""{"error":{"message":"Invalid Credentials"}}""", HttpStatusCode.Unauthorized)
        val path = request.url.encodedPath
        val query = request.url.parameters
        val method = request.method.value
        val id = path.substringAfter("/files/", "").substringBefore("/")
        val file = files[id]
        fun canRead(f: File) = f.owner == user || (f.anyoneRole != null &&
            (f.resourceKey == null || request.headers["X-Goog-Drive-Resource-Keys"] == "$id/${f.resourceKey}"))
        fun canWrite(f: File) = f.owner == user || (canRead(f) && f.anyoneRole == "writer")
        when {
            method == "GET" && path == "/drive/v3/files" -> {
                // Only the name search the app makes: name = '…' in the user's top folder.
                val wanted = NAME_QUERY.find(query["q"].orEmpty())?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                val found = files.filter { (_, f) -> f.owner == user && f.name == wanted }.keys
                respond("""{"files":[${found.joinToString(",") { """{"id":"$it"}""" }}]}""")
            }
            method == "POST" && path == "/drive/v3/files" -> {
                val name = NAME_FIELD.find(request.body.toByteArray().decodeToString())?.groupValues?.get(1).orEmpty()
                val newId = addFile(user, anyoneRole = null, name = name)
                respond("""{"id":"$newId"}""")
            }
            method == "POST" && path.endsWith("/permissions") && file != null && file.owner == user -> {
                file.anyoneRole = "writer"
                respond("{}")
            }
            file == null || !canRead(file) -> respond("""{"error":{"message":"File not found"}}""", HttpStatusCode.NotFound)
            method == "GET" && query["alt"] == "media" -> respond(file.content)
            method == "GET" -> respond("""{"version":"${file.version}","mimeType":"${file.mimeType}"}""")
            method == "PATCH" && path.startsWith("/upload/") -> {
                beforeNextUpload?.let { beforeNextUpload = null; it() }
                if (!canWrite(file)) {
                    respond("""{"error":{"message":"The user does not have sufficient permissions for this file."}}""", HttpStatusCode.Forbidden)
                } else {
                    file.content = request.body.toByteArray().decodeToString()
                    file.version++
                    respond("{}")
                }
            }
            else -> respond("", HttpStatusCode.BadRequest)
        }
    }
}

/** A Google account for tests. Its first token can be made to expire to exercise refresh. */
class FakeGoogleAuth(private val drive: FakeGoogleDrive, private val user: String, signedIn: Boolean = true) : GoogleAuth {
    private var session = 0
    private var token: String? = null
    var signInCalls = 0
    var failSignIn = false

    override val signedIn: StateFlow<Boolean> get() = state
    private val state = MutableStateFlow(false)

    init {
        if (signedIn) issue()
    }

    private fun issue() {
        token = "$user-token-${++session}".also { drive.tokens[it] = user }
        state.value = true
    }

    /** Invalidates the current token on the server, as if it had expired. */
    fun expireToken() {
        drive.tokens.remove(token)
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        if (!state.value) return null
        if (forceRefresh) issue()
        return token
    }

    override suspend fun signIn() {
        signInCalls++
        if (failSignIn) throw RemoteException("Sign-in was cancelled.")
        issue()
    }

    override suspend fun signOut() {
        token = null
        state.value = false
    }
}
