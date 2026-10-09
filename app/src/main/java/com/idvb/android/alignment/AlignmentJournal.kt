package com.idvb.android.alignment

import java.io.File
import java.io.OutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Request-local, app-private write-ahead evidence. Completed files have stable names;
 * a failed archive must never consume them. No file here is automatically media-published. */
internal class AlignmentJournal(val directory: File) {
    val artifacts = File(directory, "artifacts")
    private val events = File(directory, "events.jsonl")
    private val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }

    init { check(artifacts.mkdirs() || artifacts.isDirectory) }

    @Synchronized fun append(event: AlignmentLogEvent) {
        // One event at a time; closing publishes bytes to subsequent readers/restarted processes.
        val text = json.encodeToString(event) + "\n"
        val previousLength = events.length()
        try { events.appendText(text) }
        catch (error: Exception) {
            // A short write must not poison every later event. Never truncate prior complete events.
            runCatching { java.io.RandomAccessFile(events, "rw").use { it.setLength(previousLength) } }
            throw error
        }
    }

    @Synchronized fun forEachEvent(visit: (AlignmentLogEvent) -> Unit) {
        if (!events.exists()) return
        events.bufferedReader().use { input ->
            while (true) {
                val line = input.readLine() ?: break
                visit(json.decodeFromString<AlignmentLogEvent>(line))
            }
        }
    }

    fun writeArtifact(name: String, write: (OutputStream) -> Unit): File {
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        return publish(File(artifacts, name), write)
    }

    fun writeState(text: String) = publish(File(directory, "state.json")) { it.write(text.toByteArray()) }

    companion object {
        /** Old committed content survives a failed replacement. Temp files are not export inputs. */
        fun publish(destination: File, write: (OutputStream) -> Unit): File {
            val parent = requireNotNull(destination.parentFile)
            check(parent.mkdirs() || parent.isDirectory)
            val temporary = File.createTempFile(".pending-", ".tmp", parent)
            try {
                temporary.outputStream().buffered().use(write)
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                return destination
            } finally { temporary.delete() }
        }
    }
}
