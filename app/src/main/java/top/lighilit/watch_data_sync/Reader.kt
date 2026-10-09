package top.lighilit.watch_data_sync

/**
 * Pages through one document (or one chapter). Every backend reads parts by offset with
 * random access (seekable ZIP, loaded PDF/FB2), so going back is supported for all.
 */
internal class Reader(
    source: TextSource,
    format: DocumentFormat,
    chunkSize: Int,
    offset: Int,
    /** 0-based chapter this reader covers (chaptered formats only). */
    val chapter: Int = 0
) {
    private val transfer = FileTransfer(if (format.chaptered) source.chapterSource(chapter) else source, chunkSize, offset)

    /** From the document backend; see [DocumentFormat.skipImages]. */
    val skipImages: Boolean = format.skipImages

    /** From the document backend; see [DocumentFormat.positionUnit]. */
    val positionUnit: PositionUnit = format.positionUnit

    /** Phrase for a position, e.g. "character 300", "page 3" or "chapter 2". */
    fun describe(offset: Int, through: Boolean = false): Pair<Int, Int> {
        val value = if (through) {
            positionUnit.through(offset, transfer.chunkSize, chapter)
        } else {
            positionUnit.display(offset, transfer.chunkSize, chapter)
        }
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

    fun previousPart(): FileTransfer.Part? = transfer.previousPart()

}
