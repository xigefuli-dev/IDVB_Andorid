package com.idvb.android.recognize

import com.idvb.android.recognize.vpsg.VpsgFastSolver
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VpsgFastSolverTest {
    @Test fun sharedCoordinateRefinementExactlyMatchesScalarSearch() {
        val random = Random(58091)
        repeat(30) { iteration ->
            val width = 97; val height = 91
            val k3 = LongArray(2 * height) { random.nextLong() and random.nextLong() }
            val k5 = LongArray(2 * height) { k3[it] or random.nextLong() }
            val index = VpsgFastSolver.Index(width, height, k3, k5, VpsgFastSolver.Peak(20.0, 3.0), 500)
            val points = List(150) { VpsgFastSolver.Point(random.nextInt(-10, 140), random.nextInt(-10, 130)) }
            val seed = VpsgFastSolver.Pose(listOf(.35, .4, 1.013, 2.5, 3.98)[iteration % 5],
                random.nextDouble(-20.0, 20.0), random.nextDouble(-20.0, 20.0))
            val expectedProbes = mutableListOf<Double>()
            var expected = seed.copy(score = VpsgFastSolver.score(points, index, seed))
            fun probe(center: VpsgFastSolver.Pose, ds: Double, dx: Double, dy: Double) {
                val s = center.scale + ds
                if (s !in .35..4.0) return
                val pose = VpsgFastSolver.Pose(s, 80.0 - (80.0 - center.x) / center.scale * s + dx,
                    60.0 - (60.0 - center.y) / center.scale * s + dy)
                val value = VpsgFastSolver.score(points, index, pose, expected.score)
                expectedProbes.addAll(listOf(s, pose.x, pose.y, value))
                if (value > expected.score) expected = pose.copy(score = value)
            }
            for (ds in doubleArrayOf(-.020, -.015, 0.0, .015, .020))
                for (dx in -6..6 step 2) for (dy in -6..6 step 2) probe(seed, ds, dx.toDouble(), dy.toDouble())
            val coarse = expected
            for (ds in doubleArrayOf(-.020, -.010, -.005, .005, .010, .020)) probe(coarse, ds, 0.0, 0.0)
            val fine = expected
            for (ds in doubleArrayOf(-.005, 0.0, .005)) for (dx in doubleArrayOf(-1.5, 0.0, 1.5))
                for (dy in doubleArrayOf(-1.5, 0.0, 1.5)) probe(fine, ds, dx, dy)
            val trace = com.idvb.android.alignment.AlignmentTrace()
            val actual = VpsgFastSolver.refine(points, index, seed, 160, 120, trace, 4.0)
            assertEquals("iteration=$iteration", expected, actual)
            assertEquals(expectedProbes, trace.snapshot().first { it.stage == "vpsg.refine.discrete" }.series["probesScaleXYScore"])
        }
    }
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
