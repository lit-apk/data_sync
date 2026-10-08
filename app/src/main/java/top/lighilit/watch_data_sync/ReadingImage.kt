package top.lighilit.watch_data_sync

internal data class ReadingImage(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    val alt: String
)
