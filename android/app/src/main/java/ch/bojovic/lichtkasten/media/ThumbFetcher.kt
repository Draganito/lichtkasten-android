package ch.bojovic.lichtkasten.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import ch.bojovic.lichtkasten.Logs
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Grid thumbnail for everything Android decodes itself. One 1024 px JPEG
 * per file, produced here and stored in [ThumbCache]; Coil samples that
 * file down to the cell. MediaStore's thumbnail cache is never read, so
 * these previews exist only inside the app.
 *
 * [sourceEdge] is the long edge of the stored JPEG, [displayEdge] the cell.
 */
data class ThumbRequest(val item: MediaItem, val sourceEdge: Int, val displayEdge: Int = sourceEdge) {
    val cacheKey: String get() = "thumb:${item.cacheKey}:$sourceEdge:$displayEdge"

    companion object {
        const val SOURCE = ThumbCache.SOURCE_EDGE
    }
}

class ThumbKeyer : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String = data.cacheKey
}

class ThumbFetcher(private val request: ThumbRequest, private val context: Context) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val cached = ThumbCache.fileFor(context, request.item, request.sourceEdge)
        if (cached.isFile && cached.length() > 0) {
            return@withContext fromFile(cached)
        }
        val bitmap = ThumbCache.slots.withPermit { decode() } ?: return@withContext null
        val stored = ThumbCache.store(cached, bitmap)
        if (stored && request.displayEdge < request.sourceEdge) {
            bitmap.recycle()
            fromFile(cached)
        } else {
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }
    }

    private fun decode(): Bitmap? = try {
        if (request.item.isVideo) videoFrame() else stillImage()
    } catch (e: ImageDecoder.DecodeException) {
        Logs.file { "decode failed for ${request.item.displayName}: ${e.message}" }
        null
    } catch (e: SecurityException) {
        Logs.file { "not allowed: ${request.item.displayName}" }
        null
    } catch (e: OutOfMemoryError) {
        Logs.w("out of memory decoding a thumbnail")
        null
    } catch (e: RuntimeException) {
        Logs.file { "decode failed for ${request.item.displayName}: ${e.message}" }
        null
    }

    /** ImageDecoder applies the EXIF orientation and scales while decoding. */
    private fun stillImage(): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, request.item.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fittedSize(info.size.width, info.size.height, request.sourceEdge)
            decoder.setTargetSize(w, h)
        }
    }

    /** A keyframe, scaled to fit the source edge. Rotation metadata is applied by the retriever. */
    private fun videoFrame(): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, request.item.uri)
            val edge = request.sourceEdge
            return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fromFile(file: File): FetchResult = SourceFetchResult(
        source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )

    class Factory(private val context: Context) : Fetcher.Factory<ThumbRequest> {
        override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, context.applicationContext)
    }
}
