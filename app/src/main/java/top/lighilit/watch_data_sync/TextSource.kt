package top.lighilit.watch_data_sync

import java.io.InputStream

internal data class SourcePart(
    val text: String,
    val endOffset: Int,
    val endOfSource: Boolean
)

internal interface TextSource {
    fun readPart(startOffset: Int, maxChars: Int): SourcePart?
}

internal class PlainTextSource(private val openStream: () -> InputStream) : TextSource {
    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? {
        require(startOffset >= 0) { "startOffset must not be negative" }
        require(maxChars > 0) { "maxChars must be positive" }

        openStream().bufferedReader().use { reader ->
            skipChars(reader, startOffset)
            val buffer = CharArray(maxChars + 1)
            val count = readUpTo(reader, buffer)
            if (count <= 0) return null

            val returnedCount = if (
                count > maxChars && Character.isHighSurrogate(buffer[maxChars - 1])
            ) {
                maxChars - 1
            } else {
                minOf(count, maxChars)
            }
            return SourcePart(
                text = String(buffer, 0, returnedCount),
                endOffset = startOffset + returnedCount,
                endOfSource = count <= maxChars
            )
        }
    }

    private fun skipChars(reader: java.io.Reader, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (reader.read() < 0) {
                return
            } else {
                remaining--
            }
        }
    }

    private fun readUpTo(reader: java.io.Reader, buffer: CharArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = reader.read(buffer, total, buffer.size - total)
            if (count < 0) break
            if (count > 0) {
                total += count
            } else {
                val character = reader.read()
                if (character < 0) break
                buffer[total++] = character.toChar()
            }
        }
        return total
    }
}

internal class StringTextSource(private val content: String) : TextSource {
    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? {
        if (startOffset >= content.length) return null
        var end = minOf(startOffset + maxChars, content.length)
        if (end < content.length && Character.isHighSurrogate(content[end - 1])) end--
        return SourcePart(
            text = content.substring(startOffset, end),
            endOffset = end,
            endOfSource = end >= content.length
        )
    }
}
