package top.lighilit.watch_data_sync

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * PDF backend that reads like EPUB: each PDF page is a chapter whose text is extracted and
 * whose embedded images are sent alongside it. Suits text PDFs; scanned PDFs (no text layer)
 * read better with [PdfImageFormat].
 */
@DocumentBackend(MimeType.PDF)
internal class PdfRichFormat : DocumentFormat {
    override val description = R.string.backend_pdf_rich
    override val richText = true
    override val chaptered = true
    override val positionUnit = PositionUnit.PAGE_CHAPTER

    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?): TextSource {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PdfRichSource { context.contentResolver.openInputStream(uri) ?: error("Unable to open $name") }
    }

    override fun source(file: File, name: String, context: Context?): TextSource {
        context?.let { PDFBoxResourceLoader.init(it.applicationContext) }
        return PdfRichSource { file.inputStream() }
    }

    override fun readText(input: InputStream, name: String): String =
        PDDocument.load(input).use { PDFTextStripper().getText(it) }
}

/**
 * Whole-document source: lists pages as chapters; [chapterSource] reads one page.
 * The PDF is read and parsed once (one pass over the stream); pages are then accessed
 * from the loaded document, never by reopening or rescanning the file. Large PDFs spill
 * to a temp file instead of being held fully in memory.
 */
private class PdfRichSource(private val open: () -> InputStream) : TextSource {
    override val chaptered = true
    private val document: PDDocument by lazy {
        open().use { PDDocument.load(it, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)) }
    }

    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? = null

    override fun chapters(): List<TextChapter> = synchronized(this) {
        (0 until document.numberOfPages).map { TextChapter(it, "Page ${it + 1}", 0) }
    }

    override fun chapterSource(index: Int): TextSource = synchronized(this) {
        if (index !in 0 until document.numberOfPages) return PdfRichPageSource("", emptyList())
        val stripper = PDFTextStripper().apply {
            startPage = index + 1
            endPage = index + 1
        }
        PdfRichPageSource(stripper.getText(document).trim(), pageImages(document, index))
    }

    /** Embedded raster images of a page, as JPEG; WatchImageEncoder resizes them later. */
    private fun pageImages(document: PDDocument, index: Int): List<ByteArray> {
        val resources = document.getPage(index).resources ?: return emptyList()
        return resources.xObjectNames.mapNotNull { name ->
            val image = runCatching { resources.getXObject(name) as? PDImageXObject }.getOrNull() ?: return@mapNotNull null
            val bitmap = runCatching { image.image }.getOrNull() ?: return@mapNotNull null
            try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val MAX_MAIN_MEMORY_BYTES = 16L * 1024 * 1024
    }
}

/**
 * One page: its text, plus one image per text chunk like EPUB chapters ([FileTransfer]
 * adds image-only parts when a page has more images than text chunks).
 */
private class PdfRichPageSource(text: String, private val images: List<ByteArray>) : TextSource {
    private val content = StringTextSource(text)

    override fun readPart(startOffset: Int, maxChars: Int) = content.readPart(startOffset, maxChars)

    override fun imageCount(): Int = images.size

    override fun imagesAt(offset: Int, pageSize: Int): List<ReadingImage> {
        val index = offset / pageSize
        val bytes = images.getOrNull(index) ?: return emptyList()
        return listOf(ReadingImage("pdf-image-$index", bytes, "image/jpeg", ""))
    }
}
