package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri

/**
 * File types the app can open. Backends declare the types they handle in
 * `@DocumentBackend(...)`; a file is opened by detecting its type, then choosing among
 * the backends registered for it (see [DocumentFormats]).
 *
 * Public (not internal) because [DocumentBackend] uses it as an annotation argument.
 */
enum class MimeType(val value: String, private val extensions: List<String>, val pickerType: String = value) {
    TEXT("text/plain", listOf("txt", "md", "log", "csv"), pickerType = "text/*"),
    JSON("application/json", listOf("json")),
    EPUB("application/epub+zip", listOf("epub")),
    PDF("application/pdf", listOf("pdf"));

    companion object {
        /** By file extension; unknown extensions are read as plain text. */
        fun fromName(name: String): MimeType {
            val extension = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions } ?: TEXT
        }

        /** By MIME string, e.g. from a content provider; any other `text/` type is [TEXT]. */
        fun fromType(type: String?): MimeType? {
            val normalized = type?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.value == normalized } ?: TEXT.takeIf { normalized.startsWith("text/") }
        }

        /** The provider's reported type when known, otherwise the file extension. */
        fun detect(context: Context, uri: Uri, name: String): MimeType =
            runCatching { fromType(context.contentResolver.getType(uri)) }.getOrNull() ?: fromName(name)
    }
}
