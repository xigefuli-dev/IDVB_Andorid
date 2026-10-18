package com.idvb.android.recognize

import android.graphics.Bitmap
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.data.ScreenCaptureMethod
import org.junit.Assert.*
import org.junit.Test

/** Pure in-memory fixtures. No device screenshot, image export, or application-data changes. */
class CaptureHealthInstrumentedTest {
    @Test fun blackFrameIsRecycledAndReturnsTypedFailureWithDiagnosticThresholds() {
        val source = ScreenCaptureMethod.MEDIA_PROJECTION
        CaptureHealthMonitor.beginSession(source)
        val image = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        val events = mutableListOf<AlignmentLogEvent>()
        val result = CaptureHealthMonitor.assess(source, CaptureHealthMonitor.ticket(source),
            Result.success(image), AlignmentLogSink { events += it })
        assertEquals(CaptureFailure.BLACK_FRAME, (result.exceptionOrNull() as CaptureException).failure)
        assertTrue(image.isRecycled)
        assertEquals(CaptureAvailability.UNAVAILABLE, CaptureHealthMonitor.state.value.getValue(source).availability)
        val event = events.single { it.stage == "capture.health" }
        assertEquals(1.0, event.measurements["darkFraction"])
        assertEquals(BlackFrameDetector.BLACK_FRACTION, event.thresholds["blackFraction"])
        assertTrue(event.labels.getValue("replay").startsWith("not-replayable"))
        assertTrue(events.any { it.stage == "capture.health-diagnostic-cost" })
    }

    @Test fun usableFrameRemainsCallerOwnedAndStoppedSourceCannotBeRevivedByLateSuccess() {
        val source = ScreenCaptureMethod.MEDIA_PROJECTION
        CaptureHealthMonitor.beginSession(source)
        val late = CaptureHealthMonitor.ticket(source)
        CaptureHealthMonitor.failure(source, CaptureException(CaptureFailure.SYSTEM_STOPPED, "已停止"), invalidate = true)
        val image = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff687580.toInt()) }
        try {
            val result = CaptureHealthMonitor.assess(source, late, Result.success(image))
            assertSame(image, result.getOrThrow())
            assertFalse(image.isRecycled)
            assertEquals(CaptureFailure.SYSTEM_STOPPED, CaptureHealthMonitor.state.value.getValue(source).lastFailure)
            assertEquals(CaptureAvailability.UNAVAILABLE, CaptureHealthMonitor.state.value.getValue(source).availability)
        } finally { image.recycle() }
    }
}
