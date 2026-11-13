package org.yaop.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCoverPolicyTest {

    @Test
    fun initialState_whenNotEditing_isExpanded() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.EXPANDED)
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
        assertTrue(state.isExpanded)
        assertFalse(state.isCompact)
    }

    @Test
    fun initialState_whenEditing_isCompact() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.COMPACT)
        assertEquals(HeroDisplayMode.COMPACT, state.mode)
        assertTrue(state.isCompact)
        assertFalse(state.isExpanded)
    }

    @Test
    fun notifyEditingState_transitionsToCompact_whenEditingStarts() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.EXPANDED)

        state.notifyEditingState(isEditing = true)
        assertEquals(HeroDisplayMode.COMPACT, state.mode)
        assertTrue(state.isCompact)

        // Ending editing restores EXPANDED format
        state.notifyEditingState(isEditing = false)
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
        assertTrue(state.isExpanded)
    }

    @Test
    fun toggle_manualOverride_preservesUserChoiceUntilEditingEnds() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.EXPANDED)

        // User enters editing -> auto-collapses
        state.notifyEditingState(isEditing = true)
        assertEquals(HeroDisplayMode.COMPACT, state.mode)

        // While editing, user manually toggles to inspect full hero
        state.toggle()
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
        assertTrue(state.userManuallyToggled)

        // Subsequent notification of isEditing=true does not override user's manual choice
        state.notifyEditingState(isEditing = true)
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)

        // Once editing ends completely, manual toggle resets and mode returns to expanded
        state.notifyEditingState(isEditing = false)
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
        assertFalse(state.userManuallyToggled)
    }

    @Test
    fun toggle_whenNotEditing_switchesBetweenExpandedAndCompact() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.EXPANDED)

        state.toggle()
        assertEquals(HeroDisplayMode.COMPACT, state.mode)

        state.toggle()
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
    }

    @Test
    fun directModeAssignment_updatesState() {
        val state = EditorHeroStateImpl(initialMode = HeroDisplayMode.EXPANDED)

        state.mode = HeroDisplayMode.COMPACT
        assertEquals(HeroDisplayMode.COMPACT, state.mode)

        state.mode = HeroDisplayMode.EXPANDED
        assertEquals(HeroDisplayMode.EXPANDED, state.mode)
    }
}
