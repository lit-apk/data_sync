package top.lighilit.watch_data_sync

import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Screen geometry reported by the watch during protocol negotiation.
 * All sizes are in watch CSS pixels (the watch uses `designWidth: device-width`).
 */
internal data class WatchDisplay(
    val screenWidth: Int,
    val screenHeight: Int,
    val round: Boolean,
    /** Width available to page content after the watch's own padding. */
    val contentWidth: Int
) {
    /** Display size for an image of [width] x [height] pixels; width-preferred. */
    fun imageSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return contentWidth to contentWidth
        val aspect = width.toDouble() / height
        val target = if (round) {
            // Widest rectangle of this aspect that fits the circle when centered,
            // but never narrower than 2/3 of the diameter (tall images scroll anyway).
            val inscribed = screenWidth * aspect / sqrt(1 + aspect * aspect)
            maxOf(inscribed, screenWidth * 2.0 / 3)
        } else {
            contentWidth.toDouble()
        }
        val displayWidth = target.coerceAtMost(contentWidth.toDouble()).roundToInt().coerceAtLeast(1)
        val displayHeight = (displayWidth / aspect).roundToInt().coerceAtLeast(1)
        return displayWidth to displayHeight
    }

    /**
     * Pixel size to encode an image at. The watch decodes the whole bitmap into memory,
     * so never exceed the displayed size or one screen's worth of pixels, and never upscale.
     */
    fun bitmapSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val displayWidth = imageSize(width, height).first
        val pixelBudget = screenWidth.toDouble() * screenHeight
        val scale = minOf(1.0, displayWidth.toDouble() / width, sqrt(pixelBudget / (width.toDouble() * height)))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    companion object {
        /** Used until the watch reports its screen; matches a common 466px round watch. */
        val DEFAULT = WatchDisplay(466, 466, round = true, contentWidth = 466 - 92)

        fun fromProtocol(message: JSONObject): WatchDisplay {
            val width = message.optInt("screenWidth", 0)
            if (width <= 0) return DEFAULT
            return WatchDisplay(
                screenWidth = width,
                screenHeight = message.optInt("screenHeight", width),
                round = message.optString("screenShape") == "circle",
                contentWidth = message.optInt("contentWidth", width).coerceIn(1, width)
            )
        }
    }
}
