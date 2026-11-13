package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class SavedAudioPlaylist(
    val tracks: List<AudioTrack>,
    val originalTracks: List<AudioTrack>,
    val currentIndex: Int,
    val positionMs: Int,
    val hasManualOrder: Boolean
)

/** Durable app-private snapshot of InfiniMedia's active queue. Bitmaps are regenerated from sources. */
internal class AudioPlaylistStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(context.filesDir, "working-audio-playlist.json"))

    @Synchronized
    fun load(): SavedAudioPlaylist? = runCatching {
        val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (root.optInt("version") != VERSION) return null
        val tracks = readTracks(root.optJSONArray("tracks"))
        if (tracks.isEmpty()) return null
        val original = readTracks(root.optJSONArray("originalTracks")).ifEmpty { tracks }
        SavedAudioPlaylist(
            tracks = tracks,
            originalTracks = original,
            currentIndex = root.optInt("currentIndex", 0).coerceIn(0, tracks.lastIndex),
            positionMs = root.optInt("positionMs", 0).coerceAtLeast(0),
            hasManualOrder = root.optBoolean("hasManualOrder", false)
        )
    }.getOrNull()

    @Synchronized
    fun save(state: SavedAudioPlaylist) {
        if (state.tracks.isEmpty()) {
            clear()
            return
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("currentIndex", state.currentIndex.coerceIn(0, state.tracks.lastIndex))
            .put("positionMs", state.positionMs.coerceAtLeast(0))
            .put("hasManualOrder", state.hasManualOrder)
            .put("tracks", writeTracks(state.tracks))
            .put("originalTracks", writeTracks(state.originalTracks))
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() = file.delete()

    private fun writeTracks(tracks: List<AudioTrack>): JSONArray = JSONArray().apply {
        tracks.forEach { track ->
            val sourceLocation = AudioPlaylistLocations.authoritative(track.uri, track.sourceReference)
                ?: throw IllegalArgumentException("Cannot save a queue entry without an authoritative source URI")
            val directoryLocation = AudioPlaylistLocations.authoritative(track.directoryUri, track.directoryReference)
            val coverLocation = AudioPlaylistLocations.authoritative(track.directoryCoverUri, track.directoryCoverReference)
            put(JSONObject()
                .put("uri", sourceLocation)
                .put("title", track.title)
                .putNullable("artist", track.artist)
                .putNullable("album", track.album)
                .put("durationMs", track.durationMs)
                .put("startByte", track.startByte)
                .put("lengthBytes", track.lengthBytes)
                .putNullable("bitrateKbps", track.bitrateKbps)
                .putNullable("sampleRateHz", track.sampleRateHz)
                .putNullable("channels", track.channels)
                .put("isBitrateRelevant", track.isBitrateRelevant)
                .put("fileName", track.fileName)
                .put("isUnreadable", track.isUnreadable)
                .putNullable("decoderExtension", track.decoderExtension)
                .putNullable("sourceReference", sourceLocation)
                .putNullable("directoryCoverUri", coverLocation)
                .putNullable("directoryCoverReference", coverLocation)
                .putNullable("directoryUri", directoryLocation)
                .putNullable("directoryName", track.directoryName)
                .putNullable("directoryReference", directoryLocation)
                .put("directoryArtworkMetadataResolved", track.directoryArtworkMetadataResolved)
                .put("mediaKind", track.mediaKind.name)
                .putNullable("sourceMimeType", track.sourceMimeType))
        }
    }

    private fun readTracks(array: JSONArray?): List<AudioTrack> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uri = item.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse) ?: continue
            val storedReference = item.stringOrNull("sourceReference") ?: uri.toString()
            val itemName = item.optString("fileName").ifBlank { item.optString("title", "Audio Track") }
            val sourceReference = sequenceOf(storedReference, uri.toString())
                .mapNotNull { io.github.gretchycat.infiniselect.InfiniSelect
                    .authoritativeUri(appContext, it)?.toString() }
                .firstOrNull() ?: continue
            val restoredUri = Uri.parse(sourceReference)
            val storedCoverReference = item.stringOrNull("directoryCoverReference")
                ?: item.stringOrNull("directoryCoverUri")
            val storedCoverUri = item.stringOrNull("directoryCoverUri")
            val coverReference = sequenceOf(storedCoverReference, storedCoverUri).filterNotNull()
                .mapNotNull { io.github.gretchycat.infiniselect.InfiniSelect
                    .authoritativeUri(appContext, it)?.toString() }
                .firstOrNull()
            val restoredDirectoryCover = coverReference?.let(Uri::parse)
            val storedDirectoryReference = item.stringOrNull("directoryReference")
                ?: item.stringOrNull("directoryUri")
            val directoryReference = sequenceOf(storedDirectoryReference).filterNotNull()
                .mapNotNull { io.github.gretchycat.infiniselect.InfiniSelect
                    .authoritativeUri(appContext, it)?.toString() }
                .firstOrNull()
            add(AudioTrack(
                uri = restoredUri,
                title = item.optString("title").ifBlank { itemName },
                artist = item.stringOrNull("artist"),
                album = item.stringOrNull("album"),
                durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                startByte = item.optLong("startByte", 0L).coerceAtLeast(0L),
                lengthBytes = item.optLong("lengthBytes", -1L),
                bitrateKbps = item.intOrNull("bitrateKbps"),
                sampleRateHz = item.intOrNull("sampleRateHz"),
                channels = item.intOrNull("channels"),
                isBitrateRelevant = item.optBoolean("isBitrateRelevant", true),
                fileName = itemName,
                isUnreadable = item.optBoolean("isUnreadable", false),
                decoderExtension = item.stringOrNull("decoderExtension"),
                sourceReference = sourceReference,
                directoryCoverUri = restoredDirectoryCover,
                directoryCoverReference = coverReference,
                directoryUri = directoryReference?.let(Uri::parse),
                directoryName = item.stringOrNull("directoryName"),
                directoryReference = directoryReference,
                directoryArtworkMetadataResolved = item.optBoolean("directoryArtworkMetadataResolved", false),
                mediaKind = runCatching { PlaylistMediaKind.valueOf(item.optString("mediaKind", "AUDIO")) }
                    .getOrDefault(PlaylistMediaKind.AUDIO),
                sourceMimeType = item.stringOrNull("sourceMimeType")
            ))
        }
    }

    companion object {
        private const val VERSION = 1
    }
}

/** JSPF export keeps the active queue order, including duplicate and segmented tracks. */
internal object AudioPlaylistJspf {
    const val MIME_TYPE = "application/jspf+json"

    fun encode(tracks: List<AudioTrack>, title: String): ByteArray {
        val items = JSONArray()
        tracks.forEach { track ->
            val sourceLocation = AudioPlaylistLocations.authoritative(track.uri, track.sourceReference)
                ?: throw IllegalArgumentException("Cannot save a playlist entry without an authoritative source URI")
            val directoryLocation = AudioPlaylistLocations.authoritative(track.directoryUri, track.directoryReference)
            val coverLocation = AudioPlaylistLocations.authoritative(track.directoryCoverUri, track.directoryCoverReference)
            val item = JSONObject()
                .put("location", sourceLocation)
                .put("title", track.title)
            track.artist?.takeIf(String::isNotBlank)?.let { item.put("creator", it) }
            track.album?.takeIf(String::isNotBlank)?.let { item.put("album", it) }
            track.durationMs.takeIf { it > 0L }?.let { item.put("duration", it) }
            val extension = JSONObject()
                .put("mimeType", track.sourceMimeType ?: mimeForName(track.fileName))
                .put("mediaKind", track.mediaKind.name.lowercase())
                .put("sourceReference", sourceLocation)
            track.startByte.takeIf { it > 0L }?.let { extension.put("startByte", it) }
            track.lengthBytes.takeIf { it >= 0L }?.let { extension.put("lengthBytes", it) }
            track.decoderExtension?.takeIf(String::isNotBlank)?.let { extension.put("decoderExtension", it) }
            directoryLocation?.let { extension.put("directoryUri", it) }
            track.directoryName?.takeIf(String::isNotBlank)?.let { extension.put("directoryName", it) }
            directoryLocation?.let { extension.put("directoryReference", it) }
            coverLocation?.let { extension.put("directoryCoverUri", it) }
            coverLocation?.let { extension.put("directoryCoverReference", it) }
            extension.put("directoryArtworkMetadataResolved", track.directoryArtworkMetadataResolved)
            item.put("extension", JSONObject().put("io.github.gretchycat.infiniselect", extension))
            items.put(item)
        }
        val cleanTitle = title.substringBeforeLast('.', title).ifBlank { "InfiniMedia Playlist" }
        return JSONObject().put("playlist", JSONObject()
            .put("title", cleanTitle)
            .put("track", items)).toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun mimeForName(name: String): String =
        com.infinifolder.mime.MimeRegistry.guess(name).takeIf {
            it.startsWith("audio/", true) || it.startsWith("image/", true) || it.startsWith("video/", true)
        } ?: "application/octet-stream"
}

/** Prefer InfiniSelect's stable source identity over its short-lived selection relay. */
internal object AudioPlaylistLocations {
    fun authoritative(uri: Uri?, reference: String?): String? =
        io.github.gretchycat.infiniselect.InfiniSelect.authoritativeLocationCandidate(reference, uri)
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
