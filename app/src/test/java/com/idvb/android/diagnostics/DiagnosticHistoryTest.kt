package com.idvb.android.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.ZipFile

class DiagnosticHistoryTest {
    @Test fun firstQueuedRequestCreatesSessionAtCaptureTimeInsteadOfDelayedWriterTime() {
        val root = temporary.newFolder()
        val history = DiagnosticHistory(root) { 10_000L }
        val first = history.directoryAt(1000, "alignment")
        assertTrue(first.parentFile!!.name.startsWith("1000-"))
        repeat(12) { assertEquals(first, history.directoryAt(1000L + it, "alignment")) }
        assertTrue(runCatching { history.directoryAt(999, "alignment") }.isFailure)
    }

    @Test fun committedJournalRemainsReadableUntilConcurrentExportLeaseCloses() {
        val root = temporary.newFolder()
        val history = DiagnosticHistory(root)
        val journal = File(root, "spool/request").apply { mkdirs() }
        val input = File(journal, "input").apply { writeText("original capture") }
        val capture = history.retainFiles()
        val export = history.retainFiles()
        history.discardCommittedJournal(journal)
        capture.close()
        assertEquals("original capture", input.readText())
        assertTrue(runCatching { synchronized(DiagnosticHistory.lock) { DiagnosticHistory.requireIdle(root) } }.isFailure)
        export.close(); export.close()
        assertFalse(journal.exists())
        synchronized(DiagnosticHistory.lock) { DiagnosticHistory.requireIdle(root) }
    }

    @Test fun exportIncludesUnpublishedInputsAndTheirIncompleteState() {
        val root = temporary.newFolder()
        val journal = File(root, "spool/request/artifacts").apply { mkdirs() }
        File(journal, "reference.png").writeText("original input")
        File(journal.parentFile, "state.json").writeText("{\"status\":\"collecting\",\"replayReady\":false}")
        File(journal, ".pending-failed.tmp").writeText("partial")
        val archive = temporary.newFile()
        assertEquals(2, HistoryArchive.write(root, archive, true, 0))
        ZipFile(archive).use { zip ->
            assertEquals("original input", zip.getInputStream(zip.getEntry("spool/request/artifacts/reference.png")).reader().readText())
            assertNotNull(zip.getEntry("spool/request/state.json"))
            assertNull(zip.getEntry("spool/request/artifacts/.pending-failed.tmp"))
            assertTrue(zip.getInputStream(zip.getEntry("diagnostic-export.json")).reader().readText().contains("collecting records are incomplete"))
        }
    }

    @Test fun slowWriterPinsFilesWithoutBlockingHistoryAccessAndPreventsConcurrentClear() {
        val root = temporary.newFolder()
        val history = DiagnosticHistory(root)
        val first = history.begin()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val writing = worker.submit { history.preservingFiles {
                entered.countDown()
                check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(first, history.directoryFor(first.name, "alignment").parentFile)
            synchronized(DiagnosticHistory.lock) {
                assertTrue(runCatching { DiagnosticHistory.requireIdle(root) }.isFailure)
            }
            finish.countDown()
            writing.get(2, java.util.concurrent.TimeUnit.SECONDS)
            synchronized(DiagnosticHistory.lock) { DiagnosticHistory.requireIdle(root) }
        } finally { finish.countDown(); worker.shutdownNow() }
    }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun keepsWholeSessionsAndPrunesTwentyFirstIncludingLegacy() {
        val root = temporary.newFolder()
        val legacy = File(root, "recognition/recognition-old.zip").apply { parentFile!!.mkdirs(); writeText("old") }
        var now = 1000L
        val history = DiagnosticHistory(root) { now }
        val first = history.begin()
        repeat(30) { File(history.directoryAt(now, "recognition"), "recognition-$it.zip").writeText("scan $it") }
        assertEquals(31, history.files("recognition").size)
        repeat(18) { now += 10; history.begin() }
        assertTrue(legacy.exists())
        assertEquals(19, history.sessionDirectories().size)
        now += 10; history.begin()
        assertFalse(legacy.exists())
        assertTrue(first.exists())
        now += 10; history.begin()
        assertFalse(first.exists())
        assertEquals(20, history.sessionDirectories().size)
    }

    @Test fun lateWritesStayWithOriginalSessionAndHistorySurvivesRecreation() {
        val root = temporary.newFolder()
        var now = 1000L
        val history = DiagnosticHistory(root) { now }
        val first = history.begin()
        now = 2000; val second = history.begin()
        val restored = DiagnosticHistory(root) { now }
        assertEquals(first, restored.directoryAt(1500, "alignment").parentFile)
        assertEquals(second, restored.directoryAt(2000, "recognition").parentFile)
        repeat(20) { now += 10; history.begin() }
        assertTrue(runCatching { restored.directoryAt(1500, "alignment") }.isFailure)
    }

    @Test fun explicitSessionIdentitySurvivesSameMillisecondResetAndClockRollback() {
        val root = temporary.newFolder()
        val history = DiagnosticHistory(root) { 1000L }
        val first = history.begin()
        val second = history.begin()
        assertEquals(first, history.directoryFor(first.name, "alignment").parentFile)
        assertEquals(second, history.directoryFor(second.name, "recognition").parentFile)
        repeat(20) { history.begin() }
        assertTrue(runCatching { history.directoryFor(first.name, "alignment") }.isFailure)
    }

    @Test fun exportLeaseDefersDeletionButDoesNotExpandVisibleHistory() {
        val root = temporary.newFolder()
        var now = 1000L
        val history = DiagnosticHistory(root) { now }
        val first = history.begin()
        File(history.directoryFor(first.name, "recognition"), "recognition-first.zip").writeText("first")
        history.preservingFiles {
            repeat(20) { now += 10; history.begin() }
            assertTrue(first.exists())
            assertTrue(history.files("recognition").isEmpty())
        }
        assertFalse(first.exists())
        assertEquals(20, history.sessionDirectories().size)
    }

    @Test fun archiveIncludesAllSessionsSidecarsAndMoreThanTwentyMegabytes() {
        val root = temporary.newFolder()
        var now = 1000L
        val history = DiagnosticHistory(root) { now }
        val first = history.begin()
        File(first, "app.log").writeText("first session log")
        val input = File(history.directoryAt(now, "recognition"), "recognition-large.zip")
        val random = Random(42)
        input.outputStream().use { out ->
            val bytes = ByteArray(1024 * 1024)
            repeat(21) { random.nextBytes(bytes); out.write(bytes) }
        }
        now += 10
        val second = history.begin()
        File(second, "app.log").writeText("second session log")
        val alignment = history.directoryAt(now, "alignment")
        File(alignment, "alignment-test.zip").writeText("diagnostic")
        File(alignment, "alignment-test.zip.write.json").writeText("timing")
        File(alignment, "request-test.lifecycle.jsonl").writeText("lifecycle")
        File(alignment, ".pending.tmp").writeText("incomplete")
        val archive = temporary.newFile()
        assertEquals(4, HistoryArchive.write(root, archive, true))
        assertTrue(archive.length() > 20L * 1024 * 1024)
        ZipFile(archive).use { zip ->
            assertEquals(6, zip.size())
            assertNotNull(zip.getEntry("diagnostic-export.json"))
            val entry = zip.getEntry(input.relativeTo(root).invariantSeparatorsPath)
            input.inputStream().use { expected -> zip.getInputStream(entry).use { actual ->
                val left = ByteArray(8192); val right = ByteArray(8192)
                while (true) {
                    val count = expected.read(left)
                    if (count < 0) break
                    var offset = 0
                    while (offset < count) { val n = actual.read(right, offset, count - offset); assertTrue(n > 0); offset += n }
                    assertArrayEquals(left.copyOf(count), right.copyOf(count))
                }
                assertEquals(-1, actual.read())
            } }
        }
        assertEquals(2, HistoryArchive.write(root, archive, false))
        ZipFile(archive).use { zip ->
            assertEquals(3, zip.size())
            assertNotNull(zip.getEntry(first.relativeTo(root).invariantSeparatorsPath + "/app.log"))
            assertNotNull(zip.getEntry(second.relativeTo(root).invariantSeparatorsPath + "/app.log"))
        }
    }
}
