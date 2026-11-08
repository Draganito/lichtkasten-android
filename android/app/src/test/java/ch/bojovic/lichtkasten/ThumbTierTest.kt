package ch.bojovic.lichtkasten

import ch.bojovic.lichtkasten.media.RustRequest
import ch.bojovic.lichtkasten.ui.adaptiveCellPx
import ch.bojovic.lichtkasten.ui.thumbTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbTierTest {
    @Test
    fun tiersRoundUpAndCapAtTheRustSource() {
        assertEquals(256, thumbTier(100))
        assertEquals(256, thumbTier(256))
        assertEquals(512, thumbTier(257))
        assertEquals(512, thumbTier(512))
        assertEquals(RustRequest.THUMB_SOURCE, thumbTier(513))
        assertEquals(RustRequest.THUMB_SOURCE, thumbTier(3000))
    }

    @Test
    fun adaptiveCellsFillTheRow() {
        // 1080 px wide, 330 px minimum: three columns of 360.
        assertEquals(360, adaptiveCellPx(1080, 330))
        // Minimum wider than the screen: one column, full width.
        assertEquals(1080, adaptiveCellPx(1080, 2000))
        // Degenerate inputs never divide by zero.
        assertEquals(1080, adaptiveCellPx(1080, 0))
        assertEquals(0, adaptiveCellPx(0, 100))
    }

    @Test
    fun smallestAndLargestGridOnAPhone() {
        // 64 dp and 240 dp at 3x density on a 1080 px screen.
        assertEquals(256, thumbTier(adaptiveCellPx(1080, 192)))
        assertEquals(RustRequest.THUMB_SOURCE, thumbTier(adaptiveCellPx(1080, 720)))
    }
}
