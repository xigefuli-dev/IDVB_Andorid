package com.idvb.android.alignment

import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Private-file evidence writes run here, never on the calculation or capture threads.
 * One FIFO thread keeps same-name replacements ordered; queued bytes are bounded so a slow
 * disk applies backpressure (recorded synchronously) instead of growing the heap. */
internal object AlignmentSpool {
    const val EXECUTOR_LABEL = "background-private-spool"
    /** Covers one alignment (about 15-25 MiB of masks and frames) plus a following request. */
    const val MAX_QUEUED_BYTES = 48L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "idvb-diagnostic-spool").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val writing = ThreadLocal<Boolean>()
    private var queuedBytes = 0L

    /** True on the spool thread, whose work must not be counted as nested calculation cost. */
    val onSpoolThread: Boolean get() = writing.get() == true

    @Synchronized fun queuedBytes() = queuedBytes

    @Synchronized fun tryReserve(bytes: Long): Boolean {
        if (queuedBytes > 0 && queuedBytes + bytes > MAX_QUEUED_BYTES) return false
        queuedBytes += bytes
        return true
    }

    @Synchronized private fun release(bytes: Long) { queuedBytes -= bytes }

    /** The caller has reserved [bytes]; they are released when the write finishes or fails. */
    fun submit(bytes: Long, write: () -> Unit): Future<*> = try {
        executor.submit {
            writing.set(true)
            try { write() } finally { writing.set(false); release(bytes) }
        }
    } catch (error: Throwable) { release(bytes); throw error }
}

/** Growable capture buffer whose bytes are handed to the spool thread without a second copy. */
internal class AlignmentCapturedBytes(initial: Int = 64 * 1024) : java.io.ByteArrayOutputStream(initial) {
    fun writeContentTo(output: java.io.OutputStream) = output.write(buf, 0, count)
}
