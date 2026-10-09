package top.lighilit.watch_data_sync

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * Minimal ZIP reader over an already-open, seekable channel (e.g. from
 * `ContentResolver.openFileDescriptor`): reads the central directory once, then reads
 * any entry by seeking to it. No copy, no reopen, no sequential rescan.
 * Supports stored and deflated entries; not ZIP64 or encrypted archives.
 */
internal class SeekableZip(private val channel: FileChannel, private val owner: Closeable) : Closeable {
    class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localHeader: Long)

    val entries: List<Entry> = readCentralDirectory()

    fun read(entry: Entry): ByteArray {
        // Local header: fixed 30 bytes, then name and extra field (lengths may differ from central).
        val header = readAt(entry.localHeader, 30)
        require(header.getInt(0) == LOCAL_SIGNATURE) { "Bad local header for ${entry.name}" }
        val dataStart = entry.localHeader + 30 + header.getShort(26).toUShort().toLong() + header.getShort(28).toUShort().toLong()
        val raw = readAt(dataStart, entry.compressedSize.toInt()).array()
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> Inflater(true).run {
                try {
                    setInput(raw)
                    val out = ByteArray(entry.size.toInt())
                    var length = 0
                    while (length < out.size && !finished()) {
                        val count = inflate(out, length, out.size - length)
                        if (count == 0 && (needsInput() || needsDictionary())) break
                        length += count
                    }
                    out
                } finally {
                    end()
                }
            }
            else -> error("Unsupported ZIP method ${entry.method} for ${entry.name}")
        }
    }

    override fun close() {
        channel.close()
        owner.close()
    }

    private fun readCentralDirectory(): List<Entry> {
        val size = channel.size()
        // End of central directory: last 22 bytes plus an optional comment of up to 64 KiB.
        val tailLength = minOf(size, 22L + 0xFFFF).toInt()
        val tail = readAt(size - tailLength, tailLength)
        val eocd = (tailLength - 22 downTo 0).firstOrNull { tail.getInt(it) == END_SIGNATURE }
            ?: error("Not a ZIP archive")
        val count = tail.getShort(eocd + 10).toUShort().toInt()
        val dirSize = tail.getInt(eocd + 12).toUInt().toLong()
        val dirOffset = tail.getInt(eocd + 16).toUInt().toLong()
        val dir = readAt(dirOffset, dirSize.toInt())
        var position = 0
        return List(count) {
            require(dir.getInt(position) == CENTRAL_SIGNATURE) { "Bad central directory" }
            val nameLength = dir.getShort(position + 28).toUShort().toInt()
            val extraLength = dir.getShort(position + 30).toUShort().toInt()
            val commentLength = dir.getShort(position + 32).toUShort().toInt()
            val name = String(dir.array(), position + 46, nameLength, Charsets.UTF_8)
            val entry = Entry(
                name = name,
                method = dir.getShort(position + 10).toUShort().toInt(),
                compressedSize = dir.getInt(position + 20).toUInt().toLong(),
                size = dir.getInt(position + 24).toUInt().toLong(),
                localHeader = dir.getInt(position + 42).toUInt().toLong()
            )
            position += 46 + nameLength + extraLength + commentLength
            entry
        }
    }

    private fun readAt(offset: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var at = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            require(read > 0) { "Unexpected end of ZIP" }
            at += read
        }
        buffer.flip()
        return buffer
    }

    private companion object {
        const val LOCAL_SIGNATURE = 0x04034b50
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val END_SIGNATURE = 0x06054b50
        const val STORED = 0
        const val DEFLATED = 8
    }
}
