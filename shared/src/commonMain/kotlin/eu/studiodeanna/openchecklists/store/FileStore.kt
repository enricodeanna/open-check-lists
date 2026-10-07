package eu.studiodeanna.openchecklists.store

/** Flat, name-addressed storage for the app's own files on this device. */
interface FileStore {
    suspend fun read(name: String): String?
    suspend fun write(name: String, content: String)
    suspend fun delete(name: String)
}

class MemoryFileStore : FileStore {
    val files = mutableMapOf<String, String>()
    override suspend fun read(name: String): String? = files[name]
    override suspend fun write(name: String, content: String) {
        files[name] = content
    }
    override suspend fun delete(name: String) {
        files.remove(name)
    }
}
