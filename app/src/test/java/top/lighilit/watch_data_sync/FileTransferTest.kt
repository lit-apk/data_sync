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

    private fun imageSource(text: String, images: Int) = object : TextSource {
        val content = StringTextSource(text)
        override fun readPart(startOffset: Int, maxChars: Int) = content.readPart(startOffset, maxChars)
        override fun imageCount() = images
        override fun imagesAt(offset: Int, pageSize: Int) =
            listOf(ReadingImage("img-${offset / pageSize}", ByteArray(0), "image/jpeg", ""))
    }

    @Test
    fun reportsWhetherNextPartIsImageOnly() {
        // Pages: "abc"+img0, "d"+img1, img2, img3.
        val transfer = FileTransfer(imageSource("abcd", 4), chunkSize = 3)
        transfer.pendingPart()
        assertEquals(false, transfer.nextIsImageOnly())
        transfer.markSent()
        transfer.pendingPart()
        assertEquals(true, transfer.nextIsImageOnly())
        transfer.markSent()
        transfer.pendingPart()
        assertEquals(true, transfer.nextIsImageOnly())
        transfer.markSent()
        transfer.pendingPart()
        assertEquals(false, transfer.nextIsImageOnly())
    }

    @Test
    fun skipImageOnlyPartsDiscardsRemainingImages() {
        val transfer = FileTransfer(imageSource("abcd", 4), chunkSize = 3)
        repeat(2) { transfer.pendingPart(); transfer.markSent() }

        transfer.skipImageOnlyParts()

        assertNull(transfer.pendingPart())
        assertEquals(true, transfer.isComplete)
    }

    @Test
    fun skipImageOnlyPartsKeepsNextTextPart() {
        val transfer = FileTransfer(imageSource("abcdef", 4), chunkSize = 3)

        transfer.skipImageOnlyParts()

        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.pendingPart())
    }

    @Test
    fun continuesWithImageOnlyPagesAfterTextEnds() {
        val source = object : TextSource {
            val text = StringTextSource("abcd")
            override fun readPart(startOffset: Int, maxChars: Int) = text.readPart(startOffset, maxChars)
            override fun imageCount() = 4
            override fun imagesAt(offset: Int, pageSize: Int) =
                listOf(ReadingImage("img-${offset / pageSize}", ByteArray(0), "image/jpeg", ""))
        }
        val transfer = FileTransfer(source, chunkSize = 3)
        val imageIds = mutableListOf<String>()

        while (true) {
            val part = transfer.pendingPart() ?: break
            imageIds += transfer.imagesAt(part.startOffset).single().id
            transfer.markSent()
        }

        assertEquals(listOf("img-0", "img-1", "img-2", "img-3"), imageIds)
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

    @Test
    fun savedOffsetMatchesRequestedPageStart() {
        val content = "x".repeat(700)
        val transfer = FileTransfer(content, chunkSize = 300)

        // First read: request page 1 -> start offset is 0.
        val firstPage = transfer.pendingPart()!!
        assertEquals(0, firstPage.startOffset)
        transfer.markSent()

        // Request page 2 -> start offset is 1 * msg_size.
        val secondPage = transfer.pendingPart()!!
        assertEquals(300, secondPage.startOffset)
        transfer.markSent()

        // Request page 3 -> start offset is 2 * msg_size.
        val thirdPage = transfer.pendingPart()!!
        assertEquals(600, thirdPage.startOffset)
    }

    @Test
    fun previousPartReturnsLastSentPage() {
        val transfer = FileTransfer("abcdefgh", chunkSize = 3)

        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("def", 3, 6), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("gh", 6, 8), transfer.pendingPart())
        transfer.markSent()

        assertEquals(FileTransfer.Part("def", 3, 6), transfer.previousPart())
        transfer.markPreviousSent()
        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.previousPart())
        transfer.markPreviousSent()

        assertNull(transfer.previousPart())
    }

    @Test
    fun nextAfterPreviousContinuesForward() {
        val transfer = FileTransfer("abcdefgh", chunkSize = 3)

        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("def", 3, 6), transfer.pendingPart())
        transfer.markSent()

        assertEquals(FileTransfer.Part("abc", 0, 3), transfer.previousPart())
        transfer.markPreviousSent()

        assertEquals(FileTransfer.Part("def", 3, 6), transfer.pendingPart())
        transfer.markSent()
        assertEquals(FileTransfer.Part("gh", 6, 8), transfer.pendingPart())
    }

}
