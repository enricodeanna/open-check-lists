package eu.studiodeanna.openchecklists.model

import eu.studiodeanna.openchecklists.Messages
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes list files. The format is plain, indented JSON so the files stay inspectable. */
object ListCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(doc: ListDocument): String = json.encodeToString(ListDocument.serializer(), doc)

    /** Parses a list file; throws [InvalidListFile] when it is not one this version can read. */
    fun decode(text: String): ListDocument {
        val doc = try {
            json.decodeFromString(ListDocument.serializer(), text)
        } catch (e: SerializationException) {
            throw InvalidListFile(Messages.current.notAList, e)
        } catch (e: IllegalArgumentException) {
            throw InvalidListFile(Messages.current.notAList, e)
        }
        if (doc.format > ListDocument.FORMAT) {
            throw InvalidListFile(Messages.current.newerVersion)
        }
        return doc
    }
}

class InvalidListFile(message: String, cause: Throwable? = null) : Exception(message, cause)
