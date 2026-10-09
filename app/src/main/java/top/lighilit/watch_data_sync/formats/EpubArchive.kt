package top.lighilit.watch_data_sync

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Random access to an EPUB's entries. The file is opened once as a seekable descriptor;
 * its ZIP index is read once and every entry is read by seeking straight to it
 * ([SeekableZip]), never by copying, reopening or rescanning the archive.
 */
internal class EpubArchive(private val openDescriptor: () -> ParcelFileDescriptor) {
    private val zip: SeekableZip by lazy {
        val descriptor = openDescriptor()
        SeekableZip(FileInputStream(descriptor.fileDescriptor).channel, descriptor)
    }

    private val byPath: Map<String, SeekableZip.Entry> by lazy {
        zip.entries.associateBy { EpubTextExtractor.normalizeForSource(it.name) }
    }

    fun entryBytes(path: String): ByteArray? = synchronized(this) {
        byPath[EpubTextExtractor.normalizeForSource(path)]?.let(zip::read)
    }

    fun entryText(path: String): String? = entryBytes(path)?.toString(Charsets.UTF_8)

    fun firstEntryEnding(suffix: String): Pair<String, String>? = synchronized(this) {
        val entry = zip.entries.firstOrNull { it.name.endsWith(suffix, true) } ?: return null
        entry.name to zip.read(entry).toString(Charsets.UTF_8)
    }
}

/**
 * On-disk cache of document entries. Entry names (e.g. `OEBPS/content.opf`) repeat
 * across documents, so callers must use [forDocument]; the size limit spans all documents.
 */
internal class DocumentCache private constructor(
    private val root: File,
    private val maxBytes: Long,
    private val directory: File
) {
    constructor(root: File, maxBytes: Long) : this(root, maxBytes, root)

    init {
        directory.mkdirs()
        // Entries written directly under the root come from the old unscoped layout,
        // where documents overwrote each other's entries; they are unsafe to reuse.
        if (directory == root) root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    /** A cache private to one document; [key] must change whenever the document content may change. */
    fun forDocument(key: String): DocumentCache {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val scope = digest.joinToString("") { "%02x".format(it) }.take(32)
        return DocumentCache(root, maxBytes, File(root, scope))
    }

    fun get(name: String): ByteArray? = File(directory, safeName(name)).takeIf { it.isFile }?.readBytes()

    fun put(name: String, bytes: ByteArray) {
        if (bytes.size > maxBytes) return
        File(directory, safeName(name)).writeBytes(bytes)
        trim()
    }

    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
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
