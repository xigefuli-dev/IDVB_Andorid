package com.idvb.android.alignment

import java.util.concurrent.CancellationException

/** One request owns one token. Cancellation interrupts its worker and every bounded algorithm
 * loop checks it; native OpenCV calls finish before the next checkpoint releases their Mats.
 * Never use Thread.stop: it can strand native allocations and corrupt the shared executor.
 */
class AlignmentCancellation {
    @field:androidx.annotation.Keep
    private var nativeStopMemory: java.nio.ByteBuffer? = null
    private var nativeStop = 0L
    @androidx.annotation.Keep
    @Synchronized fun nativeStopAddress(): Long {
        if (nativeStop == 0L) {
            val memory = java.nio.ByteBuffer.allocateDirect(64)
            nativeStop = com.idvb.android.recognize.vpsg.VpsgNativeKernel.initializeStopFlag(memory, isCancelled)
            nativeStopMemory = memory
        }
        return nativeStop
    }
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
        if (nativeStop != 0L) com.idvb.android.recognize.vpsg.VpsgNativeKernel.signalStopFlag(nativeStop)
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
        internal fun current(): AlignmentCancellation? = active.get()
        /** Also available to future algorithms; outside an alignment request this is a no-op. */
        fun checkpoint(stage: String) { active.get()?.throwIfCancelled(stage) }
    }
}

class AlignmentCancelledException(val stage: String, reason: String, val acknowledgementNanos: Long) :
    CancellationException(reason)
