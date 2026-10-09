package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * CBZ comic: a ZIP of page images, shown one image per page in file-name order
 * (natural sort, so "2.jpg" comes before "10.jpg").
 */
@DocumentBackend(MimeType.CBZ)
internal class CbzFormat : DocumentFormat {
    override val description = R.string.backend_cbz
    override val richText = true
    override val chaptered = false
    override val skipImages = false
    override val positionUnit = PositionUnit.PAGE

    /** Opened once as a seekable descriptor: no copy, no reopen. */
    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?): TextSource =
        CbzSource { context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $name") }

    override fun source(file: File, name: String, context: Context?): TextSource =
        CbzSource { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun readText(input: InputStream, name: String): String = error("CBZ pages are images and have no text")
}

/**
 * Image-only source like [PdfImageFormat]: page n is stored at `n * chunkSize`.
 * The file is opened once; its ZIP index is read once and each page is read by seeking
 * straight to it (see [SeekableZip]), never by copying, reopening or rescanning.
 */
private class CbzSource(private val open: () -> ParcelFileDescriptor) : TextSource {
    private val zip: SeekableZip by lazy {
        val descriptor = open()
        SeekableZip(FileInputStream(descriptor.fileDescriptor).channel, descriptor)
    }

    private val pages: List<SeekableZip.Entry> by lazy {
        zip.entries
            .filter { !it.name.endsWith("/") && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }

    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? = null

    override fun imageCount(): Int = pages.size

    override fun imagesAt(offset: Int, pageSize: Int): List<ReadingImage> = synchronized(this) {
        val index = offset / pageSize
        val entry = pages.getOrNull(index) ?: return emptyList()
        val bytes = zip.read(entry)
        listOf(ReadingImage("cbz-$index", bytes, "", ""))
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}

/** Compares names with embedded numbers numerically: "page2" < "page10". */
internal object NaturalOrder : Comparator<String> {
    private val chunks = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val result = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (result != 0) return result
        }
        return x.size.compareTo(y.size)
    }
}
