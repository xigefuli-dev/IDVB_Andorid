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
    private val artifacts = linkedMapOf<String, ByteArray>()
    private val deferredArtifacts = linkedMapOf<String, () -> ByteArray>()
    private var recordingNanos = 0L
    val completed = AtomicBoolean(false)

    @Synchronized override fun record(event: AlignmentLogEvent) {
        val started = System.nanoTime()
        val associated = event.copy(labels = event.labels + ("requestId" to id))
        events += associated
        downstream.emit(associated)
        recordingNanos += System.nanoTime() - started
    }

    override fun attach(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        val content = measure("diagnostics.read-artifact") { bytes() }
        synchronized(this) { artifacts[name] = content }
    }

    /** The supplier must own immutable input, never a borrowed/recyclable capture. Encoding
     * runs when the diagnostics writer snapshots artifacts, after the interaction completes. */
    @Synchronized fun attachDeferred(name: String, bytes: () -> ByteArray) {
        if (!captureArtifacts) return
        require(Regex("[a-zA-Z0-9_.-]+").matches(name))
        deferredArtifacts[name] = bytes
    }

    override fun attachOwned(name: String, bytes: () -> ByteArray) = attachDeferred(name, bytes)

    @Synchronized fun snapshot(): List<AlignmentLogEvent> = events.toList() + AlignmentLogEvent(
        "diagnostics.trace-recording", "Cumulative adapter and trace append cost, already included in enclosing spans; event construction excluded",
        measurements = mapOf("recordingMs" to recordingNanos / 1e6, "eventCount" to events.size.toDouble()))
    @Synchronized fun artifactSnapshot(): Map<String, ByteArray> {
        for ((name, encode) in deferredArtifacts) {
            artifacts[name] = measure("diagnostics.encode-deferred-artifact") { encode() }
        }
        deferredArtifacts.clear()
        return artifacts.toMap()
    }
}
