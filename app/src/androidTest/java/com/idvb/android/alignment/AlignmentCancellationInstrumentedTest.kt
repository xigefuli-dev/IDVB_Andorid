package com.idvb.android.alignment

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AlignmentCancellationInstrumentedTest {
    @Test fun runningCancellationIsAcknowledgedAndANewRequestCanCompleteOnTheSameWorker() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val floor = FloorRecord("1f", "1F", 1, "fixture.png", 100, 100)
        val map = MapRecord("fixture", "fixture", "fixture", "fixture", 1, listOf(floor))
        val ready = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<AlignmentLogEvent>()
        val executor = Executors.newSingleThreadExecutor()
        val cancellation = AlignmentCancellation()
        val registry = AlignmentRegistry(listOf(object : AlignmentMethod {
            override val id = "cancellable-test"
            override fun align(request: AlignmentRequest, log: AlignmentLogSink): AlignmentResult {
                ready.countDown()
                while (true) request.cancellation.throwIfCancelled("test.running-computation")
            }
        }, object : AlignmentMethod {
            override val id = "next-request"
            override fun align(request: AlignmentRequest, log: AlignmentLogSink): AlignmentResult {
                assertFalse(Thread.currentThread().isInterrupted)
                return AlignmentResult.Aligned(AlignmentTransform(1.0, 0.0, 0.0, 100, 100))
            }
        }))
        val request = AlignmentRequest(frame, ScreenRect(0.0, 0.0, 100.0, 100.0), map, floor, cancellation)
        try {
            val first = executor.submit<Boolean> {
                try { registry.align("cancellable-test", request, AlignmentLogSink { events += it }); false }
                catch (expected: AlignmentCancelledException) { true }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            cancellation.cancel("eye-hidden")
            assertTrue(first.get(2, TimeUnit.SECONDS))
            assertFalse(events.any { it.stage == "finish" || it.stage == "error" })
            val acknowledgement = events.single { it.stage == "cancelled" }
            assertEquals("test.running-computation", acknowledgement.labels["checkpoint"])
            assertTrue(acknowledgement.measurements.getValue("acknowledgementMs") in 0.0..1000.0)
            assertTrue(executor.submit<AlignmentResult> {
                registry.align("next-request", request.copy(cancellation = AlignmentCancellation()))
            }.get(2, TimeUnit.SECONDS) is AlignmentResult.Aligned)
            // Target-UID instrumentation cannot write the test APK's private directory.
            val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "test-evidence/alignment-cancellation.json")
            output.parentFile!!.mkdirs()
            output.writeText(buildJsonObject {
                put("stage", acknowledgement.stage); put("checkpoint", acknowledgement.labels.getValue("checkpoint"))
                put("acknowledgementMs", acknowledgement.measurements.getValue("acknowledgementMs"))
                put("nextRequestCompleted", true)
            }.toString())
        } finally { executor.shutdownNow(); frame.recycle() }
    }
}
