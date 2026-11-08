package ch.bojovic.lichtkasten

import ch.bojovic.lichtkasten.ui.SelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    @Test
    fun toggleAddsAndRemovesAndActivates() {
        val s0 = SelectionState()
        assertFalse(s0.active)
        val s1 = s0.toggle(5)
        assertTrue(s1.active)
        assertEquals(setOf(5L), s1.ids)
        val s2 = s1.toggle(5)
        assertTrue(s2.active)
        assertEquals(emptySet<Long>(), s2.ids)
    }

    @Test
    fun selectAllAndNone() {
        val all = listOf(1L, 2L, 3L)
        val s = SelectionState().selectAll(all)
        assertEquals(3, s.count)
        assertTrue(s.allSelected(3))
        val cleared = s.clear()
        assertTrue(cleared.active)
        assertEquals(0, cleared.count)
        assertFalse(cleared.allSelected(3))
    }

    @Test
    fun retainDropsVanishedIds() {
        val s = SelectionState().selectAll(listOf(1L, 2L, 3L)).retain(setOf(2L, 3L, 9L))
        assertEquals(setOf(2L, 3L), s.ids)
    }

    @Test
    fun allSelectedIsFalseForEmptyLibrary() {
        assertFalse(SelectionState().allSelected(0))
    }
}
