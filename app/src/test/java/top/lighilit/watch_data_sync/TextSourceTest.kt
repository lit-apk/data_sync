package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSourceTest {
    @Test
    fun readsOnlyRequestedTextWindow() {
        val source = PlainTextSource { "0123456789".byteInputStream() }

        assertEquals(SourcePart("3456", 7, false), source.readPart(3, 4))
        assertEquals(SourcePart("89", 10, true), source.readPart(8, 4))
        assertNull(source.readPart(10, 4))
    }

    @Test
    fun doesNotSplitSurrogatePairAtWindowBoundary() {
        val source = StringTextSource("ab\uD83D\uDE00cd")

        assertEquals(SourcePart("ab", 2, false), source.readPart(0, 3))
        assertEquals(SourcePart("\uD83D\uDE00c", 5, false), source.readPart(2, 3))
    }
}
