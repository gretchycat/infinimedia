package com.infinifolder.viewer

/** Common interchange formats offered by the playlist Save control. */
internal enum class AudioPlaylistExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    JSPF("JSPF — InfiniMedia (recommended)", "jspf", AudioPlaylistJspf.MIME_TYPE),
    M3U8("M3U8 — UTF-8 extended playlist", "m3u8", "application/vnd.apple.mpegurl"),
    M3U("M3U — Extended playlist", "m3u", "audio/x-mpegurl"),
    PLS("PLS — Winamp playlist", "pls", "audio/x-scpls"),
    XSPF("XSPF — XML Shareable Playlist", "xspf", "application/xspf+xml");

    fun encode(tracks: List<AudioTrack>, title: String): ByteArray = when (this) {
        JSPF -> AudioPlaylistJspf.encode(tracks, title)
        M3U8, M3U -> encodeM3u(tracks).toByteArray(Charsets.UTF_8)
        PLS -> encodePls(tracks).toByteArray(Charsets.UTF_8)
        XSPF -> encodeXspf(tracks, title).toByteArray(Charsets.UTF_8)
    }

    fun filename(baseName: String): String {
        val clean = baseName.trim().ifBlank { "InfiniMedia Playlist" }
        val existingExtension = clean.substringAfterLast('.', "").lowercase()
        val base = if (entries.any { it.extension == existingExtension }) clean.substringBeforeLast('.') else clean
        val safeBase = base.ifBlank { "InfiniMedia Playlist" }
        return "$safeBase.$extension"
    }

    private fun encodeM3u(tracks: List<AudioTrack>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            val location = track.authoritativeLocation()
            val title = listOfNotNull(track.artist?.takeIf(String::isNotBlank), track.title)
                .joinToString(" - ").replace('\r', ' ').replace('\n', ' ')
            val seconds = if (track.durationMs > 0L) (track.durationMs / 1000L).toString() else "-1"
            appendLine("#EXTINF:$seconds,$title")
            appendLine(location)
        }
    }

    private fun encodePls(tracks: List<AudioTrack>): String = buildString {
        appendLine("[playlist]")
        tracks.forEachIndexed { index, track ->
            val number = index + 1
            appendLine("File$number=${track.authoritativeLocation().singleLine()}")
            appendLine("Title$number=${track.title.singleLine()}")
            appendLine("Length$number=${if (track.durationMs > 0L) track.durationMs / 1000L else -1}")
        }
        appendLine("NumberOfEntries=${tracks.size}")
        appendLine("Version=2")
    }

    private fun encodeXspf(tracks: List<AudioTrack>, title: String): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">")
        appendLine("  <title>${title.xmlEscaped()}</title>")
        appendLine("  <trackList>")
        tracks.forEach { track ->
            appendLine("    <track>")
            appendLine("      <location>${track.authoritativeLocation().xmlEscaped()}</location>")
            appendLine("      <title>${track.title.xmlEscaped()}</title>")
            track.artist?.takeIf(String::isNotBlank)?.let { appendLine("      <creator>${it.xmlEscaped()}</creator>") }
            track.album?.takeIf(String::isNotBlank)?.let { appendLine("      <album>${it.xmlEscaped()}</album>") }
            track.durationMs.takeIf { it > 0L }?.let { appendLine("      <duration>$it</duration>") }
            appendLine("    </track>")
        }
        appendLine("  </trackList>")
        append("</playlist>\n")
    }

    private fun AudioTrack.authoritativeLocation(): String =
        AudioPlaylistLocations.authoritative(uri, sourceReference)
            ?: throw IllegalArgumentException("Cannot export a playlist entry without an authoritative source URI")

    private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ')

    private fun String.xmlEscaped(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
