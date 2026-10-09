package top.lighilit.watch_data_sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class SeekableZipTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsStoredAndDeflatedEntriesBySeeking() {
        val big = Random(1).nextBytes(50_000)
        val text = "page ".repeat(5000).toByteArray()
        val file = temp.newFile("comic.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("p10.jpg")); zip.write(big); zip.closeEntry()
            zip.putNextEntry(ZipEntry("dir/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("p2.txt")); zip.write(text); zip.closeEntry()
        }
        val raf = RandomAccessFile(file, "r")
        SeekableZip(raf.channel, Closeable { raf.close() }).use { zip ->
            assertEquals(listOf("p10.jpg", "dir/", "p2.txt"), zip.entries.map { it.name })
            // Read out of order to show entries are reached by seeking, not streaming.
            assertArrayEquals(text, zip.read(zip.entries[2]))
            assertArrayEquals(big, zip.read(zip.entries[0]))
        }
    }
}
