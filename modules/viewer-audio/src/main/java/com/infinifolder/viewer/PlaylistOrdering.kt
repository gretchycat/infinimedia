package com.infinifolder.viewer

internal enum class PlaylistInsertion { AFTER_CURRENT, END }

internal enum class PlaylistSortOrder { FILE_NAME, TITLE, ARTIST, ALBUM }

/** The insertion chooser only matters when after-current and append differ. */
internal fun shouldAskPlaylistInsertion(trackCount: Int, currentIndex: Int): Boolean =
    trackCount > 0 && currentIndex in 0 until trackCount && currentIndex < trackCount - 1

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
): Int = sortPlaylist(items, currentIndex) { left, right ->
    val nameOrder = String.CASE_INSENSITIVE_ORDER.compare(fileName(left).trim(), fileName(right).trim())
    if (nameOrder != 0) nameOrder else offset(left).compareTo(offset(right))
}

/** Stable sort that keeps the exact selected occurrence selected, even for duplicates. */
internal fun <T> sortPlaylist(items: MutableList<T>, currentIndex: Int, compare: (T, T) -> Int): Int {
    val order = items.withIndex().sortedWith { left, right ->
        compare(left.value, right.value).takeIf { it != 0 } ?: left.index.compareTo(right.index)
    }
    items.clear()
    items.addAll(order.map { it.value })
    return if (currentIndex < 0) -1 else order.indexOfFirst { it.index == currentIndex }
}

/** Reverse the visible queue while preserving the selected occurrence. */
internal fun <T> reversePlaylist(items: MutableList<T>, currentIndex: Int): Int {
    if (currentIndex !in items.indices) {
        items.reverse()
        return -1
    }
    items.reverse()
    return items.lastIndex - currentIndex
}
