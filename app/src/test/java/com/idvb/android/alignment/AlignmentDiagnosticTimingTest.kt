package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentDiagnosticTimingTest {
    @Test fun durableTimingAccountingDoesNotReadOrDeserializeTheJournal() {
        val root = java.nio.file.Files.createTempDirectory("timing-journal").toFile()
        try {
            val trace = AlignmentTrace(spoolDirectory = java.io.File(root, "spool"))
            trace.emit(AlignmentLogEvent("diagnostics.copy", timestampNanos = 5_000_000L, durationNanos = 4_000_000L))
            trace.emit(AlignmentLogEvent("diagnostics.nested", timestampNanos = 4_000_000L, durationNanos = 1_000_000L))
            // A result must not depend on reopening the evidence stream. Export still
            // validates that stream independently and will report corruption.
            java.io.File(requireNotNull(trace.journal).directory, "events.jsonl").writeText("not-json\n")
            assertEquals(4_000_000L, trace.diagnosticNanosBetween(0L, 10_000_000L))
            trace.retainUnpublishedEvidence()
        } finally { root.deleteRecursively() }
    }

    @Test fun backgroundSpoolEncodingAndWriteAreNotNestedCalculationCost() {
        val root = java.nio.file.Files.createTempDirectory("timing-spool").toFile()
        try {
            val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = java.io.File(root, "spool"))
            val callerThread = Thread.currentThread()
            var encoderThread: Thread? = null
            val started = System.nanoTime()
            trace.attachEncoded("slow.png", { ByteArray(1024) { it.toByte() } }) { raw, output ->
                encoderThread = Thread.currentThread()
                Thread.sleep(120)
                output.write(raw)
            }
            val returned = System.nanoTime()
            trace.awaitSpool()
            val ended = System.nanoTime()
            assertTrue("caller must not wait for encoding", (returned - started) / 1e6 < 100.0)
            assertNotSame(callerThread, encoderThread)
            // The span covers the background encode, yet only the synchronous copy is nested cost.
            assertTrue(trace.diagnosticNanosBetween(started, ended) / 1e6 < 100.0)
            val spool = trace.snapshot().single { it.stage == "diagnostics.spool-artifact" }
            assertEquals(AlignmentSpool.EXECUTOR_LABEL, spool.labels["executor"])
            assertEquals("complete", spool.labels["status"])
            assertTrue(spool.durationNanos!! / 1e6 >= 100.0)
            assertEquals(1, trace.snapshot().count { it.stage == "diagnostics.capture-artifact" })
            assertArrayEquals(ByteArray(1024) { it.toByte() }, trace.artifactSnapshot().getValue("slow.png"))
            val summary = trace.snapshot().single { it.stage == "diagnostics.trace-recording" }
            assertEquals(1.0, summary.measurements.getValue("backgroundSpoolArtifacts"), 0.0)
            trace.releaseWrittenArtifacts()
        } finally { root.deleteRecursively() }
    }

    @Test fun nestedAndOverlappingDiagnosticsCountOnceAndClipToAlgorithmCall() {
        val trace = AlignmentTrace()
        trace.emit(AlignmentLogEvent("diagnostics.copy", timestampNanos = 5_000_000L, durationNanos = 4_000_000L))
        trace.emit(AlignmentLogEvent("diagnostics.read-artifact", timestampNanos = 4_000_000L, durationNanos = 1_000_000L))
        trace.emit(AlignmentLogEvent("diagnostics.serialize", timestampNanos = 12_000_000L, durationNanos = 6_000_000L))
        trace.emit(AlignmentLogEvent("algorithm.structure", timestampNanos = 10_000_000L, durationNanos = 10_000_000L))
        assertEquals(8_000_000L, trace.diagnosticNanosBetween(0L, 10_000_000L))
        assertEquals(0L, trace.diagnosticNanosBetween(13_000_000L, 14_000_000L))
    }
}
