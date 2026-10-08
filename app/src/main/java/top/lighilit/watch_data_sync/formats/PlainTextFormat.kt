package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

@DocumentBackend
internal class PlainTextFormat : DocumentFormat {
    override val mimeTypes = listOf("text/*", "application/json")
    override val richText = false
    override val chaptered = false
    override fun matches(name: String) = true
    override val fallback = true
    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?) = PlainTextSource {
        context.contentResolver.openInputStream(uri) ?: error("Unable to open $name")
    }
    override fun source(file: File, name: String, context: Context?) = PlainTextSource { file.inputStream() }
    override fun readText(input: InputStream, name: String) = input.bufferedReader().readText()
    override fun copyToBackup(context: Context, uri: Uri, target: File, name: String) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.writeText(input.bufferedReader().use { it.readText() })
        } ?: error("Unable to open $name")
    }
}
