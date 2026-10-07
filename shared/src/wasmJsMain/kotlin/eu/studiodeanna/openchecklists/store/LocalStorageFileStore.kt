@file:OptIn(ExperimentalWasmJsInterop::class)

package eu.studiodeanna.openchecklists.store

/** The browser's localStorage, one key per file. */
class LocalStorageFileStore(private val prefix: String = "openchecklists/") : FileStore {
    override suspend fun read(name: String): String? = getItem(prefix + name)
    override suspend fun write(name: String, content: String) = setItem(prefix + name, content)
    override suspend fun delete(name: String) = removeItem(prefix + name)
}

private fun getItem(key: String): String? = js("localStorage.getItem(key)")
private fun setItem(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun removeItem(key: String): Unit = js("localStorage.removeItem(key)")
