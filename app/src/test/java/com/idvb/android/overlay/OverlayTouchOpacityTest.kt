package com.idvb.android.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTouchOpacityTest {
    @Test fun faintCalibrationBorderPreservesDefaultGuideOpacity() {
        val alphas = OverlayTouchOpacity.distribute(.8f, listOf(.46f, .2f, 1f))
        assertEquals(.46f, alphas[0], .00001f)
        assertEquals(.2f, alphas[1], .00001f)
        val combined = 1f - alphas.fold(1f) { transmission, alpha -> transmission * (1f - alpha) }
        assertTrue(combined <= .8f)
    }

    @Test fun requestedLayersStayWithinSystemBudgetForEveryOrdering() {
        for (limit in listOf(0f, .1f, .5f, .8f, 1f)) {
            for (requested in listOf(listOf(.2f, .46f, 1f), listOf(1f, .2f, .46f),
                listOf(.46f, 1f, .2f), List(10) { 1f })) {
                val alphas = OverlayTouchOpacity.distribute(limit, requested)
                val combined = 1f - alphas.fold(1f) { transmission, alpha -> transmission * (1f - alpha) }
                assertTrue("limit=$limit combined=$combined", combined <= limit)
                alphas.forEachIndexed { index, alpha -> assertTrue(alpha <= requested[index]) }
            }
        }
    }

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
