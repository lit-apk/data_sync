package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Mobipocket (.mobi/.prc/.azw without DRM): a PalmDB whose text records hold HTML.
 * Chapters are split at `<mbp:pagebreak>`, like EPUB chapters; `<img recindex="N">`
 * images are sent alongside their chapter. Supports no compression and PalmDOC
 * compression; HUFF/CDIC-compressed and DRM files are rejected with a message.
 */
@DocumentBackend(MimeType.MOBI)
internal class MobiFormat : DocumentFormat {
    override val description = R.string.backend_chaptered_rich
    override val richText = true
    override val chaptered = true
    override val positionUnit = PositionUnit.CHAPTER

    /** Opened once as a seekable descriptor: no copy, no reopen. */
    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?): TextSource =
        MobiSource { context.contentResolver.openFileDescriptor(uri, "r") ?: throw OpenLocalFileException(name) }

    override fun source(file: File, name: String, context: Context?): TextSource =
        MobiSource { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun readText(input: InputStream, name: String): String =
        error("MOBI needs random access; open it as a file")
}

internal class MobiChapter(val title: String, val text: String, val imageRecords: List<Int>)

/**
 * Record-level MOBI reader over an open channel. The text is decompressed once;
 * image records are read by seeking to them.
 */
internal class MobiBook(private val channel: FileChannel) {
    private val recordOffsets: List<Long>
    private val firstImageRecord: Int
    val chapters: List<MobiChapter>

    init {
        val header = read(0, 78)
        val count = header.getShort(76).toUShort().toInt()
        val table = read(78, count * 8)
        recordOffsets = List(count) { table.getInt(it * 8).toUInt().toLong() } + channel.size()

        val record0 = record(0)
        val compression = record0.getShort(0).toUShort().toInt()
        val textRecords = record0.getShort(8).toUShort().toInt()
        val encryption = record0.getShort(12).toUShort().toInt()
        require(encryption == 0) { "DRM-protected MOBI files are not supported" }
        require(compression == NONE || compression == PALMDOC) {
            "This MOBI uses HUFF/CDIC compression, which is not supported"
        }
        val isMobi = record0.capacity() > 20 && record0.getInt(16) == MOBI_MAGIC
        val mobiHeaderLength = if (isMobi) record0.getInt(20) else 0
        val encoding = if (isMobi) record0.getInt(28) else 1252
        firstImageRecord = if (isMobi) record0.getInt(108) else -1
        // Bits of extra data appended to each text record (header length >= 0xE4).
        val extraFlags = if (isMobi && mobiHeaderLength >= 0xE4 && record0.capacity() >= 0xF4) {
            record0.getShort(0xF2).toUShort().toInt()
        } else {
            0
        }

        val html = ByteArrayOutputStream()
        for (index in 1..textRecords) {
            val raw = record(index).array()
            val data = raw.copyOf(raw.size - trailingSize(raw, extraFlags))
            html.write(if (compression == PALMDOC) palmDocDecompress(data) else data)
        }
        val charset = if (encoding == 65001) Charsets.UTF_8 else charset("windows-1252")
        chapters = splitChapters(html.toByteArray().toString(charset))
    }

    /** Image `recindex` is 1-based from the first image record. */
    fun image(recindex: Int): ByteArray? {
        if (firstImageRecord < 0) return null
        val index = firstImageRecord + recindex - 1
        if (index !in 0 until recordOffsets.size - 1) return null
        return record(index).array()
    }

    private fun record(index: Int): ByteBuffer =
        read(recordOffsets[index], (recordOffsets[index + 1] - recordOffsets[index]).toInt())

    private fun read(offset: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        var at = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            require(count > 0) { "Unexpected end of MOBI file" }
            at += count
        }
        buffer.flip()
        return buffer
    }

    companion object {
        private const val NONE = 1
        private const val PALMDOC = 2
        private const val MOBI_MAGIC = 0x4D4F4249 // "MOBI"
        private val PAGEBREAK = Regex("(?i)<mbp:pagebreak[^>]*>")
        private val RECINDEX = Regex("(?i)<img\\b[^>]*\\brecindex=[\"']?(\\d+)")
        private val HEADING = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>")

        /** Splits book HTML at page breaks; each part keeps its images in reading order. */
        internal fun splitChapters(html: String): List<MobiChapter> =
            html.split(PAGEBREAK).mapNotNull { part ->
                val text = EpubTextExtractor.htmlToTextForSource(part)
                val images = RECINDEX.findAll(part).map { it.groupValues[1].toInt() }.toList()
                if (text.isBlank() && images.isEmpty()) null else part to MobiChapter("", text, images)
            }.mapIndexed { index, (part, chapter) ->
                val heading = HEADING.find(part)?.groupValues?.get(1)
                    ?.let { EpubTextExtractor.htmlToTextForSource(it).replace(Regex("\\s+"), " ").trim() }
                MobiChapter(heading?.take(80)?.ifBlank { null } ?: "${index + 1}", chapter.text, chapter.imageRecords)
            }

        /**
         * Size of the extra data at the end of a text record, per the MOBI extra-data flags:
         * entries for bits 1..15 are stripped from the end first (bit 1 outermost), then the
         * multibyte block (bit 0), which sits just before them.
         */
        internal fun trailingSize(data: ByteArray, flags: Int): Int {
            var size = 0
            for (bit in 1..15) {
                if (flags and (1 shl bit) == 0) continue
                // Backward-encoded variable-length size, counted from the current end.
                var value = 0
                var shift = 0
                var position = data.size - size - 1
                for (step in 0 until 4) {
                    if (position < 0) break
                    val byte = data[position].toInt() and 0xFF
                    value = value or ((byte and 0x7F) shl shift)
                    shift += 7
                    position--
                    if (byte and 0x80 != 0) break
                }
                size += value
            }
            if (flags and 1 != 0 && data.size - size - 1 >= 0) size += (data[data.size - size - 1].toInt() and 3) + 1
            return size.coerceIn(0, data.size)
        }

        /** PalmDOC (LZ77 variant) decompression. */
        internal fun palmDocDecompress(input: ByteArray): ByteArray {
            var i = 0
            val bytes = ArrayList<Byte>(input.size * 2)
            while (i < input.size) {
                val c = input[i++].toInt() and 0xFF
                when (c) {
                    0, in 0x09..0x7F -> bytes.add(c.toByte())
                    in 0x01..0x08 -> repeat(c) { if (i < input.size) bytes.add(input[i++]) }
                    in 0x80..0xBF -> {
                        if (i >= input.size) break
                        val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                        val distance = (pair shr 3) and 0x7FF
                        val length = (pair and 7) + 3
                        val start = bytes.size - distance
                        if (distance == 0 || start < 0) continue
                        repeat(length) { bytes.add(bytes[start + it]) }
                    }
                    else -> {
                        bytes.add(' '.code.toByte())
                        bytes.add((c xor 0x80).toByte())
                    }
                }
            }
            return bytes.toByteArray()
        }
    }
}

/** Opens the file once and serves chapters from the parsed book. */
private class MobiSource(private val open: () -> ParcelFileDescriptor) : TextSource {
    override val chaptered = true
    private val book by lazy {
        val descriptor = open()
        MobiBook(FileInputStream(descriptor.fileDescriptor).channel)
    }

    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? = null

    override fun chapters(): List<TextChapter> =
        book.chapters.mapIndexed { index, chapter -> TextChapter(index, chapter.title, 0) }

    override fun chapterSource(index: Int): TextSource {
        val chapter = book.chapters.getOrNull(index) ?: MobiChapter("", "", emptyList())
        return MobiChapterSource(chapter, book)
    }
}

/** One chapter: text, plus one image per text chunk like EPUB chapters. */
private class MobiChapterSource(private val chapter: MobiChapter, private val book: MobiBook) : TextSource {
    private val content = StringTextSource(chapter.text)

    override fun readPart(startOffset: Int, maxChars: Int) = content.readPart(startOffset, maxChars)

    override fun imageCount(): Int = chapter.imageRecords.size

    override fun imagesAt(offset: Int, pageSize: Int): List<ReadingImage> = synchronized(book) {
        val index = offset / pageSize
        val bytes = chapter.imageRecords.getOrNull(index)?.let(book::image) ?: return emptyList()
        listOf(ReadingImage("mobi-$index", bytes, "", ""))
    }
}
