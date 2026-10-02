package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentLatencyTest {
    @Test fun onlyAlignmentComputationIsExcludedWhileCaptureQueueAndDrawStayCounted() {
        val latency = AlignmentLatency.measure(1_000_000L, 811_000_000L, 700_000_000L)
        assertEquals(810.0, latency.totalMs, 0.0)
        assertEquals(110.0, latency.overheadMs, 0.0)
        assertTrue(latency.withinBudget)
    }

    @Test fun excessivePreparationMustFailEvenWhenAlignmentIsFast() {
        val latency = AlignmentLatency.measure(0L, 171_000_000L, 50_000_000L)
        assertEquals(121.0, latency.overheadMs, 0.0)
        assertFalse(latency.withinBudget)
        assertTrue(AlignmentLatency.measure(0L, 170_000_000L, 50_000_000L).withinBudget)
    }
}
