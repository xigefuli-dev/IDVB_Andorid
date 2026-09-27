package com.idvb.android.recognize

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ImageReader
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureLifecycleInstrumentedTest {
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
