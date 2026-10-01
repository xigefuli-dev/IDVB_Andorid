package com.idvb.android.alignment

import java.util.concurrent.CancellationException

/** One request owns one token. Cancellation interrupts its worker and every bounded algorithm
 * loop checks it; native OpenCV calls finish before the next checkpoint releases their Mats.
 * Never use Thread.stop: it can strand native allocations and corrupt the shared executor.
 */
class AlignmentCancellation {
    @Volatile var requestedAtNanos: Long = 0L
        private set
    @Volatile var reason: String = ""
        private set
    private var worker: Thread? = null
    val isCancelled: Boolean get() = requestedAtNanos != 0L

    @Synchronized fun cancel(reason: String = "state-changed") {
        if (isCancelled) return
        this.reason = reason
        requestedAtNanos = System.nanoTime()
        worker?.interrupt()
    }

    fun throwIfCancelled(stage: String) {
        if (isCancelled) throw AlignmentCancelledException(stage, reason, System.nanoTime() - requestedAtNanos)
    }

    internal fun <T> run(block: () -> T): T {
        synchronized(this) {
            throwIfCancelled("worker.start")
            check(worker == null) { "Alignment request is already running" }
            worker = Thread.currentThread()
        }
        val previous = active.get()
        active.set(this)
        try { return block().also { throwIfCancelled("worker.finish") } }
        finally {
            synchronized(this) {
                worker = null
                // Do not let this cancelled request interrupt the next job on the serial worker.
                if (isCancelled) Thread.interrupted()
            }
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    companion object {
        private val active = ThreadLocal<AlignmentCancellation>()
        /** Also available to future algorithms; outside an alignment request this is a no-op. */
        fun checkpoint(stage: String) { active.get()?.throwIfCancelled(stage) }
    }
}

class AlignmentCancelledException(val stage: String, reason: String, val acknowledgementNanos: Long) :
    CancellationException(reason)
