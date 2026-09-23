package app.recall.model

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.recall.App
import java.io.File
import java.security.MessageDigest

data class InstalledModel(val file: File, val spec: ModelSpec?) {
    val name get() = spec?.name ?: file.nameWithoutExtension
}

/** Model files live in the app's private storage, in filesDir/models. */
object ModelStore {
    val dir: File get() = File(App.ctx.filesDir, "models").apply { mkdirs() }

    fun installed(): List<InstalledModel> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty()
            .sortedByDescending { it.length() }
            .map { InstalledModel(it, ModelCatalog.byFile(it.name)) }

    fun active(): InstalledModel? {
        val all = installed()
        val chosen = App.prefs.activeModel?.let { name -> all.firstOrNull { it.file.name == name } }
        return chosen ?: all.firstOrNull()
    }

    fun setActive(file: File) {
        App.prefs.activeModel = file.name
        Brain.unloadNow()
    }

    fun delete(file: File) {
        if (App.prefs.activeModel == file.name) App.prefs.activeModel = null
        Brain.unloadNow()
        file.delete()
    }

    fun freeBytes(): Long = dir.usableSpace

    fun ramGb(context: Context): Double {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    /**
     * Copies a .gguf the user picked (e.g. downloaded in a browser) into Recall's storage.
     * Used by the Offline edition, and available in both.
     */
    fun import(context: Context, uri: Uri, onProgress: (Long, Long) -> Unit): Result<File> = runCatching {
        val resolver = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        if (!name.endsWith(".gguf")) name += ".gguf"
        if (size > 0 && size + 500_000_000 > freeBytes()) error("Not enough free space")

        val tmp = File(dir, "$name.import")
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open the file" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var first = true
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (first) {
                        require(n >= 4 && String(buf, 0, 4, Charsets.US_ASCII) == "GGUF") { "That isn't a GGUF model file" }
                        first = false
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    total += n
                    onProgress(total, size)
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val finalName = ModelCatalog.bySha(sha)?.file ?: name
        val dest = File(dir, finalName)
        dest.delete()
        check(tmp.renameTo(dest)) { "Couldn't save the model" }
        if (App.prefs.activeModel == null) App.prefs.activeModel = dest.name
        dest
    }.onFailure {
        dir.listFiles { f -> f.name.endsWith(".import") }?.forEach { it.delete() }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
