package ch.bojovic.lichtkasten.media

import android.net.Uri

/**
 * One row of MediaStore. Everything the grid, the viewer and the info sheet
 * need without touching the file; the file itself is opened only to decode.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
    /** MediaStore RELATIVE_PATH, e.g. `DCIM/Camera/`. */
    val relativePath: String,
    val sizeBytes: Long,
    /** Unix millis: DATE_TAKEN when present, otherwise DATE_MODIFIED * 1000. */
    val sortMillis: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    /** EXIF orientation in degrees as MediaStore reports it (0/90/180/270). */
    val orientation: Int,
    val durationMillis: Long,
    val isVideo: Boolean,
) {
    /** TIFF and RAW go through the Rust core; Android has no decoder for them. */
    val needsRust: Boolean
        get() = !isVideo && (MediaKinds.isTiff(mimeType, displayName) || MediaKinds.isRaw(mimeType, displayName))

    /** Stable identity for caches: the row, its last change, its size. */
    val cacheKey: String
        get() = "$id-$dateModifiedSeconds-$sizeBytes"
}

object MediaKinds {
    private val RAW_EXTENSIONS = setOf(
        "3fr", "arw", "cr2", "cr3", "crw", "dcr", "dng", "erf", "fff", "iiq", "kdc", "mdc", "mef",
        "mos", "mrw", "nef", "nrw", "orf", "ori", "pef", "raf", "raw", "rw2", "rwl", "sr2", "srf",
        "srw", "x3f",
    )
    private val RAW_MIMES = setOf(
        "image/x-adobe-dng", "image/x-nikon-nef", "image/x-canon-cr2", "image/x-canon-cr3",
        "image/x-sony-arw", "image/x-fuji-raf", "image/x-panasonic-rw2", "image/x-olympus-orf",
        "image/x-pentax-pef", "image/x-dcraw", "image/x-raw",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isTiff(mime: String, name: String): Boolean =
        mime == "image/tiff" || mime == "image/x-tiff" || extensionOf(name) in setOf("tif", "tiff")

    fun isRaw(mime: String, name: String): Boolean =
        mime in RAW_MIMES || extensionOf(name) in RAW_EXTENSIONS
}
