package top.lighilit.watch_data_sync

internal class FileTransfer(
    private val content: String,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    startOffset: Int = 0
) {
    private var offset = startOffset.coerceIn(0, content.length)
    private var pendingEnd = offset
    private val sentStarts = ArrayDeque<Int>()
    private var pendingPreviousEnd = -1

    val currentOffset: Int
        get() = offset

    val sentParts: Int
        get() = if (offset <= 0) 0 else (offset + chunkSize - 1) / chunkSize

    val totalParts: Int
        get() = if (content.isEmpty()) 1 else (content.length + chunkSize - 1) / chunkSize

    val isComplete: Boolean
        get() = offset >= content.length

    fun pendingPart(): Part? {
        val part = partStartingAt(offset) ?: return null
        pendingEnd = part.endOffset
        return part
    }

    fun markSent() {
        sentStarts.addLast(offset)
        offset = pendingEnd
    }

    fun previousPart(): Part? {
        if (sentStarts.size < 2) return null
        val previousStart = sentStarts[sentStarts.size - 2]
        val part = partStartingAt(previousStart) ?: return null
        pendingPreviousEnd = part.endOffset
        return part
    }

    fun markPreviousSent() {
        sentStarts.removeLast()
        offset = pendingPreviousEnd
    }

    private fun partStartingAt(start: Int): Part? {
        if (start >= content.length) return null
        var end = minOf(start + chunkSize, content.length)
        if (end < content.length && Character.isHighSurrogate(content[end - 1])) end--
        return Part(
            text = content.substring(start, end),
            startOffset = start,
            endOffset = end
        )
    }

    data class Part(val text: String, val startOffset: Int, val endOffset: Int)

    companion object {
        const val DEFAULT_CHUNK_SIZE = 300
    }
}
