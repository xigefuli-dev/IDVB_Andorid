package com.idvb.android.alignment

/** Frame-driven detection has no touch window or open/close polling backoff. */
object AutoMapOpenTiming {
    const val INTERVAL_MS = 16L
    const val NON_ALIGNMENT_BUDGET_MS = 120L
    const val MAXIMUM_CAPTURE_AGE_MS = 80L

    fun delayAfterSample(startedAtMs: Long, nowMs: Long, intervalMs: Long = INTERVAL_MS): Long {
        if (nowMs <= startedAtMs) return intervalMs
        val elapsed = nowMs - startedAtMs
        return if (elapsed >= intervalMs || elapsed < 0L) 0L else intervalMs - elapsed
    }
}
