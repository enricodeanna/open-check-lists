package eu.studiodeanna.openchecklists.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Files in one directory; writes go through a temporary file so a crash never leaves half a list. */
class DirectoryFileStore(private val dir: File) : FileStore {
    init {
        dir.mkdirs()
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(dir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, content: String) = withContext(Dispatchers.IO) {
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(content)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(dir, name).delete()
        Unit
    }
}
