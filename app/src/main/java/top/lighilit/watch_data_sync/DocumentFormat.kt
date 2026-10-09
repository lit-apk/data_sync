package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

@Target(AnnotationTarget.CLASS)
annotation class DocumentBackend

internal interface DocumentFormat {
    val mimeTypes: List<String>
    val richText: Boolean
    val chaptered: Boolean
    fun matches(name: String): Boolean
    fun source(context: Context, uri: Uri, name: String, cache: DocumentCache? = null): TextSource
    fun source(file: File, name: String, context: Context? = null): TextSource
    fun copyToBackup(context: Context, uri: Uri, target: File, name: String)
    fun readText(input: InputStream, name: String): String
    fun preserveOriginalOnBackup(): Boolean = false
    val fallback: Boolean get() = false

    /**
     * Whether the watch may offer to skip a run of image-only pages and jump to the next text.
     * True for text with illustrations (e.g. EPUB); image-only formats (e.g. comics) should
     * return false, since the images are the content.
     */
    val skipImages: Boolean get() = true

    /** How reading positions are shown and entered; see [PositionUnit]. */
    val positionUnit: PositionUnit get() = PositionUnit.CHARACTER
}

internal object DocumentFormats {
    private val formats: List<DocumentFormat> = GeneratedDocumentFormats.all()

    fun mimeTypes(): Array<String> = formats.flatMap { it.mimeTypes }.distinct().toTypedArray()
    fun forName(name: String): DocumentFormat =
        formats.firstOrNull { !it.fallback && it.matches(name) }
            ?: formats.firstOrNull { it.fallback }
            ?: error("No fallback document format registered")
    fun isRichText(name: String) = forName(name).richText
    fun isChaptered(name: String) = forName(name).chaptered
    fun skipsImages(name: String) = forName(name).skipImages
    fun positionUnit(name: String) = forName(name).positionUnit
}
