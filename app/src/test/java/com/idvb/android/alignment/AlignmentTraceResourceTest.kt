package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class AlignmentTraceResourceTest {
    @Test fun streamingEvidenceSurvivesWrittenArtifactReleaseAndNumericHistoryRemains() {
        val trace = AlignmentTrace(captureArtifacts = true)
        trace.record(AlignmentLogEvent("cancelled", measurements = mapOf("acknowledgementMs" to 2.0)))
        trace.attachDirect("mask.raw") { ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(1, 2, 3, 4)); flip() } }
        trace.attachOwned("reference.raw") { byteArrayOf(5, 6) }
        val writing = trace.artifactDataSnapshot()
        val output = ByteArrayOutputStream()
        writing.getValue("mask.raw").writeTo(output, ByteArray(2))
        trace.releaseWrittenArtifacts()
        assertTrue(trace.artifactDataSnapshot().isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertArrayEquals(byteArrayOf(5, 6), writing.getValue("reference.raw").toByteArray())
        assertEquals(2.0, trace.snapshot().first { it.stage == "cancelled" }.measurements.getValue("acknowledgementMs"), 0.0)
    }
}
