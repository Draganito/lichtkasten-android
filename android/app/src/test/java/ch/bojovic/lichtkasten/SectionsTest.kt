package ch.bojovic.lichtkasten

import android.net.Uri
import ch.bojovic.lichtkasten.media.GridRow
import ch.bojovic.lichtkasten.media.MediaItem
import ch.bojovic.lichtkasten.media.Sections
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class SectionsTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun item(id: Long, millis: Long) = MediaItem(
        id = id, uri = Mockito.mock(Uri::class.java), mimeType = "image/jpeg", displayName = "$id.jpg",
        relativePath = "DCIM/", sizeBytes = 1, sortMillis = millis, dateModifiedSeconds = 0,
        width = 10, height = 10, orientation = 0, durationMillis = 0, isVideo = false,
    )

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun emptyLibraryHasNoRows() {
        assertTrue(Sections.build(emptyList(), zone).rows.isEmpty())
        assertEquals(0, Sections.EMPTY.rowForItem(3))
        assertNull(Sections.EMPTY.headerForRow(0))
    }

    @Test
    fun oneHeaderPerMonthInListOrder() {
        val items = listOf(
            item(1, at(2026, 11, 9)),
            item(2, at(2026, 11, 1)),
            item(3, at(2026, 10, 31)),
            item(4, at(2025, 10, 5)), // same month name, other year: own header
        )
        val s = Sections.build(items, zone)
        val headers = s.rows.filterIsInstance<GridRow.Header>()
        assertEquals(listOf(GridRow.Header(2026, 11), GridRow.Header(2026, 10), GridRow.Header(2025, 10)), headers)
        assertEquals(items.size + 3, s.rows.size)
        assertTrue(s.rows[0] is GridRow.Header)
        assertEquals(GridRow.Cell(0, items[0]), s.rows[1])
    }

    @Test
    fun rowForItemSkipsHeaders() {
        val items = listOf(item(1, at(2026, 11, 9)), item(2, at(2026, 11, 8)), item(3, at(2026, 10, 1)))
        val s = Sections.build(items, zone)
        // H(11) c0 c1 H(10) c2
        assertEquals(1, s.rowForItem(0))
        assertEquals(2, s.rowForItem(1))
        assertEquals(4, s.rowForItem(2))
        assertEquals(0, s.rowForItem(99)) // out of range: top of the grid
    }

    @Test
    fun headerForRowOwnsEveryRowOfItsMonth() {
        val items = listOf(item(1, at(2026, 11, 9)), item(2, at(2026, 10, 1)))
        val s = Sections.build(items, zone)
        assertEquals(GridRow.Header(2026, 11), s.headerForRow(0))
        assertEquals(GridRow.Header(2026, 11), s.headerForRow(1))
        assertEquals(GridRow.Header(2026, 10), s.headerForRow(2))
        assertEquals(GridRow.Header(2026, 10), s.headerForRow(3))
        assertNull(s.headerForRow(4))
    }

    @Test
    fun monthBoundaryFollowsTheZone() {
        // 2026-10-31 23:30 UTC is already November in Zurich.
        val m = LocalDateTime.of(2026, 10, 31, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(GridRow.Header(2026, 10), Sections.build(listOf(item(1, m)), ZoneOffset.UTC).headerForRow(0))
        assertEquals(GridRow.Header(2026, 11), Sections.build(listOf(item(1, m)), ZoneId.of("Europe/Zurich")).headerForRow(0))
    }

    @Test
    fun undatedItemsShareOneHeader() {
        val items = listOf(item(1, at(2026, 1, 1)), item(2, 0), item(3, -5))
        val s = Sections.build(items, zone)
        val headers = s.rows.filterIsInstance<GridRow.Header>()
        assertEquals(listOf(GridRow.Header(2026, 1), GridRow.Header(0, 0)), headers)
    }
}
