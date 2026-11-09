package ch.bojovic.lichtkasten.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import ch.bojovic.lichtkasten.Logs
import ch.bojovic.lichtkasten.core.DecodeException
import ch.bojovic.lichtkasten.core.decodeImage
import ch.bojovic.lichtkasten.core.readMetadata
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface ExportResult {
    data class Saved(val uri: Uri, val width: Int, val height: Int) : ExportResult
    data class Failed(val reason: Reason) : ExportResult

    enum class Reason { Decode, TooLarge, Write }
}

/**
 * Crops and shrinks one picture into a *new* JPEG under
 * `Pictures/Lichtkasten`. The original is never touched. Of the original's
 * metadata only the capture time is carried over (so the copy sorts next to
 * its source); location, camera, serial numbers and everything else stay
 * behind.
 *
 * The decode produces exactly one bitmap at the final size: ImageDecoder
 * applies the EXIF orientation, scales the whole picture and keeps only the
 * crop, so memory is bounded by the output, not by the source.
 */
object ImageExport {

    private const val RELATIVE_PATH = "Pictures/Lichtkasten/"
    private const val JPEG_QUALITY = 92

    /** Rust decodes a RAW/TIFF preview up to this long edge; larger exports of those are not offered. */
    const val RUST_MAX_EDGE = 4096

    suspend fun export(context: Context, item: MediaItem, crop: CropRect, targetEdge: Int): ExportResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val bitmap = try {
                ThumbCache.slots.withPermit {
                    if (item.needsRust) decodeRust(app, item, crop, targetEdge) else decodeAndroid(app, item, crop, targetEdge)
                }
            } catch (e: OutOfMemoryError) {
                Logs.w("export: out of memory")
                return@withContext ExportResult.Failed(ExportResult.Reason.TooLarge)
            } ?: return@withContext ExportResult.Failed(ExportResult.Reason.Decode)
            try {
                write(app, item, bitmap)
            } finally {
                bitmap.recycle()
            }
        }

    // ---- decode -----------------------------------------------------------

    private fun decodeAndroid(context: Context, item: MediaItem, crop: CropRect, targetEdge: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, item.uri)
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // info.size is the oriented size; the crop is in that space.
                val w = info.size.width
                val h = info.size.height
                val cropPx = crop.toPixels(w, h)
                val scale = ImageGeometry.exportScale(cropPx.width, cropPx.height, targetEdge)
                val (tw, th) = ImageGeometry.scaled(w, h, scale)
                val target = crop.toPixels(tw, th)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(tw, th)
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                decoder.crop = Rect(target.left, target.top, target.right, target.bottom)
            }
        } catch (e: IOException) {
            Logs.file { "export decode failed for ${item.displayName}: ${e.message}" }
            null
        } catch (e: ImageDecoder.DecodeException) {
            Logs.file { "export decode failed for ${item.displayName}: ${e.message}" }
            null
        } catch (e: IllegalArgumentException) {
            Logs.w("export: decoder rejected the request")
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun decodeRust(context: Context, item: MediaItem, crop: CropRect, targetEdge: Int): Bitmap? {
        val fd = try {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.detachFd()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return null
        // Rust owns `fd` and closes it. It applies the orientation itself.
        val rgba = try {
            decodeImage(fd, item.displayName, RUST_MAX_EDGE.toUInt())
        } catch (e: DecodeException) {
            Logs.file { "export rust decode failed for ${item.displayName}: ${e.message}" }
            return null
        }
        val w = rgba.width.toInt()
        val h = rgba.height.toInt()
        if (w <= 0 || h <= 0 || rgba.pixels.size != w * h * 4) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba.pixels))
        }
        val box = crop.toPixels(w, h)
        val cropped = if (box.left == 0 && box.top == 0 && box.width == w && box.height == h) {
            full
        } else {
            Bitmap.createBitmap(full, box.left, box.top, box.width, box.height).also { full.recycle() }
        }
        val scale = ImageGeometry.exportScale(cropped.width, cropped.height, targetEdge)
        if (scale >= 1f) return cropped
        val (ow, oh) = ImageGeometry.scaled(cropped.width, cropped.height, scale)
        return shrink(cropped, ow, oh)
    }

    /**
     * Bilinear scaling is only good for up to 2x; halve first, like the
     * desktop core's area shrink, then do the final step.
     */
    private fun shrink(src: Bitmap, outW: Int, outH: Int): Bitmap {
        var cur = src
        while (cur.width >= outW * 2 && cur.height >= outH * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (next !== cur) cur.recycle()
            cur = next
        }
        if (cur.width != outW || cur.height != outH) {
            val next = Bitmap.createScaledBitmap(cur, outW, outH, true)
            if (next !== cur) cur.recycle()
            cur = next
        }
        return cur
    }

    // ---- write ------------------------------------------------------------

    private fun write(context: Context, item: MediaItem, bitmap: Bitmap): ExportResult {
        val resolver = context.contentResolver
        val base = item.displayName.substringBeforeLast('.').ifBlank { "image" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$base-lk.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (item.sortMillis > 0) put(MediaStore.Images.Media.DATE_TAKEN, item.sortMillis)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return ExportResult.Failed(ExportResult.Reason.Write)

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("compress")
            } ?: throw IOException("open")
            writeExif(context, item, uri, bitmap.width, bitmap.height)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return ExportResult.Saved(uri, bitmap.width, bitmap.height)
        } catch (e: IOException) {
            Logs.file { "export write failed: ${e.message}" }
        } catch (e: SecurityException) {
            Logs.w("export: write not allowed")
        }
        runCatching { resolver.delete(uri, null, null) }
        return ExportResult.Failed(ExportResult.Reason.Write)
    }

    /** Capture time from the source, orientation 1 (pixels are upright), nothing else. */
    private fun writeExif(context: Context, item: MediaItem, uri: Uri, width: Int, height: Int) {
        val captured = captureTime(context, item)
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Lichtkasten")
                captured?.let { (date, offset, subsec) ->
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, date)
                    offset?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it) }
                    subsec?.let { exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, it) }
                }
                exif.saveAttributes()
            }
        } catch (e: IOException) {
            // The picture is saved; only the date tag is missing. Not worth failing over.
            Logs.w("export: exif not written")
        }
    }

    private data class Captured(val dateTime: String, val offset: String?, val subsec: String?)

    private fun captureTime(context: Context, item: MediaItem): Captured? {
        if (item.needsRust) {
            val fd = try {
                context.contentResolver.openFileDescriptor(item.uri, "r")?.detachFd()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } ?: return fromMillis(item.sortMillis)
            val meta = try {
                readMetadata(fd)
            } catch (e: DecodeException) {
                return fromMillis(item.sortMillis)
            }
            val original = meta.exif.firstOrNull { it.key == "DateTimeOriginal" }?.value
            if (original != null && EXIF_DATE.matches(original)) return Captured(original, null, null)
            return meta.captured?.let { fromMillis(it.toLong() * 1000) } ?: fromMillis(item.sortMillis)
        }
        return try {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.takeIf { EXIF_DATE.matches(it) }
                if (date != null) {
                    Captured(
                        date,
                        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                        exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                    )
                } else {
                    fromMillis(item.sortMillis)
                }
            }
        } catch (e: IOException) {
            fromMillis(item.sortMillis)
        } catch (e: SecurityException) {
            fromMillis(item.sortMillis)
        }
    }

    private fun fromMillis(millis: Long): Captured? {
        if (millis <= 0) return null
        val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return Captured(EXIF_FORMAT.format(local), null, null)
    }

    private val EXIF_DATE = Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")
    private val EXIF_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
}
