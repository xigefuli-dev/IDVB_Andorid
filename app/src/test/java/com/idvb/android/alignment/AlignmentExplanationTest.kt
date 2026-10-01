package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentExplanationTest {
    @Test fun finalWinnerGatesTakePriorityOverRejectedRivals() {
        val events = listOf(
            AlignmentLogEvent("vpsg.verify.pose-evidence", gates = listOf(AlignmentGate("visible-support", .2, ">=", .88, false))),
            AlignmentLogEvent("vpsg.verify.final", gates = listOf(AlignmentGate("longest-conflict", 30.1, "<", 30.0, false))))
        val result = AlignmentExplanation.rejected(events)
        assertEquals("longest-conflict", result.code)
        assertTrue(result.reason.contains("30.1"))
        assertFalse(result.reason.contains("结构支持"))
    }
    @Test fun smallVisibleRegionExplainsTheMeasuredLimit() {
        val result = AlignmentExplanation.rejected(listOf(AlignmentLogEvent("vpsg.observation.bounds",
            gates = listOf(AlignmentGate("live-span-x", 63.0, ">=", 100.0, false)))))
        assertTrue(result.reason.contains("63 px"))
        assertTrue(result.reason.contains("100 px"))
        assertEquals("live-span-x", result.code)
    }
}
