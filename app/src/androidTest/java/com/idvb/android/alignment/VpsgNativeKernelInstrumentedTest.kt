package com.idvb.android.alignment

import com.idvb.android.recognize.vpsg.VpsgFastSolver
import com.idvb.android.recognize.vpsg.VpsgNativeKernel
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VpsgNativeKernelInstrumentedTest {
    @Test fun forwardResidualsCellsAndContoursMatchTheIndependentOracle() {
        val random = Random(88421)
        val width = 96; val height = 80; val rw = 130; val rh = 120
        val points = IntArray(2048 * 2) { if (it and 1 == 0) random.nextInt(width) else random.nextInt(height) }
        val segments = intArrayOf(0, 0, 95, 79, 8, 7, 90, 7, 80, 6, 80, 75, 70, 40, 70, 40)
        for (maximum in listOf(1.0, 9.0)) for (scale in listOf(.5, 1.1)) {
            val distances = FloatArray(rw * rh) { random.nextDouble(0.0, maximum).toFloat() }
            val tx = -12.5; val ty = -6.25
            fun distance(px: Int, py: Int): Double {
                val x = (px - tx) / scale; val y = (py - ty) / scale
                if (x < 0 || y < 0 || x >= rw - 1 || y >= rh - 1) return 50.0
                val ix = x.toInt(); val iy = y.toInt(); val fx = x - ix; val fy = y - iy
                val top = distances[iy * rw + ix] * (1 - fx) + distances[iy * rw + ix + 1] * fx
                val bottom = distances[(iy + 1) * rw + ix] * (1 - fx) + distances[(iy + 1) * rw + ix + 1] * fx
                return (top * (1 - fy) + bottom * fy) * scale
            }
            val count = points.size / 2; var hits = 0; var sum = 0.0; var longest = 0.0
            val totals = IntArray(16); val cellHits = IntArray(16); val residuals = DoubleArray(count)
            for (i in 0 until count) {
                val x = points[i * 2]; val y = points[i * 2 + 1]; val d = distance(x, y)
                residuals[i] = d; sum += d
                val q = minOf(3, x * 4 / width) + 4 * minOf(3, y * 4 / height)
                totals[q]++; if (d <= 5.5) { hits++; cellHits[q]++ }
            }
            val check = hits / count.toDouble() >= .88 && (0..15).none { totals[it] >= 30 && cellHits[it] < totals[it] * .70 }
            val evidence = mutableListOf<Double>()
            if (check) for (i in segments.indices step 4) {
                val ax = segments[i]; val ay = segments[i + 1]; val bx = segments[i + 2]; val by = segments[i + 3]
                val dx = bx - ax; val dy = by - ay; val steps = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                if (steps == 0) continue
                val stride = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) / steps
                var run = 0.0; var segmentMax = 0.0
                for (step in 0..steps) {
                    val x = kotlin.math.floor(ax + dx * step.toDouble() / steps + .5).toInt()
                    val y = kotlin.math.floor(ay + dy * step.toDouble() / steps + .5).toInt()
                    if (distance(x, y) > 5.5) run += if (step == 0) 0.0 else stride else run = 0.0
                    longest = maxOf(longest, run); segmentMax = maxOf(segmentMax, run)
                }
                evidence.addAll(listOf(ax.toDouble(), ay.toDouble(), bx.toDouble(), by.toDouble(), segmentMax))
            }
            val actual = VpsgNativeKernel.verifyForward(points, segments, VpsgNativeKernel.direct(distances), rw, rh, width, height, scale, tx, ty, true)
            assertEquals(hits.toDouble(), actual[0], 0.0); assertEquals(sum, actual[1], 0.0)
            assertEquals(longest, actual[2], 1e-10); assertEquals(if (check) 1.0 else 0.0, actual[3], 0.0)
            assertArrayEquals(totals.map(Int::toDouble).toDoubleArray(), actual.copyOfRange(6, 22), 0.0)
            assertArrayEquals(cellHits.map(Int::toDouble).toDoubleArray(), actual.copyOfRange(22, 38), 0.0)
            assertArrayEquals(residuals, actual.copyOfRange(38, 38 + count), 0.0)
            assertArrayEquals(evidence.toDoubleArray(), actual.copyOfRange(38 + count, actual.size), 1e-10)
        }
    }
    @Test fun continuousLossBatchesEqualTheIndependentBilinearOracle() {
        assertTrue(VpsgNativeKernel.available)
        val random = Random(62633)
        val width = 140; val height = 130
        val distances = FloatArray(width * height) { random.nextDouble(0.0, 8.0).toFloat() }
        val points = IntArray(256 * 2) { random.nextInt(-10, 150) }
        val cx = 70.0; val cy = 65.0; val rcx = 50.25; val rcy = 51.5
        val poses = DoubleArray(27 * 3) { when (it % 3) { 0 -> .55 + it / 3 * .01; 1 -> (it / 3 % 3 - 1).toDouble(); else -> (it / 3 / 3 % 3 - 1).toDouble() } }
        val expected = DoubleArray(27) { i ->
            val scale = poses[i * 3]; val dx = poses[i * 3 + 1]; val dy = poses[i * 3 + 2]
            val counts = IntArray(4); val sums = DoubleArray(4)
            for (p in 0 until points.size / 2) {
                val px = points[p * 2]; val py = points[p * 2 + 1]
                val q = (if (px < cx) 0 else 1) + (if (py < cy) 0 else 2)
                val x = rcx + (px - cx - dx) / scale; val y = rcy + (py - cy - dy) / scale
                var d = 50.0
                if (x >= 0 && y >= 0 && x < width - 1 && y < height - 1) {
                    val ix = x.toInt(); val iy = y.toInt(); val fx = x - ix; val fy = y - iy
                    val top = distances[iy * width + ix] * (1 - fx) + distances[iy * width + ix + 1] * fx
                    val bottom = distances[(iy + 1) * width + ix] * (1 - fx) + distances[(iy + 1) * width + ix + 1] * fx
                    d = (top * (1 - fy) + bottom * fy) * scale
                }
                d = minOf(6.0, d); counts[q]++
                sums[q] += if (d <= 1.5) .5 * d * d else 1.5 * (d - .75)
            }
            (0..3).filter { counts[it] >= 15 }.map { sums[it] / counts[it] }.average()
        }
        assertArrayEquals(expected, VpsgNativeKernel.precisionLosses(points, poses, VpsgNativeKernel.direct(distances), width, height, cx, cy, rcx, rcy), 0.0)
    }
    @Test fun nativeReversePreservesVisibilitySamplesResidualsAndCounts() {
        assertTrue(VpsgNativeKernel.available)
        val random = Random(84517)
        val width = 65; val height = 43; val referenceWidth = 97
        val positions = IntArray(4000) { it * 2 }
        val domain = ByteArray(width * height) { if (random.nextBoolean()) 255.toByte() else 0 }
        val distances = FloatArray(width * height) { random.nextDouble(0.0, 10.0).toFloat() }
        val buffer = java.nio.ByteBuffer.allocateDirect(distances.size * 4).order(java.nio.ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(distances)
        for (legacy in listOf(false, true)) for (scale in listOf(.4, .5, 1.3, 4.0)) {
            val tx = -15.5; val ty = -20.5
            val eligible = mutableListOf<Int>()
            var outside = 0; var unknown = 0; var hits = 0
            val step = maxOf(1, (positions.size + 2047) / 2048)
            for ((i, position) in positions.withIndex()) {
                if (legacy && i % step != 0) continue
                val ix = kotlin.math.floor(position % referenceWidth * scale + tx + .5).toInt()
                val iy = kotlin.math.floor(position / referenceWidth * scale + ty + .5).toInt()
                if (ix !in 0 until width || iy !in 0 until height) { outside++; continue }
                if ((domain[iy * width + ix].toInt() and 255) <= 128) { unknown++; continue }
                eligible += position
            }
            val count = minOf(2048, eligible.size)
            val expected = DoubleArray(5 + count * 5)
            for (i in 0 until count) {
                val position = eligible[i * eligible.size / count]
                val x = position % referenceWidth; val y = position / referenceWidth
                val sx = x * scale + tx; val sy = y * scale + ty
                val residual = if (sx < 0 || sy < 0 || sx >= width - 1 || sy >= height - 1) 50.0 else {
                    val ix = sx.toInt(); val iy = sy.toInt(); val fx = sx - ix; val fy = sy - iy
                    val top = distances[iy * width + ix] * (1 - fx) + distances[iy * width + ix + 1] * fx
                    val bottom = distances[(iy + 1) * width + ix] * (1 - fx) + distances[(iy + 1) * width + ix + 1] * fx
                    top * (1 - fy) + bottom * fy
                }
                if (residual <= 5.5) hits++
                val at = 5 + i * 5
                expected[at] = x.toDouble(); expected[at + 1] = y.toDouble(); expected[at + 2] = sx
                expected[at + 3] = sy; expected[at + 4] = residual
            }
            expected[0] = hits.toDouble(); expected[1] = count.toDouble(); expected[2] = outside.toDouble()
            expected[3] = unknown.toDouble(); expected[4] = eligible.size.toDouble()
            assertArrayEquals(expected, VpsgNativeKernel.reverseCoverage(positions, referenceWidth, domain, width, height,
                buffer, scale, tx, ty, legacy, true), 0.0)
        }
    }
    @Test fun nativeVotesAndEveryRefinementProbeEqualTheManagedOracle() {
        assertTrue("The packaged native backend must load", VpsgNativeKernel.available)
        val random = Random(93457)
        for (width in listOf(63, 64, 65, 130)) {
            val height = 37
            val k3 = LongArray((width + 63) / 64 * height) { random.nextLong() }
            // Clear padding bits, as the production reference packer does.
            if (width % 64 != 0) for (row in 0 until height)
                k3[(row + 1) * ((width + 63) / 64) - 1] = k3[(row + 1) * ((width + 63) / 64) - 1] and ((1L shl (width % 64)) - 1)
            val k5 = k3.copyOf()
            val index = VpsgFastSolver.Index(width, height, k3, k5, VpsgFastSolver.Peak(20.0, 3.0), 500)
            val points = List(256) { VpsgFastSolver.Point(random.nextInt(-10, width + 10), random.nextInt(-5, 45)) }
            for (stride in listOf(1, 4, 7)) assertArrayEquals(
                VpsgFastSolver.scoreGridManaged(points, index, -70, 71, -8, 15, stride),
                VpsgFastSolver.scoreGrid(points, index, -70, 71, -8, 15, stride))
            val scores = VpsgFastSolver.scoreGridManaged(points.take(150), index, -70, 71, -8, 15, 4)
            for (rivalDistance in listOf(10.0, 42.0, 90.0)) {
            val (pool, expectedCandidates) = VpsgFastSolver.translationCandidatesManaged(points.take(150), index, scores, -70, 71, -8, 15, .592, rivalDistance)
            val actualCandidates = VpsgNativeKernel.translationCandidates(points.take(150), index, scores, -70, 71, -8, 15, .592, rivalDistance)
            assertEquals(pool.toDouble(), actualCandidates[0], 0.0)
            assertEquals(expectedCandidates, (1 until actualCandidates.size step 3).map {
                VpsgFastSolver.Pose(.592, -actualCandidates[it].toInt() * .592, -actualCandidates[it + 1].toInt() * .592, actualCandidates[it + 2])
            })
            }
            for (scale in listOf(.35, .4, 1.0, 1.013, 2.5, 3.98)) {
                val seed = VpsgFastSolver.Pose(scale, -20.5, 10.5)
                val expectedTrace = AlignmentTrace()
                val actualTrace = AlignmentTrace()
                val expected = VpsgFastSolver.refineManaged(points, index, seed, 160, 120, expectedTrace, 4.0)
                val actual = VpsgFastSolver.refine(points, index, seed, 160, 120, actualTrace, 4.0)
                assertEquals(expected, actual)
                assertEquals(expectedTrace.snapshot().single { it.stage == "vpsg.refine.discrete" }.series,
                    actualTrace.snapshot().single { it.stage == "vpsg.refine.discrete" }.series)
                assertEquals(VpsgFastSolver.refineManaged(points, index, seed, 160, 120, maximumScale = 4.0, centerX = 27.75, centerY = 85.5),
                    VpsgFastSolver.refine(points, index, seed, 160, 120, maximumScale = 4.0, centerX = 27.75, centerY = 85.5))
            }
        }
    }

    @Test fun cancellationStopsAnActiveNativeGridAndTheWorkerCanRunAgain() {
        assertTrue(VpsgNativeKernel.available)
        val token = AlignmentCancellation()
        val started = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val index = VpsgFastSolver.Index(4096, 4096, LongArray(64 * 4096) { -1L },
                LongArray(64 * 4096) { -1L }, VpsgFastSolver.Peak(20.0, 3.0), 4096)
            val points = List(256) { VpsgFastSolver.Point(it, it) }
            val future = executor.submit<Throwable?> {
                runCatching { token.run {
                    started.countDown()
                    VpsgFastSolver.scoreGrid(points, index, -256, 4095, -256, 4095, 1)
                } }.exceptionOrNull()
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(20)
            token.cancel("eye-hidden-native-grid")
            assertTrue(future.get(5, java.util.concurrent.TimeUnit.SECONDS) is AlignmentCancelledException)
            assertTrue((System.nanoTime() - token.requestedAtNanos) / 1e6 < 100.0)
            executor.submit {
                assertFalse(Thread.currentThread().isInterrupted)
                assertTrue(VpsgFastSolver.scoreGrid(points, index, 0, 3, 0, 3, 1).all { it == 256 })
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
}
