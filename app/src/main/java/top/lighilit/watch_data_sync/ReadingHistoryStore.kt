package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ReadingHistory(
    val id: String,
    val name: String,
    val source: String,
    val backedUp: Boolean,
    val offset: Int,
    val chapter: Int = 0,
    val localOffset: Boolean = false,
    /** [DocumentFormat.id] chosen for this file; empty = the default for its type. */
    val backend: String = ""
)

internal class ReadingHistoryStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("reading_history", Context.MODE_PRIVATE)
    private val backupDirectory = File(context.filesDir, "reading_backups").apply { mkdirs() }

    fun load(): List<ReadingHistory> {
        val array = runCatching { JSONArray(preferences.getString("entries", "[]")) }
            .getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ReadingHistory(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        source = item.optString("source"),
                        backedUp = item.optBoolean("backedUp"),
                        offset = item.optInt("offset").coerceAtLeast(0),
                        chapter = item.optInt("chapter").coerceAtLeast(0),
                        localOffset = item.optBoolean("localOffset"),
                        backend = item.optString("backend")
                    )
                )
            }
        }
    }

    fun addUri(uri: Uri, name: String, backup: Boolean, limit: Int, backend: String = ""): ReadingHistory {
        val entry = if (backup) {
            val file = uniqueBackupFile(name)
            copyToBackup(uri, file, name)
            ReadingHistory(UUID.randomUUID().toString(), file.name, file.absolutePath, true, 0, backend = backend)
        } else {
            ReadingHistory(UUID.randomUUID().toString(), name, uri.toString(), false, 0, backend = backend)
        }
        saveEntry(entry, limit)
        return entry
    }

    /** File type of a history entry: backups by name, others as reported by their provider. */
    fun mimeType(entry: ReadingHistory): MimeType =
        if (entry.backedUp) MimeType.fromName(entry.name) else mimeType(Uri.parse(entry.source), entry.name)

    fun mimeType(uri: Uri, name: String): MimeType = MimeType.detect(context, uri, name)

    /** The backend stored for [entry] (or the default for its type if unset/unavailable). */
    fun format(entry: ReadingHistory): DocumentFormat = DocumentFormats.forMime(mimeType(entry), entry.backend)

    fun source(entry: ReadingHistory, format: DocumentFormat = format(entry)): TextSource = if (entry.backedUp) {
        format.source(File(entry.source), entry.name, context)
    } else {
        source(Uri.parse(entry.source), entry.name, format)
    }

    fun source(uri: Uri, name: String, format: DocumentFormat): TextSource {
        val cache = documentCache(context).forDocument(documentKey(uri))
        return format.source(context, uri, name, cache)
    }

    /** Identifies document content for caching: the URI plus size/mtime when the provider reports them. */
    private fun documentKey(uri: Uri): String {
        val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val details = runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                (0 until cursor.columnCount).joinToString("|") { cursor.getString(it).orEmpty() }
            }
        }.getOrNull()
        return "$uri|${details.orEmpty()}"
    }

    fun displayName(uri: Uri): String {
        val queried = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
    }

    fun updateOffset(id: String, offset: Int, limit: Int) {
        val entry = load().firstOrNull { it.id == id } ?: return
        saveEntry(entry.copy(offset = offset.coerceAtLeast(0)), limit)
    }

    fun updatePosition(id: String, offset: Int, chapter: Int, limit: Int) {
        val entry = load().firstOrNull { it.id == id } ?: return
        saveEntry(
            entry.copy(
                offset = offset.coerceAtLeast(0),
                chapter = chapter.coerceAtLeast(0),
                localOffset = format(entry).chaptered
            ),
            limit
        )
    }

    fun delete(id: String) {
        val entries = load()
        entries.firstOrNull { it.id == id && it.backedUp }?.let { File(it.source).delete() }
        val array = JSONArray()
        entries.filterNot { it.id == id }.forEach {
            array.put(it.toJson())
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    fun update(
        entry: ReadingHistory,
        newName: String,
        newSource: String,
        backup: Boolean,
        limit: Int,
        backend: String = entry.backend
    ): ReadingHistory {
        val updated = when {
            backup && entry.backedUp -> {
                val oldFile = File(entry.source)
                val target = uniqueBackupFile(newName, oldFile)
                if (oldFile.absolutePath != target.absolutePath) oldFile.renameTo(target)
                entry.copy(name = target.name, source = target.absolutePath, backedUp = true)
            }
            backup -> {
                val target = uniqueBackupFile(newName)
                copyToBackup(Uri.parse(newSource), target, newName)
                entry.copy(name = target.name, source = target.absolutePath, backedUp = true)
            }
            else -> {
                val uri = Uri.parse(newSource)
                require(uri.scheme == "content") { "Choose a document path" }
                context.contentResolver.openInputStream(uri)?.close()
                    ?: error("Unable to open $newName")
                entry.copy(name = newName, source = newSource, backedUp = false)
            }
        }
            .copy(backend = backend)
        saveEntry(updated, limit)
        return updated
    }

    fun trim(limit: Int) {
        val entries = load()
        val keep = entries.take(limit.coerceAtLeast(1))
        entries.drop(keep.size).filter { it.backedUp }.forEach { File(it.source).delete() }
        val array = JSONArray()
        keep.forEach {
            array.put(it.toJson())
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    private fun saveEntry(entry: ReadingHistory, limit: Int) {
        val entries = load().filterNot { it.id == entry.id }.toMutableList()
        entries.add(0, entry)
        entries.drop(limit.coerceAtLeast(1)).filter { it.backedUp }.forEach {
            File(it.source).delete()
        }
        val array = JSONArray()
        entries.take(limit.coerceAtLeast(1)).forEach {
            array.put(it.toJson())
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    private fun uniqueBackupFile(requestedName: String, existing: File? = null): File {
        val safe = requestedName.substringAfterLast('/').ifBlank { "document.txt" }
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(backupDirectory, safe)
        var suffix = 2
        while (candidate.exists() && candidate.absolutePath != existing?.absolutePath) {
            candidate = File(backupDirectory, "$stem ($suffix)$extension")
            suffix++
        }
        return candidate
    }

    /** Backups are byte-exact copies for every format; readers decode them like the original. */
    private fun copyToBackup(uri: Uri, target: File, name: String) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open $name")
    }

    private fun ReadingHistory.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("source", source)
        .put("backedUp", backedUp)
        .put("offset", offset)
        .put("chapter", chapter)
        .put("localOffset", localOffset)
        .put("backend", backend)
}
