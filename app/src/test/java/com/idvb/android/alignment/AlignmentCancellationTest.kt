package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.idvb.android.recognize.vpsg.VpsgFastSolver

class AlignmentCancellationTest {
    @Test fun cancelExitsRunningWorkAndDoesNotPoisonNextTask() {
        val cancellation = AlignmentCancellation()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Boolean> {
                try {
                    cancellation.run {
                        started.countDown()
                        while (true) AlignmentCancellation.checkpoint("expensive-loop")
                    }
                    false
                } catch (expected: AlignmentCancelledException) {
                    assertEquals("expensive-loop", expected.stage)
                    assertTrue(expected.acknowledgementNanos >= 0)
                    true
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cancellation.cancel("eye-hidden")
            assertTrue(future.get(2, TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { Thread.currentThread().isInterrupted }.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun cancelledQueuedWorkNeverEntersAlgorithm() {
        val cancellation = AlignmentCancellation()
        cancellation.cancel("eye-hidden")
        try {
            cancellation.run { fail("Cancelled work must not enter the algorithm") }
            fail("Cancellation should propagate")
        } catch (expected: AlignmentCancelledException) { assertEquals("worker.start", expected.stage) }
    }

    @Test fun cancellationExitsTheProductionTranslationLoop() {
        val cancellation = AlignmentCancellation()
        val ready = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val size = 4096
        val words = LongArray(size * (size / 64)) { -1L }
        val index = VpsgFastSolver.Index(size, size, words, words, VpsgFastSolver.Peak(25.0, 6.0), size * size)
        val points = (0 until 150).map { VpsgFastSolver.Point(it, it) }
        try {
            val future = executor.submit<String> {
                try {
                    cancellation.run {
                        ready.countDown()
                        VpsgFastSolver.scoreGrid(points, index, 0, size - 1, 0, size - 1)
                    }
                    "not-cancelled"
                } catch (expected: AlignmentCancelledException) { expected.stage }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            cancellation.cancel("eye-hidden")
            assertEquals("vpsg.translation.score-grid.block", future.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
