package top.lighilit.watch_data_sync

import android.content.Context
import java.io.File

internal const val CACHE_SIZE_KEY = "cache_size"
internal const val DEFAULT_CACHE_SIZE_MB = 32
internal const val IMAGE_SIZE_KEY = "image_size_kb"
internal const val IMAGE_REDUCE_KEY = "image_reduce_percent"
internal const val DEFAULT_IMAGE_SIZE_KB = 2048
internal const val DEFAULT_IMAGE_REDUCE_PERCENT = 10

internal fun documentCache(context: Context): DocumentCache {
    val size = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(CACHE_SIZE_KEY, DEFAULT_CACHE_SIZE_MB).toLong() * 1024 * 1024
    return DocumentCache(File(context.cacheDir, context.packageName), size)
}

internal fun imageLimitBytes(context: Context): Int =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(IMAGE_SIZE_KEY, DEFAULT_IMAGE_SIZE_KB) * 1024

internal fun imageReducePercent(context: Context): Int =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(IMAGE_REDUCE_KEY, DEFAULT_IMAGE_REDUCE_PERCENT)
