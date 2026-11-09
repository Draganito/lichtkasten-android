package ch.bojovic.lichtkasten

import ch.bojovic.lichtkasten.media.CropRect
import ch.bojovic.lichtkasten.media.ImageGeometry
import ch.bojovic.lichtkasten.media.ImageGeometry.Corner
import ch.bojovic.lichtkasten.media.ImageGeometry.Edge
import ch.bojovic.lichtkasten.media.IntBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGeometryTest {
    private val eps = 1e-4f

    private fun assertRect(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    private fun assertInside(r: CropRect) {
        assertTrue(r.toString(), r.left >= 0f && r.top >= 0f && r.right <= 1f + eps && r.bottom <= 1f + eps)
        assertTrue(r.toString(), r.width >= CropRect.MIN_SIDE - eps && r.height >= CropRect.MIN_SIDE - eps)
    }

    @Test
    fun toPixelsRoundsAndNeverCollapses() {
        assertEquals(IntBox(0, 0, 4000, 3000), CropRect.FULL.toPixels(4000, 3000))
        assertEquals(IntBox(1000, 750, 3000, 2250), CropRect(0.25f, 0.25f, 0.75f, 0.75f).toPixels(4000, 3000))
        val tiny = CropRect(0.5f, 0.5f, 0.5f, 0.5f).toPixels(10, 10)
        assertEquals(1, tiny.width)
        assertEquals(1, tiny.height)
        val edge = CropRect(0.999f, 0.999f, 1f, 1f).toPixels(10, 10)
        assertEquals(IntBox(9, 9, 10, 10), edge)
    }

    @Test
    fun exportScaleNeverUpscalesAndHonoursTargetAndCap() {
        assertEquals(1f, ImageGeometry.exportScale(1000, 800, 0), eps)
        assertEquals(1f, ImageGeometry.exportScale(1000, 800, 2048), eps)
        assertEquals(0.5f, ImageGeometry.exportScale(4096, 3000, 2048), eps)
        assertEquals(0.5f, ImageGeometry.exportScale(3000, 4096, 2048), eps)
        // 8000x6000 = 48 MP must come down to the 32 MP cap even at "original".
        val s = ImageGeometry.exportScale(8000, 6000, 0)
        val (w, h) = ImageGeometry.scaled(8000, 6000, s)
        assertTrue(w.toLong() * h <= ImageGeometry.MAX_OUT_PIXELS)
        assertTrue(w.toLong() * h > ImageGeometry.MAX_OUT_PIXELS * 0.98)
    }

    @Test
    fun scaledKeepsAspectAndMinimumOfOne() {
        assertEquals(2048 to 1536, ImageGeometry.scaled(4096, 3072, 0.5f))
        assertEquals(1 to 1, ImageGeometry.scaled(10, 10, 0.001f))
    }

    @Test
    fun centeredCropFitsTheRatioInsideThePicture() {
        // Square crop in a 3:2 picture: full height, centred horizontally.
        val sq = ImageGeometry.centeredCrop(1f, 1.5f)
        assertEquals(0f, sq.top, eps)
        assertEquals(1f, sq.bottom, eps)
        assertEquals(1f / 1.5f, sq.width, eps)
        assertEquals((1f - sq.width) / 2f, sq.left, eps)
        // 16:9 in a 4:3 picture: full width, letterboxed.
        val wide = ImageGeometry.centeredCrop(16f / 9f, 4f / 3f)
        assertEquals(1f, wide.width, eps)
        assertEquals((4f / 3f) / (16f / 9f), wide.height, eps)
        assertEquals(CropRect.FULL, ImageGeometry.centeredCrop(null, 1.5f))
    }

    @Test
    fun normalizedRatioMatchesPixelRatio() {
        // A 1:1 pixel crop in a 4000x3000 picture is 0.75 wide per 1.0 high in normalized space.
        val r = ImageGeometry.normalizedRatio(1f, 4000f / 3000f)
        val crop = ImageGeometry.centeredCrop(1f, 4000f / 3000f)
        val px = crop.toPixels(4000, 3000)
        assertEquals(0.75f, r, eps)
        assertEquals(px.width, px.height)
    }

    @Test
    fun freeCornerDragMovesOnlyThatCorner() {
        val r = ImageGeometry.dragCorner(CropRect.FULL, Corner.BOTTOM_RIGHT, 0.6f, 0.7f, null)
        assertRect(CropRect(0f, 0f, 0.6f, 0.7f), r)
        val r2 = ImageGeometry.dragCorner(CropRect.FULL, Corner.TOP_LEFT, 0.3f, 0.1f, null)
        assertRect(CropRect(0.3f, 0.1f, 1f, 1f), r2)
    }

    @Test
    fun cornerDragRespectsMinimumAndPictureBounds() {
        val r = ImageGeometry.dragCorner(CropRect.FULL, Corner.BOTTOM_RIGHT, 0.0f, 0.0f, null)
        assertEquals(CropRect.MIN_SIDE, r.width, eps)
        assertEquals(CropRect.MIN_SIDE, r.height, eps)
        val out = ImageGeometry.dragCorner(CropRect.FULL, Corner.BOTTOM_RIGHT, 5f, -3f, null)
        assertInside(out)
    }

    @Test
    fun lockedCornerDragKeepsRatioAndStaysInside() {
        val ratio = 0.75f // square in 4:3
        val start = ImageGeometry.centeredCrop(1f, 4f / 3f)
        val r = ImageGeometry.dragCorner(start, Corner.BOTTOM_RIGHT, 0.5f, 0.5f, ratio)
        assertEquals(ratio, r.width / r.height, eps)
        assertEquals(start.left, r.left, eps)
        assertEquals(start.top, r.top, eps)
        assertInside(r)
        // Dragging far beyond the picture: clamps to the available room, ratio intact.
        val big = ImageGeometry.dragCorner(start, Corner.BOTTOM_RIGHT, 2f, 2f, ratio)
        assertEquals(ratio, big.width / big.height, eps)
        assertInside(big)
        assertTrue(big.right <= 1f + eps && big.bottom <= 1f + eps)
        // Tiny: minimum applies to both sides through the ratio.
        val small = ImageGeometry.dragCorner(start, Corner.BOTTOM_RIGHT, start.left, start.top, ratio)
        assertEquals(ratio, small.width / small.height, eps)
        assertTrue(small.height >= CropRect.MIN_SIDE - eps && small.width >= CropRect.MIN_SIDE - eps)
    }

    @Test
    fun edgeDragKeepsTheOppositeEdge() {
        val r = ImageGeometry.dragEdge(CropRect.FULL, Edge.LEFT, 0.4f)
        assertRect(CropRect(0.4f, 0f, 1f, 1f), r)
        val crossed = ImageGeometry.dragEdge(CropRect.FULL, Edge.LEFT, 1.5f)
        assertEquals(1f - CropRect.MIN_SIDE, crossed.left, eps)
        val top = ImageGeometry.dragEdge(CropRect(0.2f, 0.2f, 0.8f, 0.8f), Edge.TOP, 0.75f)
        assertEquals(0.75f, top.top, eps)
    }

    /** Where stored pixel (x, y) lands after rotating a w×h picture clockwise. */
    private fun rotatePoint(x: Int, y: Int, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        90 -> (h - 1 - y) to x
        180 -> (w - 1 - x) to (h - 1 - y)
        270 -> y to (w - 1 - x)
        else -> x to y
    }

    @Test
    fun toStoredMatchesBruteForceRotation() {
        val w = 7
        val h = 5
        for (rotation in listOf(0, 90, 180, 270)) {
            val (ow, oh) = ImageGeometry.orientedSize(w, h, rotation)
            // Every oriented box up to 3x3 at every position.
            for (t in 0 until oh) for (l in 0 until ow) for (bh in 1..3) for (bw in 1..3) {
                val r = l + bw
                val b = t + bh
                if (r > ow || b > oh) continue
                val box = IntBox(l, t, r, b)
                // Stored pixels whose rotated position falls into the box.
                var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
                for (y in 0 until h) for (x in 0 until w) {
                    val (ox, oy) = rotatePoint(x, y, w, h, rotation)
                    if (ox in l until r && oy in t until b) {
                        minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                    }
                }
                val expected = IntBox(minX, minY, maxX + 1, maxY + 1)
                assertEquals("rotation $rotation box $box", expected, ImageGeometry.toStored(box, w, h, rotation))
            }
        }
    }

    @Test
    fun orientedSizeSwapsForQuarterTurns() {
        assertEquals(3000 to 4000, ImageGeometry.orientedSize(4000, 3000, 90))
        assertEquals(3000 to 4000, ImageGeometry.orientedSize(4000, 3000, 270))
        assertEquals(4000 to 3000, ImageGeometry.orientedSize(4000, 3000, 180))
        assertEquals(4000 to 3000, ImageGeometry.orientedSize(4000, 3000, 0))
    }

    @Test
    fun moveStopsAtThePictureEdge() {
        val r = CropRect(0.2f, 0.2f, 0.6f, 0.6f)
        assertRect(CropRect(0.3f, 0.1f, 0.7f, 0.5f), ImageGeometry.move(r, 0.1f, -0.1f))
        val pinned = ImageGeometry.move(r, 5f, -5f)
        assertEquals(1f, pinned.right, eps)
        assertEquals(0f, pinned.top, eps)
        assertEquals(r.width, pinned.width, eps)
        assertEquals(r.height, pinned.height, eps)
    }
}
