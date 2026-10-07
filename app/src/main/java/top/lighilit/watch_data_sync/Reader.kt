package top.lighilit.watch_data_sync

internal class PreviousPageNotSupportedException(message: String) : RuntimeException(message)

internal abstract class Reader(protected val transfer: FileTransfer) {
    val currentOffset: Int
        get() = transfer.currentOffset

    val isComplete: Boolean
        get() = transfer.isComplete

    fun chapterAt(offset: Int): Int = transfer.chapterAt(offset)

    fun nextPart(): FileTransfer.Part? = transfer.pendingPart()

    fun markSent() = transfer.markSent()

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
            return if (DocumentFormats.isRichText(name)) {
                EpubReader(transfer)
            } else {
                PlainTextReader(transfer)
            }
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
