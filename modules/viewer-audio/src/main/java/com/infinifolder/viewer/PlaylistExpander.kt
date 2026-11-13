package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.model.ResourceEntry
import com.infinifolder.audio.playlist.PlaylistParser
import java.io.File
import java.net.URI
import java.net.URLDecoder

object PlaylistExpander {

    var uriFactory: (String) -> Uri = { str -> Uri.parse(str) }

    fun isPlaylist(name: String, mimeType: String = ""): Boolean {
        return ArchiveFormats.isPlaylist(name, mimeType) || PlaylistParser.isPlaylist(name)
    }

    fun isPlaylist(uri: Uri, name: String? = null, mimeType: String? = null): Boolean {
        // A display name may be a user-defined shortcut title without an extension.
        // Keep it as a useful clue, but never let it hide the URI's actual file name.
        val uriName = uri.lastPathSegment.orEmpty()
        val uriPathName = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return sequenceOf(name, uriName, uriPathName)
            .filterNotNull()
            .distinct()
            .any { isPlaylist(it, mimeType.orEmpty()) }
    }

    /** Content sniffing is a bounded fallback for providers that omit a filename or MIME. */
    fun isPlaylist(context: Context, uri: Uri, name: String? = null, mimeType: String? = null): Boolean {
        val candidateName = name ?: io.github.gretchycat.infiniselect.InfiniSelect
            .displayName(context, uri).orEmpty()
        if (isPlaylist(uri, candidateName, mimeType)) return true
        return runCatching {
            val prefix = MediaSourceAdapter.readText(context, uri, maxBytes = 64 * 1024).first
            PlaylistParser.isPlaylist(candidateName, prefix)
        }.getOrDefault(false)
    }

    /** Resolve a CDDB metadata record to its matching playable sibling playlist, when unique. */
    fun matchingCddbPlaylist(context: Context, uri: Uri): ResourceEntry? = runCatching {
        val record = com.infinifolder.audio.cddb.CddbManager.parseXmcd(
            MediaSourceAdapter.readText(context, uri, maxBytes = 64 * 1024).first
        ) ?: return null
        if (record.discId.isBlank()) return null
        val parent = MediaSourceAdapter.getParentDirectory(context, uri) ?: return null
        val siblings = MediaSourceAdapter.listFolder(context, parent)
        val matches = siblings.asSequence()
            .filter { !it.metadata.isDirectory && it.uri != uri &&
                it.metadata.name.substringAfterLast('.', "").equals("cue", ignoreCase = true) }
            .mapNotNull { candidate ->
                val text = runCatching {
                    MediaSourceAdapter.readText(context, candidate.uri, maxBytes = 256 * 1024).first
                }.getOrNull() ?: return@mapNotNull null
                val tracks = PlaylistParser.parse(candidate.metadata.name, text)
                val matchingTrack = tracks.firstOrNull {
                    it.discId.equals(record.discId, ignoreCase = true) && it.pathOrUri.isNotBlank()
                } ?: return@mapNotNull null
                val source = resolveEntryUri(context, candidate.uri, matchingTrack.pathOrUri)
                if (!isSourceAvailable(context, source)) return@mapNotNull null
                candidate
            }
            .take(2)
            .toList()
        matches.singleOrNull()
    }.onFailure {
        com.infinifolder.storage.DiagnosticLog.record("audio.cddb.companion.resolve.failed", error = it)
    }.getOrNull()

    private fun entryFileName(context: Context?, reference: String): String {
        val parsed = runCatching { URI(reference.replace(" ", "%20")) }.getOrNull()
        if (parsed?.isAbsolute == true) {
            val uri = runCatching { uriFactory(parsed.toASCIIString()) }.getOrNull()
            if (uri != null) {
                val canonical = io.github.gretchycat.infiniselect.InfiniSelect.authoritativeUri(context, uri.toString()) ?: uri
                io.github.gretchycat.infiniselect.InfiniSelect.displayName(context, canonical)?.let { return it }
            }
        }
        val path = parsed?.path ?: reference.substringBefore('?').substringBefore('#')
        return URLDecoder.decode(path.substringAfterLast('/').substringAfterLast('\\')
            .replace("+", "%2B"), Charsets.UTF_8.name())
    }

    /** Compatibility wrapper; URI resolution belongs to libinfiniselect. */
    fun resolveEntryUri(context: Context?, playlistUri: Uri, pathOrUri: String): Uri =
        io.github.gretchycat.infiniselect.InfiniSelect.resolveRelativeLocationText(
            context, playlistUri.toString(), pathOrUri)?.let { uriFactory(it) } ?: Uri.EMPTY

    fun expandPlaylist(
        context: Context?,
        playlistUri: Uri,
        visited: MutableSet<String> = mutableSetOf(),
        authoritativePlaylistUri: Uri? = null,
    ): List<AudioTrack> {
        val requestedPlaylistLocation = authoritativePlaylistUri ?: playlistUri
        val playlistLocation = io.github.gretchycat.infiniselect.InfiniSelect.authoritativeUri(
            context, requestedPlaylistLocation.toString()) ?: requestedPlaylistLocation
        val uriKey = playlistLocation.toString()
        if (visited.contains(uriKey)) return emptyList()
        visited.add(uriKey)

        val tracks = mutableListOf<AudioTrack>()
        var hasMoreText = false
        var playlistName = "playlist.m3u"
        try {
            val name = io.github.gretchycat.infiniselect.InfiniSelect.displayName(context, playlistLocation,
                "playlist.m3u") ?: "playlist.m3u"
            playlistName = name
            val localFile = if (playlistUri.scheme == "file") {
                playlistUri.path?.let { File(it) }
            } else {
                context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri) }.getOrNull() }
            }

            val text = if (localFile != null && localFile.canRead()) {
                localFile.readText(Charsets.UTF_8)
            } else {
                if (context != null) MediaSourceAdapter.readText(context, playlistUri).let { (content, truncated) ->
                    hasMoreText = truncated
                    content
                } else ""
            }
            val entries = PlaylistParser.parse(name, text)
            var missingSources = 0
            if (entries.isEmpty()) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.empty",
                    "format=${name.substringAfterLast('.', "").lowercase()} chars=${text.length} truncated=$hasMoreText")
            }

            for (entry in entries) {
                val locationUri = resolveEntryUri(context, playlistLocation, entry.pathOrUri)
                val requestedSource = entry.sourceReference?.let {
                    runCatching { Uri.parse(it) }.getOrNull()
                } ?: locationUri
                val canonicalSource = context?.let { appContext ->
                    io.github.gretchycat.infiniselect.InfiniSelect.authoritativeUri(appContext,
                        requestedSource.toString())
                } ?: requestedSource
                if (locationUri == Uri.EMPTY || canonicalSource == Uri.EMPTY ||
                    io.github.gretchycat.infiniselect.InfiniSelect.isTemporaryAccessUri(canonicalSource)) {
                    missingSources++
                    continue
                }
                val itemName = entryFileName(context, canonicalSource.toString()).ifBlank {
                    entryFileName(context, entry.pathOrUri)
                }
                if (isPlaylist(itemName, entry.mimeType.orEmpty())) {
                    // Recursive expansion for nested playlists
                    val nestedAccess = context?.let { appContext ->
                        io.github.gretchycat.infiniselect.InfiniSelect.resolveSource(
                            appContext, canonicalSource.toString(), itemName, entry.mimeType)
                    }
                    val nestedContentUri = nestedAccess?.contentUri
                        ?: canonicalSource.takeIf { it.scheme.equals("file", true) }
                    if (nestedContentUri == null) {
                        missingSources++
                        continue
                    }
                    val nestedTracks = expandPlaylist(context, nestedContentUri, visited,
                        authoritativePlaylistUri = nestedAccess?.authoritativeUri ?: canonicalSource)
                    tracks.addAll(nestedTracks)
                } else {
                    val title = entry.title.ifBlank { itemName }
                    val durationMs = entry.durationMillis ?: if (entry.durationSeconds > 0) entry.durationSeconds * 1000L else 0L
                    val declaredKind = entry.mediaKind?.uppercase()?.let {
                        runCatching { PlaylistMediaKind.valueOf(it) }.getOrNull()
                    }
                    val mediaKind = when {
                        entry.decoderExtension != null || entry.startByte > 0 || entry.lengthBytes >= 0 -> PlaylistMediaKind.AUDIO
                        AnsiPlaybackSupport.isAnsiFile(itemName, entry.mimeType) -> PlaylistMediaKind.ANSI
                        declaredKind != null -> declaredKind
                        else -> when (MediaTypeDetector.detect(itemName, entry.mimeType)) {
                            MediaType.IMAGE -> PlaylistMediaKind.IMAGE
                            MediaType.VIDEO -> PlaylistMediaKind.VIDEO
                            else -> PlaylistMediaKind.AUDIO
                        }
                    }
                    tracks.add(
                        AudioTrack(
                            // Keep the backend address in the queue. The player asks
                            // InfiniSelect for a content URI only when this item is used.
                            uri = canonicalSource,
                            title = title,
                            fileName = itemName,
                            artist = entry.performer.ifBlank { null },
                            album = entry.album.ifBlank { null },
                            durationMs = durationMs,
                            startByte = entry.startByte,
                            lengthBytes = entry.lengthBytes,
                            decoderExtension = entry.decoderExtension,
                            sourceReference = canonicalSource.toString(),
                            directoryUri = entry.directoryReference?.let(Uri::parse)
                                ?: entry.directoryUri?.let(Uri::parse),
                            directoryName = entry.directoryName,
                            directoryReference = entry.directoryReference?.let { reference ->
                                context?.let { appContext -> io.github.gretchycat.infiniselect.InfiniSelect
                                    .authoritativeUri(appContext, reference)?.toString() }
                            } ?: entry.directoryReference,
                            directoryCoverUri = entry.directoryCoverReference?.let(Uri::parse)
                                ?: entry.directoryCoverUri?.let(Uri::parse),
                            directoryCoverReference = entry.directoryCoverReference?.let { reference ->
                                context?.let { appContext -> io.github.gretchycat.infiniselect.InfiniSelect
                                    .authoritativeUri(appContext, reference)?.toString() }
                            } ?: entry.directoryCoverReference,
                            directoryArtworkMetadataResolved = entry.directoryArtworkMetadataResolved,
                            mediaKind = mediaKind,
                            sourceMimeType = entry.mimeType
                        )
                    )
                }
            }
            if (missingSources > 0) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.sources_missing",
                    "format=${name.substringAfterLast('.', "").lowercase()} missing=$missingSources parsed=${entries.size}")
            }

            // CDDB enrichment for CUE sheets / CD albums
            val discId = entries.firstOrNull { it.discId.isNotEmpty() }?.discId
            val parentDir = if (localFile != null) localFile.parentFile
                else context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri)?.parentFile }.getOrNull() }

            val cddbRecord = if (parentDir != null) {
                com.infinifolder.audio.cddb.CddbManager.findLocalCddb(parentDir, discId)
            } else null

            if (cddbRecord != null) {
                for (i in 0 until tracks.size) {
                    val t = tracks[i]
                    val cTitle = cddbRecord.trackTitles.getOrNull(i)
                    val newTitle = if (!cTitle.isNullOrBlank() && (t.title.startsWith("Track ") || t.title.isBlank())) cTitle else t.title
                    val newArtist = t.artist ?: cddbRecord.artist.ifBlank { null }
                    tracks[i] = t.copy(title = newTitle, artist = newArtist, album = cddbRecord.album.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("audio.playlist.expand.failed",
                "format=${playlistName.substringAfterLast('.', "").lowercase()} truncated=$hasMoreText", e)
        }

        return tracks
    }

    private fun isSourceAvailable(context: Context?, uri: Uri): Boolean {
        if (context == null) return true
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it).isFile && File(it).canRead() } == true
            "content" -> {
                // Opening a stream just to check a playlist entry starts real content
                // reads. InfiniFolder selection URIs are pipe-backed; probing a large
                // generated JSPF this way can fill its bounded relay queue and make
                // valid later tracks look missing. Ask the provider for lightweight
                // metadata first, and only stream-probe providers that do not support
                // queries.
                val queryResult: Boolean? = try {
                    context.contentResolver.query(uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> cursor.moveToFirst() }
                } catch (_: Exception) { null }
                if (queryResult == true) true
                else if (queryResult == false) false
                else runCatching {
                    MediaSourceAdapter.openStream(context, uri).use { }
                    true
                }.getOrDefault(false)
            }
            else -> true
        }
    }

    fun expandResourceEntries(
        context: Context?,
        entries: List<ResourceEntry>,
        queueMultimedia: Boolean = false,
        visited: MutableSet<String> = mutableSetOf()
    ): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        for (entry in entries) {
            if (entry.metadata.isDirectory) continue
            val authoritativeUri = context?.let { appContext ->
                io.github.gretchycat.infiniselect.InfiniSelect.authoritativeUri(appContext, entry.uri.toString())
            } ?: entry.uri
            if (io.github.gretchycat.infiniselect.InfiniSelect.isTemporaryAccessUri(authoritativeUri)) continue
            if (isPlaylist(entry.uri, entry.metadata.name, entry.metadata.mimeType)) {
                val access = context?.let { appContext -> io.github.gretchycat.infiniselect.InfiniSelect.resolveSource(
                    appContext, authoritativeUri.toString(), entry.metadata.name,
                    entry.metadata.mimeType, entry.metadata.sizeBytes) }
                val playlistContentUri = access?.contentUri
                    ?: authoritativeUri.takeIf { it.scheme.equals("file", true) }
                if (playlistContentUri == null) continue
                val playlistTracks = expandPlaylist(context, playlistContentUri, visited,
                    authoritativePlaylistUri = access?.authoritativeUri ?: authoritativeUri)
                tracks.addAll(playlistTracks)
            } else {
                val type = MediaTypeDetector.detect(entry.metadata.name, entry.metadata.mimeType)
                val ansi = AnsiPlaybackSupport.isAnsiFile(entry.metadata.name, entry.metadata.mimeType)
                if (ansi || type == MediaType.AUDIO || (queueMultimedia && type == MediaType.VIDEO)) {
                    val kind = if (ansi) PlaylistMediaKind.ANSI else when (type) {
                        MediaType.VIDEO -> PlaylistMediaKind.VIDEO
                        else -> PlaylistMediaKind.AUDIO
                    }
                    tracks.add(AudioTrack(authoritativeUri, entry.metadata.name, fileName = entry.metadata.name,
                        sourceReference = authoritativeUri.toString(),
                        mediaKind = kind, sourceMimeType = entry.metadata.mimeType))
                }
            }
        }
        return tracks
    }
}
