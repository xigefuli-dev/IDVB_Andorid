package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.overlay.GuideMapView
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Rendering/ownership regression inputs; not evidence of acceptance in the game. */
class AutoMapOpenRecoveryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun alignedGuideLeavesDetectionPixelsTransparentWithoutMovingThePose() {
        val guide = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff0000.toInt()) }
        val output = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                val view = GuideMapView(instrumentation.targetContext)
                view.layout(0, 0, 240, 180)
                view.showBitmap(guide)
                view.showAlignment(RectF(100f, -20f, 230f, 160f), RectF(0f, 0f, 240f, 180f), RectF(200f, 0f, 240f, 180f))
                view.draw(Canvas(output))
                assertEquals(0xffff0000.toInt(), output.getPixel(150, 80))
                assertEquals(0, output.getPixel(210, 80))
                assertEquals(0, output.getPixel(90, 80))
                assertFalse(AutoMapOpenOcclusion.overlaps(RectF(200f, 0f, 240f, 180f), requireNotNull(view.visibleAlignedParts())))
                assertTrue(AutoMapOpenOcclusion.overlaps(RectF(100f, 30f, 180f, 130f), requireNotNull(view.visibleAlignedParts())))
                view.clearAlignment()
                assertNull(view.visibleAlignedParts())
            }
        } finally { guide.recycle(); output.recycle() }
    }

    @Test fun detectorFrameKeepsSeparateMapAndIndicatorOwnershipAndNeedsNoSecondScreenshot() {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val map = Rect(20, 30, 180, 130)
        val indicator = Rect(120, 0, 240, 30)
        val done = CountDownLatch(1)
        var owned: PreparedMapFrame? = null
        var transferred: Bitmap? = null
        var calls = 0
        val trace = AlignmentTrace(captureArtifacts = true)
        try {
            instrumentation.runOnMainSync {
                AutoMapOpenFrameSampler(main, executor, captureMethod = "MEDIA_PROJECTION", captureFrame = { _, callback ->
                    val input = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff687580.toInt()) }
                    callback(Result.success(input)); {}
                }).sampleWithFrame(Rect(200, 0, 240, 180), Rect(0, 0, 240, 180), map,
                    { true }, { false }, { false }, indicatorBounds = indicator, isIndicatorOccluded = { false }) { sampled ->
                    val frame = requireNotNull(sampled.getOrThrow().mapFrame)
                    owned = frame
                    // The map bounds must stay the map viewport, despite the enlarged capture.
                    assertTrue(frame.isFreshFor(map, SystemClock.uptimeMillis(), 1_000L))
                    assertFalse(frame.isFreshFor(Rect(0, 0, 240, 180), SystemClock.uptimeMillis(), 1_000L))
                    transferred = frame.takeIndicatorFor(indicator, SystemClock.uptimeMillis(), 1_000L)
                    assertNotNull(transferred)
                    assertNull(frame.takeIndicatorFor(indicator, SystemClock.uptimeMillis(), 1_000L))
                    val reference = MapOpenReadiness.signature(IntArray(160 * 100) { 0xff687580.toInt() })
                    MapOpenFrameCapture(main, executor, AlignmentCancellation(), trace, reference,
                        callback = { result, _ -> assertSame(frame.bitmap, result.getOrThrow()); done.countDown() },
                        captureFrame = { _, _ -> calls++; error("Floor selection must preserve the already clean map frame") })
                        .start(map, listOf(frame))
                }
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(0, calls)
            assertTrue(trace.snapshot().any { it.stage == "readiness.prepared-frame" && it.detail == "reused-clean-detector-capture" })
            owned?.recycle()
            assertFalse(requireNotNull(transferred).isRecycled)
        } finally { owned?.recycle(); transferred?.recycle(); executor.shutdownNow() }
    }

    @Test fun discardedMapFrameRecyclesItsUntransferredIndicator() {
        val map = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val indicator = Bitmap.createBitmap(120, 30, Bitmap.Config.ARGB_8888)
        val frame = PreparedMapFrame(map, Rect(20, 30, 180, 130), 0L, 0L,
            indicatorBitmap = indicator, indicatorBounds = Rect(120, 0, 240, 30))
        assertNull(frame.takeIndicatorFor(Rect(120, 0, 240, 30), 2_000L, 1_000L))
        frame.recycle()
        assertTrue(map.isRecycled); assertTrue(indicator.isRecycled)
    }
}
