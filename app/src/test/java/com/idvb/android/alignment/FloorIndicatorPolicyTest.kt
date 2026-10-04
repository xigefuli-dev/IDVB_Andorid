package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class FloorIndicatorPolicyTest {
    @Test fun regionUsesPhysicalScreenMidpointAndCalibrationTop() {
        val area = requireNotNull(FloorIndicatorPolicy.region(2401, 1080, 183.25))
        assertEquals(1200.0, area.x, 0.0)
        assertEquals(0.0, area.y, 0.0)
        assertEquals(1201.0, area.width, 0.0)
        assertEquals(184.0, area.height, 0.0)
        assertNull(FloorIndicatorPolicy.region(1080, 2400, 183.25))
        assertNull(FloorIndicatorPolicy.region(2400, 1080, 0.0))
    }

    @Test fun resolutionAndScaleCannotOverruleHighestContentSimilarity() {
        val low = score("hard-1f", "1f", .89, 1.0, 176.0)
        val high = score("nightmare-b1f", "b1f", .90, .43, 104.0)
        assertSame(high, FloorIndicatorPolicy.winner(listOf(low, high), setOf("1f", "b1f")))
    }

    @Test fun lowConfidenceAndTiedScoresStillSelectAFloor() {
        val first = score("hard-2f", "2f", -.5)
        val second = score("nightmare-1f", "1f", -.5)
        assertSame(first, FloorIndicatorPolicy.winner(listOf(first, second), setOf("1f", "2f")))
    }

    @Test fun neverSelectsAFloorMissingFromTheSelectedMap() {
        val basement = score("nightmare-b1f", "b1f", 1.0)
        val second = score("hard-2f", "2f", .1)
        assertSame(second, FloorIndicatorPolicy.winner(listOf(basement, second), setOf("1f", "2f")))
    }

    private fun score(template: String, floor: String, similarity: Double, scale: Double = 1.0, width: Double = 240.0) =
        FloorIndicatorScore(template, floor, similarity, scale, 0.0, 0.0, width, 80.0)
}
