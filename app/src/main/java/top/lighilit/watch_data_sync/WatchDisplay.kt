package top.lighilit.watch_data_sync

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Image geometry reported by the watch with the protocol handshake and every action
 * (every action, so a restarted phone app never sizes images with stale values).
 */
internal data class WatchDisplay(
    /** Width of the watch's reading card, in watch layout pixels (measured by the watch). */
    val contentWidth: Int,
    /**
     * Bitmap pixels per layout pixel: the watch lays an image out at its pixel size divided
     * by this. Ignoring it was the "image is cut" bug: a 406px bitmap was laid out 313 px
     * wide (~1.3) and covered only ~77% of the card.
     */
    val pixelRatio: Double = 1.0
) {
    /**
     * Width to encode an image at so the watch lays it out exactly [contentWidth] wide
     * (upscaling small images too).
     */
    fun bitmapWidth(): Int = (contentWidth * pixelRatio).roundToInt().coerceAtLeast(1)

    companion object {
        /** Used until the watch reports: a 466px watch minus the 2 x 30px page padding. */
        val DEFAULT = WatchDisplay(contentWidth = 466 - 60)

        fun fromMessage(message: JSONObject): WatchDisplay? {
            val width = message.optInt("contentWidth", 0).takeIf { it > 0 } ?: return null
            return WatchDisplay(
                contentWidth = width,
                pixelRatio = message.optDouble("pixelRatio", 1.0).takeIf { it in 0.5..4.0 } ?: 1.0
            )
        }
    }
}
