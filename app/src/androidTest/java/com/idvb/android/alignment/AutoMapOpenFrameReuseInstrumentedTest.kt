package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owned Bitmap/cancellation/replay behavior; these synthetic inputs are not live-game acceptance. */
class AutoMapOpenFrameReuseInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val bounds = Rect(20, 30, 180, 130)
    private fun frame(color: Int, at: Long = SystemClock.uptimeMillis(), region: Rect = bounds): PreparedMapFrame =
        PreparedMapFrame(Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, Rect(region), at, at)

    @Test fun twoCleanDetectionFramesRunReadinessAndReplayWithoutAnotherScreenshotOrWait() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val trace = AlignmentTrace(captureArtifacts = true)
        val first = frame(0xff687580.toInt())
        val latest = frame(0xff697681.toInt())
        var calls = 0
        var output: Result<Bitmap>? = null
        try {
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, null,
                    callback = { result, _ -> output = result; done.countDown() },
                    captureFrame = { _, _ -> calls++; error("Clean prepared frames must not request another screenshot") })
                    .start(bounds, listOf(first, latest))
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(checkNotNull(output).isSuccess)
            assertSame("Structure must receive the latest frame", latest.bitmap, output!!.getOrThrow())
            assertTrue(first.bitmap.isRecycled)
            assertEquals(0, calls)
            assertEquals(listOf("wait", "ready"), trace.snapshot().filter { it.stage == "readiness.frame" }.map { it.labels["decision"] })
            assertEquals(2, trace.snapshot().count { it.stage == "readiness.prepared-frame" && it.detail == "reused-clean-detector-capture" })

            val root = java.io.File(instrumentation.targetContext.cacheDir, "reuse-replay-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
                override fun getFilesDir() = root
            }
            val floor = com.idvb.android.idvm.FloorRecord("1f", "test", 1, "fixture", 160, 100)
            val map = com.idvb.android.idvm.MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
            val archive = AlignmentDiagnosticsStore(context).record(trace,
                AlignmentDiagnosticContext("vpsg", map, floor.key, com.idvb.android.recognize.gate.ScreenRect(20.0, 30.0, 160.0, 100.0), 240, 180, "synthetic-reused-frames"),
                null, null, "readiness-only-test").getOrThrow()
            val replay = MapOpenReadinessReplay.run(archive)
            assertEquals(2, replay.size)
            assertTrue(replay.all { it.recordedReady == it.replayed.ready })
        } finally { first.recycle(); latest.recycle(); executor.shutdownNow() }
    }

    @Test fun acceptedReferenceStillUsesLatestPreparedFrameAndRecyclesEarlierFrame() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val first = frame(0xff687580.toInt())
        val latest = frame(0xff697681.toInt())
        val signature = MapOpenReadiness.signature(IntArray(160 * 100) { 0xff687580.toInt() })
        try {
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, executor, AlignmentCancellation(), AlignmentTrace(), signature,
                    callback = { result, _ -> assertSame(latest.bitmap, result.getOrThrow()); done.countDown() },
                    captureFrame = { _, _ -> error("No screenshot expected") }).start(bounds, listOf(first, latest))
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(first.bitmap.isRecycled)
        } finally { first.recycle(); latest.recycle(); executor.shutdownNow() }
    }

    @Test fun staleOrDifferentViewportIsDiscardedAndFreshCaptureStillMustPassReadiness() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val trace = AlignmentTrace()
        val stale = frame(0xff687580.toInt(), SystemClock.uptimeMillis() - 2_000L)
        val moved = frame(0xff687580.toInt(), region = Rect(21, 30, 181, 130))
        var calls = 0
        try {
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, null,
                    callback = { result, _ -> result.getOrThrow().recycle(); done.countDown() },
                    captureFrame = { _, callback -> calls++; callback(Result.success(frame(0xff687580.toInt()).bitmap)); {} })
                    .start(bounds, listOf(stale, moved))
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls)
            assertTrue(stale.bitmap.isRecycled); assertTrue(moved.bitmap.isRecycled)
            assertEquals(2, trace.snapshot().count { it.stage == "readiness.prepared-frame" && it.detail == "discarded-stale-or-region-changed" })
        } finally { stale.recycle(); moved.recycle(); executor.shutdownNow() }
    }

    @Test fun closingBeforePreparedFramesAreAssessedRecyclesEveryOwnedFrame() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val first = frame(0xff687580.toInt())
        val latest = frame(0xff687580.toInt())
        var callbacks = 0
        try {
            instrumentation.runOnMainSync {
                val cancel = MapOpenFrameCapture(main, executor, AlignmentCancellation(), AlignmentTrace(), null,
                    callback = { result, _ -> assertTrue(result.isFailure); callbacks++; done.countDown() },
                    captureFrame = { _, _ -> error("Cancellation must not capture") }).start(bounds, listOf(first, latest))
                cancel()
            }
            assertTrue(done.await(1, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue(first.bitmap.isRecycled); assertTrue(latest.bitmap.isRecycled)
            assertEquals(1, callbacks)
        } finally { first.recycle(); latest.recycle(); executor.shutdownNow() }
    }

    @Test fun fullCaptureProducesExactReferencePatchAndCleanViewportWhileOcclusionDropsOnlyViewport() {
        val executor = Executors.newSingleThreadExecutor()
        val reference = Rect(0, 0, 16, 16)
        val display = Rect(0, 0, 240, 180)
        var occluded = false
        var output: Result<AutoMapOpenSample>? = null
        var screenshot: Bitmap? = null
        val done = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                AutoMapOpenFrameSampler(main, executor) { region, callback ->
                    assertEquals(display, region)
                    screenshot = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff687580.toInt()) }
                    callback(Result.success(checkNotNull(screenshot))); {}
                }.sampleWithFrame(reference, display, bounds, { true }, { false }, { occluded }) {
                    output = it; done.countDown()
                }
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            val sample = checkNotNull(output).getOrThrow()
            assertEquals(768, sample.pixels.size)
            assertEquals(0xff687580.toInt(), sample.pixels[0])
            assertTrue(checkNotNull(screenshot).isRecycled)
            assertEquals(bounds, checkNotNull(sample.mapFrame).bounds)
            assertEquals(0xff687580.toInt(), sample.mapFrame.bitmap.getPixel(0, 0))
            sample.mapFrame.recycle()

            val occludedDone = CountDownLatch(1)
            instrumentation.runOnMainSync {
                AutoMapOpenFrameSampler(main, executor) { _, callback ->
                    val captured = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
                    callback(Result.success(captured)); occluded = true; {}
                }.sampleWithFrame(reference, display, bounds, { true }, { false }, { occluded }) {
                    assertTrue(it.isSuccess); assertNull(it.getOrThrow().mapFrame); occludedDone.countDown()
                }
            }
            assertTrue(occludedDone.await(2, TimeUnit.SECONDS))
        } finally { output?.getOrNull()?.mapFrame?.recycle(); executor.shutdownNow() }
    }
}
