package com.idvb.android.alignment

/**
 * 脉冲式自动开图检测时钟控制器。
 * 避开固定的 350ms 缓慢轮询，在用户点击特定象限后的窗口期内进行适度高频的识别尝试。
 */
object AutoMapOpenTiming {
    /** 脉冲识别期间的尝试间隔（毫秒）。避免过慢的 350ms，同时保持适中性能开销。 */
    const val INTERVAL_MS = 180L
    const val BURST_INTERVAL_MS = 180L

    /** 用户点击特定象限后的高频识别窗口持续时间（毫秒）。 */
    const val BURST_DURATION_MS = 2_000L

    fun delayAfterSample(startedAtMs: Long, nowMs: Long, intervalMs: Long = BURST_INTERVAL_MS): Long {
        if (nowMs <= startedAtMs) return intervalMs
        val elapsed = nowMs - startedAtMs
        return if (elapsed >= intervalMs || elapsed < 0L) 0L else intervalMs - elapsed
    }
}
