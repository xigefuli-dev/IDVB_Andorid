package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentDiagnosticTimingTest {
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
