package top.lighilit.watch_data_sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** PDF backend: each PDF page is rendered on the phone and sent as one image-only page. */
@DocumentBackend
internal class PdfFormat : DocumentFormat {
    override val mimeTypes = listOf("application/pdf")
    override val richText = true
    override val chaptered = false

    /** Every page is content; never offer to skip images. */
    override val skipImages = false

    override fun matches(name: String) = name.substringAfterLast('.', "").equals("pdf", true)

    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?): TextSource =
        PdfPageSource { context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $name") }

    override fun source(file: File, name: String, context: Context?): TextSource =
        PdfPageSource { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun readText(input: InputStream, name: String): String =
        error("PDF pages are rendered as images and have no text")

    override fun preserveOriginalOnBackup() = true

    override fun copyToBackup(context: Context, uri: Uri, target: File, name: String) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open $name")
    }
}

/**
 * Image-only source: no text, one image per page. [FileTransfer] turns this into one
 * image-only part per PDF page, so the stored offset is `pageIndex * chunkSize`.
 */
private class PdfPageSource(private val open: () -> ParcelFileDescriptor) : TextSource {
    private val pageCount by lazy { withRenderer { it.pageCount } }

    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? = null

    override fun imageCount(): Int = pageCount

    override fun imagesAt(offset: Int, pageSize: Int): List<ReadingImage> {
        val index = offset / pageSize
        if (index !in 0 until pageCount) return emptyList()
        val (bytes, width, height) = withRenderer { renderer -> renderer.openPage(index).use(::render) }
        return listOf(ReadingImage("pdf-$index", bytes, "image/jpeg", "", width, height))
    }

    private fun <T> withRenderer(block: (PdfRenderer) -> T): T =
        open().use { descriptor -> PdfRenderer(descriptor).use(block) }

    /**
     * Renders at a bounded resolution; [WatchImageEncoder] later fits it to the watch.
     * PDF pages are transparent by default, so draw onto white.
     */
    private fun render(page: PdfRenderer.Page): Triple<ByteArray, Int, Int> {
        val scale = minOf(RENDER_MAX_WIDTH.toFloat() / page.width, RENDER_MAX_SCALE)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, RENDER_QUALITY, output)
            return Triple(output.toByteArray(), width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val RENDER_MAX_WIDTH = 1080
        const val RENDER_MAX_SCALE = 2f
        const val RENDER_QUALITY = 90
    }
}
