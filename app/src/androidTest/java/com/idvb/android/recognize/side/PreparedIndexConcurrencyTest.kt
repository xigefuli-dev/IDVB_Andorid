package com.idvb.android.recognize.side

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedIndexConcurrencyTest {
    @Test fun clearingDuringBuildCannotPublishAnOldEpoch() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        com.idvb.android.AppServices.scanPreparation.awaitIdle()
        val file = File.createTempFile("index-epoch",".fixture",target.cacheDir)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val old = executor.submit<SparseGateSearch.Index> { SparseGateSearch.load(file,"epoch") {
                entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
                SparseGateSearch.Index(8,8,ByteArray(64))
            } }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            SparseGateSearch.clear()
            val fresh = SparseGateSearch.load(file,"epoch") { SparseGateSearch.Index(8,8,ByteArray(64)) }
            release.countDown()
            assertNotSame(fresh,old.get(5,TimeUnit.SECONDS))
            assertSame(fresh,SparseGateSearch.load(file,"epoch") { error("New epoch was evicted") })
        } finally { release.countDown(); executor.shutdownNow(); SparseGateSearch.clear(); file.delete() }
    }

    @Test fun foregroundAndPreloadShareOneBuildAndInvalidateChangedSources() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        com.idvb.android.AppServices.scanPreparation.awaitIdle()
        val file = File.createTempFile("index-race",".fixture",target.cacheDir)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            val a = executor.submit<SparseGateSearch.Index> { SparseGateSearch.load(file,"race") {
                count.incrementAndGet(); entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
                SparseGateSearch.Index(8,8,ByteArray(64))
            } }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val b = executor.submit<SparseGateSearch.Index> { SparseGateSearch.load(file,"race") {
                count.incrementAndGet(); SparseGateSearch.Index(8,8,ByteArray(64))
            } }
            release.countDown()
            assertSame(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))
            assertEquals(1,count.get())
            assertTrue(SparseGateSearch.isCached(file,"race"))
            file.appendText("new source generation")
            assertFalse(SparseGateSearch.isCached(file,"race"))
            val changed = SparseGateSearch.load(file,"race") {
                count.incrementAndGet(); SparseGateSearch.Index(8,8,ByteArray(64))
            }
            assertNotSame(a.get(),changed)
            assertEquals(2,count.get())
        } finally { release.countDown(); executor.shutdownNow(); SparseGateSearch.clear(); file.delete() }
    }
}
