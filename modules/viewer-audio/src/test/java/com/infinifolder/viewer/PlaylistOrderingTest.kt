package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderingTest {
    private data class Track(val filename: String, val title: String, val id: Int, val offset: Long = 0)
    private fun sort(tracks: MutableList<Track>, current: Int) =
        sortPlaylistByFileName(tracks, current, { it.filename }, { it.offset })

    @Test fun sortsByFilenameIgnoringCaseAndKeepsTheSelectedTrack() {
        val tracks = mutableListOf(Track("Zulu.mp3", "A title", 1), Track("apple.mp3", "Z title", 2), Track("Banana.mp3", "B title", 3))
        val current = sort(tracks, 0)
        assertEquals(listOf("apple.mp3", "Banana.mp3", "Zulu.mp3"), tracks.map { it.filename })
        assertEquals(2, current)
        assertEquals(1, tracks[current].id)
    }

    @Test fun metadataChangesDoNotChangeFileOrderOrSelection() {
        val tracks = mutableListOf(Track("02.mp3", "First", 1), Track("01.mp3", "Second", 2))
        var current = sort(tracks, 0)
        tracks[current] = tracks[current].copy(title = "A different title")
        current = sort(tracks, current)
        assertEquals(listOf("01.mp3", "02.mp3"), tracks.map { it.filename })
        assertEquals(1, tracks[current].id)
    }

    @Test fun sortsSlicesOfOneFileByOffsetAndKeepsDuplicateOccurrence() {
        val duplicate = Track("album.bin", "Same", 1, 100)
        val tracks = mutableListOf(duplicate, Track("album.bin", "Later", 2, 200), Track("album.bin", "First", 3, 0), duplicate)
        val current = sort(tracks, 3)
        assertEquals(listOf(0L, 100L, 100L, 200L), tracks.map { it.offset })
        assertEquals(2, current)
    }

    @Test fun noSelectionAndEmptyQueuesStayUnselected() {
        val tracks = mutableListOf(Track("B.mp3", "B", 1), Track("a.mp3", "A", 2))
        assertEquals(-1, sort(tracks, -1))
        assertEquals(listOf("a.mp3", "B.mp3"), tracks.map { it.filename })
        assertEquals(-1, sort(mutableListOf(), -1))
    }

    @Test fun insertionChoicePlacesTracksAfterCurrentOrAtQueueEnd() {
        assertEquals(3, playlistInsertionIndex(5, 2, PlaylistInsertion.AFTER_CURRENT))
        assertEquals(5, playlistInsertionIndex(5, 2, PlaylistInsertion.END))
        assertEquals(0, playlistInsertionIndex(0, -1, PlaylistInsertion.AFTER_CURRENT))
        assertEquals(5, playlistInsertionIndex(5, -1, PlaylistInsertion.AFTER_CURRENT))
    }

    @Test fun insertionPromptOnlyAppearsWhenChoicesDiffer() {
        assertFalse(shouldAskPlaylistInsertion(0, -1))
        assertFalse(shouldAskPlaylistInsertion(5, -1))
        assertTrue(shouldAskPlaylistInsertion(5, 2))
        assertFalse(shouldAskPlaylistInsertion(5, 4))
    }

    @Test fun metadataSortAndReverseKeepTheSelectedDuplicateOccurrence() {
        val tracks = mutableListOf(
            Track("z.mp3", "Bravo", 1),
            Track("a.mp3", "Alpha", 2),
            Track("b.mp3", "Alpha", 3),
        )
        var current = sortPlaylist(tracks, 2) { left, right ->
            String.CASE_INSENSITIVE_ORDER.compare(left.title, right.title)
        }
        assertEquals(listOf(2, 3, 1), tracks.map { it.id })
        assertEquals(1, current)
        assertEquals(3, tracks[current].id)

        current = reversePlaylist(tracks, current)
        assertEquals(listOf(1, 3, 2), tracks.map { it.id })
        assertEquals(1, current)
        assertEquals(3, tracks[current].id)
    }
}
