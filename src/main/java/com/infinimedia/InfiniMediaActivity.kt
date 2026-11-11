package com.infinimedia

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.SelectionItem
import com.infinifolder.viewer.ViewerActivity

class InfiniMediaActivity : ViewerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMediaPlayer(intent)
        finish()
    }
    /** InfiniMedia's application entry point forwards Android shares straight to its player. */
    private fun openMediaPlayer(source: Intent?) {
        val action = source?.action
        val uris = linkedSetOf<Uri>()
        source?.data?.let(uris::add)
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            source?.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)?.forEach(uris::add)
            source?.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add) }
            source?.getParcelableExtraCompat(Intent.EXTRA_STREAM)?.let(uris::add)
        }
        val playerIntent = if (uris.size > 1) {
            val items = uris.map { uri ->
                val queried = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) null to null else {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                (nameIndex.takeIf { it >= 0 }?.let(cursor::getString)) to
                                    (sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it >= 0L })
                            }
                        } ?: (null to null)
                }.getOrDefault(null to null)
                SelectionItem(uri, queried.first ?: uri.lastPathSegment.orEmpty(),
                    contentResolver.getType(uri) ?: "application/octet-stream", queried.second)
            }
            val playlist = InfiniSelect.createJspfPlaylist(this, items, "Shared audio.jspf")
            Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                setDataAndType(playlist, "application/jspf+json")
                putExtra("displayName", "Shared audio.jspf")
                putExtra("mimeType", "application/jspf+json")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val uri = uris.firstOrNull()
            if (uri == null) Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
            else Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                setDataAndType(uri, source?.type ?: contentResolver.getType(uri))
                putExtra("displayName", source?.getStringExtra("displayName")
                    ?: source?.getStringExtra("extra_name") ?: displayName(uri))
                putExtra("mimeType", source?.type ?: contentResolver.getType(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        source?.clipData?.let { playerIntent.clipData = it }
        playerIntent.addFlags(source?.flags?.and(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ) ?: 0)
        val only = uris.singleOrNull()
        if (only != null && com.infinifolder.viewer.MediaTypeDetector.detect(displayName(only),
                source?.type ?: contentResolver.getType(only)) == com.infinifolder.viewer.MediaType.VIDEO) {
            playerIntent.setClass(this, com.infinifolder.viewer.VideoPlayerActivity::class.java)
        }
        startActivity(playerIntent)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio track"

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java)
        else getParcelableArrayListExtra(key)
}
