package com.idvb.android.alignment

/** Only alignment work is excluded. Capture, readiness, queues, diagnostics,
 * display preparation and drawing remain part of the overhead budget. */
data class AlignmentLatency(val totalMs: Double, val alignmentMs: Double, val overheadMs: Double) {
    val withinBudget: Boolean get() = overheadMs <= AutoMapOpenTiming.NON_ALIGNMENT_BUDGET_MS

    companion object {
        fun measure(startedNanos: Long, completedNanos: Long, alignmentNanos: Long): AlignmentLatency {
            val total = (completedNanos - startedNanos).coerceAtLeast(0L)
            val alignment = alignmentNanos.coerceIn(0L, total)
            return AlignmentLatency(total / 1e6, alignment / 1e6, (total - alignment) / 1e6)
        }
    }
}
