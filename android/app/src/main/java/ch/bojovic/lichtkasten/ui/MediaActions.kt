package ch.bojovic.lichtkasten.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import ch.bojovic.lichtkasten.Logs
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.media.MediaItem

/** Share and permanent delete. Both end in a system UI; nothing is silent. */
object MediaActions {

    fun share(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = ArrayList<Uri>(items.map { it.uri })
        val type = commonType(items)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris[0])
                this.type = type
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                this.type = type
            }
        }
        // ClipData is what the system reads when granting read access to
        // several content:// URIs; EXTRA_STREAM alone does not scope the
        // grant on every API level.
        intent.clipData = clipOf(uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, context.getString(R.string.share))
        chooser.clipData = intent.clipData
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(chooser)
        } catch (e: RuntimeException) {
            Logs.w("share failed", e)
        }
    }

    /**
     * Permanent delete through the system confirmation. The result arrives
     * in [launcher]'s callback as [Activity.RESULT_OK]; MediaStore then
     * notifies the observer and the grid reloads itself.
     */
    fun requestDelete(
        resolver: ContentResolver,
        items: List<MediaItem>,
        launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>,
    ): Boolean {
        if (items.isEmpty()) return false
        return try {
            val pending = MediaStore.createDeleteRequest(resolver, items.map { it.uri })
            launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            true
        } catch (e: RuntimeException) {
            Logs.w("delete request failed", e)
            false
        }
    }

    private fun clipOf(uris: List<Uri>): ClipData {
        val clip = ClipData.newRawUri("", uris[0])
        for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
        return clip
    }

    private fun commonType(items: List<MediaItem>): String {
        val allImages = items.all { !it.isVideo }
        val allVideos = items.all { it.isVideo }
        return when {
            items.size == 1 -> items[0].mimeType
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }
    }
}
