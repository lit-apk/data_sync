package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDisplayTest {
    private val round = WatchDisplay(466, 466, round = true, contentWidth = 374)
    private val rect = WatchDisplay(336, 480, round = false, contentWidth = 244)

    @Test
    fun rectangularScreenUsesFullContentWidth() {
        assertEquals(244 to 122, rect.imageSize(800, 400))
        assertEquals(244 to 488, rect.imageSize(100, 200))
    }

    @Test
    fun roundScreenTallImageUsesTwoThirdsOfDiameter() {
        // Inscribed width for 1:2 is 466/sqrt(5) ~ 208, below the 2/3 floor (~311).
        assertEquals(311 to 622, round.imageSize(100, 200))
    }

    @Test
    fun roundScreenSquareImageUsesInscribedSquare() {
        // 466/sqrt(2) ~ 330.
        assertEquals(330 to 330, round.imageSize(500, 500))
    }

    @Test
    fun roundScreenWideImageIsCappedAtContentWidth() {
        // Inscribed width for 4:1 is ~452, wider than the content area.
        assertEquals(374 to 94, round.imageSize(400, 100))
    }

    @Test
    fun bitmapIsDownscaledToDisplayedWidth() {
        // 1500x1000 used to be sent nearly as-is (~6 MB decoded on the watch).
        assertEquals(374 to 249, round.bitmapSize(1500, 1000))
    }

    @Test
    fun bitmapNeverUpscales() {
        assertEquals(120 to 80, round.bitmapSize(120, 80))
    }

    @Test
    fun tallBitmapIsCappedAtOneScreenOfPixels() {
        val (width, height) = round.bitmapSize(1000, 6000)
        assert(width.toLong() * height <= 466L * 466) { "${width}x$height exceeds one screen" }
        assertEquals(6.0, height.toDouble() / width, 0.05)
    }
}
