package com.idvb.android.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTouchOpacityTest {
    @Test fun singleWindowUsesSystemLimitWithRoundingMargin() {
        assertEquals(.799f, OverlayTouchOpacity.cap(.8f, 1), .00001f)
    }

    @Test fun overlappingGuideProgressAndLockedControlsStayWithinBudget() {
        for (limit in listOf(0f, .1f, .5f, .8f, 1f)) {
            for (count in 1..10) {
                val cap = OverlayTouchOpacity.cap(limit, count)
                var combined = 0f
                repeat(count) { combined = 1f - (1f - combined) * (1f - cap) }
                assertTrue("limit=$limit count=$count combined=$combined", combined <= limit)
            }
        }
    }

    @Test fun defaultMapOpacityIsPreservedAlongsideProgress() {
        val cap = OverlayTouchOpacity.cap(.8f, 2)
        assertTrue(.46f < cap)
        assertTrue(1f - (1f - .46f) * (1f - cap) < .8f)
    }

    @Test fun removalRestoresAvailableOpacity() {
        assertTrue(OverlayTouchOpacity.cap(.8f, 1) > OverlayTouchOpacity.cap(.8f, 2))
    }
}
