package com.idvb.android.recognize

import android.util.Log
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.side.SparseGateRecognizer
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Active-class preparation only. Old requests stop between floors, and their
 * completion cannot mark a newer catalog/class ready. Memory remains LRU-bounded. */
internal class RecognitionPreparation(
    private val repository: MapRepository,
    private val selectedClassId: () -> String?,
) : Closeable {
    enum class Phase { IDLE, PREPARING, READY, PARTIAL, FAILED }
    data class Status(val phase: Phase, val classId: String? = null, val ready: Int = 0,
        val total: Int = 0, val elapsedMs: Double = 0.0)

    private val generation = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task,"idvb-index-preparation").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile var status = Status(Phase.IDLE)
        private set

    @Synchronized fun request() {
        if (closed || paused) return
        val revision = generation.incrementAndGet()
        status = Status(Phase.PREPARING)
        worker.execute {
            fun current() = !closed && !paused && generation.get() == revision
            if (!current()) return@execute
            val started = System.nanoTime()
            try {
                val catalog = repository.loadCatalog()
                val classId = selectedClassId()?.takeIf { id -> catalog.classes.any { it.id == id } }
                    ?: catalog.classes.firstOrNull()?.id
                val maps = catalog.maps.filter { it.classId == classId }
                val ready = SparseGateRecognizer(repository).prepare(maps,::current)
                synchronized(this) {
                    if (current()) {
                        status = Status(if (ready == maps.size) Phase.READY else Phase.PARTIAL,
                            classId,ready,maps.size,(System.nanoTime()-started)/1e6)
                        Log.i("IDVB-Prepare","class=$classId ready=$ready/${maps.size} elapsedMs=${status.elapsedMs}")
                    }
                }
            } catch (failure: Exception) {
                synchronized(this) { if (current()) status = Status(Phase.FAILED,elapsedMs=(System.nanoTime()-started)/1e6) }
                Log.w("IDVB-Prepare","Preparation failed",failure)
            }
        }
    }

    /** Worker/test barrier; never call from the UI thread. */
    fun awaitIdle(): Status {
        worker.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
        return status
    }

    @Synchronized fun pauseForResourceRelease() {
        paused = true
        generation.incrementAndGet()
        status = Status(Phase.IDLE)
    }

    /** Re-enable future preparation; do not immediately refill explicitly released caches. */
    @Synchronized fun resume() { paused = false }

    @Synchronized override fun close() {
        closed = true
        generation.incrementAndGet()
        worker.shutdown()
    }
}
