package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.Messages
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

/**
 * The parts of the Drive v3 API the app uses, with per-user access and the `drive.file` scope: each
 * user's app may use only the files it created and the files that user picked for it.
 */
class FakeGoogleDrive {
    class File(var content: String, var mimeType: String = "application/json", val owner: String, var name: String = "") {
        var version = 1L
        var anyoneRole: String? = null
        var resourceKey: String? = null
    }

    val files = mutableMapOf<String, File>()
    /** access token → user */
    val tokens = mutableMapOf<String, String>()
    /** user → the files the app may use for them */
    private val granted = mutableMapOf<String, MutableSet<String>>()
    private var nextId = 1

    /** Runs just before an upload is applied, to simulate someone else saving at that moment. */
    var beforeNextUpload: (suspend () -> Unit)? = null

    val http = HttpClient(MockEngine { handle(it) })

    private companion object {
        val NAME_QUERY = Regex("""name = '((?:[^'\\]|\\.)*)'""")
        val NAME_FIELD = Regex(""""name"\s*:\s*"([^"]*)"""")
    }

    /** A file made outside the app, in Drive itself; no one's app may use it until they pick it. */
    fun addFile(owner: String, content: String = "", anyoneRole: String? = "writer", name: String = ""): String {
        val id = "file${nextId++}"
        files[id] = File(content, owner = owner, name = name).also { it.anyoneRole = anyoneRole }
        return id
    }

    /** Google's file picker: grants [fileId] to [user]'s app if they can see the file. */
    fun pick(user: String, fileId: String): Boolean {
        val file = files[fileId] ?: return false
        if (file.owner != user && file.anyoneRole == null) return false
        granted.getOrPut(user) { mutableSetOf() } += fileId
        return true
    }

    /** As if [user] removed the app's access in their Google account. */
    fun revoke(user: String, fileId: String) {
        granted[user]?.remove(fileId)
    }

    private fun mayUse(user: String, fileId: String) = fileId in granted[user].orEmpty()

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
                val found = files.filter { (id, f) -> f.owner == user && f.name == wanted && mayUse(user, id) }.keys
                respond("""{"files":[${found.joinToString(",") { """{"id":"$it"}""" }}]}""")
            }
            method == "POST" && path == "/drive/v3/files" -> {
                val name = NAME_FIELD.find(request.body.toByteArray().decodeToString())?.groupValues?.get(1).orEmpty()
                val newId = addFile(user, anyoneRole = null, name = name)
                granted.getOrPut(user) { mutableSetOf() } += newId
                respond("""{"id":"$newId"}""")
            }
            method == "POST" && path.endsWith("/permissions") && file != null && file.owner == user && mayUse(user, id) -> {
                file.anyoneRole = "writer"
                respond("{}")
            }
            file == null || !mayUse(user, id) || !canRead(file) -> respond("""{"error":{"message":"File not found"}}""", HttpStatusCode.NotFound)
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
    var pickCalls = 0
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

    override suspend fun pickFile(fileId: String) {
        pickCalls++
        if (failSignIn) throw RemoteException("Sign-in was cancelled.")
        issue()
        if (!drive.pick(user, fileId)) throw RemoteException(Messages.current.driveFileNotPicked)
    }

    override suspend fun signOut() {
        token = null
        state.value = false
    }
}
