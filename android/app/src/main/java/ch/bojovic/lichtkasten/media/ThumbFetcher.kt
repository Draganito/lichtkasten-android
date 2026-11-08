package ch.bojovic.lichtkasten.media

import android.content.Context
import android.util.Log
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source

/**
 * Grid thumbnail for everything Android decodes itself. MediaStore keeps
 * its own thumbnails for photos and videos and hands them over in a few
 * milliseconds; only when it cannot (unusual container, provider hiccup)
 * do we stream the file to Coil's BitmapFactory path.
 */
data class ThumbRequest(val item: MediaItem, val sizePx: Int) {
    val cacheKey: String get() = "thumb:${item.cacheKey}:$sizePx"
}

class ThumbKeyer : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String = data.cacheKey
}

class ThumbFetcher(private val request: ThumbRequest, private val context: Context) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val bmp = resolver.loadThumbnail(request.item.uri, Size(request.sizePx, request.sizePx), null)
            return@withContext ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } catch (e: FileNotFoundException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext null
        } catch (e: IOException) {
            // No system thumbnail for this one; decode it ourselves below.
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "loadThumbnail refused ${request.item.displayName}: ${e.message}")
        }
        val stream = try {
            resolver.openInputStream(request.item.uri)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return@withContext null
        SourceFetchResult(
            source = ImageSource(source = stream.source().buffer(), fileSystem = FileSystem.SYSTEM),
            mimeType = request.item.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<ThumbRequest> {
        override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, context.applicationContext)
    }

    private companion object {
        const val TAG = "lichtkasten"
    }
}
