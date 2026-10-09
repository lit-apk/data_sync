package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import java.io.File
import java.io.InputStream

/**
 * Marks a document backend for the build-time registry generator. [mimeTypes] are the
 * file types it can open; several backends may share a type, and the user then picks one
 * (shown with [DocumentFormat.description]).
 */
@Target(AnnotationTarget.CLASS)
annotation class DocumentBackend(vararg val mimeTypes: MimeType)

internal interface DocumentFormat {
    /** Stable id stored in reading history to remember the chosen backend. */
    val id: String get() = javaClass.simpleName

    /** Shown when choosing between backends for the same file type. */
    @get:StringRes
    val description: Int

    val richText: Boolean
    val chaptered: Boolean
    fun source(context: Context, uri: Uri, name: String, cache: DocumentCache? = null): TextSource
    fun source(file: File, name: String, context: Context? = null): TextSource
    fun readText(input: InputStream, name: String): String

    /**
     * Whether the watch may offer to skip a run of image-only pages and jump to the next text.
     * True for text with illustrations (e.g. EPUB); image-only formats (e.g. comics) should
     * return false, since the images are the content.
     */
    val skipImages: Boolean get() = true

    /** How reading positions are shown and entered; see [PositionUnit]. */
    val positionUnit: PositionUnit get() = PositionUnit.CHARACTER
}

/**
 * Thrown by a backend when a file cannot be opened as a local, seekable file (e.g. a
 * cloud provider returned nothing). Not a bug: the UI shows a translated hint instead
 * of a stack trace (see `errorText` in MainActivity).
 */
internal class OpenLocalFileException(val name: String) : java.io.IOException("Failed to open $name, maybe not a local file?")

/** One generated registry entry: a backend and the types from its `@DocumentBackend`. */
internal class BackendRegistration(val format: DocumentFormat, val mimeTypes: List<MimeType>)

/** Registry: file type -> the backends that can open it (in declaration-name order). */
internal object DocumentFormats {
    private val byMime: Map<MimeType, List<DocumentFormat>> = GeneratedDocumentFormats.all().let { registrations ->
        MimeType.entries.associateWith { mime ->
            registrations.filter { mime in it.mimeTypes }.map { it.format }
        }
    }

    /** Types offered by the file picker. */
    fun pickerTypes(): Array<String> =
        byMime.filterValues { it.isNotEmpty() }.keys.map { it.pickerType }.distinct().toTypedArray()

    fun backendsFor(mime: MimeType): List<DocumentFormat> = byMime[mime].orEmpty()

    /** The backend [backendId] if it handles [mime], else the first one registered for [mime]. */
    fun forMime(mime: MimeType, backendId: String? = null): DocumentFormat {
        val backends = backendsFor(mime).ifEmpty { backendsFor(MimeType.TEXT) }
        return backends.firstOrNull { it.id == backendId } ?: backends.firstOrNull()
            ?: error("No document backend registered for ${mime.value}")
    }

    /** Default backend by file name (extension only). */
    fun forName(name: String): DocumentFormat = forMime(MimeType.fromName(name))
}
