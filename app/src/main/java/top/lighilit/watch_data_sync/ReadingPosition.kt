package top.lighilit.watch_data_sync

import androidx.annotation.StringRes

/**
 * How a document's reading position is shown to the user. Positions are always stored
 * as transfer offsets; image-only formats (e.g. PDF) store one page per chunk, i.e.
 * `pageIndex * chunkSize`, so their offsets mean nothing to a reader and are shown as
 * 1-based page numbers instead. Chaptered formats (EPUB) store offsets local to the
 * current chapter, so the chapter number is the meaningful position.
 */
internal enum class PositionUnit(
    @field:StringRes val label: Int,
    @field:StringRes val value: Int,
    @field:StringRes val phrase: Int
) {
    CHARACTER(R.string.character_offset_label, R.string.character_offset, R.string.position_character),
    PAGE(R.string.page_label, R.string.page_value, R.string.position_page),
    CHAPTER(R.string.chapter_label, R.string.chapter_value, R.string.position_chapter),

    /** Chaptered by page (e.g. PDF read as text): chapter math, shown as page numbers. */
    PAGE_CHAPTER(R.string.page_label, R.string.page_value, R.string.position_page);

    /** User-facing position of the part starting at stored [offset] in 0-based [chapter]. */
    fun display(offset: Int, chunkSize: Int, chapter: Int = 0): Int = when (this) {
        CHARACTER -> offset
        PAGE -> offset / chunkSize.coerceAtLeast(1) + 1
        CHAPTER, PAGE_CHAPTER -> chapter + 1
    }

    /** User-facing "sent through" position for a transfer that has reached [endOffset]. */
    fun through(endOffset: Int, chunkSize: Int, chapter: Int = 0): Int = when (this) {
        CHARACTER -> endOffset
        PAGE -> (endOffset + chunkSize.coerceAtLeast(1) - 1) / chunkSize.coerceAtLeast(1)
        CHAPTER, PAGE_CHAPTER -> chapter + 1
    }

    /**
     * Stored offset for a user-entered position, or null if it is not valid for this unit.
     * Chapters are chosen with the chapter picker; a typed value is a character offset.
     */
    fun toOffset(value: Int, chunkSize: Int): Int? = when (this) {
        CHARACTER, CHAPTER -> value.takeIf { it >= 0 }
        // The typed page selects the chapter; the position within it restarts at 0.
        PAGE_CHAPTER -> 0.takeIf { value >= 1 }
        PAGE -> value.takeIf { it >= 1 }?.let { (it - 1) * chunkSize.coerceAtLeast(1) }
    }
}
