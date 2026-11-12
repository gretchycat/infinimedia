package com.infinifolder.viewer

import com.infinifolder.audio.AnsiMusic
import com.infinifolder.audio.PcmFormat
import com.infinifolder.audio.PcmStream
import io.github.gretchycat.infiniscribe.DosTextDocument
import io.github.gretchycat.infiniscribe.DosTextDocuments
import io.github.gretchycat.infiniscribe.DosTextEncoding
import io.github.gretchycat.infinistream.ByteSource
import io.github.gretchycat.infinistream.SourceResource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays

internal data class AnsiPlaybackDocument(
    val terminal: DosTextDocument,
    val encoding: String,
    val displayDurationMs: Long
)

/** Bridges the shared DOS terminal parser and ANSI Music synthesizer for Infinimedia. */
internal object AnsiPlaybackSupport {
    const val BAUD_RATE = 9_600
    const val NEXT_ITEM_PAUSE_MS = 2_000L
    const val COLUMNS = 80
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val SERIAL_BITS_PER_CHARACTER = 10 // start bit + 8 data bits + stop bit
    val extensions = setOf("ans", "ansi")
    val mimeTypes = setOf("text/x-ansi", "image/x-ansi", "text/x-ascii-art")

    fun isAnsiFile(name: String, mimeType: String? = null): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        return extension in extensions || mime in mimeTypes
    }

    fun read(resource: SourceResource, name: String): AnsiPlaybackDocument {
        val declaredLength = resource.metadata.length
        if (declaredLength != null && declaredLength > MAX_FILE_BYTES) {
            throw IOException("ANSI text exceeds the 4 MiB playback limit")
        }
        return parse(resource.open().use(::readBounded), name)
    }

    fun parse(bytes: ByteArray, name: String = "art.ans"): AnsiPlaybackDocument {
        if (bytes.size > MAX_FILE_BYTES) throw IOException("ANSI text exceeds the 4 MiB playback limit")
        val detection = DosTextEncoding.detect(bytes, name)
        val terminal = DosTextDocuments.parse(bytes, columns = COLUMNS)
        val durationMs = displayDurationMs(terminal.rows, terminal.columns)
        val encoding = if (detection.charset == Charsets.UTF_8) "UTF-8" else "CP437"
        return AnsiPlaybackDocument(terminal, encoding, durationMs)
    }

    private fun readBounded(source: ByteSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("ANSI read cancelled")
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) throw IOException("ANSI source returned an empty read")
            if (output.size().toLong() + count > MAX_FILE_BYTES) {
                throw IOException("ANSI text exceeds the 4 MiB playback limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** 9600 baud with 8N1 framing carries about 960 terminal cells per second. */
    fun displayDurationMs(rows: Int, columns: Int = COLUMNS): Long {
        require(rows >= 0 && columns > 0)
        val cells = rows.toLong() * columns
        val numerator = cells * SERIAL_BITS_PER_CHARACTER * 1_000L
        return (numerator + BAUD_RATE - 1) / BAUD_RATE
    }

    fun displayedCells(elapsedMs: Long, totalCells: Int): Int {
        if (elapsedMs <= 0L || totalCells <= 0) return 0
        val cells = elapsedMs * BAUD_RATE / (SERIAL_BITS_PER_CHARACTER * 1_000L)
        return cells.coerceAtMost(totalCells.toLong()).toInt()
    }

    fun firstVisibleRow(completedCells: Int, rows: Int, columns: Int, visibleRows: Int): Int {
        if (rows <= 0 || visibleRows <= 0) return 0
        val completedRows = (completedCells.coerceAtLeast(0) + columns - 1) / columns
        return (completedRows - visibleRows).coerceAtLeast(0).coerceAtMost((rows - visibleRows).coerceAtLeast(0))
    }

    /** A melody continues while the terminal is being typed; silence fills any remaining display time. */
    fun openPcm(document: AnsiPlaybackDocument): PcmStream {
        val melody = document.terminal.music.takeIf { it.isNotEmpty() }?.let(AnsiMusic::open)
        return TimedPcmStream(melody, document.displayDurationMs)
    }

    private class TimedPcmStream(private val melody: PcmStream?, minimumDurationMs: Long) : PcmStream {
        private val sampleRate = melody?.format?.sampleRate ?: 8_000
        private val channels = melody?.format?.channels ?: 1
        private val bitsPerSample = melody?.format?.bitsPerSample ?: 16
        private val melodyFrames = melody?.format?.totalFrames
            ?: if (melody == null) 0L else throw IOException("ANSI Music duration is unknown")
        private val totalFrames = maxOf(melodyFrames, (minimumDurationMs * sampleRate + 999) / 1_000)
        override val format = PcmFormat(sampleRate, channels, totalFrames, bitsPerSample)
        override val decoderName = melody?.decoderName ?: "ANSI terminal silence"
        override val sourceFormat = melody?.sourceFormat ?: "ANSI terminal"
        private var frame = 0L
        private var closed = false

        @Synchronized override fun read(samples: IntArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && length <= samples.size - offset)
            require(length % channels == 0) { "ANSI PCM reads must contain complete frames" }
            if (closed) throw IOException("ANSI PCM stream is closed")
            if (length == 0) return 0
            if (frame >= totalFrames) return -1
            val wanted = (minOf((length / channels).toLong(), totalFrames - frame) * channels).toInt()
            var written = 0
            while (written < wanted && frame < melodyFrames) {
                val framesToRead = minOf(((wanted - written) / channels).toLong(), melodyFrames - frame).toInt()
                val count = melody!!.read(samples, offset + written, framesToRead * channels)
                if (count <= 0 || count % channels != 0) throw IOException("ANSI Music ended before its declared duration")
                written += count
                frame += count / channels
            }
            if (written < wanted) {
                Arrays.fill(samples, offset + written, offset + wanted, 0)
                frame += (wanted - written) / channels
                written = wanted
            }
            return written
        }

        @Synchronized override fun close() {
            if (!closed) {
                closed = true
                melody?.close()
            }
        }
    }
}
