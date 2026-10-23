package com.idvb.android.alignment

import java.io.File
import java.io.OutputStream
import kotlinx.serialization.json.Json

/** Request-local, app-private write-ahead evidence. Completed files have stable names;
 * a failed archive must never consume them. No file here is automatically media-published. */
internal class AlignmentJournal(val directory: File) {
    val artifacts = File(directory, "artifacts")
    private val events = File(directory, "events.jsonl")
    // Absent fields decode to the data class defaults, so omitting them keeps every event
    // reconstructible while removing the always-empty collections from the hot path.
    private val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = false }
    private val reader = Json { allowSpecialFloatingPointValues = true; ignoreUnknownKeys = true }
    private var stream: java.io.BufferedOutputStream? = null
    /** Bytes already handed to the file system; a failed buffer flush rewinds to here. */
    private var committedLength = 0L

    init { check(artifacts.mkdirs() || artifacts.isDirectory) }

    /** Serialization is request-local work and must not hold the append monitor: the scan runs
     * one trace from several floor workers, and encoding under the lock serializes them all. */
    fun encode(event: AlignmentLogEvent): ByteArray =
        (json.encodeToString(AlignmentLogEvent.serializer(), event) + "\n").toByteArray()

    fun append(event: AlignmentLogEvent) = appendEncoded(encode(event))

    /** One buffered byte copy per event. Readers and artifact writers flush first, so a
     * subsequent reader or restarted process still sees every completed event. */
    @Synchronized fun appendEncoded(text: ByteArray) {
        val output = stream ?: java.io.FileOutputStream(events, true).buffered(64 * 1024).also {
            committedLength = events.length(); stream = it
        }
        try { output.write(text) }
        catch (error: Exception) {
            // A short write must not poison every later event. Never truncate prior complete events.
            stream = null
            runCatching { output.close() }
            runCatching { java.io.RandomAccessFile(events, "rw").use { it.setLength(committedLength) } }
            throw error
        }
    }

    @Synchronized fun flush() {
        val output = stream ?: return
        try { output.flush(); committedLength = events.length() }
        catch (error: Exception) {
            stream = null
            runCatching { output.close() }
            runCatching { java.io.RandomAccessFile(events, "rw").use { it.setLength(committedLength) } }
            throw error
        }
    }

    fun forEachEvent(visit: (AlignmentLogEvent) -> Unit) {
        flush()
        synchronized(this) {
            if (!events.exists()) return
            events.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isNotBlank()) visit(reader.decodeFromString(AlignmentLogEvent.serializer(), line))
                }
            }
        }
    }

    /** Releases the append handle. Completed events are flushed first; a journal whose
     * directory is being discarded must not keep a descriptor open for every past request. */
    @Synchronized fun close() {
        val output = stream ?: return
        stream = null
        runCatching { output.flush() }
        runCatching { output.close() }
        committedLength = events.length()
    }

    fun writeArtifact(name: String, write: (OutputStream) -> Unit): File {
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        return publish(File(artifacts, name), write)
    }

    fun writeState(text: String): File {
        runCatching { flush() }
        return publish(File(directory, "state.json")) { it.write(text.toByteArray()) }
    }

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
