package eu.studiodeanna.openchecklists.sync

import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.google.GoogleAuth
import eu.studiodeanna.openchecklists.google.GoogleFileAccessRequired
import eu.studiodeanna.openchecklists.google.GoogleSignInRequired
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A list kept in a Google Drive file, read and written with the signed-in user's own account.
 *
 * Drive's API cannot make a write conditional on the file being unchanged, so the version is checked
 * again just before writing. If someone else still slips in between, their copy is overwritten, but
 * their app still holds their changes and merges them back in on its next sync.
 */
class GoogleDriveFile(
    private val link: ParsedLink.GoogleDrive,
    private val drive: GoogleDriveApi,
) : RemoteFile {
    override suspend fun read(): RemoteContent {
        val version = drive.version(link)
        val text = drive.call(HttpMethod.Get, "$FILES/${link.fileId}?alt=media&supportsAllDrives=true", link).bodyAsText()
        return RemoteContent(text, RemoteVersion.Tag(version))
    }

    override suspend fun write(content: String, expected: RemoteVersion): WriteResult {
        if (expected is RemoteVersion.Tag && drive.version(link) != expected.etag) return WriteResult.Conflict
        drive.upload(link.fileId, content, link)
        return WriteResult.Written
    }

    companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }
}

/** The few Drive calls the app makes, with token refresh and user-facing errors. */
class GoogleDriveApi(private val auth: GoogleAuth, private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val MAX_NUMBER = 99
    }

    /** Drive's version number for the file, which goes up with every change. */
    suspend fun version(link: ParsedLink.GoogleDrive): String {
        val meta = call(HttpMethod.Get, "${GoogleDriveFile.FILES}/${link.fileId}?fields=version,mimeType&supportsAllDrives=true", link)
            .jsonBody()
        if (meta["mimeType"]?.jsonPrimitive?.content?.startsWith("application/vnd.google-apps") == true) {
            throw RemoteException(Messages.current.googleAppFile)
        }
        return meta["version"]?.jsonPrimitive?.content ?: throw RemoteException(Messages.current.driveUnexpected)
    }

    suspend fun upload(fileId: String, content: String, link: ParsedLink.GoogleDrive? = null) {
        call(HttpMethod.Patch, "${GoogleDriveFile.UPLOAD}/$fileId?uploadType=media&supportsAllDrives=true", link, writing = true) {
            contentType(ContentType.Application.Json)
            setBody(content)
        }
    }

    /**
     * Creates a file for a list called [title] in the user's Drive, holding [content], lets anyone
     * with the link edit it, and returns the link. The file is named after the list, numbered if a
     * file of that name is already there.
     */
    suspend fun createSharedFile(title: String, content: String): String {
        val name = unusedName(title)
        val id = call(HttpMethod.Post, "${GoogleDriveFile.FILES}?fields=id", writing = true) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name)
                put("mimeType", "application/json")
            }.toString())
        }.jsonBody()["id"]?.jsonPrimitive?.content ?: throw RemoteException(Messages.current.driveUnexpected)
        upload(id, content)
        try {
            call(HttpMethod.Post, "${GoogleDriveFile.FILES}/$id/permissions", writing = true) {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"anyone","role":"writer","allowFileDiscovery":false}""")
            }
        } catch (e: RemoteException) {
            throw RemoteException(Messages.current.driveCreatedNotShared(e.message), e)
        }
        return "https://drive.google.com/file/d/$id/view?usp=sharing"
    }

    /**
     * The first of "Title.json", "Title (2).json", … not yet in the top folder of the user's Drive.
     * Drive only lists the files this app may use, so other files of the same name are not seen.
     */
    private suspend fun unusedName(title: String): String {
        for (n in 1..MAX_NUMBER) {
            val name = listFileName(title, n)
            val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
            val url = URLBuilder(GoogleDriveFile.FILES).apply {
                parameters.append("q", "name = '$escaped' and 'root' in parents and trashed = false")
                parameters.append("fields", "files(id)")
                parameters.append("pageSize", "1")
            }.buildString()
            if (call(HttpMethod.Get, url).jsonBody()["files"]?.jsonArray.isNullOrEmpty()) return name
        }
        throw RemoteException(Messages.current.driveNameTaken(title))
    }

    internal suspend fun call(
        method: HttpMethod,
        url: String,
        link: ParsedLink.GoogleDrive? = null,
        writing: Boolean = false,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        var token = auth.accessToken() ?: throw GoogleSignInRequired()
        repeat(2) { attempt ->
            val response = try {
                http.request(url) {
                    this.method = method
                    bearerAuth(token)
                    link?.resourceKey?.let { header("X-Goog-Drive-Resource-Keys", "${link.fileId}/$it") }
                    configure()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RemoteException(Messages.current.cantReachDrive, e)
            }
            if (response.status.isSuccess()) return response
            if (response.status == HttpStatusCode.Unauthorized && attempt == 0) {
                token = auth.accessToken(forceRefresh = true) ?: throw GoogleSignInRequired()
            } else {
                fail(response, writing, link)
            }
        }
        throw GoogleSignInRequired()
    }

    /**
     * With the `drive.file` scope, a file the user has not picked for the app answers "not found" (or
     * "app not authorized"), the same as a file they cannot see at all; picking it tells the two apart.
     */
    private suspend fun fail(response: HttpResponse, writing: Boolean, link: ParsedLink.GoogleDrive?): Nothing {
        val error = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject }.getOrNull()
        val detail = error?.get("message")?.jsonPrimitive?.content
        val reason = runCatching { error?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.content }
            .getOrNull()
        throw when (response.status) {
            HttpStatusCode.Unauthorized -> GoogleSignInRequired()
            HttpStatusCode.NotFound if link != null -> GoogleFileAccessRequired(link.fileId)
            HttpStatusCode.Forbidden if link != null && reason == "appNotAuthorizedToFile" -> GoogleFileAccessRequired(link.fileId)
            HttpStatusCode.NotFound -> RemoteException(Messages.current.driveNotFound)
            HttpStatusCode.Forbidden if writing -> RemoteException(Messages.current.driveReadOnly(detail))
            else -> RemoteException(Messages.current.driveAnswered(response.status.value, detail))
        }
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
}
