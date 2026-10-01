package com.idvb.android.alignment

import android.graphics.Bitmap
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.*
import org.junit.Test

class AlignmentExtensionInstrumentedTest {
    @Test fun registeredAlternativeUsesSharedResultsLoggingAndReplayAccuracyChecks() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val floor = FloorRecord("2f", "second", 2, "fixture", 100, 100)
            val map = MapRecord("map", "class", "fixture", "source", 1, listOf(floor))
            val request = AlignmentRequest(frame, ScreenRect(23.0, 47.0, 100.0, 100.0), map, floor)
            val expected = AlignmentTransform(1.0, 23.0, 47.0, 100, 100)
            var calls = 0
            val method = object : AlignmentMethod {
                override val id = "test-alternative"
                override fun align(request: AlignmentRequest, log: AlignmentLogSink): AlignmentResult {
                    calls++
                    log.record(AlignmentLogEvent("alternative-stage", "test"))
                    return AlignmentResult.Aligned(expected)
                }
            }
            val registry = AlignmentRegistry(listOf(method))
            val runner: AlignmentTestRunner = AlignmentReplayRunner(registry)
            val correct = runner.run(AlignmentTestCase(method.id, request, expected))
            assertTrue(correct.passed)
            assertEquals(listOf("start", "alternative-stage", "finish"), correct.events.map { it.stage })
            val incorrect = runner.run(AlignmentTestCase(method.id, request, expected.copy(scale = 1.1)))
            assertFalse(incorrect.passed)
            assertTrue(incorrect.maximumCornerErrorPixels!! > 10)
            val loggingFailure = registry.align(method.id, request, AlignmentLogSink { error("Broken log adapter") })
            assertEquals(AlignmentResult.Aligned(expected), loggingFailure)
            assertEquals(3, calls)
            val unsupported = runner.run(AlignmentTestCase("missing", request,
                expectedOutcome = AlignmentOutcome.REJECTED))
            assertFalse("An unavailable method is not a successful rejection test", unsupported.passed)
            assertFalse(frame.isRecycled)
        } finally { frame.recycle() }
    }
}
