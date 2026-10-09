package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionUnitTest {
    @Test
    fun characterOffsetsAreShownAndEnteredAsIs() {
        assertEquals(600, PositionUnit.CHARACTER.display(600, 300))
        assertEquals(650, PositionUnit.CHARACTER.through(650, 300))
        assertEquals(42, PositionUnit.CHARACTER.toOffset(42, 300))
        assertNull(PositionUnit.CHARACTER.toOffset(-1, 300))
    }

    @Test
    fun pageOffsetsAreOneBasedPageNumbers() {
        // PDF page n (0-based) is stored at n * chunkSize.
        assertEquals(1, PositionUnit.PAGE.display(0, 300))
        assertEquals(3, PositionUnit.PAGE.display(600, 300))
        // After sending pages 1..3 the transfer is at 900: "sent through page 3".
        assertEquals(3, PositionUnit.PAGE.through(900, 300))
        assertEquals(600, PositionUnit.PAGE.toOffset(3, 300))
        assertNull(PositionUnit.PAGE.toOffset(0, 300))
    }

    @Test
    fun chapterPositionsAreOneBasedChapterNumbers() {
        assertEquals(3, PositionUnit.CHAPTER.display(1234, 300, chapter = 2))
        assertEquals(3, PositionUnit.CHAPTER.through(1500, 300, chapter = 2))
    }

    @Test
    fun backendsChooseTheirPositionUnit() {
        assertEquals(PositionUnit.PAGE, DocumentFormats.forName("scan.pdf").positionUnit)
        assertEquals(PositionUnit.CHAPTER, DocumentFormats.forName("book.epub").positionUnit)
        assertEquals(PositionUnit.CHARACTER, DocumentFormats.forName("notes.txt").positionUnit)
    }

    @Test
    fun richPdfShowsPagesWithChapterPositions() {
        val unit = DocumentFormats.forMime(MimeType.PDF, "PdfRichFormat").positionUnit
        assertEquals(PositionUnit.PAGE_CHAPTER, unit)
        assertEquals(R.string.page_value, unit.value)
        assertEquals(3, unit.display(1234, 300, chapter = 2))
        assertEquals(0, unit.toOffset(3, 300))
        assertEquals(null, unit.toOffset(0, 300))
    }
}
