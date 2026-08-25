package com.idvb.android.recognize.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTemplateDetectorPolicyTest {
    @Test
    fun fullScaleScheduleMatchesDesktopOrder() {
        assertScalesEqual(
            listOf(.275, .23375, .31625, .1925, .37125, .15125, .45375, .55, .66, .77),
            GateTemplateDetector.fullScales(2560.0),
        )
    }

    @Test
    fun warmScaleTakesPriorityAndDeduplicatesRoundedScales() {
        assertScalesEqual(
            listOf(.34, .36, .38, .4, .42, .44, .46),
            GateTemplateDetector.warmScales(.4),
        )
    }

    @Test
    fun rememberedScalePrecedesClientRelativeColdSchedule() {
        val scales = GateTemplateDetector.fullScales(2560.0, rememberedScale = .4)
        assertScalesEqual(listOf(.4, .37, .43, .34, .46), scales.take(5))
    }

    @Test
    fun candidatesAtAdjacentScalesClusterToBestPeak() {
        val first = GateDetection(.91, .30, ScreenRect(100.0, 80.0, 30.0, 30.0))
        val nearby = GateDetection(.86, .33, ScreenRect(102.0, 81.0, 33.0, 33.0))
        val secondGate = GateDetection(.89, .30, ScreenRect(300.0, 180.0, 30.0, 30.0))
        val clusters = GateTemplateDetector.clusterAcrossScales(listOf(nearby, secondGate, first))
        val selected = GateTemplateDetector.selectTopCandidates(clusters)

        assertEquals(2, clusters.size)
        assertEquals(listOf(first, secondGate), selected)
        assertTrue(GateTemplateDetector.intersectionOverUnion(first.screenBounds, nearby.screenBounds) >= .35)
    }

    private fun assertScalesEqual(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertEquals(left, right, 1e-12) }
    }
}
