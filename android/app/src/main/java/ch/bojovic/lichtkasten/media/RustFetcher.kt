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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A request Coil routes to the Rust core: TIFF and RAW, which Android
 * cannot decode. [maxEdge] is part of the identity so a thumbnail and a
 * screen-sized view are two cache entries.
 */
data class RustRequest(val item: MediaItem, val maxEdge: Int) {
    val cacheKey: String get() = "rust:${item.cacheKey}:$maxEdge"
    val uri: Uri get() = item.uri
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
        val cached = RustThumbCache.fileFor(context, request.item, request.maxEdge)
        if (cached.isFile && cached.length() > 0) {
            return@withContext SourceFetchResult(
                source = ImageSource(file = cached.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }
        val bitmap = decodeThroughRust() ?: return@withContext null
        RustThumbCache.store(cached, bitmap)
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

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
            decodeSlots.withPermit {
                decodeImage(fd, request.item.displayName, request.maxEdge.toUInt())
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

    private companion object {
        /** Rust decodes are CPU and memory heavy; three at a time is plenty. */
        val decodeSlots = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(1, 3))
    }
}

/**
 * JPEG copies of Rust-decoded images under `cacheDir/rust-thumbs`. The
 * system may wipe the directory at any time; every file is re-creatable.
 * Files are named `<sha1(item)>-<edge>.jpg`, so everything belonging to one
 * picture can be removed by prefix when the picture is deleted.
 */
object RustThumbCache {
    private const val DIR = "rust-thumbs"
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun fileFor(context: Context, item: MediaItem, maxEdge: Int): File =
        File(dir(context), "${prefix(item)}-$maxEdge.jpg")

    private fun prefix(item: MediaItem): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(item.cacheKey.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun store(target: File, bitmap: Bitmap) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Logs.w("cache write skipped", e)
        }
    }

    /** Remove every cached rendition of [items]; called after a successful delete. */
    fun evict(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val prefixes = items.map { prefix(it) }.toHashSet()
        dir(context).listFiles()?.forEach { f ->
            if (f.name.substringBefore('-') in prefixes) f.delete()
        }
    }

    /** Oldest first until the directory is under [MAX_BYTES]. */
    fun trim(context: Context) {
        val files = dir(context).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
