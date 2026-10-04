package com.idvb.android.alignment

/** Owns one queued poll; a temporary prerequisite failure must not strand the detector. */
class AutoMapOpenPollLoop(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val canSchedule: () -> Boolean,
    private val canRecover: () -> Boolean,
    private val sampleInFlight: () -> Boolean,
    private val poll: () -> Unit,
    private val recoveryIntervalMs: Long = 350L,
) {
    var pending: Boolean = false
        private set
    private val tick = Runnable { runTick() }

    init { require(recoveryIntervalMs > 0L) }

    fun schedule(delayMs: Long = recoveryIntervalMs) {
        require(delayMs >= 0L)
        stop()
        if (canSchedule()) {
            pending = true
            post(tick, delayMs)
        }
    }

    fun stop() {
        remove(tick)
        pending = false
    }

    private fun runTick() {
        pending = false
        try {
            poll()
        } finally {
            // Asynchronous capture schedules its successor at completion. Preserve that
            // ownership and any explicit delay chosen by the sample callback.
            if (!pending && !sampleInFlight() && canRecover()) schedule()
        }
    }
}
