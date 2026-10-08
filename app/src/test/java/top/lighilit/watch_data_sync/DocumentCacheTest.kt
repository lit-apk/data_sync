package top.lighilit.watch_data_sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun documentsWithSameEntryNameDoNotShareEntries() {
        val cache = DocumentCache(temp.root, maxBytes = 1024)
        val first = cache.forDocument("content://book-a")
        val second = cache.forDocument("content://book-b")

        first.put("OEBPS/content.opf", byteArrayOf(1))

        assertNull(second.get("OEBPS/content.opf"))
        second.put("OEBPS/content.opf", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), first.get("OEBPS/content.opf"))
        assertArrayEquals(byteArrayOf(2), second.get("OEBPS/content.opf"))
    }

    @Test
    fun sameKeyReusesEntries() {
        val cache = DocumentCache(temp.root, maxBytes = 1024)
        cache.forDocument("content://book-a").put("toc.ncx", byteArrayOf(7))

        assertArrayEquals(byteArrayOf(7), cache.forDocument("content://book-a").get("toc.ncx"))
    }

    @Test
    fun sizeLimitSpansAllDocuments() {
        val cache = DocumentCache(temp.root, maxBytes = 10)
        cache.forDocument("a").put("x", ByteArray(6))
        cache.forDocument("b").put("y", ByteArray(6))

        val total = temp.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(true, total <= 10)
    }

    @Test
    fun removesEntriesFromOldUnscopedLayout() {
        temp.newFile("OEBPS_content.opf").writeBytes(byteArrayOf(9))

        DocumentCache(temp.root, maxBytes = 1024)

        assertEquals(emptyList<String>(), temp.root.listFiles()!!.filter { it.isFile }.map { it.name })
    }
}
