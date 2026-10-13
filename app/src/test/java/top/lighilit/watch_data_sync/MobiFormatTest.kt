package top.lighilit.watch_data_sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MobiFormatTest {
    @Test
    fun mobiIsDetectedAndRegistered() {
        assertEquals(MimeType.MOBI, MimeType.fromName("book.MOBI"))
        assertEquals(MimeType.MOBI, MimeType.fromName("old.prc"))
        assertEquals(listOf("MobiFormat"), DocumentFormats.backendsFor(MimeType.MOBI).map { it.id })
    }

    @Test
    fun palmDocDecompressHandlesLiteralsSpacesAndBackReferences() {
        val input = byteArrayOf(
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            // Back-reference: distance 3, length 3 -> "abc".
            (0x80 or (3 shr 5)).toByte(), ((3 shl 3) and 0xFF or 0).toByte(),
            // Space + 'x' (0x78 ^ 0x80 = 0xF8).
            0xF8.toByte(),
            // 2 raw bytes follow.
            0x02, 0xE9.toByte(), 0x01
        )
        assertArrayEquals(
            "abcabc x".toByteArray() + byteArrayOf(0xE9.toByte(), 0x01),
            MobiBook.palmDocDecompress(input)
        )
    }

    @Test
    fun trailingSizeStripsMultibyteAndVariableLengthEntries() {
        // Text "hi", then the multibyte block (low 2 bits = 1 -> 2 bytes: 0x41 0x01), then
        // the bit-1 entry at the very end (3 bytes, size encoded backwards as 0x83).
        val data = "hi".toByteArray() + byteArrayOf(0x41, 0x01, 0x00, 0x00, 0x83.toByte())
        assertEquals(5, MobiBook.trailingSize(data, flags = 0b11))
    }

    @Test
    fun pagebreaksSplitChaptersWithTheirImages() {
        val html = "<html><body><h1>One</h1><p>Hello</p><img recindex=\"00002\">" +
            "<mbp:pagebreak/><p>Second</p></body></html>"
        val chapters = MobiBook.splitChapters(html)
        assertEquals(listOf("One", "2"), chapters.map { it.title })
        assertEquals(listOf(2), chapters[0].imageRecords)
        assertEquals("Second", chapters[1].text)
    }
}
