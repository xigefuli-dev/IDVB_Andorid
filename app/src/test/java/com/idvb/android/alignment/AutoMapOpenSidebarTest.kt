package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AutoMapOpenSidebarTest {
    private fun color(y: Int): Int = if (y in 2..4 || y in 8..15 || y in 20..21) 0xff909ba8.toInt() else 0xff1b2029.toInt()
    private val template = AutoMapOpenDetector.signature(IntArray(768) { color(it / 32) })

    @Test fun sidebarIsLocatedInsideAWiderStripWithItsAspectRatioPreserved() {
        val strip = AutoMapOpenDetector.signature(IntArray(768) { index ->
            if (index % 32 in 10..21) color(index / 32) else 0xff1b2029.toInt()
        })
        assertTrue(AutoMapOpenDetector.compare(template, strip).score < .85)
        val match = AutoMapOpenDetector.compareSidebar(template, strip, 12.0 / 32)
        assertTrue(match.score >= .85)
        assertTrue(match.windowWidth < 1)
        assertTrue(match.windowLeft > 0)
        val detector = AutoMapOpenDetector(template, AutoMapOpenConfig(sidebarWidthFraction = 12.0 / 32))
        assertEquals(AutoMapOpenTransition.NONE, detector.observe(strip, 0).transition)
        assertEquals(AutoMapOpenTransition.OPENED, detector.observe(strip, 350).transition)
    }

    @Test fun aFlatDarkGameFrameCannotReplaceTheSidebarsFixedDetails() {
        val candidate = AutoMapOpenDetector.signature(IntArray(768) { 0xff1b2029.toInt() })
        assertTrue(AutoMapOpenDetector.compareSidebar(template, candidate, .4).score < .85)
    }

    @Test fun fullWidthReferencesKeepTheOriginalScoresAndInvalidSearchIsRejected() {
        assertEquals(1.0, AutoMapOpenDetector.compareSidebar(template, template, 1.0).score, 1e-9)
        for (width in listOf(0.0, -1.0, Double.NaN, 1.1)) {
            try {
                AutoMapOpenConfig(sidebarWidthFraction = width)
                fail("invalid sidebar width must not create an active detector")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
