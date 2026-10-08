package top.lighilit.watch_data_sync

/** An encoded image ready to send; [width] and [height] are its pixel size (0 if unknown). */
internal data class ReadingImage(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    val alt: String,
    val width: Int = 0,
    val height: Int = 0
)
