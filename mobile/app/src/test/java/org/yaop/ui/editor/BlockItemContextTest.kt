package org.yaop.ui.editor

import org.yaop.core.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockItemContextTest {

    @Test
    fun blockVisualState_equalityEnablesComposeSkipping() {
        val state1 = BlockVisualState(
            isFocused = false,
            isSelected = false,
            isSelectionMode = false,
            hiddenChildrenCount = 0,
        )

        val state2 = BlockVisualState(
            isFocused = false,
            isSelected = false,
            isSelectionMode = false,
            hiddenChildrenCount = 0,
        )

        // Same properties must be structurally equal for Compose smart recomposition skipping
        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())

        val focusedState = state1.copy(isFocused = true)
        assertNotEquals(state1, focusedState)
    }

    @Test
    fun blockActionCallbacks_defaultImplementations_doNotThrow() {
        var updateCalled = false
        var deleteCalled = false
        var enterCalled = false

        val callbacks = object : BlockActionCallbacks {
            override fun onUpdate(updatedBlock: Block) {
                updateCalled = true
            }

            override fun onDelete() {
                deleteCalled = true
            }

            override fun onEnterPressed(before: String, after: String) {
                enterCalled = true
            }

            override fun onBackspaceOnEmpty() {}
        }

        // Call optional default methods
        callbacks.onFocusGained()
        callbacks.onFocusCleared()
        callbacks.onCursorMoved()
        callbacks.onToggleSelection()
        callbacks.onHorizontalIndentDelta(1)

        val dummyParagraph = Block.Paragraph(id = "p1")
        callbacks.onUpdate(dummyParagraph)
        callbacks.onDelete()
        callbacks.onEnterPressed("hello", "world")

        assertTrue(updateCalled)
        assertTrue(deleteCalled)
        assertTrue(enterCalled)
    }

    @Test
    fun blockNavigationCallbacks_defaultImplementations_doNotThrow() {
        var navigatedId: String? = null
        val navigation = object : BlockNavigationCallbacks {
            override fun onNavigateToObject(objectId: String) {
                navigatedId = objectId
            }
        }

        navigation.onNavigateToTag("tag1")
        navigation.onNavigateToObject("obj-123")
        assertEquals("obj-123", navigatedId)
    }
}
