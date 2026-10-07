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
    fun source(context: Context, uri: Uri, name: String): TextSource
    fun source(file: File, name: String): TextSource
    fun readText(input: InputStream, name: String): String
    fun preserveOriginalOnBackup(): Boolean = false
    val fallback: Boolean get() = false
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
}
