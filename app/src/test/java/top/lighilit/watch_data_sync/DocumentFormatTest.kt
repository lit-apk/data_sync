package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun mimeTypeIsDetectedFromExtensionWithTextFallback() {
        assertEquals(MimeType.EPUB, MimeType.fromName("book.EPUB"))
        assertEquals(MimeType.PDF, MimeType.fromName("Scan.pdf"))
        assertEquals(MimeType.JSON, MimeType.fromName("data.json"))
        assertEquals(MimeType.TEXT, MimeType.fromName("notes.txt"))
        assertEquals(MimeType.TEXT, MimeType.fromName("unknown.xyz"))
    }

    @Test
    fun mimeTypeIsDetectedFromProviderType() {
        assertEquals(MimeType.EPUB, MimeType.fromType("application/epub+zip"))
        assertEquals(MimeType.TEXT, MimeType.fromType("text/markdown; charset=utf-8"))
        assertNull(MimeType.fromType("application/octet-stream"))
        assertNull(MimeType.fromType(null))
    }

    @Test
    fun registryMapsEachTypeToItsBackends() {
        assertEquals(listOf("EpubTextExtractor"), DocumentFormats.backendsFor(MimeType.EPUB).map { it.id })
        // Two PDF backends, so the chooser and history switcher appear for PDFs.
        assertEquals(listOf("PdfImageFormat", "PdfRichFormat"), DocumentFormats.backendsFor(MimeType.PDF).map { it.id })
        assertEquals(listOf("PlainTextFormat"), DocumentFormats.backendsFor(MimeType.TEXT).map { it.id })
        assertEquals(listOf("PlainTextFormat"), DocumentFormats.backendsFor(MimeType.JSON).map { it.id })
    }

    @Test
    fun storedBackendIsUsedOnlyIfItHandlesTheType() {
        assertEquals("PdfRichFormat", DocumentFormats.forMime(MimeType.PDF, "PdfRichFormat").id)
        // An unknown or mismatched stored id falls back to the type's default backend.
        assertEquals("PdfImageFormat", DocumentFormats.forMime(MimeType.PDF, "EpubTextExtractor").id)
        assertEquals("EpubTextExtractor", DocumentFormats.forMime(MimeType.EPUB, "").id)
    }

    @Test
    fun pickerOffersEveryRegisteredType() {
        val types = DocumentFormats.pickerTypes().toList()
        assertTrue(types.containsAll(listOf("text/*", "application/json", "application/epub+zip", "application/pdf")))
    }

    @Test
    fun backendsDescribeThemselvesForTheChooser() {
        assertTrue(MimeType.entries.flatMap { DocumentFormats.backendsFor(it) }.all { it.description != 0 })
    }
}
