package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class AnsiPlaybackSupportTest {
    @Test fun utf8FixtureExtractsAnsiMusicAndPlaysItsEntireDeclaredDuration() {
        val directory = System.getProperty("infini.audio.fixtures")
            ?: error("Audio test fixture path was not configured")
        val bytes = File(directory, "c_major_piano_ansi_music.ans").readBytes()
        val document = AnsiPlaybackSupport.parse(bytes, "c_major_piano_ansi_music.ans")
        assertEquals(80, document.terminal.columns)
        assertEquals("UTF-8", document.encoding)
        assertTrue(document.terminal.rows > 1)
        assertTrue(document.terminal.music.isNotEmpty())
        assertTrue(document.displayDurationMs > 0L)

        AnsiPlaybackSupport.openPcm(document).use { pcm ->
            assertEquals("ANSI Music PC speaker", pcm.decoderName)
            assertTrue("music must finish even when it outlasts the terminal output",
                pcm.format.durationMs!! > document.displayDurationMs)
            val samples = IntArray(4096)
            var framesRead = 0L
            var heardTone = false
            while (true) {
                val count = pcm.read(samples, 0, samples.size)
                if (count < 0) break
                assertTrue(count > 0)
                framesRead += count / pcm.format.channels
                if (samples.take(count).any { it != 0 }) heardTone = true
            }
            assertEquals(pcm.format.totalFrames, framesRead)
            assertTrue(heardTone)
        }
    }

    @Test fun legacyAnsiArtUsesCp437WhenItsBoxDrawingBytesAreNotUtf8() {
        val bytes = byteArrayOf(
            0xC9.toByte(), 0xCD.toByte(), 0xBB.toByte(), '\r'.code.toByte(), '\n'.code.toByte(),
            0xC8.toByte(), 0xCD.toByte(), 0xBC.toByte()
        )
        val document = AnsiPlaybackSupport.parse(bytes, "legacy.ans")
        assertEquals("CP437", document.encoding)
        assertTrue(document.terminal.text.startsWith("╔═╗"))
        assertTrue(document.terminal.text.contains("╚═╝"))
    }

    @Test fun utf8AnsiUsesUnicodeAndStillHasAnEightyColumnTerminal() {
        val bytes = "\u001b[31mCAFÉ\u001b[0m".toByteArray(StandardCharsets.UTF_8)
        val document = AnsiPlaybackSupport.parse(bytes, "utf8.ansi")
        assertEquals("UTF-8", document.encoding)
        assertEquals(80, document.terminal.columns)
        assertTrue(document.terminal.text.contains("CAFÉ"))
        assertTrue(document.terminal.runs.isNotEmpty())
    }

    @Test fun textWithoutMusicGetsSilentPcmForTheTerminalPlaybackWindow() {
        val document = AnsiPlaybackSupport.parse("plain text".toByteArray(), "plain.ans")
        assertTrue(document.terminal.music.isEmpty())
        AnsiPlaybackSupport.openPcm(document).use { pcm ->
            assertEquals(document.displayDurationMs, pcm.format.durationMs)
            val samples = IntArray(1024)
            val count = pcm.read(samples, 0, samples.size)
            assertTrue(count > 0)
            assertTrue(samples.take(count).all { it == 0 })
        }
    }

    @Test fun silentAnsiCanHandOffWhileItsVisualSequenceContinues() {
        val document = AnsiPlaybackSupport.parse("line\n".repeat(24).toByteArray(), "plain.ans")
        AnsiPlaybackSupport.openPcm(document, overlapFollowingAudio = true).use { pcm ->
            assertEquals(PlaylistVisualTiming.SILENT_ANSI_HANDOFF_MS, pcm.format.durationMs!!)
            assertTrue(pcm.format.durationMs!! < document.displayDurationMs)
        }
    }

    @Test fun ansiMusicStillPlaysToCompletionWhenNextAudioCanOverlap() {
        val directory = System.getProperty("infini.audio.fixtures")
            ?: error("Audio test fixture path was not configured")
        val bytes = File(directory, "c_major_piano_ansi_music.ans").readBytes()
        val document = AnsiPlaybackSupport.parse(bytes, "c_major_piano_ansi_music.ans")
        assertTrue(document.terminal.music.isNotEmpty())
        val originalFrames = AnsiPlaybackSupport.openPcm(document).use { it.format.totalFrames }
        val overlappingFrames = AnsiPlaybackSupport.openPcm(document, overlapFollowingAudio = true)
            .use { it.format.totalFrames }
        assertEquals(originalFrames, overlappingFrames)
    }

    @Test fun serialTimingAndOverflowScrollingUseEightyColumn9600BaudRules() {
        assertEquals(9_600, AnsiPlaybackSupport.BAUD_RATE)
        assertEquals(2_000L, AnsiPlaybackSupport.displayDurationMs(rows = 24))
        assertEquals(960, AnsiPlaybackSupport.displayedCells(elapsedMs = 1_000, totalCells = 4_000))
        assertEquals(0, AnsiPlaybackSupport.firstVisibleRow(1_920, rows = 25, columns = 80, visibleRows = 24))
        assertEquals(1, AnsiPlaybackSupport.firstVisibleRow(1_921, rows = 25, columns = 80, visibleRows = 24))
        assertEquals(2, AnsiPlaybackSupport.firstVisibleRow(2_001, rows = 30, columns = 80, visibleRows = 24))
    }

    @Test fun ansiExtensionsAndMimeTypesAreAdmittedAsVisualAudioItems() {
        assertTrue(AnsiPlaybackSupport.isAnsiFile("demo.ans"))
        assertTrue(AnsiPlaybackSupport.isAnsiFile("demo.ansi"))
        assertTrue(AnsiPlaybackSupport.isAnsiFile("demo.bin", "image/x-ansi"))
        assertFalse(AnsiPlaybackSupport.isAnsiFile("demo.nfo"))
    }
}
