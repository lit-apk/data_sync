package top.lighilit.watch_data_sync

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDisplayTest {
    @Test
    fun bitmapWidthIsTheContentWidthWithoutPixelRatio() {
        assertEquals(406, WatchDisplay(contentWidth = 406).bitmapWidth())
        assertEquals(406, WatchDisplay.DEFAULT.bitmapWidth())
    }

    @Test
    fun bitmapWidthIncludesTheWatchPixelRatio() {
        // Observed: a 406px bitmap was laid out 313 layout px wide (ratio ~1.297), so a
        // 406 layout-px card needs a ~527px bitmap.
        assertEquals(527, WatchDisplay(contentWidth = 406, pixelRatio = 406.0 / 313).bitmapWidth())
    }
}
