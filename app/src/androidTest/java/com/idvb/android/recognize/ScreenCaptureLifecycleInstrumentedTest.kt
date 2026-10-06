package com.idvb.android.recognize

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.idvb.android.alignment.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.Executor

class ScreenCaptureLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val bounds = Rect(0, 0, 32, 32)
    private val mapColor = 0xff687580.toInt()

    private fun renderFrame(session: ScreenCaptureSession, reader: ImageReader) {
        val arrived = CountDownLatch(1)
        session.onFrameAvailable = { arrived.countDown() }
        val canvas = reader.surface.lockCanvas(null)
        canvas.drawColor(mapColor)
        reader.surface.unlockCanvasAndPost(canvas)
        assertTrue("The single submitted physical frame must reach the session", arrived.await(2, TimeUnit.SECONDS))
    }

    @Test fun automaticReadinessConsumesStaticFrameAlreadyArrivedDuringOverlayHide() {
        val (session, reader) = readerSession()
        val executor = Executors.newSingleThreadExecutor()
        val trace = AlignmentTrace(captureArtifacts = true)
        val done = CountDownLatch(1)
        val output = AtomicReference<Result<Bitmap>>()
        val watermark = session.prepareCapture()
        try {
            renderFrame(session, reader)
            val arrivedSequence = session.frameSequenceWatermark()
            val reference = MapOpenReadiness.signature(IntArray(MapOpenReadiness.WIDTH * MapOpenReadiness.HEIGHT) { mapColor })
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, reference,
                    callback = { result, _ -> output.set(result); done.countDown() },
                    captureProjectionFrame = { rect, consumed, callback -> session.captureAfter(rect, consumed, trace, callback) },
                    initialProjectionSequence = watermark, intervalMs = 0L).start(bounds)
            }
            assertTrue("Automatic readiness must use the retained frame without another render", done.await(2, TimeUnit.SECONDS))
            val bitmap = output.get().getOrThrow()
            try { assertEquals(mapColor, bitmap.getPixel(16, 16)) } finally { bitmap.recycle() }
            val observed = trace.snapshot().single { it.stage == "readiness.frame" }
            assertEquals(arrivedSequence.toDouble(), observed.measurements["frameSequence"])
            assertTrue(checkNotNull(observed.measurements["frameReceivedNanos"]) > 0)
            assertEquals("ready", observed.labels["decision"])
        } finally { executor.shutdownNow(); session.close() }
    }

    @Test fun automaticReadinessConsumesFrameArrivedWhileSeedSignatureWorkerWasBlocked() {
        val (session, reader) = readerSession()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val output = AtomicReference<Result<Bitmap>>()
        val trace = AlignmentTrace()
        val seed = AtomicReference<ProjectionCaptureFrame>()
        try {
            val watermark = session.prepareCapture()
            renderFrame(session, reader)
            val captured = CountDownLatch(1)
            session.captureAfter(bounds, watermark, trace) { seed.set(it.getOrThrow()); captured.countDown() }
            assertTrue(captured.await(2, TimeUnit.SECONDS))
            val frame = seed.get()
            val now = SystemClock.uptimeMillis()
            var first = true
            val blockedWorker = Executor { task -> executor.execute {
                if (first) { first = false; entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                task.run()
            } }
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, blockedWorker, AlignmentCancellation(), trace, null,
                    callback = { result, _ -> output.set(result); done.countDown() },
                    captureProjectionFrame = { rect, consumed, callback -> session.captureAfter(rect, consumed, trace, callback) },
                    initialProjectionSequence = watermark, intervalMs = 0L).start(bounds,
                        listOf(PreparedMapFrame(frame.bitmap, Rect(bounds), now, now, "MEDIA_PROJECTION", frame.sequence,
                            frameReceivedNanos = frame.receivedNanos)))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            renderFrame(session, reader)
            val nextSequence = session.frameSequenceWatermark()
            release.countDown()
            assertTrue("N+1 arrived during evaluation; no N+2 will be rendered", done.await(2, TimeUnit.SECONDS))
            output.get().getOrThrow().recycle()
            val observations = trace.snapshot().filter { it.stage == "readiness.frame" }
            assertEquals(listOf(frame.sequence.toDouble(), nextSequence.toDouble()), observations.map { it.measurements["frameSequence"] })
            assertEquals(listOf("wait", "ready"), observations.map { it.labels["decision"] })
            assertTrue(frame.bitmap.isRecycled)
        } finally { release.countDown(); seed.get()?.bitmap?.takeUnless { it.isRecycled }?.recycle(); executor.shutdownNow(); session.close() }
    }

    @Test fun samePhysicalFrameCannotProvideBothReadinessObservations() {
        val (session, reader) = readerSession()
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val trace = AlignmentTrace()
        val output = AtomicReference<Result<Bitmap>>()
        try {
            val watermark = session.prepareCapture()
            renderFrame(session, reader)
            instrumentation.runOnMainSync {
                MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, null,
                    callback = { result, _ -> output.set(result); done.countDown() },
                    captureProjectionFrame = { rect, consumed, callback -> session.captureAfter(rect, consumed, trace, callback) },
                    initialProjectionSequence = watermark, intervalMs = 0L).start(bounds)
            }
            assertTrue(done.await(4, TimeUnit.SECONDS))
            assertTrue(output.get().isFailure)
            assertEquals(1, trace.snapshot().count { it.stage == "readiness.frame" })
            assertEquals("wait", trace.snapshot().single { it.stage == "readiness.frame" }.labels["decision"])
        } finally { executor.shutdownNow(); session.close() }
    }

    @Test fun cancelledReplacementPreservesActiveGrantAndCaptureSession() {
        ScreenCaptureGrant.update(Activity.RESULT_OK, Intent("active"))
        val revision = ScreenCaptureGrant.revision
        assertNotNull(ScreenCaptureGrant.consume(revision))
        val (session, reader) = readerSession()
        try {
            assertFalse(ScreenCaptureGrant.update(Activity.RESULT_CANCELED, null))
            assertFalse(ScreenCaptureGrant.update(Activity.RESULT_OK, null))
            assertEquals(revision, ScreenCaptureGrant.revision)
            assertTrue(ScreenCaptureGrant.available)
            assertNull("Cancellation must not make the consumed token reusable", ScreenCaptureGrant.consume(revision))
            renderFrame(session, reader)
            val done = CountDownLatch(1)
            val output = AtomicReference<Result<Bitmap>>()
            session.capture(bounds) { output.set(it); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            output.get().getOrThrow().recycle()
        } finally { session.close() }
    }

    private fun readerSession(): Pair<ScreenCaptureSession, ImageReader> {
        val session = ScreenCaptureSession(InstrumentationRegistry.getInstrumentation().targetContext)
        val factory = ScreenCaptureSession::class.java.getDeclaredMethod("newReader", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        factory.isAccessible = true
        val reader = factory.invoke(session, 32, 32) as ImageReader
        ScreenCaptureSession::class.java.getDeclaredField("reader").apply { isAccessible = true }.set(session, reader)
        return session to reader
    }

    @Test fun finalStaticFrameSurvivesTheOverlayHideDelay() {
        val (session, reader) = readerSession()
        try {
            session.prepareCapture()
            val canvas = reader.surface.lockCanvas(null)
            canvas.drawColor(Color.GREEN)
            reader.surface.unlockCanvasAndPost(canvas)
            // No further frames are rendered. The old drain-then-poll implementation times out.
            Thread.sleep(200)
            val done = CountDownLatch(1)
            val result = AtomicReference<Result<Bitmap>>()
            session.capture(Rect(0, 0, 32, 32)) { result.set(it); done.countDown() }
            assertTrue(done.await(4, TimeUnit.SECONDS))
            val bitmap = result.get().getOrThrow()
            try { assertFalse(bitmap.isRecycled); assertEquals(Color.GREEN, bitmap.getPixel(16, 16)) }
            finally { bitmap.recycle() }
        } finally { session.close() }
    }

    @Test fun closingWhileWaitingCompletesOnceWithoutTimeout() {
        val (session, _) = readerSession()
        val count = AtomicInteger()
        val result = AtomicReference<Result<Bitmap>>()
        session.capture(Rect(0, 0, 32, 32)) { result.set(it); count.incrementAndGet() }
        session.close()
        assertEquals(1, count.get())
        assertTrue(result.get().exceptionOrNull()!!.message!!.contains("已关闭"))
        Thread.sleep(100)
        assertEquals(1, count.get())
    }

    @Test fun oldSessionCannotInvalidateRenewedGrantAndTokensAreSingleUse() {
        ScreenCaptureGrant.update(Activity.RESULT_OK, Intent("first"))
        val old = ScreenCaptureGrant.revision
        assertNotNull(ScreenCaptureGrant.consume(old))
        assertNull(ScreenCaptureGrant.consume(old))
        ScreenCaptureGrant.update(Activity.RESULT_OK, Intent("second"))
        val renewed = ScreenCaptureGrant.revision
        ScreenCaptureGrant.invalidate(old)
        assertTrue(ScreenCaptureGrant.available)
        assertEquals("second", ScreenCaptureGrant.consume(renewed)?.action)
        ScreenCaptureGrant.invalidate(renewed)
        assertFalse(ScreenCaptureGrant.available)
    }

    @Test fun systemStopFailsPendingCaptureAndClearsAuthorization() {
        ScreenCaptureGrant.update(Activity.RESULT_OK, Intent("active"))
        val (session, _) = readerSession()
        try {
            val result = AtomicReference<Result<Bitmap>>()
            session.capture(Rect(0, 0, 32, 32)) { result.set(it) }
            val callback = ScreenCaptureSession::class.java.getDeclaredField("projectionCallback")
                .apply { isAccessible = true }.get(session) as android.media.projection.MediaProjection.Callback
            callback.onStop()
            assertTrue(result.get().exceptionOrNull()!!.message!!.contains("授权已失效"))
            assertFalse(ScreenCaptureGrant.available)
            assertFalse(session.start(32, 32))
        } finally { session.close() }
    }
}
