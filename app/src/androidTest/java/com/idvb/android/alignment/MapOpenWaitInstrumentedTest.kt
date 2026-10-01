package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MapOpenWaitInstrumentedTest {
    private val main = Handler(Looper.getMainLooper())
    private fun bitmap(color: Int) = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun waitsThroughGameplayAndFadeThenReturnsTheReadyFrame() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val trace = AlignmentTrace(captureArtifacts = true)
        var calls = 0
        var output: Result<Bitmap>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, null,
                callback = { result, _ -> output = result; done.countDown() },
                captureFrame = { _, callback ->
                    val colors = listOf(0xffa08450.toInt(), 0xff20252a.toInt(), 0xff687580.toInt(), 0xff687580.toInt())
                    callback(Result.success(bitmap(colors[minOf(calls++, 3)])));
                    {}
                }).start(Rect(0, 0, 160, 100))
        }
        assertTrue(done.await(4, TimeUnit.SECONDS))
        assertTrue(output!!.isSuccess); assertEquals(4, calls)
        assertEquals(0xff687580.toInt(), output!!.getOrThrow().getPixel(0, 0))
        output!!.getOrThrow().recycle(); executor.shutdown()
        assertEquals(listOf("wait", "wait", "wait", "ready"), trace.snapshot().filter { it.stage == "readiness.frame" }.map { it.labels["decision"] })
        assertEquals(8, trace.artifactSnapshot().size)
        // The capture owner was recycled above; deferred encoding must retain exact pixels.
        val retained = trace.artifactSnapshot().getValue("readiness-4.png")
        val decoded = android.graphics.BitmapFactory.decodeByteArray(retained, 0, retained.size)
        try { assertEquals(0xff687580.toInt(), decoded.getPixel(0, 0)) } finally { decoded.recycle() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Instrumentation executes under the target UID, which cannot write the test APK's private directory.
        val root = java.io.File(instrumentation.targetContext.cacheDir, "readiness-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = root
        }
        val floor = com.idvb.android.idvm.FloorRecord("1f", "test", 1, "fixture", 160, 100)
        val map = com.idvb.android.idvm.MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
        val archive = AlignmentDiagnosticsStore(context).record(trace,
            AlignmentDiagnosticContext("vpsg", map, floor.key, com.idvb.android.recognize.gate.ScreenRect(0.0, 0.0, 160.0, 100.0), 160, 100, "synthetic-test"),
            null, null, "readiness-only-test").getOrThrow()
        val replay = MapOpenReadinessReplay.run(archive)
        assertEquals(4, replay.size)
        assertTrue(replay.all { it.recordedReady == it.replayed.ready })
    }

    @Test fun missingMapStopsWithinThreeSecondsAndNeverReturnsAFrame() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val trace = AlignmentTrace()
        var output: Result<Bitmap>? = null
        val started = android.os.SystemClock.elapsedRealtime()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, null,
                callback = { result, _ -> output = result; done.countDown() },
                captureFrame = { _, callback -> callback(Result.success(bitmap(0xffa08450.toInt()))); {} }
            ).start(Rect(0, 0, 160, 100))
        }
        assertTrue(done.await(4, TimeUnit.SECONDS)); assertTrue(output!!.isFailure)
        assertTrue(android.os.SystemClock.elapsedRealtime() - started < 3_800)
        assertEquals("timeout", trace.snapshot().last { it.stage == "readiness.total" }.detail)
        executor.shutdown()
    }

    @Test fun closeCancelsPendingCaptureAndLateFrameIsRecycled() {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        var pending: ((Result<Bitmap>) -> Unit)? = null
        var aborted = false
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cancel = MapOpenFrameCapture(main, executor, AlignmentCancellation(), AlignmentTrace(), null,
                callback = { result, _ -> assertTrue(result.isFailure); count++; done.countDown() },
                captureFrame = { _, callback -> pending = callback; { aborted = true } }
            ).start(Rect(0, 0, 160, 100))
            cancel()
        }
        assertTrue(done.await(1, TimeUnit.SECONDS)); assertTrue(aborted)
        val late = bitmap(0xff687580.toInt())
        pending!!(Result.success(late))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(late.isRecycled); assertEquals(1, count)
        executor.shutdown()
    }
}
