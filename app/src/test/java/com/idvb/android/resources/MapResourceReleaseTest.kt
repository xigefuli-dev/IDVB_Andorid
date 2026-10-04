package com.idvb.android.resources

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MapResourceReleaseTest {
    @Test fun outstandingOwnerAndMainDeliveryFinishBeforeEvictionAndResume() {
        val worker = Executors.newSingleThreadExecutor()
        val ownerEntered = CountDownLatch(1)
        val ownerExited = CountDownLatch(1)
        val callbackQueued = CountDownLatch(1)
        val callback = AtomicReference<Runnable>()
        val cache = GenerationCache<String, ByteArray>(128) { it.size.toLong() }
        val borrowed = cache.load("map") { ByteArray(64) { 42 } }!!
        val evicted = AtomicBoolean(false)
        val resumed = AtomicBoolean(false)
        val release = MapResourceRelease({ worker.execute(it) }, { callback.set(it); callbackQueued.countDown() })
        try {
            release.start(listOf(
                MapResourceRelease.Step("owner") {
                    ownerEntered.countDown()
                    check(ownerExited.await(5, TimeUnit.SECONDS))
                    assertEquals(42, borrowed[0].toInt())
                },
                MapResourceRelease.Step("cache") { cache.clear(); evicted.set(true) },
            ), { _, _, _ -> }) { result -> result.getOrThrow(); resumed.set(true) }
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
            assertTrue(release.pending)
            assertEquals(64L, cache.retainedBytes)
            assertFalse(evicted.get()); assertFalse(resumed.get())
            ownerExited.countDown()
            assertTrue(callbackQueued.await(5, TimeUnit.SECONDS))
            assertEquals(0L, cache.retainedBytes)
            assertTrue(release.pending); assertFalse(resumed.get())
            callback.get().run()
            assertFalse(release.pending); assertTrue(resumed.get())
            // Borrowed values are never recycled by a shared cache boundary.
            assertEquals(42, borrowed[0].toInt())
        } finally { ownerExited.countDown(); worker.shutdownNow() }
    }

    @Test fun failedOwnerBarrierRetainsCacheAndDoesNotRunDependentCleanup() {
        val cache = GenerationCache<String, ByteArray>(128) { it.size.toLong() }
        cache.load("map") { ByteArray(64) }
        val release = MapResourceRelease({ it.run() }, { it.run() })
        var outcome: Result<Unit>? = null
        release.start(listOf(
            MapResourceRelease.Step("owner") { throw IllegalStateException("still running") },
            MapResourceRelease.Step("cache") { cache.clear() },
        ), { _, _, _ -> }) { outcome = it }
        assertTrue(outcome!!.isFailure)
        assertEquals(64L, cache.retainedBytes)
        assertFalse(release.pending)
    }

    @Test fun executorRejectionCompletesWithFailureAndCanBeRetried() {
        val release = MapResourceRelease({ throw java.util.concurrent.RejectedExecutionException() }, { it.run() })
        repeat(2) {
            var failure = false
            release.start(emptyList(), { _, _, _ -> }) { failure = it.isFailure }
            assertTrue(failure); assertFalse(release.pending)
        }
    }
}
