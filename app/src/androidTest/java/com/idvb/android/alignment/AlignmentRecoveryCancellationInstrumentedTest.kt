package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in production replay: cancelling the new recovery must stop its actual computation. */
class AlignmentRecoveryCancellationInstrumentedTest {
    @Test fun eyeHiddenDuringCentroidRecoveryExitsAndNextRequestCompletes() {
        val relative = InstrumentationRegistry.getArguments().getString("alignmentCorpus")
        assumeTrue("Private original corpus required", !relative.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val archive = root.listFiles()!!.first { it.name.endsWith("-58bb626f.zip") }
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                AlignmentPackageReplay.open(context, archive).use { replay ->
                    val trace = AlignmentTrace()
                    var requested = false
                    val sink = AlignmentLogSink { event ->
                        trace.record(event)
                        if (!requested && event.stage == "vpsg.refine.centroid-recovery") {
                            requested = true
                            replay.testCase.request.cancellation.cancel("eye-hidden-centroid-recovery")
                        }
                    }
                    val failure = runCatching { replay.run(sink, AlignmentOutcome.ALIGNED) }.exceptionOrNull()
                    assertTrue("Must reach actual centroid recovery before cancellation", requested)
                    assertTrue("$failure", failure is AlignmentCancelledException)
                    val exit = trace.snapshot().single { it.stage == "cancelled" }
                    assertTrue(exit.measurements.getValue("acknowledgementMs") < 1000.0)
                    assertTrue(exit.labels.getValue("checkpoint").startsWith("vpsg.refine"))
                    assertFalse(trace.snapshot().any { it.stage == "finish" })
                }
                AlignmentPackageReplay.open(context, archive).use { replay ->
                    assertTrue("Showing again starts a usable new request",
                        replay.run(expectedOutcome = AlignmentOutcome.ALIGNED).passed)
                }
            }.get(30, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
    }
}
