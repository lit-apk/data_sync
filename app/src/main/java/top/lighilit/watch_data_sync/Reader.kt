package top.lighilit.watch_data_sync

internal class PreviousPageNotSupportedException(message: String) : RuntimeException(message)

internal abstract class Reader(protected val transfer: FileTransfer) {
    /** From the document backend; see [DocumentFormat.skipImages]. */
    var skipImages: Boolean = true
        private set

    /** From the document backend; see [DocumentFormat.positionUnit]. */
    var positionUnit: PositionUnit = PositionUnit.CHARACTER
        private set

    /** Phrase for a position, e.g. "character 300" or "page 3". */
    fun describe(offset: Int, through: Boolean = false): Pair<Int, Int> {
        val value = if (through) positionUnit.through(offset, transfer.chunkSize) else positionUnit.display(offset, transfer.chunkSize)
        return positionUnit.phrase to value
    }

    val currentOffset: Int
        get() = transfer.currentOffset

    val isComplete: Boolean
        get() = transfer.isComplete

    fun chapterAt(offset: Int): Int = transfer.chapterAt(offset)

    fun nextPart(): FileTransfer.Part? = transfer.pendingPart()

    fun imagesAt(offset: Int): List<ReadingImage> = transfer.imagesAt(offset)

    fun markSent() = transfer.markSent()

    fun nextIsImageOnly(): Boolean = transfer.nextIsImageOnly()

    fun skipImageOnlyParts() = transfer.skipImageOnlyParts()

    fun markPreviousSent() = transfer.markPreviousSent()

    abstract fun previousPart(): FileTransfer.Part?

    companion object {
        fun of(source: TextSource, name: String, chunkSize: Int, offset: Int, chapter: Int = 0): Reader {
            val transferSource = if (DocumentFormats.isChaptered(name)) {
                source.chapterSource(chapter)
            } else {
                source
            }
            val transfer = FileTransfer(transferSource, chunkSize, offset)
            val reader = if (DocumentFormats.isRichText(name)) {
                EpubReader(transfer)
            } else {
                PlainTextReader(transfer)
            }
            reader.skipImages = DocumentFormats.skipsImages(name)
            reader.positionUnit = DocumentFormats.positionUnit(name)
            return reader
        }
    }
}

internal open class PlainTextReader(transfer: FileTransfer) : Reader(transfer) {
    override fun previousPart(): FileTransfer.Part? = transfer.previousPart()
}

internal open class RichTextReader(transfer: FileTransfer) : Reader(transfer) {
    override fun previousPart(): FileTransfer.Part? =
        throw PreviousPageNotSupportedException("Previous page is not supported for this content")
}

internal class EpubReader(transfer: FileTransfer) : RichTextReader(transfer)
