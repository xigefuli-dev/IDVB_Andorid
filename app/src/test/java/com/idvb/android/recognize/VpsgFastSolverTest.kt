package com.idvb.android.recognize

import com.idvb.android.recognize.vpsg.VpsgFastSolver
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VpsgFastSolverTest {
    @Test fun bitPlanesEqualScalarIncludingNegativeOffsetsAndPartialWords() {
        val random = Random(93457)
        for (width in listOf(63, 64, 65, 130)) {
            val height = 37
            val words = LongArray((width + 63) / 64 * height)
            repeat(width * height / 4) {
                val x = random.nextInt(width); val y = random.nextInt(height)
                val at = y * ((width + 63) / 64) + (x ushr 6)
                words[at] = words[at] or (1L shl (x and 63))
            }
            val index = VpsgFastSolver.Index(width, height, words, words, VpsgFastSolver.Peak(20.0, 3.0), 300)
            val points = List(150) { VpsgFastSolver.Point(random.nextInt(-10, width + 10), random.nextInt(-5, 45)) }
            for (stride in listOf(1, 4, 7)) {
                val scores = VpsgFastSolver.scoreGrid(points, index, -70, 71, -8, 15, stride)
                var at = 0
                for (y in -8..15 step stride) for (x in -70..71 step stride)
                    assertEquals("width=$width stride=$stride x=$x y=$y",
                        points.count { index.hit(words, it.x + x, it.y + y) }, scores[at++])
            }
        }
    }

    @Test fun bitPlanesCarryAcrossAllEightBits() {
        val words = LongArray(128 * 2) { -1L }
        val index = VpsgFastSolver.Index(128, 128, words, words, VpsgFastSolver.Peak(20.0, 3.0), 300)
        val points = List(256) { VpsgFastSolver.Point(0, 0) }
        assertTrue(VpsgFastSolver.scoreGrid(points, index, 0, 127, 0, 127).all { it == 256 })
    }

    @Test fun scaleUsesPitchRatioAndRejectsDegenerateReferences() {
        val reference = DoubleArray(800) { if (it % 40 <= 1) 1.0 else 0.0 }
        val live = DoubleArray(1000) { if (it % 60 <= 1) 1.0 else 0.0 }
        val prior = VpsgFastSolver.Correlation(reference).peak()
        val index = VpsgFastSolver.Index(800, 500, LongArray(0), LongArray(0), prior, 1000)
        assertEquals(1.5, VpsgFastSolver.scale(VpsgFastSolver.Correlation(live), index)!!, .01)
        assertNull(VpsgFastSolver.scale(VpsgFastSolver.Correlation(live), index.copy(edgeCount = 20)))
        assertEquals(0.0, VpsgFastSolver.Correlation(DoubleArray(800)).peak().pitch, 0.0)
    }
}
