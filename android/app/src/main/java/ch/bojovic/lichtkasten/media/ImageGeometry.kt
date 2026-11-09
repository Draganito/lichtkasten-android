package ch.bojovic.lichtkasten.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Integer pixel box, left/top inclusive, right/bottom exclusive. No Android types, so it runs in JVM tests. */
data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A crop as the user sees it, every side in 0..1 of the *oriented*
 * picture. Resolution-independent, so the same rect applies to the
 * viewer's preview and to the full decode at export time.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Pixel box of this crop in a picture of the given (oriented) size; never empty. */
    fun toPixels(width: Int, height: Int): IntBox {
        val l = (left * width).roundToInt().coerceIn(0, width - 1)
        val t = (top * height).roundToInt().coerceIn(0, height - 1)
        val r = (right * width).roundToInt().coerceIn(l + 1, width)
        val b = (bottom * height).roundToInt().coerceIn(t + 1, height)
        return IntBox(l, t, r, b)
    }

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        /** A crop may not get narrower than this share of the picture, per axis. */
        const val MIN_SIDE = 0.05f
    }
}

object ImageGeometry {

    /** Upper bound on pixels of an exported bitmap: 32 MP, 128 MB as ARGB. */
    const val MAX_OUT_PIXELS = 32L * 1024 * 1024

    /**
     * Factor (≤ 1) to scale the whole picture by so that the crop's long
     * edge becomes [targetEdge] (0 = keep the original size) and the crop
     * stays under [maxPixels]. Never upscales.
     */
    fun exportScale(cropWidthPx: Int, cropHeightPx: Int, targetEdge: Int, maxPixels: Long = MAX_OUT_PIXELS): Float {
        val w = cropWidthPx.coerceAtLeast(1)
        val h = cropHeightPx.coerceAtLeast(1)
        var s = 1f
        if (targetEdge > 0) s = min(s, targetEdge.toFloat() / max(w, h))
        val pixels = w.toLong() * h.toLong()
        // The margin keeps the rounded width×height under the cap.
        if (pixels > maxPixels) s = min(s, (sqrt(maxPixels.toDouble() / pixels) * 0.999).toFloat())
        return s
    }

    /** [w]x[h] scaled by [scale], at least 1x1. */
    fun scaled(w: Int, h: Int, scale: Float): Pair<Int, Int> =
        max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())

    /**
     * A pixel aspect ratio [aspect] (w/h) expressed in the normalized 0..1
     * space of a picture whose own ratio is [imageAspect]: a normalized
     * rect w×h covers `(w·W)/(h·H)` pixels wide-to-high, so `w/h = aspect / imageAspect`.
     */
    fun normalizedRatio(aspect: Float, imageAspect: Float): Float = aspect / imageAspect

    /** The largest centred crop of pixel ratio [aspect] (null = free, whole picture). */
    fun centeredCrop(aspect: Float?, imageAspect: Float): CropRect {
        if (aspect == null) return CropRect.FULL
        val r = normalizedRatio(aspect, imageAspect)
        var w = 1f
        var h = w / r
        if (h > 1f) {
            h = 1f
            w = h * r
        }
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        return CropRect(l, t, l + w, t + h)
    }

    /**
     * Resizes [rect] by dragging one corner to the normalized point
     * ([x],[y]); the opposite corner stays. With a [ratio] (normalized w/h,
     * see [normalizedRatio]) the corner slides along the locked diagonal and
     * the smaller of the two implied sizes wins, so the rect never leaves
     * the picture. Minimum size is [CropRect.MIN_SIDE].
     */
    fun dragCorner(rect: CropRect, corner: Corner, x: Float, y: Float, ratio: Float?): CropRect {
        val fx = x.coerceIn(0f, 1f)
        val fy = y.coerceIn(0f, 1f)
        val anchorX = if (corner.isLeft) rect.right else rect.left
        val anchorY = if (corner.isTop) rect.bottom else rect.top
        val roomX = if (corner.isLeft) anchorX else 1f - anchorX
        val roomY = if (corner.isTop) anchorY else 1f - anchorY
        var w = abs(fx - anchorX)
        var h = abs(fy - anchorY)
        if (ratio != null) {
            w = min(w, h * ratio)
            w = max(w, max(CropRect.MIN_SIDE, CropRect.MIN_SIDE * ratio))
            w = min(w, min(roomX, roomY * ratio))
            h = w / ratio
        } else {
            w = w.coerceIn(CropRect.MIN_SIDE, roomX)
            h = h.coerceIn(CropRect.MIN_SIDE, roomY)
        }
        val l = if (corner.isLeft) anchorX - w else anchorX
        val t = if (corner.isTop) anchorY - h else anchorY
        return CropRect(l, t, l + w, t + h).clamped()
    }

    /** Moves one edge to the normalized coordinate [v]; only offered when the aspect is free. */
    fun dragEdge(rect: CropRect, edge: Edge, v: Float): CropRect {
        val f = v.coerceIn(0f, 1f)
        val m = CropRect.MIN_SIDE
        return when (edge) {
            Edge.LEFT -> rect.copy(left = min(f, rect.right - m))
            Edge.RIGHT -> rect.copy(right = max(f, rect.left + m))
            Edge.TOP -> rect.copy(top = min(f, rect.bottom - m))
            Edge.BOTTOM -> rect.copy(bottom = max(f, rect.top + m))
        }.clamped()
    }

    /** Translates [rect] by ([dx],[dy]) without letting it leave the picture. */
    fun move(rect: CropRect, dx: Float, dy: Float): CropRect {
        val ddx = dx.coerceIn(-rect.left, 1f - rect.right)
        val ddy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return CropRect(rect.left + ddx, rect.top + ddy, rect.right + ddx, rect.bottom + ddy)
    }

    private fun CropRect.clamped(): CropRect = CropRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    enum class Corner(val isLeft: Boolean, val isTop: Boolean) {
        TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false)
    }

    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }
}
