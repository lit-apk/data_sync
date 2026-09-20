package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTextExtractorTest {
    @Test
    fun extractsTextInSpineOrder() {
        val epub = buildEpub(
            manifest = mapOf(
                "ch1" to "text/ch1.xhtml",
                "ch2" to "text/ch2.xhtml"
            ),
            spine = listOf("ch1", "ch2"),
            chapters = mapOf(
                "OEBPS/text/ch1.xhtml" to
                    "<html><head><title>ignored</title></head><body><h1>Chapter One</h1>" +
                    "<p>Hello &amp; welcome.</p><p>Second line.</p></body></html>",
                "OEBPS/text/ch2.xhtml" to
                    "<html><body><p>Next chapter.</p></body></html>"
            )
        )

        val text = EpubTextExtractor.extract(ByteArrayInputStream(epub))

        assertEquals(
            "Chapter One\n\nHello & welcome.\n\nSecond line.\n\nNext chapter.",
            text
        )
    }

    private fun buildEpub(
        manifest: Map<String, String>,
        spine: List<String>,
        chapters: Map<String, String>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                "<?xml version=\"1.0\"?>" +
                    "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
            )
            val manifestXml = manifest.entries.joinToString("") { (id, href) ->
                "<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>"
            }
            val spineXml = spine.joinToString("") { "<itemref idref=\"$it\"/>" }
            put(
                "OEBPS/content.opf",
                "<?xml version=\"1.0\"?>" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
                    "<manifest>$manifestXml</manifest><spine>$spineXml</spine></package>"
            )
            chapters.forEach { (name, content) -> put(name, content) }
        }
        return output.toByteArray()
    }
}
