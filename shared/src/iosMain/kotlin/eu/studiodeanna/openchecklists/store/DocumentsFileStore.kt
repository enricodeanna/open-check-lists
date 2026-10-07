package eu.studiodeanna.openchecklists.store

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** Files in the app's Documents directory. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DocumentsFileStore : FileStore {
    private val dir: String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    private fun path(name: String) = "$dir/$name"

    override suspend fun read(name: String): String? =
        NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)

    override suspend fun write(name: String, content: String) {
        NSString.create(string = content).writeToFile(path(name), true, NSUTF8StringEncoding, null)
    }

    override suspend fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), null)
    }
}
