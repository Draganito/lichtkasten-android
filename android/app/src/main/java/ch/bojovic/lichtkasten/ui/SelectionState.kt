package ch.bojovic.lichtkasten.ui

/**
 * Multi-select like a file manager. Pure data so it is unit-testable
 * without Android.
 */
data class SelectionState(
    val active: Boolean = false,
    val ids: Set<Long> = emptySet(),
) {
    val count: Int get() = ids.size

    fun toggle(id: Long): SelectionState {
        val next = if (id in ids) ids - id else ids + id
        return copy(active = true, ids = next)
    }

    fun selectAll(all: Collection<Long>): SelectionState = copy(active = true, ids = all.toSet())

    fun clear(): SelectionState = copy(ids = emptySet())

    /** Drop ids that no longer exist (after a delete or a rescan). */
    fun retain(existing: Set<Long>): SelectionState = copy(ids = ids.filterTo(HashSet()) { it in existing })

    /** "All" when something is unselected, "None" when everything is selected. */
    fun allSelected(total: Int): Boolean = total > 0 && ids.size >= total
}
