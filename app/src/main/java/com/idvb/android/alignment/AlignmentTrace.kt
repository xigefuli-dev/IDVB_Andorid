package com.idvb.android.alignment

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One monotonic timeline across UI, capture, worker and persistence threads. */
class AlignmentTrace(
    val startedNanos: Long = System.nanoTime(),
    val captureArtifacts: Boolean = false,
    private val downstream: AlignmentLogSink = AlignmentLogSink.NONE,
) : AlignmentLogSink {
    val id: String = UUID.randomUUID().toString()
    val createdAtMillis: Long = System.currentTimeMillis()
    private val events = mutableListOf<AlignmentLogEvent>()
    private val artifacts = linkedMapOf<String, AlignmentArtifact>()
    private val deferredArtifacts = linkedMapOf<String, () -> ByteArray>()
    private var recordingNanos = 0L
    private val recordingIntervals = mutableListOf<Pair<Long, Long>>()
    val completed = AtomicBoolean(false)

    @Synchronized override fun record(event: AlignmentLogEvent) {
        val started = System.nanoTime()
        val associated = event.copy(labels = event.labels + ("requestId" to id))
        events += associated
        downstream.emit(associated)
        val ended = System.nanoTime()
        recordingNanos += ended - started
        recordingIntervals += started to ended
    }

    override fun attach(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.read-artifact") { bytes() }
        synchronized(this) { artifacts[name] = AlignmentArtifact.Bytes(content) }
    }

    /** The supplier must own immutable input, never a borrowed/recyclable capture. Encoding
     * runs when the diagnostics writer snapshots artifacts, after the interaction completes. */
    @Synchronized fun attachDeferred(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        deferredArtifacts[name] = bytes
    }

    override fun attachOwned(name: String, bytes: () -> ByteArray) = attachDeferred(name, bytes)

    override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) {
        if (!captureArtifacts) return
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.copy-direct-artifact") { AlignmentArtifact.Direct(capture()) }
        synchronized(this) { artifacts[name] = content }
    }

    @Synchronized fun snapshot(): List<AlignmentLogEvent> = events.toList() + AlignmentLogEvent(
        "diagnostics.trace-recording", "Cumulative adapter and trace append cost, already included in enclosing spans; event construction excluded",
        measurements = mapOf("recordingMs" to recordingNanos / 1e6, "eventCount" to events.size.toDouble()))

    /** Union of synchronous diagnostic spans inside a call, so nested artifact/copy spans
     * are counted once. Deferred encoding/persistence outside the call is excluded. */
    @Synchronized fun diagnosticNanosBetween(started: Long, ended: Long): Long {
        val spans = recordingIntervals + events.mapNotNull { event ->
            event.durationNanos?.takeIf { it > 0 && event.stage.startsWith("diagnostics.") }
                ?.let { event.timestampNanos - it to event.timestampNanos }
        }
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
        return total
    }
    @Synchronized fun artifactDataSnapshot(): Map<String, AlignmentArtifact> {
        for ((name, encode) in deferredArtifacts) {
            artifacts[name] = AlignmentArtifact.Bytes(measure("diagnostics.encode-deferred-artifact") { encode() })
        }
        deferredArtifacts.clear()
        return artifacts.toMap()
    }
    /** Compatibility API for callers explicitly requesting heap bytes. The production
     * writer uses artifactDataSnapshot and streams direct masks without this allocation. */
    @Synchronized fun artifactSnapshot(): Map<String, ByteArray> = artifactDataSnapshot().mapValues { it.value.toByteArray() }

    /** Async writer owns these suppliers/buffers; numeric cancellation and timing evidence remains. */
    @Synchronized fun releaseWrittenArtifacts() { artifacts.clear(); deferredArtifacts.clear() }
}
