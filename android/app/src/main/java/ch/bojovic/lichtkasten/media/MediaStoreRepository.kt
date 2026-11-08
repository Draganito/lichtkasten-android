package ch.bojovic.lichtkasten.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * One stream over every image and video MediaStore knows on the external
 * volume: DCIM, Pictures, Download, an SD card, wherever. Newest first.
 *
 * Read-only. Deleting goes through [MediaStore.createDeleteRequest] in the
 * UI so the system asks the user.
 */
class MediaStoreRepository(private val resolver: ContentResolver) {

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.WIDTH,
        MediaStore.Files.FileColumns.HEIGHT,
        MediaStore.Files.FileColumns.ORIENTATION,
        MediaStore.Files.FileColumns.DURATION,
    )

    /** Emits once immediately, then after every change MediaStore reports. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * Full scan. Never throws: a revoked permission or a flaky provider
     * yields an empty list and a log line, not a crash.
     */
    suspend fun loadAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<MediaItem>(1024)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val order = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        try {
            resolver.query(collection, projection, selection, args, order)?.use { c ->
                while (c.moveToNext()) {
                    readRow(c)?.let(items::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore query refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MediaStore query failed: ${e.message}")
        } catch (e: RuntimeException) {
            // Provider crashed or died mid-cursor. Keep what we have.
            Log.w(TAG, "MediaStore query aborted: ${e.message}")
        }
        // Rows without DATE_TAKEN sort by the SQL order above only within
        // their column; one merged order by sortMillis is what the grid wants.
        items.sortByDescending { it.sortMillis }
        items
    }

    private fun readRow(c: Cursor): MediaItem? {
        val id = c.getLong(0)
        val mediaType = c.getInt(1)
        val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mime = c.getString(2) ?: return null
        val name = c.getString(3) ?: return null
        val dateTaken = if (c.isNull(6)) 0L else c.getLong(6)
        val dateModified = if (c.isNull(7)) 0L else c.getLong(7)
        val uri = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            id,
        )
        return MediaItem(
            id = id,
            uri = uri,
            mimeType = mime,
            displayName = name,
            relativePath = c.getString(4) ?: "",
            sizeBytes = if (c.isNull(5)) 0L else c.getLong(5),
            sortMillis = if (dateTaken > 0) dateTaken else dateModified * 1000,
            dateModifiedSeconds = dateModified,
            width = if (c.isNull(8)) 0 else c.getInt(8),
            height = if (c.isNull(9)) 0 else c.getInt(9),
            orientation = if (c.isNull(10)) 0 else c.getInt(10),
            durationMillis = if (c.isNull(11)) 0L else c.getLong(11),
            isVideo = isVideo,
        )
    }

    private companion object {
        const val TAG = "lichtkasten"
    }
}
