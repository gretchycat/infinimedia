package com.infinifolder.viewer

internal enum class PlaylistInsertion { AFTER_CURRENT, END }

/** Return the insertion point for a batch of newly selected tracks. */
internal fun playlistInsertionIndex(
    trackCount: Int,
    currentIndex: Int,
    insertion: PlaylistInsertion,
): Int = if (insertion == PlaylistInsertion.AFTER_CURRENT && currentIndex in 0 until trackCount) {
    currentIndex + 1
} else {
    trackCount
}

/** Sort by source filename, keeping the selected occurrence and ordering slices of one file. */
internal fun <T> sortPlaylistByFileName(
    items: MutableList<T>, currentIndex: Int, fileName: (T) -> String, offset: (T) -> Long = { 0L }
): Int {
    val order = items.withIndex().sortedWith { left, right ->
        val nameOrder = String.CASE_INSENSITIVE_ORDER.compare(fileName(left.value).trim(), fileName(right.value).trim())
        if (nameOrder != 0) nameOrder else offset(left.value).compareTo(offset(right.value))
    }
    items.clear()
    items.addAll(order.map { it.value })
    return if (currentIndex < 0) -1 else order.indexOfFirst { it.index == currentIndex }
}
