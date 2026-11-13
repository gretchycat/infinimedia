package com.infinifolder.viewer

import android.net.TestUri
import com.infinifolder.audio.playlist.PlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioPlaylistJspfTest {
    @Test fun exportedPlaylistUsesAuthoritativeLocationsInsteadOfTemporaryRelayLinks() {
        val track = AudioTrack(
            uri = TestUri("content://infinifolder.selection/temporary-track"),
            title = "Track",
            fileName = "track.mp3",
            sourceReference = "sftp://192.168.1.25/Music/Album/track.mp3",
            directoryUri = TestUri("content://com.infinifolder.documents/document/stable-directory"),
            directoryReference = "sftp://192.168.1.25/Music/Album",
            directoryCoverUri = TestUri("content://infinifolder.selection/temporary-cover"),
            directoryCoverReference = "sftp://192.168.1.25/Music/Album/cover.jpg",
        )

        val saved = AudioPlaylistJspf.encode(listOf(track), "References")
            .toString(Charsets.UTF_8)
        val restored = PlaylistParser.parse("references.jspf", saved).single()

        assertEquals("sftp://192.168.1.25/Music/Album/track.mp3", restored.pathOrUri)
        assertEquals("sftp://192.168.1.25/Music/Album/track.mp3", restored.sourceReference)
        assertEquals("sftp://192.168.1.25/Music/Album", restored.directoryUri)
        assertEquals("sftp://192.168.1.25/Music/Album", restored.directoryReference)
        assertEquals("sftp://192.168.1.25/Music/Album/cover.jpg", restored.directoryCoverUri)
        assertEquals("sftp://192.168.1.25/Music/Album/cover.jpg", restored.directoryCoverReference)
        assertFalse(saved.contains("infinifolder.selection"))
        assertFalse(saved.contains("content://"))
    }

    @Test fun staleRelayReferenceDoesNotReplaceAnAlreadyStableTrackUri() {
        assertEquals(
            "sftp://192.168.1.25/Music/track.mp3",
            AudioPlaylistLocations.authoritative(
                TestUri("sftp://192.168.1.25/Music/track.mp3"),
            "content://infinifolder.selection/expired-track",
            ),
        )
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
