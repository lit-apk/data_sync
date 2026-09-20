package top.lighilit.watch_data_sync

internal class FileTransfer(
    private val content: String,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    startOffset: Int = 0
) {
    private var offset = startOffset.coerceIn(0, content.length)
    private var pendingEnd = offset

    val currentOffset: Int
        get() = offset

    val sentParts: Int
        get() = if (offset <= 0) 0 else (offset + chunkSize - 1) / chunkSize

    val totalParts: Int
        get() = if (content.isEmpty()) 1 else (content.length + chunkSize - 1) / chunkSize

    val isComplete: Boolean
        get() = offset >= content.length

    fun pendingPart(): Part? {
        if (isComplete) return null
        var end = minOf(offset + chunkSize, content.length)
        if (end < content.length && Character.isHighSurrogate(content[end - 1])) end--
        pendingEnd = end
        return Part(
            text = content.substring(offset, end),
            startOffset = offset,
            endOffset = end
        )
    }

    fun markSent() {
        offset = pendingEnd
    }

    data class Part(val text: String, val startOffset: Int, val endOffset: Int)

    companion object {
        const val DEFAULT_CHUNK_SIZE = 300
    }
}
