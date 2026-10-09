package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

@DocumentBackend(MimeType.TEXT, MimeType.JSON)
internal class PlainTextFormat : DocumentFormat {
    override val description = R.string.backend_plain_text
    override val richText = false
    override val chaptered = false
    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?) = PlainTextSource {
        context.contentResolver.openInputStream(uri) ?: error("Unable to open $name")
    }
    override fun source(file: File, name: String, context: Context?) = PlainTextSource { file.inputStream() }
    override fun readText(input: InputStream, name: String) = input.bufferedReader().readText()
}
