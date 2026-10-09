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
