package top.lighilit.watch_data_sync

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal class EpubArchive(
    private val openStream: () -> InputStream,
    private val localFile: File? = null,
    private val cache: DocumentCache? = null
) {
    fun entryBytes(path: String): ByteArray? {
        val target = EpubTextExtractor.normalizeForSource(path)
        localFile?.let { file ->
            ZipFile(file).use { zip ->
                return zip.getEntry(target)?.let { zip.getInputStream(it).readBytes() }
            }
        }
        cache?.get(target)?.let { return it }
        val bytes = openStream().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (EpubTextExtractor.normalizeForSource(entry.name) == target) return@use zip.readBytes()
                    entry = zip.nextEntry
                }
                null
            }
        }
        if (bytes != null) cache?.put(target, bytes)
        return bytes
    }

    fun entryText(path: String): String? = entryBytes(path)?.toString(Charsets.UTF_8)

    fun firstEntryEnding(suffix: String): Pair<String, String>? {
        localFile?.let { file ->
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(suffix, true) }
                    ?: return null
                return entry.name to zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(suffix, true)) return entry.name to zip.readBytes().toString(Charsets.UTF_8)
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }
}

internal class DocumentCache(private val directory: File, private val maxBytes: Long) {
    init { directory.mkdirs() }

    fun get(name: String): ByteArray? = File(directory, safeName(name)).takeIf { it.isFile }?.readBytes()

    fun put(name: String, bytes: ByteArray) {
        if (bytes.size > maxBytes) return
        File(directory, safeName(name)).writeBytes(bytes)
        trim()
    }

    private fun trim() {
        val files = directory.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        files.forEach { file ->
            if (total > maxBytes) {
                total -= file.length()
                file.delete()
            }
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
}
