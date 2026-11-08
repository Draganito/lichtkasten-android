package ch.bojovic.lichtkasten.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import ch.bojovic.lichtkasten.Logs
import ch.bojovic.lichtkasten.core.DecodeException
import ch.bojovic.lichtkasten.core.decodeImage
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
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A request Coil routes to the Rust core: TIFF and RAW, which Android
 * cannot decode. [sourceEdge] is the long edge Rust decodes to and the
 * JPEG on disk has; [displayEdge] is what the cell needs. The grid asks
 * for one 1024 px source per picture at every zoom level and lets Coil
 * sample the file down, so Rust runs once per picture; the viewer asks
 * for source == display.
 */
data class RustRequest(val item: MediaItem, val sourceEdge: Int, val displayEdge: Int = sourceEdge) {
    val cacheKey: String get() = "rust:${item.cacheKey}:$sourceEdge:$displayEdge"
    val uri: Uri get() = item.uri

        companion object {
        /** Long edge of the one cached rendition the grid uses. */
        const val THUMB_SOURCE = ThumbCache.SOURCE_EDGE
    }
}

class RustKeyer : Keyer<RustRequest> {
    override fun key(data: RustRequest, options: Options): String = data.cacheKey
}

/**
 * Opens the MediaStore row, hands the *detached* descriptor to Rust, turns
 * the RGBA bytes into a Bitmap, and remembers the result as a JPEG in the
 * app-private cache so the next start is a plain BitmapFactory decode.
 */
class RustFetcher(
    private val request: RustRequest,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val cached = ThumbCache.fileFor(context, request.item, request.sourceEdge)
        if (cached.isFile && cached.length() > 0) {
            return@withContext fromFile(cached)
        }
        val bitmap = decodeThroughRust() ?: return@withContext null
        val stored = ThumbCache.store(cached, bitmap)
        if (stored && request.displayEdge < request.sourceEdge) {
            // Let Coil's decoder sample the JPEG down to the cell; the full
            // 1024 px bitmap would be four times the memory of a 512 px cell.
            bitmap.recycle()
            fromFile(cached)
        } else {
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }
    }

    private fun fromFile(file: File): FetchResult = SourceFetchResult(
        source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )

    private suspend fun decodeThroughRust(): Bitmap? {
        val fd = try {
            context.contentResolver.openFileDescriptor(request.uri, "r")?.detachFd()
        } catch (e: FileNotFoundException) {
            Logs.file { "gone: ${request.item.displayName}" }
            return null
        } catch (e: SecurityException) {
            Logs.file { "not allowed: ${request.item.displayName}" }
            return null
        } ?: return null

        // Rust owns `fd` from here and closes it, success or error.
        val rgba = try {
            ThumbCache.slots.withPermit {
                decodeImage(fd, request.item.displayName, request.sourceEdge.toUInt())
            }
        } catch (e: DecodeException) {
            Logs.file { "rust decode failed for ${request.item.displayName}: ${e.message}" }
            return null
        }
        val w = rgba.width.toInt()
        val h = rgba.height.toInt()
        if (w <= 0 || h <= 0 || rgba.pixels.size != w * h * 4) {
            Logs.w("rust returned an inconsistent buffer")
            return null
        }
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba.pixels))
            }
        } catch (e: OutOfMemoryError) {
            Logs.w("out of memory for a ${w}x${h} bitmap")
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<RustRequest> {
        override fun create(data: RustRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            RustFetcher(data, context.applicationContext)
    }
}
