package top.lighilit.watch_data_sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Re-encodes source images for the watch. Format backends only extract raw image bytes;
 * sizing depends on the watch, not on the document format, so it lives here.
 */
internal object WatchImageEncoder {
    private const val JPEG_QUALITY = 82
    private const val QUALITY_STEP = 8
    private const val MAX_PASSES = 6

    /** Returns a JPEG no larger than [WatchDisplay.bitmapSize], or null if undecodable/too large. */
    fun encode(image: ReadingImage, display: WatchDisplay, maxBytes: Int, reducePercent: Int): ReadingImage? {
        val bytes = image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (targetWidth, targetHeight) = display.bitmapSize(bounds.outWidth, bounds.outHeight)

        // Decode at the smallest power-of-two sample that is still >= the target, to save phone memory.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var current = scaled(decoded, targetWidth, targetHeight)
        try {
            val scale = reducePercent.coerceIn(1, 100) / 100f
            repeat(MAX_PASSES) { pass ->
                if (pass > 0) {
                    current = scaled(
                        current,
                        (current.width * scale).toInt().coerceAtLeast(1),
                        (current.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                val output = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY - pass * QUALITY_STEP, output)
                if (output.size() <= maxBytes) {
                    return image.copy(bytes = output.toByteArray(), mimeType = "image/jpeg", width = current.width, height = current.height)
                }
            }
            return null
        } finally {
            current.recycle()
        }
    }

    private fun scaled(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val next = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (next !== bitmap) bitmap.recycle()
        return next
    }
}
