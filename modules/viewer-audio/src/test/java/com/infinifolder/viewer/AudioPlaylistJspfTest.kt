package com.infinifolder.viewer

import android.net.TestUri
import com.infinifolder.audio.playlist.PlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaylistJspfTest {
    @Test fun exportedPlaylistRetainsStableReferencesBesideTemporaryContentLinks() {
        val track = AudioTrack(
            uri = TestUri("content://infinifolder.selection/temporary-track"),
            title = "Track",
            fileName = "track.mp3",
            sourceReference = "content://com.infinifolder.documents/document/stable-track",
            directoryUri = TestUri("content://com.infinifolder.documents/document/stable-directory"),
            directoryReference = "content://com.infinifolder.documents/document/stable-directory",
            directoryCoverUri = TestUri("content://infinifolder.selection/temporary-cover"),
            directoryCoverReference = "content://com.infinifolder.documents/document/stable-cover",
        )

        val saved = AudioPlaylistJspf.encode(listOf(track), "References")
            .toString(Charsets.UTF_8)
        val restored = PlaylistParser.parse("references.jspf", saved).single()

        assertEquals("content://infinifolder.selection/temporary-track", restored.pathOrUri)
        assertEquals("content://com.infinifolder.documents/document/stable-track", restored.sourceReference)
        assertEquals("content://com.infinifolder.documents/document/stable-directory", restored.directoryUri)
        assertEquals("content://com.infinifolder.documents/document/stable-directory", restored.directoryReference)
        assertEquals("content://infinifolder.selection/temporary-cover", restored.directoryCoverUri)
        assertEquals("content://com.infinifolder.documents/document/stable-cover", restored.directoryCoverReference)
    }

    @Test fun exportedPlaylistKeepsImageItemsAndDirectoryImageMarkers() {
        val audio = AudioTrack(
            uri = TestUri("content://media/song.mp3"),
            title = "Song",
            fileName = "song.mp3",
            directoryUri = TestUri("content://media/album"),
            directoryName = "Album",
            directoryCoverUri = TestUri("content://media/album/cover.jpg"),
            directoryArtworkMetadataResolved = true,
        )
        val image = AudioTrack(
            uri = TestUri("content://media/album/cover.png"),
            title = "Cover",
            fileName = "cover.png",
            mediaKind = PlaylistMediaKind.IMAGE,
        )

        val saved = AudioPlaylistJspf.encode(listOf(audio, image), "Album playlist")
            .toString(Charsets.UTF_8)
        val restored = PlaylistParser.parse("album.jspf", saved)

        assertEquals(2, restored.size)
        assertEquals("image", restored[1].mediaKind)
        assertEquals("image/png", restored[1].mimeType)
        assertEquals("content://media/album", restored[0].directoryUri)
        assertEquals("Album", restored[0].directoryName)
        assertEquals("content://media/album/cover.jpg", restored[0].directoryCoverUri)
        assertEquals(true, restored[0].directoryArtworkMetadataResolved)
    }
}
