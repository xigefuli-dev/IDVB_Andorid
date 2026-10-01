package com.idvb.android.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pin retained files during export; bounded reads exclude subsequent log appends. */
object HistoryArchive {
    fun write(root: File, destination: File, diagnostics: Boolean): Int {
        val history = DiagnosticHistory(root)
        return history.preservingFiles {
        val snapshot = synchronized(DiagnosticHistory.lock) {
        val files = if (diagnostics) listOf("recognition", "alignment").flatMap(history::files)
            .filter { !it.name.startsWith(".") }
        else history.sessionDirectories().take(DiagnosticHistory.MAX_SESSIONS).flatMap { folder ->
            listOf(File(folder, "app.log"), File(folder, "logcat-error.txt"))
        }.filter(File::isFile)
        files.map { it to it.length() }
        }
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for ((file, size) in snapshot) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { input ->
                    var remaining = size
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "诊断记录在打包期间发生变化，请重试" }
                        zip.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("history.txt"))
            zip.write(buildString {
                appendLine("schemaVersion=1")
                appendLine("maxSessions=${DiagnosticHistory.MAX_SESSIONS}")
                appendLine("sessionBoundary=overlay start or map identity reset; no automatic game-end detection")
                appendLine("legacy=recognition/ and alignment/ have unknown session identity")
                appendLine("snapshot=completed files only; pending asynchronous writes may be absent")
                appendLine("alignment=ZIP plus adjacent .write.json and request lifecycle sidecars")
                snapshot.forEach { (file, size) -> appendLine("$size\t${file.relativeTo(root).invariantSeparatorsPath}") }
            }.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        snapshot.size
        }
    }
}
