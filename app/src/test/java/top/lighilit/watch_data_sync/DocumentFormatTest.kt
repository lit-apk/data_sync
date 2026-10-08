package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun epubUsesEpubBackendInsteadOfPlainTextFallback() {
        val format = DocumentFormats.forName("book.epub")

        assertTrue(format.richText)
        assertTrue(format.chaptered)
        assertEquals(listOf("application/epub+zip"), format.mimeTypes)
    }

    @Test
    fun pdfUsesImageOnlyBackend() {
        val format = DocumentFormats.forName("Scan.PDF")

        assertEquals(listOf("application/pdf"), format.mimeTypes)
        assertEquals(false, format.chaptered)
        assertEquals(false, format.skipImages)
        assertTrue(DocumentFormats.mimeTypes().contains("application/pdf"))
    }

    @Test
    fun ordinaryTextUsesPlainTextFallback() {
        val format = DocumentFormats.forName("notes.txt")

        assertEquals(listOf("text/*", "application/json"), format.mimeTypes)
        assertEquals(false, format.richText)
        assertEquals(false, format.chaptered)
    }
}
