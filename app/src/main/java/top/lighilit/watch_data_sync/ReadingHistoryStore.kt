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
    val localOffset: Boolean = false
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
                        localOffset = item.optBoolean("localOffset")
                    )
                )
            }
        }
    }

    fun addUri(uri: Uri, name: String, backup: Boolean, limit: Int): ReadingHistory {
        val entry = if (backup) {
            val file = uniqueBackupFile(name)
            DocumentFormats.forName(name).copyToBackup(context, uri, file, name)
            ReadingHistory(UUID.randomUUID().toString(), file.name, file.absolutePath, true, 0)
        } else {
            ReadingHistory(UUID.randomUUID().toString(), name, uri.toString(), false, 0)
        }
        saveEntry(entry, limit)
        return entry
    }

    fun source(entry: ReadingHistory): TextSource = if (entry.backedUp) {
        DocumentFormats.forName(entry.name).source(File(entry.source), entry.name, context)
    } else {
        source(Uri.parse(entry.source), entry.name)
    }

    fun source(uri: Uri, name: String): TextSource {
        val cache = documentCache(context).forDocument(documentKey(uri))
        return DocumentFormats.forName(name).source(context, uri, name, cache)
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
                localOffset = DocumentFormats.isChaptered(entry.name)
            ),
            limit
        )
    }

    fun delete(id: String) {
        val entries = load()
        entries.firstOrNull { it.id == id && it.backedUp }?.let { File(it.source).delete() }
        val array = JSONArray()
        entries.filterNot { it.id == id }.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("source", it.source)
                    .put("backedUp", it.backedUp)
                    .put("offset", it.offset)
                    .put("chapter", it.chapter)
            )
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    fun update(entry: ReadingHistory, newName: String, newSource: String, backup: Boolean, limit: Int): ReadingHistory {
        val updated = when {
            backup && entry.backedUp -> {
                val oldFile = File(entry.source)
                val target = uniqueBackupFile(newName, oldFile)
                if (oldFile.absolutePath != target.absolutePath) oldFile.renameTo(target)
                entry.copy(name = target.name, source = target.absolutePath, backedUp = true)
            }
            backup -> {
                val target = uniqueBackupFile(newName)
                DocumentFormats.forName(newName).copyToBackup(context, Uri.parse(newSource), target, newName)
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
        saveEntry(updated, limit)
        return updated
    }

    fun trim(limit: Int) {
        val entries = load()
        val keep = entries.take(limit.coerceAtLeast(1))
        entries.drop(keep.size).filter { it.backedUp }.forEach { File(it.source).delete() }
        val array = JSONArray()
        keep.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("source", it.source)
                    .put("backedUp", it.backedUp)
                    .put("offset", it.offset)
                    .put("chapter", it.chapter)
            )
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
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("source", it.source)
                    .put("backedUp", it.backedUp)
                    .put("offset", it.offset)
                    .put("chapter", it.chapter)
            )
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
}
