package com.idvb.android.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Pin retained files during export; bounded reads exclude subsequent log appends. */
object HistoryArchive {
    fun write(root: File, destination: File, diagnostics: Boolean, waitForWritesMs: Long = 30_000): Int {
        val history = DiagnosticHistory(root)
        return history.preservingFiles {
        val barrier = if (diagnostics) DiagnosticWriteTracker.awaitAccepted(root, waitForWritesMs) else null
        val snapshot = synchronized(DiagnosticHistory.lock) {
        val files = if (diagnostics) listOf("recognition", "alignment").flatMap(history::files)
            .filter { !it.name.startsWith(".") } + File(root, "spool").walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }.toList()
        else history.sessionDirectories().take(DiagnosticHistory.MAX_SESSIONS).flatMap { folder ->
            listOf(File(folder, "app.log"), File(folder, "logcat-error.txt"))
        }.filter(File::isFile)
        files.map { it to it.length() }
        }
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            val hashes = linkedMapOf<String, Pair<Long, String>>()
            for ((file, size) in snapshot) {
                val path = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    var remaining = size
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "诊断记录在打包期间发生变化，请重试" }
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                }
                zip.closeEntry()
                hashes[path] = size to digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (barrier != null) {
                zip.putNextEntry(ZipEntry("diagnostic-export.json"))
                zip.write(buildJsonObject {
                    put("schemaVersion", 1); put("acceptedWritesCompleted", barrier.unfinished.isEmpty())
                    put("waitedMilliseconds", barrier.waitedMs)
                    put("scope", "wait for writes accepted before export cutoff; later work is outside this snapshot; completed writes may contain explicit non-replayable records")
                    put("unpublishedEvidence", "spool/<requestId> contains retained original inputs and event journal; only state=ready-to-publish is eligible for automatic ZIP recovery; collecting records are incomplete, never a successful replay")
                    fun jobs(values: List<DiagnosticWriteTracker.Pending>) = buildJsonArray { values.forEach { job -> add(buildJsonObject {
                        put("id", job.id); put("kind", job.kind); put("acceptedAtMillis", job.acceptedAtMillis)
                    }) } }
                    put("writesAtCutoff", jobs(barrier.accepted)); put("unfinishedWrites", jobs(barrier.unfinished))
                    put("unavailableReason", if (barrier.unfinished.isEmpty()) "" else "pending-writes-did-not-finish-before-export-timeout")
                    put("files", buildJsonObject { hashes.forEach { (path, info) -> put(path, buildJsonObject {
                        put("bytes", info.first); put("sha256", info.second)
                    }) } })
                }.toString().toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("history.txt"))
            zip.write(buildString {
                appendLine("schemaVersion=1")
                appendLine("maxSessions=${DiagnosticHistory.MAX_SESSIONS}")
                appendLine("sessionBoundary=overlay start or map identity reset; no automatic game-end detection")
                appendLine("legacy=recognition/ and alignment/ have unknown session identity")
                appendLine("snapshot=wait for work accepted at export cutoff; timeout and unfinished requests are explicit in diagnostic-export.json; later work is outside this snapshot")
                appendLine("alignment=ZIP plus adjacent .write.json and request lifecycle sidecars")
                snapshot.forEach { (file, size) -> appendLine("$size\t${file.relativeTo(root).invariantSeparatorsPath}") }
            }.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        snapshot.size
        }
    }
}
