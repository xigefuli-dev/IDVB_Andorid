package com.idvb.android.alignment

/** Match the shared accessibility capture reservation without adding a post-capture pause. */
object AutoMapOpenTiming {
    const val INTERVAL_MS = 350L

    fun delayAfterSample(startedAtMs: Long, nowMs: Long): Long {
        if (nowMs <= startedAtMs) return INTERVAL_MS
        val elapsed = nowMs - startedAtMs
        return if (elapsed >= INTERVAL_MS || elapsed < 0L) 0L else INTERVAL_MS - elapsed
    }
}
