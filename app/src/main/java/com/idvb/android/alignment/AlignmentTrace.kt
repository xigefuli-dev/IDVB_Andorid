package com.idvb.android.alignment

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One monotonic timeline across UI, capture, worker and persistence threads. */
class AlignmentTrace(
    val startedNanos: Long = System.nanoTime(),
    val captureArtifacts: Boolean = false,
    private val downstream: AlignmentLogSink = AlignmentLogSink.NONE,
    private val spoolDirectory: java.io.File? = null,
) : AlignmentLogSink {
    val id: String = UUID.randomUUID().toString()
    val createdAtMillis: Long = System.currentTimeMillis()
    private var storageLease = spoolDirectory?.parentFile?.let {
        com.idvb.android.diagnostics.DiagnosticHistory(it).retainFiles()
    }
    private var pendingCapture = spoolDirectory?.parentFile?.let {
        com.idvb.android.diagnostics.DiagnosticWriteTracker.begin(it, "alignment-collect", id)
    }
    internal var journal: AlignmentJournal? = spoolDirectory?.let { runCatching { AlignmentJournal(java.io.File(it, id)) }.getOrNull() }
        private set
    internal var recordingFailures: Int = if (spoolDirectory != null && journal == null) 1 else 0
        private set
    private var eventCount = 0L
    private val events = mutableListOf<AlignmentLogEvent>()
    private val artifacts = linkedMapOf<String, AlignmentArtifact>()
    private val deferredArtifacts = linkedMapOf<String, () -> ByteArray>()
    private var recordingNanos = 0L
    private val recordingIntervals = mutableListOf<Pair<Long, Long>>()
    // Keep only timing pairs on the interaction path. The durable journal can contain
    // megabytes of candidate grids; parsing it must never delay a computed result.
    private val diagnosticIntervals = mutableListOf<Pair<Long, Long>>()
    val completed = AtomicBoolean(false)
    init {
        runCatching { journal?.writeState("""{"schemaVersion":1,"status":"collecting","requestId":"$id","createdAtMillis":$createdAtMillis,"replayReady":false,"reason":"request-not-yet-committed"}""") }
            .onFailure { recordingFailures++ }
    }

    @Synchronized override fun record(event: AlignmentLogEvent) {
        val started = System.nanoTime()
        val associated = event.copy(labels = event.labels + ("requestId" to id))
        try {
            if (spoolDirectory != null && journal == null) throw java.io.IOException("Private event journal unavailable")
            journal?.append(associated) ?: events.add(associated)
            eventCount++
            associated.durationNanos?.takeIf { it > 0 && associated.stage.startsWith("diagnostics.") }
                ?.let { diagnosticIntervals += associated.timestampNanos - it to associated.timestampNanos }
        } catch (error: Exception) { recordingFailures++; throw error }
        downstream.emit(associated)
        val ended = System.nanoTime()
        recordingNanos += ended - started
        recordingIntervals += started to ended
    }

    override fun attach(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        if (journal != null) { attachStream(name) { it.write(bytes()) }; return }
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.read-artifact") { bytes() }
        synchronized(this) { artifacts.put(name, AlignmentArtifact.Bytes(content))?.release() }
    }

    /** Production spools immediately so suppliers cannot retain grids across requests.
     * The memory-only test adapter may defer immutable managed inputs. */
    @Synchronized fun attachDeferred(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        // Production must not retain suppliers that close over full score grids/pixels.
        if (journal != null) { attach(name, bytes); return }
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        deferredArtifacts[name] = bytes
    }

    override fun attachOwned(name: String, bytes: () -> ByteArray) = attachDeferred(name, bytes)

    /** Called on the capture worker while the borrowed bitmap is alive. Streaming to
     * private storage bounds memory even when readiness accepts hundreds of frames. */
    override fun attachStream(name: String, encode: (java.io.OutputStream) -> Unit) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val storage = journal ?: run { super.attachStream(name, encode); return }
        val file = measure("diagnostics.spool-artifact") { storage.writeArtifact(name, encode) }
        // Replacements use the same atomic destination; releasing the old handle would delete the new file.
        synchronized(this) { artifacts[name] = AlignmentArtifact.Spool(file) }
        emit(AlignmentLogEvent("diagnostics.artifact-storage", name,
            measurements = mapOf("bytes" to file.length().toDouble()),
            labels = mapOf("storage" to "private-file", "retainedFullFrameHeapBytes" to "0")))
    }

    override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        if (journal != null) {
            attachStream(name) { output -> AlignmentArtifact.Direct(capture()).writeTo(output, ByteArray(64 * 1024)) }
            return
        }
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.copy-direct-artifact") { AlignmentArtifact.Direct(capture()) }
        synchronized(this) { artifacts.put(name, content)?.release() }
    }

    private fun recordingSummary() = AlignmentLogEvent(
        "diagnostics.trace-recording", "Cumulative adapter and trace append cost, already included in enclosing spans; event construction excluded",
        measurements = mapOf("recordingMs" to recordingNanos / 1e6, "eventCount" to eventCount.toDouble(),
            "recordingFailures" to recordingFailures.toDouble()))

    /** Production consumers must stream; snapshot is an explicit in-memory test/inspection API. */
    @Synchronized fun forEachEvent(visit: (AlignmentLogEvent) -> Unit) {
        journal?.forEachEvent(visit) ?: events.forEach(visit)
        visit(recordingSummary())
    }
    @Synchronized fun snapshot(): List<AlignmentLogEvent> = buildList { forEachEvent { add(it) } }

    /** Test/export callers may start with an in-memory trace. Production already has a journal. */
    @Synchronized internal fun persistTo(directory: java.io.File) {
        if (journal != null) return
        val storage = AlignmentJournal(java.io.File(directory, id))
        if (storageLease == null) storageLease = com.idvb.android.diagnostics.DiagnosticHistory(requireNotNull(directory.parentFile)).retainFiles()
        if (pendingCapture == null) pendingCapture = com.idvb.android.diagnostics.DiagnosticWriteTracker.begin(requireNotNull(directory.parentFile), "alignment-collect", id)
        storage.writeState("""{"schemaVersion":1,"status":"collecting","requestId":"$id","createdAtMillis":$createdAtMillis,"replayReady":false,"reason":"request-not-yet-committed"}""")
        events.forEach(storage::append)
        journal = storage
        events.clear()
        val scratch = ByteArray(64 * 1024)
        for ((name, data) in artifacts.toMap()) {
            val file = storage.writeArtifact(name) { data.writeTo(it, scratch) }
            artifacts[name] = AlignmentArtifact.Spool(file)
            data.release()
        }
        for ((name, encode) in deferredArtifacts) attach(name, encode)
        deferredArtifacts.clear()
    }

    /** Union of synchronous diagnostic spans inside a call, so nested artifact/copy spans
     * are counted once. Deferred encoding/persistence outside the call is excluded. */
    @Synchronized fun diagnosticNanosBetween(started: Long, ended: Long): Long {
        val accountingStarted = System.nanoTime()
        val spans = recordingIntervals.toMutableList()
        spans += diagnosticIntervals
        val clipped = spans.mapNotNull { (from, to) ->
            val a = maxOf(started, from); val b = minOf(ended, to)
            if (b > a) a to b else null
        }.sortedBy { it.first }
        var total = 0L
        var previousEnd = started
        for ((from, to) in clipped) {
            if (to > previousEnd) total += to - maxOf(from, previousEnd)
            previousEnd = maxOf(previousEnd, to)
        }
        emit(AlignmentLogEvent("diagnostics.cost-accounting", "timing-index-union; no-journal-read; outside-measured-algorithm-call",
            durationNanos = System.nanoTime() - accountingStarted))
        return total
    }
    @Synchronized fun artifactDataSnapshot(): Map<String, AlignmentArtifact> {
        for ((name, encode) in deferredArtifacts) {
            artifacts[name] = AlignmentArtifact.Bytes(measure("diagnostics.encode-deferred-artifact") { encode() })
        }
        deferredArtifacts.clear()
        return artifacts.toMap()
    }
    /** Destructive compatibility helper for isolated tests. Production stages a complete
     * journal and only releases its files after atomic archive publication. */
    fun consumeArtifacts(write: (String, AlignmentArtifact) -> Unit) {
        while (true) {
            val next = synchronized(this) {
                artifacts.entries.firstOrNull()?.let { entry ->
                    val pair = entry.key to entry.value
                    artifacts.remove(entry.key)
                    pair
                }
            } ?: break
            try { write(next.first, next.second) } finally { next.second.release() }
        }
        while (true) {
            val next = synchronized(this) {
                deferredArtifacts.entries.firstOrNull()?.let { entry ->
                    val pair = entry.key to entry.value
                    deferredArtifacts.remove(entry.key)
                    pair
                }
            } ?: break
            val data = AlignmentArtifact.Bytes(measure("diagnostics.encode-deferred-artifact") { next.second() })
            write(next.first, data)
        }
    }
    /** Compatibility API for callers explicitly requesting heap bytes. The production
     * writer reads the durable journal and streams masks without this allocation. */
    @Synchronized fun artifactSnapshot(): Map<String, ByteArray> = artifactDataSnapshot().mapValues { it.value.toByteArray() }

    /** Async writer owns these suppliers/buffers; numeric cancellation and timing evidence remains. */
    @Synchronized fun releaseWrittenArtifacts() {
        if (journal == null) artifacts.values.forEach { it.release() }
        artifacts.clear(); deferredArtifacts.clear()
        journal?.directory?.let { directory ->
            val root = requireNotNull(directory.parentFile?.parentFile)
            if (directory.parentFile?.name == "spool")
                com.idvb.android.diagnostics.DiagnosticHistory(root).discardCommittedJournal(directory)
            else directory.deleteRecursively() // Explicit test-supplied private spool directory.
        }
        journal = null
        recordingIntervals.clear()
        diagnosticIntervals.clear()
        storageLease?.close(); storageLease = null
        pendingCapture?.close(); pendingCapture = null
    }

    /** Archive failure: keep the disk journal for recovery/export, release only live references. */
    @Synchronized internal fun retainUnpublishedEvidence() {
        artifacts.clear(); deferredArtifacts.clear(); events.clear(); recordingIntervals.clear(); diagnosticIntervals.clear()
        storageLease?.close(); storageLease = null
        pendingCapture?.close(); pendingCapture = null
    }
}
