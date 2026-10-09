package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class AlignmentTraceResourceTest {
    private fun evidenceFiles(directory: java.io.File) =
        directory.walkTopDown().filter { it.isFile && it.parentFile?.name == "artifacts" }.toList()

    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test fun queuedProductionArtifactsExceedHeapWithoutRetainingPayloads() {
        // Run under -Xmx96m: 192 MiB must survive, with byte-identical data and no dropping.
        val directory = temporary.newFolder()
        val traces = List(12) { AlignmentTrace(captureArtifacts = true, spoolDirectory = directory) }
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").apply { repeat(16) { update(block) } }.digest()
        repeat(192) { index ->
            val trace = traces[index % traces.size]
            when (index % 3) {
                0 -> trace.attachStream("input-$index") { output -> repeat(16) { output.write(block) } }
                1 -> trace.attachOwned("input-$index") { ByteArray(1024 * 1024) { block[it % block.size] } }
                else -> trace.attach("input-$index") { ByteArray(1024 * 1024) { block[it % block.size] } }
            }
        }
        assertEquals(192, evidenceFiles(directory).size)
        var written = 0
        val scratch = ByteArray(8192)
        traces.forEach { trace -> trace.consumeArtifacts { _, artifact ->
            assertTrue("Queued payload must be a private file", artifact is AlignmentArtifact.Spool)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            artifact.writeTo(object : java.io.OutputStream() {
                override fun write(b: Int) { digest.update(b.toByte()) }
                override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
            }, scratch)
            assertArrayEquals(expected, digest.digest()); written++
        } }
        assertEquals(192, written)
        assertTrue(evidenceFiles(directory).isEmpty())
    }

    @Test fun replacingSpoolThroughEveryAttachmentAdapterReleasesOldFile() {
        val directory = temporary.newFolder()
        val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = directory)
        trace.attachStream("same") { it.write(1) }
        trace.attach("same") { byteArrayOf(2) }
        trace.attachOwned("same") { byteArrayOf(3) }
        trace.attachDirect("same") { ByteBuffer.wrap(byteArrayOf(4)) }
        assertEquals(1, evidenceFiles(directory).size)
        trace.consumeArtifacts { _, artifact -> assertArrayEquals(byteArrayOf(4), artifact.toByteArray()) }
        assertTrue(evidenceFiles(directory).isEmpty())
    }

    @Test fun cancelledStreamingDoesNotPublishPartialArtifact() {
        val directory = temporary.newFolder()
        val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = directory)
        val cancellation = AlignmentCancellation()
        assertTrue(runCatching { cancellation.run {
            trace.attachStream("mask.raw") { output ->
                output.write(1); cancellation.cancel("eye-closed")
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.chunk")
            }
        } }.exceptionOrNull() is AlignmentCancelledException)
        assertTrue(evidenceFiles(directory).isEmpty())
        assertTrue(trace.artifactDataSnapshot().isEmpty())
        trace.attachStream("next.raw") { it.write(2) }
        trace.consumeArtifacts { _, artifact -> assertArrayEquals(byteArrayOf(2), artifact.toByteArray()) }
    }

    @Test fun manyReadinessInputsStreamFromPrivateFilesAndAreRemovedAfterWriting() {
        val directory = temporary.newFolder()
        val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = directory)
        val block = ByteArray(8192) { (it % 251).toByte() }
        repeat(120) { index ->
            trace.attachStream("readiness-$index.png") { out -> repeat(32) { out.write(block) } }
        }
        assertEquals(120, evidenceFiles(directory).size)
        var count = 0
        trace.consumeArtifacts { _, artifact ->
            assertTrue(artifact is AlignmentArtifact.Spool)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            artifact.writeTo(object : java.io.OutputStream() {
                override fun write(b: Int) { digest.update(b.toByte()) }
                override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
            }, ByteArray(1024))
            val expected = java.security.MessageDigest.getInstance("SHA-256")
            repeat(32) { expected.update(block) }
            assertArrayEquals(expected.digest(), digest.digest())
            count++
        }
        assertEquals(120, count)
        assertTrue(evidenceFiles(directory).isEmpty())
    }

    @Test fun failedSpoolAndFailedZipWriteReleasePrivateFiles() {
        val directory = temporary.newFolder()
        val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = directory)
        assertTrue(runCatching {
            trace.attachStream("failed.png") { it.write(1); error("disk failure") }
        }.isFailure)
        assertTrue(evidenceFiles(directory).isEmpty())
        repeat(2) { trace.attachStream("frame-$it.png") { out -> out.write(1) } }
        assertTrue(runCatching { trace.consumeArtifacts { _, _ -> error("zip failure") } }.isFailure)
        trace.releaseWrittenArtifacts()
        assertTrue(evidenceFiles(directory).isEmpty())
    }

    @Test fun deferredEncodingConsumesPreviousInputBeforeEncodingNext() {
        val trace = AlignmentTrace(captureArtifacts = true)
        var consumed = 0
        repeat(100) { index -> trace.attachOwned("frame-$index") {
            assertEquals(index, consumed)
            byteArrayOf(index.toByte())
        } }
        trace.consumeArtifacts { _, _ -> consumed++ }
        assertEquals(100, consumed)
        assertTrue(trace.artifactDataSnapshot().isEmpty())
    }
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
