package top.lighilit.watch_data_sync

internal class FileTransfer(content: String, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
    private val parts = splitContent(content, chunkSize)
    private var nextIndex = 0

    val sentParts: Int
        get() = nextIndex

    val totalParts: Int
        get() = parts.size

    val isComplete: Boolean
        get() = nextIndex >= parts.size

    fun pendingPart(): Part? {
        if (nextIndex >= parts.size) return null
        return Part(parts[nextIndex], nextIndex + 1, parts.size)
    }

    fun markSent() {
        if (!isComplete) nextIndex++
    }

    data class Part(val text: String, val number: Int, val total: Int)

    companion object {
        // Conservative character limit for JSON transported by the wearable bridge.
        const val DEFAULT_CHUNK_SIZE = 300

        private fun splitContent(content: String, chunkSize: Int): List<String> {
            require(chunkSize > 0) { "chunkSize must be positive" }
            if (content.isEmpty()) return listOf("")

            val chunks = mutableListOf<String>()
            var start = 0
            while (start < content.length) {
                var end = minOf(start + chunkSize, content.length)
                if (end < content.length && Character.isHighSurrogate(content[end - 1])) {
                    end--
                }
                chunks += content.substring(start, end)
                start = end
            }
            return chunks
        }
    }
}
