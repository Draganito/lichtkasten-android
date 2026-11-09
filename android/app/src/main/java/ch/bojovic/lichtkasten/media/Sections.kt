package ch.bojovic.lichtkasten.media

import java.time.Instant
import java.time.ZoneId

/**
 * The grid shows the sorted item list cut into months: a full-width header
 * row before the first picture of every month, then the pictures. Built
 * once per reload, never inside composition.
 */
sealed interface GridRow {
    /** `month` 1..12; 0 for items without any date (they sort last). */
    data class Header(val year: Int, val month: Int) : GridRow

    data class Cell(val index: Int, val item: MediaItem) : GridRow
}

class Sections private constructor(
    val rows: List<GridRow>,
    /** Row index of item `i`. */
    private val rowOfItem: IntArray,
    /** Row index of the header that owns row `r`. */
    private val headerOfRow: IntArray,
) {
    fun rowForItem(index: Int): Int = rowOfItem.getOrElse(index) { 0 }

    fun headerForRow(row: Int): GridRow.Header? =
        headerOfRow.getOrNull(row)?.let { rows.getOrNull(it) as? GridRow.Header }

    companion object {
        val EMPTY = Sections(emptyList(), IntArray(0), IntArray(0))

        fun build(items: List<MediaItem>, zone: ZoneId = ZoneId.systemDefault()): Sections {
            if (items.isEmpty()) return EMPTY
            val rows = ArrayList<GridRow>(items.size + 64)
            val rowOfItem = IntArray(items.size)
            val headerOfRow = ArrayList<Int>(items.size + 64)
            var current: GridRow.Header? = null
            var headerRow = 0
            items.forEachIndexed { index, item ->
                val header = headerFor(item.sortMillis, zone)
                if (header != current) {
                    current = header
                    headerRow = rows.size
                    rows += header
                    headerOfRow += headerRow
                }
                rowOfItem[index] = rows.size
                rows += GridRow.Cell(index, item)
                headerOfRow += headerRow
            }
            return Sections(rows, rowOfItem, headerOfRow.toIntArray())
        }

        private fun headerFor(millis: Long, zone: ZoneId): GridRow.Header {
            if (millis <= 0L) return GridRow.Header(0, 0)
            val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            return GridRow.Header(date.year, date.monthValue)
        }
    }
}
