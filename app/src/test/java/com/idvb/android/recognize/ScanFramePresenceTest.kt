package com.idvb.android.recognize

import org.junit.Assert.*
import org.junit.Test

class ScanFramePresenceTest {
    private fun frame(pixel: Int) = IntArray(ScanFramePresence.WIDTH * ScanFramePresence.HEIGHT) { pixel }
    @Test fun knownBlueFogAndNeutralUnknownNeverBecomeNegativeEvidence() {
        for (pixel in listOf(0xff1c222c.toInt(), 0xff000000.toInt(), 0xffeeeeee.toInt()))
            assertFalse(ScanFramePresence.evaluate(frame(pixel)).obviousScene)
    }
    @Test fun warmSceneIsRejectedButSparseColoredMarkersDoNotRejectAMap() {
        val warm = frame(0xff5b4b34.toInt())
        assertTrue(ScanFramePresence.evaluate(warm).obviousScene)
        val map = frame(0xff1c222c.toInt())
        for (i in 0 until map.size / 5) map[i] = 0xffff3030.toInt()
        assertFalse(ScanFramePresence.evaluate(map).obviousScene)
    }
}
