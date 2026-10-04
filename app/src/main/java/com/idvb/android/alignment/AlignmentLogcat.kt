package com.idvb.android.alignment

/** The complete structured trace is appended synchronously and retained in the diagnostic ZIP.
 * A compact text receipt runs on the diagnostics writer after the interaction.
 * Never use this as a per-event downstream sink on the calculation or UI thread. */
internal object AlignmentLogcat {
    fun write(trace: AlignmentTrace, methodId: String, mapId: String, floorKey: String,
        tag: String = "IDVB-Align") {
        val events = trace.snapshot()
        val started = System.nanoTime()
        var written = 0
        var failure: Exception? = null
        try {
            val calculation = events.lastOrNull { it.stage == "finish" || it.stage == "cancelled" || it.stage == "error" }
            val terminal = events.lastOrNull { it.stage == "request.total" } ?: calculation
            val writeResult = android.util.Log.i(tag, "request=${trace.id} method=$methodId map=$mapId floor=$floorKey" +
                " outcome=${terminal?.detail} computationMs=${calculation?.durationNanos?.div(1e6)}" +
                " terminalMs=${terminal?.durationNanos?.div(1e6)} eventCount=${events.size}" +
                " eventTimestampNanos=${terminal?.timestampNanos} details=complete-structured-diagnostic-events")
            check(writeResult >= 0) { "Logcat receipt write failed: $writeResult" }
            written = 1
        } catch (error: Exception) { failure = error }
        finally {
            val ended = System.nanoTime()
            trace.emit(AlignmentLogEvent("diagnostics.logcat-write", failure?.message ?: "completed",
                measurements = mapOf("eventCount" to events.size.toDouble(), "summaryLinesWritten" to written.toDouble()),
                labels = mapOf("costScope" to "deferred-text-format-and-logcat-ipc; excluded-from-computation-and-first-draw",
                    "detailLocation" to "complete-structured-diagnostic-events; parameters-series-gates-and-rejections-retained",
                    "eventTimestamp" to "original-monotonic-event-time", "status" to if (failure == null) "complete" else "failed"),
                timestampNanos = ended, durationNanos = ended - started))
        }
    }
}
