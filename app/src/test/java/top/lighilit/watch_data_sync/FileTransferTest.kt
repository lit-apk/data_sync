package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferTest {
    @Test
    fun sendsEachPartInOrder() {
        val transfer = FileTransfer("abcdefgh", chunkSize = 3)

        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.pendingPart())
        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("def", 3, 6), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("gh", 6, 8), transfer.pendingPart())
        transfer.markSent()
        assertNull(transfer.pendingPart())
        assertEquals(3, transfer.sentParts)
        assertEquals(true, transfer.isComplete)
    }

    @Test
    fun emptyFileStillHasOnePart() {
        val transfer = FileTransfer("", chunkSize = 3)

        assertNull(transfer.pendingPart())
    }

    @Test
    fun doesNotSplitUnicodeSurrogatePair() {
        val transfer = FileTransfer("ab\uD83D\uDE00cd", chunkSize = 3)

        assertEquals("ab", transfer.pendingPart()?.text)
        transfer.markSent()
        assertEquals("\uD83D\uDE00c", transfer.pendingPart()?.text)
    }

    @Test
    fun resumesFromCharacterOffsetWithNewChunkSize() {
        val transfer = FileTransfer("abcdefghij", chunkSize = 4, startOffset = 3)

        assertEquals(FileTransfer.Part("defg", 3, 7), transfer.pendingPart())
        transfer.markSent()
        assertEquals(7, transfer.currentOffset)
    }
}
