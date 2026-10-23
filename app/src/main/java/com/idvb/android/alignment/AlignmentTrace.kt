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
    @Volatile internal var recordingFailures: Int = if (spoolDirectory != null && journal == null) 1 else 0
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
    // Private-file writes run on AlignmentSpool. Their cost is independent of the calculation
    // and is reported separately, never added to nested diagnostic accounting.
    private var spoolTail: java.util.concurrent.Future<*>? = null
    private var backgroundSpoolNanos = 0L
    private var backgroundRecordingNanos = 0L
    private var backgroundSpoolArtifacts = 0
    private var backpressureSpools = 0
    val completed = AtomicBoolean(false)
    init {
        runCatching { journal?.writeState("""{"schemaVersion":1,"status":"collecting","requestId":"$id","createdAtMillis":$createdAtMillis,"replayReady":false,"reason":"request-not-yet-committed"}""") }
            .onFailure { recordingFailures++ }
    }

    /** Label merge and JSON encoding are request-local and deliberately run outside the monitor:
     * a scan drives one trace from several floor workers, and doing that work under the lock
     * serialized them into a single thread of execution. Only shared state is synchronized. */
    override fun record(event: AlignmentLogEvent) {
        val started = System.nanoTime()
        val background = AlignmentSpool.onSpoolThread
        val associated = event.copy(labels = event.labels + ("requestId" to id))
        val storage = synchronized(this) {
            if (spoolDirectory != null && journal == null) {
                recordingFailures++
                throw java.io.IOException("Private event journal unavailable")
            }
            journal
        }
        val encoded = try { storage?.encode(associated) }
        catch (error: Exception) { synchronized(this) { recordingFailures++ }; throw error }
        try {
            synchronized(this) {
                val current = journal
                when {
                    current == null -> events.add(associated)
                    encoded != null && current === storage -> current.appendEncoded(encoded)
                    else -> current.append(associated)
                }
                eventCount++
                if (!background) associated.durationNanos?.takeIf { it > 0 && associated.stage.startsWith("diagnostics.") }
                    ?.let { diagnosticIntervals += associated.timestampNanos - it to associated.timestampNanos }
            }
        } catch (error: Exception) { synchronized(this) { recordingFailures++ }; throw error }
        downstream.emit(associated)
        val ended = System.nanoTime()
        synchronized(this) {
            if (background) { backgroundRecordingNanos += ended - started; return }
            recordingNanos += ended - started
            recordingIntervals += started to ended
        }
    }

    override fun attach(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.read-artifact") { bytes() }
        val storage = journal
        if (storage != null) { spool(storage, name, content.size.toLong()) { it.write(content) }; return }
        synchronized(this) { artifacts.put(name, AlignmentArtifact.Bytes(content))?.release() }
    }

    /** Production spools immediately so suppliers cannot retain grids across requests.
     * The memory-only test adapter may defer immutable managed inputs. */
    fun attachDeferred(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        // Production must not retain suppliers that close over full score grids/pixels.
        // Not under the monitor: spool backpressure may wait for this trace's queued writes.
        if (journal != null) { attach(name, bytes); return }
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        synchronized(this) { deferredArtifacts[name] = bytes }
    }

    override fun attachOwned(name: String, bytes: () -> ByteArray) = attachDeferred(name, bytes)

    /** Called on the capture/calculation worker while the borrowed input is alive. The encoder
     * runs now into an owned buffer; only the private-file write moves to the spool thread. */
    override fun attachStream(name: String, encode: (java.io.OutputStream) -> Unit) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val storage = journal ?: run { super.attachStream(name, encode); return }
        val captured = measure("diagnostics.capture-artifact") { AlignmentCapturedBytes().also(encode) }
        spool(storage, name, captured.size().toLong()) { captured.writeContentTo(it) }
    }

    /** Only the owned copy is taken on the caller's thread; PNG/zlib encoding runs on the spool thread. */
    override fun attachEncoded(name: String, capture: () -> ByteArray, encode: (ByteArray, java.io.OutputStream) -> Unit) {
        if (!captureArtifacts) return
        if (spoolDirectory != null && journal == null) throw java.io.IOException("Private evidence journal unavailable")
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val storage = journal ?: run { super.attachEncoded(name, capture, encode); return }
        val owned = measure("diagnostics.capture-artifact") { capture() }
        spool(storage, name, owned.size.toLong()) { encode(owned, it) }
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

    /** Queue an owned payload for the spool thread. Over the global byte budget the write happens
     * now, after this trace's earlier writes, and is recorded as nested backpressure cost. */
    private fun spool(storage: AlignmentJournal, name: String, bytes: Long, write: (java.io.OutputStream) -> Unit) {
        if (!AlignmentSpool.tryReserve(bytes)) {
            val waited = System.nanoTime()
            val queuedBytes = AlignmentSpool.queuedBytes()
            awaitSpool()
            val file = measure("diagnostics.spool-artifact") { storage.writeArtifact(name, write) }
            synchronized(this) { artifacts[name] = AlignmentArtifact.Spool(file); backpressureSpools++ }
            emit(AlignmentLogEvent("diagnostics.spool-backpressure", name, durationNanos = System.nanoTime() - waited,
                measurements = mapOf("bytes" to bytes.toDouble(), "queuedBytes" to queuedBytes.toDouble()),
                thresholds = mapOf("maxQueuedBytes" to AlignmentSpool.MAX_QUEUED_BYTES.toDouble()),
                labels = mapOf("artifact" to name, "storage" to "private-file", "mode" to "synchronous-over-queue-budget",
                    "costScope" to "nested-in-caller; includes-wait-for-this-request-earlier-writes")))
            return
        }
        val queued = System.nanoTime()
        val task = AlignmentSpool.submit(bytes) {
            val started = System.nanoTime()
            val written = runCatching { storage.writeArtifact(name, write) }
            val ended = System.nanoTime()
            synchronized(this) {
                // Replacements use the same atomic destination; releasing the old handle would delete the new file.
                written.onSuccess { artifacts[name] = AlignmentArtifact.Spool(it) }.onFailure { recordingFailures++ }
                backgroundSpoolNanos += ended - started; backgroundSpoolArtifacts++
            }
            emit(AlignmentLogEvent("diagnostics.spool-artifact",
                written.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "completed",
                measurements = mapOf("bytes" to bytes.toDouble(), "queueMs" to (started - queued) / 1e6,
                    "writtenBytes" to (written.getOrNull()?.length()?.toDouble() ?: -1.0)),
                labels = mapOf("artifact" to name, "storage" to "private-file", "executor" to AlignmentSpool.EXECUTOR_LABEL,
                    "status" to if (written.isSuccess) "complete" else "failed-not-recorded", "retainedFullFrameHeapBytes" to "0",
                    "costScope" to "background-spool-thread; independent-of-calculation-capture-and-first-draw; not-nested"),
                timestampNanos = ended, durationNanos = ended - started))
        }
        synchronized(this) { spoolTail = task }
    }

    /** Blocks until this request's queued evidence is on disk. Writers, exporters and
     * releases call it first; it must never run on the interaction path or under this monitor. */
    fun awaitSpool() {
        check(!Thread.holdsLock(this)) { "awaitSpool under trace monitor would deadlock the spool thread" }
        if (AlignmentSpool.onSpoolThread) return
        val tail = synchronized(this) { spoolTail } ?: return
        // FIFO executor: once this request's last task finished, all its earlier writes did too.
        try { tail.get() } catch (_: java.util.concurrent.ExecutionException) { }
        synchronized(this) { if (spoolTail === tail) spoolTail = null }
    }

    private fun recordingSummary() = AlignmentLogEvent(
        "diagnostics.trace-recording", "Cumulative adapter and trace append cost, already included in enclosing spans; event construction excluded. Background spool cost is independent and not nested.",
        measurements = mapOf("recordingMs" to recordingNanos / 1e6, "eventCount" to eventCount.toDouble(),
            "recordingFailures" to recordingFailures.toDouble(),
            "backgroundSpoolMs" to backgroundSpoolNanos / 1e6, "backgroundRecordingMs" to backgroundRecordingNanos / 1e6,
            "backgroundSpoolArtifacts" to backgroundSpoolArtifacts.toDouble(), "backpressureSpools" to backpressureSpools.toDouble()))

    /** Production consumers must stream; snapshot is an explicit in-memory test/inspection API. */
    fun forEachEvent(visit: (AlignmentLogEvent) -> Unit) {
        awaitSpool()
        synchronized(this) {
            journal?.forEachEvent(visit) ?: events.forEach(visit)
            visit(recordingSummary())
        }
    }
    fun snapshot(): List<AlignmentLogEvent> = buildList { forEachEvent { add(it) } }

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
        // Archive-writer path, under the monitor: write synchronously rather than through the spool.
        for ((name, encode) in deferredArtifacts) {
            val content = measure("diagnostics.encode-deferred-artifact") { encode() }
            artifacts[name] = AlignmentArtifact.Spool(storage.writeArtifact(name) { it.write(content) })
        }
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
    fun artifactDataSnapshot(): Map<String, AlignmentArtifact> {
        awaitSpool()
        synchronized(this) {
            for ((name, encode) in deferredArtifacts) {
                artifacts[name] = AlignmentArtifact.Bytes(measure("diagnostics.encode-deferred-artifact") { encode() })
            }
            deferredArtifacts.clear()
            return artifacts.toMap()
        }
    }
    /** Destructive compatibility helper for isolated tests. Production stages a complete
     * journal and only releases its files after atomic archive publication. */
    fun consumeArtifacts(write: (String, AlignmentArtifact) -> Unit) {
        awaitSpool()
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
    fun artifactSnapshot(): Map<String, ByteArray> = artifactDataSnapshot().mapValues { it.value.toByteArray() }

    /** Async writer owns these suppliers/buffers; numeric cancellation and timing evidence remains. */
    fun releaseWrittenArtifacts() {
        awaitSpool()
        synchronized(this) {
            if (journal == null) artifacts.values.forEach { it.release() }
            artifacts.clear(); deferredArtifacts.clear()
            journal?.close()
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
    }

    /** Archive failure: keep the disk journal for recovery/export, release only live references. */
    internal fun retainUnpublishedEvidence() {
        awaitSpool()
        synchronized(this) {
            journal?.close()
            artifacts.clear(); deferredArtifacts.clear(); events.clear(); recordingIntervals.clear(); diagnosticIntervals.clear()
            storageLease?.close(); storageLease = null
            pendingCapture?.close(); pendingCapture = null
        }
    }
}
