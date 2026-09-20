package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferTest {
    @Test
    fun sendsEachPartInOrder() {
        val transfer = FileTransfer("abcdefgh", chunkSize = 3)

        assertEquals(FileTransfer.Part("abc", 1, 3), transfer.pendingPart())
        assertEquals(FileTransfer.Part("abc", 1, 3), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("def", 2, 3), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("gh", 3, 3), transfer.pendingPart())
        transfer.markSent()
        assertNull(transfer.pendingPart())
        assertEquals(3, transfer.sentParts)
        assertEquals(true, transfer.isComplete)
    }

    @Test
    fun emptyFileStillHasOnePart() {
        val transfer = FileTransfer("", chunkSize = 3)

        assertEquals(FileTransfer.Part("", 1, 1), transfer.pendingPart())
        transfer.markSent()
        assertNull(transfer.pendingPart())
    }

    @Test
    fun doesNotSplitUnicodeSurrogatePair() {
        val transfer = FileTransfer("ab\uD83D\uDE00cd", chunkSize = 3)

        assertEquals("ab", transfer.pendingPart()?.text)
        transfer.markSent()
        assertEquals("\uD83D\uDE00c", transfer.pendingPart()?.text)
    }
}
