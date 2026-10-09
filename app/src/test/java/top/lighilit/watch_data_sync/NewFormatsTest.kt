package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NewFormatsTest {
    @Test
    fun cbzAndFb2AreDetectedAndRegistered() {
        assertEquals(MimeType.CBZ, MimeType.fromName("comic.CBZ"))
        assertEquals(MimeType.FB2, MimeType.fromName("book.fb2"))
        assertEquals(listOf("CbzFormat"), DocumentFormats.backendsFor(MimeType.CBZ).map { it.id })
        assertEquals(listOf("Fb2Format"), DocumentFormats.backendsFor(MimeType.FB2).map { it.id })
        assertEquals(PositionUnit.PAGE, DocumentFormats.forMime(MimeType.CBZ).positionUnit)
    }

    @Test
    fun comicPagesUseNaturalOrder() {
        val names = listOf("p10.jpg", "p2.jpg", "p1.jpg", "cover.jpg", "p02b.jpg")
        assertEquals(listOf("cover.jpg", "p1.jpg", "p2.jpg", "p02b.jpg", "p10.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun fb2SectionsBecomeChaptersWithTheirImages() {
        val xml = """
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <body>
                <section><title><p>One</p></title><p>Hello</p><image l:href="#pic"/></section>
                <section><p>Second</p></section>
              </body>
              <body name="notes"><section><p>note</p></section></body>
              <binary id="pic" content-type="image/png">AAEC</binary>
            </FictionBook>
        """.trimIndent()
        val book = Fb2Book.parse(xml.byteInputStream())
        assertEquals(listOf("One", "2"), book.chapters.map { it.title })
        assertEquals(listOf("pic"), book.chapters[0].imageIds)
        assertEquals("Second", book.chapters[1].text)
    }
}
