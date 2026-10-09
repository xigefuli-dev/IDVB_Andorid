package com.idvb.android.alignment

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlignmentDurabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unavailableJournalDoesNotFallBackToUnboundedHeapAndRetryReleasesItsLease() {
        val root = temporary.newFolder()
        val spool = File(root, "spool").apply { writeText("blocks-directory-creation") }
        val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = spool)
        trace.emit(AlignmentLogEvent("unavailable", "cannot persist"))
        var allocated = false
        assertTrue(runCatching { trace.attachOwned("frame") { allocated = true; ByteArray(1024) } }.isFailure)
        assertFalse(allocated)
        assertTrue(trace.recordingFailures > 0)
        assertEquals(1, trace.snapshot().size) // Summary only; no unbounded fallback event list.
        assertTrue(spool.delete()); assertTrue(spool.mkdir())
        trace.persistTo(spool)
        trace.attachStream("frame") { it.write(1) }
        trace.releaseWrittenArtifacts()
        synchronized(com.idvb.android.diagnostics.DiagnosticHistory.lock) {
            com.idvb.android.diagnostics.DiagnosticHistory.requireIdle(root)
        }
        assertTrue(com.idvb.android.diagnostics.DiagnosticWriteTracker.awaitAccepted(root, 0).unfinished.isEmpty())
    }

    private fun stage(root: File): Pair<AlignmentJournal, File> {
        val journal = AlignmentJournal(File(root, "spool/request"))
        val bytes = ByteArray(256 * 1024).also { java.util.Random(492).nextBytes(it) }
        journal.writeArtifact("captured.png") { it.write(bytes) }
        journal.writeArtifact("diagnostics.json") { it.write("{\"fixture\":true}".toByteArray()) }
        val destination = File(root, "sessions/1000-test/alignment/alignment-test.zip")
        AlignmentArchiveTransaction.prepare(root, journal, destination)
        return journal to destination
    }

    @Test fun failedArchivePreservesAllInputsAndFreshInstanceRecoversExactBytes() {
        val root = temporary.newFolder()
        val (journal, destination) = stage(root)
        val expected = journal.artifacts.listFiles()!!.associate { it.name to AlignmentArchiveTransaction.sha256(it) }
        assertTrue(runCatching {
            AlignmentArchiveTransaction.publish(root, journal) { file ->
                object : FilterOutputStream(file.outputStream()) {
                    var count = 0
                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                        count += len
                        if (count > 4096) throw IOException("injected disk write failure")
                        out.write(bytes, off, len)
                    }
                }
            }
        }.isFailure)
        assertFalse(destination.exists())
        expected.forEach { (name, hash) -> assertEquals(hash, AlignmentArchiveTransaction.sha256(File(journal.artifacts, name))) }
        assertTrue(destination.parentFile!!.listFiles()!!.none { it.name.startsWith(".") })
        // No in-memory trace or pending callback is reused, as after a process restart.
        assertEquals(destination, AlignmentArchiveTransaction.publish(root, AlignmentJournal(journal.directory)))
        ZipFile(destination).use { zip -> expected.forEach { (name, hash) ->
            val actual = zip.getInputStream(zip.getEntry(name)).use { java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
                .joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual)
        } }
    }

    @Test fun failedZipCloseDoesNotPublishOrDeleteStagedEvidence() {
        val root = temporary.newFolder()
        val (journal, destination) = stage(root)
        assertTrue(runCatching { AlignmentArchiveTransaction.publish(root, journal) { file ->
            object : FilterOutputStream(file.outputStream()) {
                override fun close() { super.close(); throw IOException("injected finalization failure") }
            }
        } }.isFailure)
        assertFalse(destination.exists())
        assertTrue(File(journal.artifacts, "captured.png").isFile)
        AlignmentArchiveTransaction.publish(root, AlignmentJournal(journal.directory))
        assertTrue(destination.isFile)
    }

    @Test fun corruptedInputCannotReplaceAnExistingCommittedArchive() {
        val root = temporary.newFolder()
        val (journal, destination) = stage(root)
        destination.parentFile!!.mkdirs(); destination.writeText("previous committed content")
        java.io.RandomAccessFile(File(journal.artifacts, "captured.png"), "rw").use { it.writeInt(0x1742) }
        assertTrue(runCatching { AlignmentArchiveTransaction.publish(root, journal) }.isFailure)
        assertEquals("previous committed content", destination.readText())
        assertTrue(File(journal.directory, "state.json").isFile)
    }

    @Test fun failedArtifactReplacementKeepsThePreviousCompleteInput() {
        val journal = AlignmentJournal(temporary.newFolder())
        journal.writeArtifact("frame") { it.write(byteArrayOf(1, 2, 3)) }
        assertTrue(runCatching { journal.writeArtifact("frame") { it.write(9); throw IOException("disk full") } }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(journal.artifacts, "frame").readBytes())
        assertEquals(listOf("frame"), journal.artifacts.listFiles()!!.map(File::getName))
    }

    @Test fun largeNumericJournalDoesNotAccumulateEventsOnHeapAndSurvivesReopen() {
        val root = temporary.newFolder()
        val trace = AlignmentTrace(spoolDirectory = root)
        try {
            // 128 MiB of unique event text under the suite's 96 MiB heap ceiling.
            repeat(1024) { index -> trace.record(AlignmentLogEvent("large-series", "$index:" + "x".repeat(128 * 1024),
                measurements = mapOf("index" to index.toDouble()),
                gates = listOf(AlignmentGate("visible", 0.8, ">=", 0.7, true)),
                series = mapOf("domain" to listOf(Double.NaN, 1.0)))) }
            val restored = AlignmentJournal(requireNotNull(trace.journal).directory)
            var count = 0
            restored.forEachEvent { event ->
                assertTrue(event.detail.startsWith("$count:")); assertEquals(count.toDouble(), event.measurements["index"]!!, 0.0)
                assertTrue(event.gates.single().passed); assertTrue(event.series.getValue("domain").first().isNaN())
                count++
            }
            assertEquals(1024, count)
        } finally { trace.releaseWrittenArtifacts() }
    }

    @Test fun streamingPngRoundTripsRgbaAndMultipleIdatChunksWithValidCrc() {
        val width = 513; val height = 257
        val pixels = IntArray(width * height).also { data -> val random = java.util.Random(751); data.indices.forEach { data[it] = random.nextInt() } }
        val raw = temporary.newFile()
        java.io.DataOutputStream(raw.outputStream().buffered()).use { out -> pixels.forEach(out::writeInt) }
        val png = temporary.newFile()
        raw.inputStream().buffered().use { input -> png.outputStream().buffered().use { ArgbPngStream.write(input, width, height, it) } }
        val image = javax.imageio.ImageIO.read(png)
        assertEquals(width, image.width); assertEquals(height, image.height)
        assertArrayEquals(pixels, image.getRGB(0, 0, width, height, null, 0, width))
        var idats = 0
        java.io.DataInputStream(png.inputStream().buffered()).use { input ->
            input.skipBytes(8)
            while (true) {
                val length = input.readInt(); val name = ByteArray(4).also(input::readFully)
                val bytes = ByteArray(length).also(input::readFully)
                val crc = java.util.zip.CRC32().apply { update(name); update(bytes) }
                assertEquals(crc.value.toInt(), input.readInt())
                if (String(name) == "IDAT") idats++
                if (String(name) == "IEND") break
            }
        }
        assertTrue(idats > 1)
        assertTrue(runCatching { ArgbPngStream.write(byteArrayOf(1, 2).inputStream(), width, height, java.io.ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun pngEncoderStreamsMorePixelsThanHeapWithoutAFullFrameAllocation() {
        val width = 8192; val height = 4096 // 128 MiB ARGB; test JVM is limited to 96 MiB.
        val bytes = width.toLong() * height * 4
        val input = object : java.io.InputStream() {
            var remaining = bytes
            override fun read(): Int = if (remaining-- > 0) 127 else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, len.toLong()).toInt()
                buffer.fill(127, off, off + count); remaining -= count
                return count
            }
        }
        val png = temporary.newFile()
        png.outputStream().buffered().use { ArgbPngStream.write(input, width, height, it) }
        assertTrue(png.length() > 0)
        var rows = 0
        // Validate every decoded scanline using a streaming inflater, not ImageIO's full image.
        val idat = temporary.newFile()
        java.io.DataInputStream(png.inputStream().buffered()).use { source ->
            source.skipBytes(8)
            idat.outputStream().buffered().use { compressed ->
                while (true) {
                    val length = source.readInt(); val name = ByteArray(4).also(source::readFully)
                    val content = ByteArray(length).also(source::readFully); source.readInt()
                    if (String(name) == "IDAT") compressed.write(content)
                    if (String(name) == "IEND") break
                }
            }
        }
        java.util.zip.InflaterInputStream(idat.inputStream().buffered()).use { decoded ->
            val source = java.io.DataInputStream(decoded)
            val row = ByteArray(width * 4)
            repeat(height) { assertEquals(0, source.read()); source.readFully(row); assertTrue(row.all { it == 127.toByte() }); rows++ }
            assertEquals(-1, source.read())
        }
        assertEquals(height, rows)
    }
}
