package top.lighilit.watch_data_sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Re-encodes source images for the watch. Format backends only extract raw image bytes;
 * sizing depends on the watch, not on the document format, so it lives here.
 */
internal object WatchImageEncoder {
    private const val JPEG_QUALITY = 82
    private const val MAX_FIT_PASSES = 6

    /** Margin below the exact size estimate, so a fitting pass usually succeeds first time. */
    private const val FIT_MARGIN = 0.9

    /**
     * Encodes one JPEG, aspect ratio preserved (tall images are scrolled, not split),
     * starting from the full-sharpness width [WatchDisplay.bitmapWidth], then:
     * - larger than [maxBytes] (image size limit): shrink until it fits [maxBytes];
     * - otherwise: always shrink to [reducePercent]% of the width and height (reduce
     *   factor; 100 = unchanged), to save transfer time and watch memory.
     * The bitmap may end up narrower than the card; the watch sizes the image box from
     * its aspect ratio, so it is still shown exactly card-wide (upscaled).
     * Returns null if undecodable or it cannot fit [maxBytes].
     */
    fun encode(image: ReadingImage, display: WatchDisplay, maxBytes: Int, reducePercent: Int): ReadingImage? {
        val bytes = image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = display.bitmapWidth()
        val targetHeight = (bounds.outHeight.toLong() * targetWidth / bounds.outWidth).toInt().coerceAtLeast(1)

        // Decode at the smallest power-of-two sample that is still >= the target, to save phone memory.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var current = scaled(decoded, targetWidth, targetHeight)
        try {
            var encoded = compress(current)
            if (encoded.size > maxBytes) {
                // JPEG size grows roughly with pixel count, so scale both sides by
                // sqrt(limit / size) and retry until it fits.
                var pass = 0
                while (encoded.size > maxBytes) {
                    if (++pass > MAX_FIT_PASSES) return null
                    current = scaledBy(current, sqrt(maxBytes.toDouble() / encoded.size) * FIT_MARGIN)
                    encoded = compress(current)
                }
            } else {
                // The reduce factor is the share of the width (and height) that is kept.
                val keep = reducePercent.coerceIn(1, 100) / 100.0
                if (keep < 1.0) {
                    current = scaledBy(current, keep)
                    encoded = compress(current)
                }
            }
            return image.copy(bytes = encoded, mimeType = "image/jpeg", width = current.width, height = current.height)
        } finally {
            current.recycle()
        }
    }

    /**
     * Short hex SHA-1 of the encoded image, used as its file name on the watch. Same picture
     * -> same name (a cached copy is correct); different picture -> different name (a stale
     * cached copy can never be shown).
     */
    fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }

    private fun compress(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        return output.toByteArray()
    }

    private fun scaledBy(bitmap: Bitmap, factor: Double): Bitmap = scaled(
        bitmap,
        (bitmap.width * factor).toInt().coerceAtLeast(1),
        (bitmap.height * factor).toInt().coerceAtLeast(1)
    )

    private fun scaled(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val next = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (next !== bitmap) bitmap.recycle()
        return next
    }
}
