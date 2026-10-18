package com.idvb.android.recognize

import org.junit.Assert.*
import org.junit.Test

class BlackFrameDetectorTest {
    @Test fun allBlackAndNearBlackAreDetectedWithoutAllocatingAnImage() {
        assertTrue(BlackFrameDetector.assess(2400, 1080) { _, _ -> 0xff000000.toInt() }.isBlack)
        val near = BlackFrameDetector.assess(64, 36) { _, _ -> 0xff080708.toInt() }
        assertTrue(near.isBlack)
        assertEquals(2304, near.samples)
        assertEquals(1.0, near.darkFraction, 0.0)
    }
    @Test fun aDarkButVisibleFrameAndASaturatedChannelAreNotBlack() {
        assertFalse(BlackFrameDetector.assess(64, 36) { _, _ -> 0xff101010.toInt() }.isBlack)
        assertFalse(BlackFrameDetector.assess(64, 36) { _, _ -> 0xff000009.toInt() }.isBlack)
        assertFalse(BlackFrameDetector.assess(64, 36) { x, y -> if ((x + y) % 2 == 0) -1 else 0 }.isBlack)
    }
    @Test fun toleratesOnlyTinyBrightArtifactsWithAnExplicitThreshold() {
        assertTrue(BlackFrameDetector.assess(64, 36) { x, y -> if (y == 0 && x < 11) -1 else 0 }.isBlack)
        assertFalse(BlackFrameDetector.assess(64, 36) { x, y -> if (y == 0 && x < 12) -1 else 0 }.isBlack)
    }
    @Test fun tinyAndOddImagesAreSampledInsideTheirBounds() {
        for ((w, h) in listOf(1 to 1, 3 to 7, 65 to 37, 2401 to 1081)) {
            val points = mutableSetOf<Pair<Int, Int>>()
            val result = BlackFrameDetector.assess(w, h) { x, y ->
                assertTrue(x in 0 until w && y in 0 until h)
                assertTrue(points.add(x to y)); 0
            }
            assertEquals(minOf(w, 64) * minOf(h, 36), result.samples)
            assertTrue(result.isBlack)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyInput() {
        BlackFrameDetector.assess(0, 36) { _, _ -> 0 }
    }
}
