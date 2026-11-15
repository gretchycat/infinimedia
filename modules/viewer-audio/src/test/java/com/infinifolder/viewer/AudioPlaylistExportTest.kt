package com.infinifolder.viewer

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaylistExportTest {
    private val track = AudioTrack(
        uri = TestUri("content://infinifolder.selection/temporary-track"),
        title = "Song & One",
        artist = "Artist",
        album = "Album <Live>",
        durationMs = 123_000,
        fileName = "song.flac",
        sourceReference = "sftp://192.168.1.25/Music/Song%20%26%20One.flac",
    )

    @Test fun jspfIsFirstDefaultFormatAndKeepsAuthoritativeLocations() {
        assertEquals(AudioPlaylistExportFormat.JSPF, AudioPlaylistExportFormat.entries.first())
        val result = AudioPlaylistExportFormat.JSPF.encode(listOf(track), "Mix")
            .toString(Charsets.UTF_8)
        assertTrue(result.contains("sftp://192.168.1.25/Music/Song%20%26%20One.flac"))
        assertFalse(result.contains("content://"))
        assertEquals("Mix.jspf", AudioPlaylistExportFormat.JSPF.filename("Mix.m3u"))
    }

    @Test fun commonAlternateFormatsExportAuthoritativeUrisAndMetadata() {
        val cases = mapOf(
            AudioPlaylistExportFormat.M3U8 to "#EXTM3U",
            AudioPlaylistExportFormat.M3U to "#EXTM3U",
            AudioPlaylistExportFormat.PLS to "[playlist]",
            AudioPlaylistExportFormat.XSPF to "<?xml version=\"1.0\"",
        )
        cases.forEach { (format, header) ->
            val output = format.encode(listOf(track), "Mix").toString(Charsets.UTF_8)
            assertTrue("${format.name} header", output.startsWith(header))
            assertTrue("${format.name} URI", output.contains("sftp://192.168.1.25/Music/Song%20%26%20One.flac"))
            assertFalse("${format.name} excludes temporary content URI", output.contains("content://"))
            val parsed = com.infinifolder.audio.playlist.PlaylistParser
                .parse("mix.${format.extension}", output).single()
            assertEquals("${format.name} round-trips the source", "sftp://192.168.1.25/Music/Song%20%26%20One.flac", parsed.pathOrUri)
        }
        val xspf = AudioPlaylistExportFormat.XSPF.encode(listOf(track), "Mix").toString(Charsets.UTF_8)
        assertTrue(xspf.contains("Artist"))
        assertTrue(xspf.contains("Album &lt;Live&gt;"))
    }
}
