package top.lighilit.watch_data_sync

internal class FileTransfer(
    private val source: TextSource,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    startOffset: Int = 0,
    private var complete: Boolean = false
) {
    constructor(content: String, chunkSize: Int = DEFAULT_CHUNK_SIZE, startOffset: Int = 0) :
        this(StringTextSource(content), chunkSize, startOffset)

    private var offset = startOffset.coerceAtLeast(0)
    private var pendingEnd = offset
    private val sentStarts = ArrayDeque<Int>()
    private var pendingPreviousEnd = -1

    val currentOffset: Int
        get() = offset

    val sentParts: Int
        get() = if (offset <= 0) 0 else (offset + chunkSize - 1) / chunkSize

    val isComplete: Boolean
        get() = complete

    fun chapterAt(offset: Int): Int = source.chapterAt(offset)

    fun pendingPart(): Part? {
        val sourcePart = source.readPart(offset, chunkSize) ?: run {
            complete = true
            return null
        }
        pendingEnd = sourcePart.endOffset
        pendingComplete = sourcePart.endOfSource
        return Part(sourcePart.text, offset, sourcePart.endOffset)
    }

    fun markSent() {
        sentStarts.addLast(offset)
        offset = pendingEnd
        complete = pendingComplete
    }

    fun previousPart(): Part? {
        if (sentStarts.size < 2) return null
        val previousStart = sentStarts[sentStarts.size - 2]
        val sourcePart = source.readPart(previousStart, chunkSize) ?: return null
        pendingPreviousEnd = sourcePart.endOffset
        pendingPreviousComplete = sourcePart.endOfSource
        return Part(sourcePart.text, previousStart, sourcePart.endOffset)
    }

    fun markPreviousSent() {
        sentStarts.removeLast()
        offset = pendingPreviousEnd
        complete = pendingPreviousComplete
    }

    private var pendingComplete = false
    private var pendingPreviousComplete = false

    data class Part(val text: String, val startOffset: Int, val endOffset: Int)

    companion object {
        const val DEFAULT_CHUNK_SIZE = 300

        operator fun invoke(content: String, chunkSize: Int = DEFAULT_CHUNK_SIZE, startOffset: Int = 0): FileTransfer =
            FileTransfer(StringTextSource(content), chunkSize, startOffset)
    }
}
