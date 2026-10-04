package com.idvb.android.alignment

/** Alignment is a foreground interaction even though it shares the background scan executor.
 * Restore the worker's scheduling policy before its next task; record unsupported boosts. */
internal class AlignmentScheduling private constructor(private val previous: Int?) {
    private var restored = false
    fun restore(log: AlignmentLogSink) {
        if (restored) return
        restored = true
        previous?.let {
            val started = System.nanoTime()
            val restored = runCatching { android.os.Process.setThreadPriority(it) }
            val actual = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
            log.emit(AlignmentLogEvent("worker.scheduling.restore", durationNanos = System.nanoTime() - started,
                measurements = buildMap { put("previousPriority", it.toDouble()); actual?.let { value -> put("actualPriority", value.toDouble()) } },
                labels = mapOf("status" to if (restored.isSuccess && actual == it) "restored" else "failed",
                    "failure" to restored.exceptionOrNull()?.toString().orEmpty())))
        }
    }

    companion object {
        fun enter(log: AlignmentLogSink): AlignmentScheduling {
            val previous = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
            val desired = android.os.Process.THREAD_PRIORITY_DISPLAY
            val change = previous != null && previous > desired
            val changed = if (change) runCatching { android.os.Process.setThreadPriority(desired) } else null
            val applied = changed?.isSuccess ?: true
            val actual = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
            log.emit(AlignmentLogEvent("worker.scheduling", measurements = buildMap {
                previous?.let { put("previousPriority", it.toDouble()) }
                actual?.let { put("actualPriority", it.toDouble()) }
            }, thresholds = mapOf("foregroundPriority" to desired.toDouble()),
                labels = mapOf("policy" to "display-result-calculation; restore-before-next-task", "failure" to changed?.exceptionOrNull()?.toString().orEmpty(), "status" to
                    if (previous == null || actual == null) "unavailable" else if (!applied) "boost-unavailable" else "applied")))
            return AlignmentScheduling(previous.takeIf { change && applied })
        }
    }
}
