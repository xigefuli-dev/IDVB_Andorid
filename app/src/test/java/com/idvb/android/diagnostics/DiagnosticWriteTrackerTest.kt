package com.idvb.android.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticWriteTrackerTest {
    @Test fun exportWaitsForAlreadyAcceptedPublicationWithoutHoldingHistoryLock() {
        val root = Files.createTempDirectory("diagnostic-barrier").toFile()
        val worker = Executors.newSingleThreadExecutor()
        val history = DiagnosticHistory(root)
        val session = history.begin()
        val ticket = DiagnosticWriteTracker.begin(root, "recognition", "accepted-scan")
        try {
            val output = File(root, "export.zip")
            val exported = worker.submit<Int> { HistoryArchive.write(root, output, true) }
            try { exported.get(100, TimeUnit.MILLISECONDS); fail("Export omitted pending evidence") }
            catch (_: java.util.concurrent.TimeoutException) { }
            val evidence = File(history.directoryFor(session.name, "recognition"), "recognition-complete.zip")
            evidence.writeText("atomic published evidence")
            ticket.close()
            assertEquals(1, exported.get(3, TimeUnit.SECONDS))
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry(evidence.relativeTo(root).invariantSeparatorsPath))
                val receipt = zip.getInputStream(zip.getEntry("diagnostic-export.json")).bufferedReader().use { it.readText() }
                assertTrue(receipt.contains("\"acceptedWritesCompleted\":true"))
            }
        } finally { ticket.close(); worker.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun timedOutWorkIsNamedInExportAndIsNeverReportedAsComplete() {
        val root = Files.createTempDirectory("diagnostic-timeout").toFile()
        val ticket = DiagnosticWriteTracker.begin(root, "alignment", "slow-request")
        try {
            val output = File(root, "export.zip")
            HistoryArchive.write(root, output, true, waitForWritesMs = 0)
            ZipFile(output).use { zip ->
                val receipt = zip.getInputStream(zip.getEntry("diagnostic-export.json")).bufferedReader().use { it.readText() }
                assertTrue(receipt.contains("\"acceptedWritesCompleted\":false"))
                assertTrue(receipt.contains("slow-request"))
                assertTrue(receipt.contains("pending-writes-did-not-finish-before-export-timeout"))
            }
            ticket.close(); ticket.close()
            assertTrue(DiagnosticWriteTracker.awaitAccepted(root, 0).unfinished.isEmpty())
        } finally { ticket.close(); root.deleteRecursively() }
    }
}
