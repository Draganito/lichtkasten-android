package ch.bojovic.lichtkasten.media

import android.content.Context
import android.graphics.Bitmap
import ch.bojovic.lichtkasten.Logs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Semaphore

/**
 * The only thumbnails this app keeps: JPEG renditions under
 * `cacheDir/thumbs`, named `<sha1(item)>-<edge>.jpg`. Nothing is asked of
 * MediaStore's thumbnail cache, so uninstalling the app removes every
 * preview it generated. The system may also wipe the directory at any
 * time; every file can be decoded again.
 */
object ThumbCache {
    /** Long edge of the one rendition the grid stores per file. */
    const val SOURCE_EDGE = 1024

    private const val DIR = "thumbs"

    /** 0.1.0 kept 512 px files under this name; removed on first start. */
    private const val LEGACY_DIR = "rust-thumbs"
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** Decodes are CPU and memory heavy; three at a time, shared by every fetcher. */
    val slots = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(1, 3))

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun fileFor(context: Context, item: MediaItem, maxEdge: Int): File =
        File(dir(context), "${prefix(item)}-$maxEdge.jpg")

    /** Stable per row, so [evict] can find every rendition of one picture. */
    fun prefix(item: MediaItem): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(item.cacheKey.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** True when [target] now holds the JPEG. */
    fun store(target: File, bitmap: Bitmap): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            val ok = FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            if (ok && tmp.renameTo(target)) true else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            Logs.w("cache write skipped", e)
            false
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
        File(context.cacheDir, LEGACY_DIR).deleteRecursively()
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

/** Size that brings the long edge down to [edge] without enlarging. */
internal fun fittedSize(width: Int, height: Int, edge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val long = maxOf(width, height)
    if (edge <= 0 || long <= edge) return width to height
    val scale = edge.toFloat() / long
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}
