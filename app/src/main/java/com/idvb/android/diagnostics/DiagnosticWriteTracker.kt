package com.idvb.android.diagnostics

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Register before enqueueing. An export waits only for work accepted at its cutoff. */
object DiagnosticWriteTracker {
    data class Pending(val id: String, val kind: String, val acceptedAtMillis: Long)
    data class Barrier(val accepted: List<Pending>, val unfinished: List<Pending>, val waitedMs: Double)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pending = mutableMapOf<String, LinkedHashMap<String, Pending>>()

    fun begin(root: File, kind: String, id: String = UUID.randomUUID().toString()): AutoCloseable {
        val key = root.canonicalPath
        val token = UUID.randomUUID().toString()
        lock.withLock { pending.getOrPut(key) { linkedMapOf() }[token] = Pending(id, kind, System.currentTimeMillis()) }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) lock.withLock {
                pending[key]?.let { jobs -> jobs.remove(token); if (jobs.isEmpty()) pending.remove(key) }
                changed.signalAll()
            }
        }
    }

    fun awaitAccepted(root: File, timeoutMs: Long = 30_000): Barrier {
        require(timeoutMs >= 0)
        val key = root.canonicalPath
        val started = System.nanoTime()
        return lock.withLock {
            val accepted = pending[key]?.toMap().orEmpty()
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (accepted.keys.any { pending[key]?.containsKey(it) == true } && left > 0)
                left = changed.awaitNanos(left)
            Barrier(accepted.values.toList(), accepted.filterKeys { pending[key]?.containsKey(it) == true }.values.toList(),
                (System.nanoTime() - started) / 1e6)
        }
    }
}
