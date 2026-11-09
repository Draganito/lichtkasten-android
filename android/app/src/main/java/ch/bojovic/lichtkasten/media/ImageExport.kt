package ch.bojovic.lichtkasten.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import ch.bojovic.lichtkasten.Logs
import ch.bojovic.lichtkasten.core.CropBox
import ch.bojovic.lichtkasten.core.DecodeException
import ch.bojovic.lichtkasten.core.cropJpegLossless
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
    /** [lossless]: the JPEG blocks were copied, nothing was re-encoded. */
    data class Saved(val uri: Uri, val width: Int, val height: Int, val lossless: Boolean) : ExportResult
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
 * A JPEG cropped at its original size is not decoded at all: the Rust core
 * copies the DCT blocks of the region (jpegtran-style), so the pixels stay
 * exactly what they were. Everything else is decoded exactly once at the
 * final size: ImageDecoder applies the EXIF orientation, scales the whole
 * picture and keeps only the crop, so memory is bounded by the output.
 */
object ImageExport {

    private const val RELATIVE_PATH = "Pictures/Lichtkasten/"
    private const val JPEG_QUALITY = 92

    /** Rust decodes a RAW/TIFF preview up to this long edge; larger exports of those are not offered. */
    const val RUST_MAX_EDGE = 4096

    /** Whether [export] at original size would copy JPEG blocks instead of re-encoding. */
    fun isLosslessCandidate(item: MediaItem, targetEdge: Int): Boolean =
        targetEdge == 0 && !item.isVideo && !item.needsRust &&
            (item.mimeType == "image/jpeg" || MediaKinds.extensionOf(item.displayName) in setOf("jpg", "jpeg"))

    suspend fun export(context: Context, item: MediaItem, crop: CropRect, targetEdge: Int): ExportResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (isLosslessCandidate(item, targetEdge)) {
                val lossless = ThumbCache.slots.withPermit { cropLossless(app, item, crop) }
                if (lossless != null) return@withContext lossless
                // Unusual JPEG (arithmetic, flipped orientation, over budget): re-encode instead.
            }
            val bitmap = try {
                ThumbCache.slots.withPermit {
                    if (item.needsRust) decodeRust(app, item, crop, targetEdge) else decodeAndroid(app, item, crop, targetEdge)
                }
            } catch (e: OutOfMemoryError) {
                Logs.w("export: out of memory")
                return@withContext ExportResult.Failed(ExportResult.Reason.TooLarge)
            } ?: return@withContext ExportResult.Failed(ExportResult.Reason.Decode)
            try {
                write(app, item, bitmap.width, bitmap.height, ExifInterface.ORIENTATION_NORMAL, lossless = false) { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("compress")
                }
            } finally {
                bitmap.recycle()
            }
        }

    // ---- lossless ---------------------------------------------------------

    /**
     * The crop is framed on the displayed (rotated) picture; the JPEG stores
     * the unrotated pixels, so the box is mapped back and the source's
     * Orientation tag is written to the copy. Returns null when this file
     * cannot take the lossless route, so the caller re-encodes.
     */
    private fun cropLossless(context: Context, item: MediaItem, crop: CropRect): ExportResult? {
        val header = readJpegHeader(context, item) ?: return null
        val rotation = when (header.orientation) {
            ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> return null // mirrored: let the decoder sort it out
        }
        val (ow, oh) = ImageGeometry.orientedSize(header.width, header.height, rotation)
        val stored = ImageGeometry.toStored(crop.toPixels(ow, oh), header.width, header.height, rotation)
        if (stored.width <= 0 || stored.height <= 0) return null

        val fd = try {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.detachFd()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return null
        // Rust owns `fd` and closes it.
        val cut = try {
            cropJpegLossless(fd, CropBox(stored.left.toUInt(), stored.top.toUInt(), stored.width.toUInt(), stored.height.toUInt()))
        } catch (e: DecodeException) {
            Logs.file { "lossless crop not possible for ${item.displayName}: ${e.message}" }
            return null
        }
        if (cut.jpeg.isEmpty()) return null
        val (w, h) = ImageGeometry.orientedSize(cut.rect.width.toInt(), cut.rect.height.toInt(), rotation)
        val orientationTag = if (rotation == 0) ExifInterface.ORIENTATION_NORMAL else header.orientation
        return write(context, item, w, h, orientationTag, lossless = true) { out -> out.write(cut.jpeg) }
    }

    private class JpegHeader(val width: Int, val height: Int, val orientation: Int)

    /** Stored dimensions and the raw EXIF Orientation value, without decoding pixels. */
    private fun readJpegHeader(context: Context, item: MediaItem): JpegHeader? {
        return try {
            val size = context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0 || opts.outMimeType != "image/jpeg") null
                else opts.outWidth to opts.outHeight
            } ?: return null
            val orientation = context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: return null
            JpegHeader(size.first, size.second, orientation)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
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

    private fun write(
        context: Context,
        item: MediaItem,
        width: Int,
        height: Int,
        orientationTag: Int,
        lossless: Boolean,
        body: (java.io.OutputStream) -> Unit,
    ): ExportResult {
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
            resolver.openOutputStream(uri, "w")?.use(body) ?: throw IOException("open")
            writeExif(context, item, uri, orientationTag)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return ExportResult.Saved(uri, width, height, lossless)
        } catch (e: IOException) {
            Logs.file { "export write failed: ${e.message}" }
        } catch (e: SecurityException) {
            Logs.w("export: write not allowed")
        }
        runCatching { resolver.delete(uri, null, null) }
        return ExportResult.Failed(ExportResult.Reason.Write)
    }

    /**
     * Capture time from the source, the orientation the pixels need (1 after
     * a decode, the source's tag after a lossless copy), nothing else.
     */
    private fun writeExif(context: Context, item: MediaItem, uri: Uri, orientationTag: Int) {
        val captured = captureTime(context, item)
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag.toString())
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
