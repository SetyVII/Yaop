package org.yaop.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtleSwipeActionBoxTest {

    @Test
    fun `isTouchInEdgeDeadZone correctly detects touches near left and right screen edges`() {
        val screenWidthPx = 1080f
        val deadZonePx = 72f // e.g. 24dp at 3.0 density

        // Extreme left edge touches (should trigger system back gesture)
        assertTrue(isTouchInEdgeDeadZone(0f, screenWidthPx, deadZonePx))
        assertTrue(isTouchInEdgeDeadZone(30f, screenWidthPx, deadZonePx))
        assertTrue(isTouchInEdgeDeadZone(72f, screenWidthPx, deadZonePx))

        // Safe inner content area (should allow subtle app swipes)
        assertFalse(isTouchInEdgeDeadZone(73f, screenWidthPx, deadZonePx))
        assertFalse(isTouchInEdgeDeadZone(540f, screenWidthPx, deadZonePx))
        assertFalse(isTouchInEdgeDeadZone(1007f, screenWidthPx, deadZonePx))

        // Extreme right edge touches (should trigger system back gesture)
        assertTrue(isTouchInEdgeDeadZone(1008f, screenWidthPx, deadZonePx))
        assertTrue(isTouchInEdgeDeadZone(1050f, screenWidthPx, deadZonePx))
        assertTrue(isTouchInEdgeDeadZone(1080f, screenWidthPx, deadZonePx))
    }

    @Test
    fun `calculateSubtleOffset damps movement and caps at maxTravelPx`() {
        val thresholdPx = 144f // ~48dp
        val maxTravelPx = 204f // ~68dp

        // Small initial movement should be slightly scaled
        val smallOffset = calculateSubtleOffset(50f, thresholdPx, maxTravelPx)
        assertEquals(50f * 0.82f, smallOffset, 0.01f)

        // Threshold movement
        val atThreshold = calculateSubtleOffset(144f, thresholdPx, maxTravelPx)
        assertEquals(144f * 0.82f, atThreshold, 0.01f)

        // Past threshold, rubber-banding applies with lower slope
        val pastThreshold = calculateSubtleOffset(200f, thresholdPx, maxTravelPx)
        val expectedPast = 144f * 0.82f + (200f - 144f) * 0.28f
        assertEquals(expectedPast, pastThreshold, 0.01f)

        // Huge drags must be hard-capped at maxTravelPx
        val hugeOffset = calculateSubtleOffset(1200f, thresholdPx, maxTravelPx)
        assertEquals(maxTravelPx, hugeOffset, 0.01f)

        // Negative (leftward) swipes preserve sign and cap symmetrically
        val hugeLeftOffset = calculateSubtleOffset(-1200f, thresholdPx, maxTravelPx)
        assertEquals(-maxTravelPx, hugeLeftOffset, 0.01f)
    }
}
