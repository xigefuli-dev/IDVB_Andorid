package com.idvb.android.resources

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GenerationCacheTest {
    @Test fun oldDecodeCannotReplaceNewGenerationAfterClear() {
        val cache = GenerationCache<String, ByteArray>(128) { it.size.toLong() }
        val worker = Executors.newSingleThreadExecutor()
        val decoding = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        try {
            val old = worker.submit<ByteArray> {
                cache.load("map") { decoding.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); ByteArray(64) { 1 } }!!
            }
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            cache.clear()
            val fresh = cache.load("map") { ByteArray(32) { 2 } }
            proceed.countDown()
            assertEquals(1, old.get(5, TimeUnit.SECONDS)[0].toInt())
            assertSame(fresh, cache.load("map") { error("must use the new generation") })
            assertEquals(32L, cache.retainedBytes)
        } finally { proceed.countDown(); worker.shutdownNow() }
    }

    @Test fun evictionUsesBytesAndRecentAccessWithoutKeepingOversizeValue() {
        val cache = GenerationCache<String, ByteArray>(128) { it.size.toLong() }
        val a = cache.load("a") { ByteArray(64) }
        cache.load("b") { ByteArray(64) }
        assertSame(a, cache.load("a") { error("unexpected decode") })
        cache.load("c") { ByteArray(64) }
        var decoded = false
        cache.load("b") { decoded = true; null }
        assertTrue(decoded)
        assertEquals(128L, cache.retainedBytes)
        assertEquals(256, cache.load("large") { ByteArray(256) }!!.size)
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0L, cache.retainedBytes)
        assertNotNull(a)
    }
}
