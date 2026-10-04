package com.idvb.android.alignment

import com.idvb.android.data.MapRepository

/** The composition root is the only place that enumerates concrete alignment algorithms. */
class AlignmentRegistry(methods: List<AlignmentMethod>) {
    private val registered = methods.associateBy { it.id }

    init {
        require(registered.size == methods.size) { "Duplicate alignment method ID" }
        require(methods.all { it.id.isNotBlank() })
    }

    fun resolve(id: String): AlignmentMethod? = registered[id]

    fun prepare(methodId: String, map: com.idvb.android.idvm.MapRecord,
        floor: com.idvb.android.idvm.FloorRecord, cancellation: AlignmentCancellation,
        log: AlignmentLogSink = AlignmentLogSink.NONE) = cancellation.run {
        resolve(methodId)?.prepare(map, floor, log)
    }

    fun align(methodId: String, request: AlignmentRequest,
        log: AlignmentLogSink = AlignmentLogSink.NONE): AlignmentResult {
        val started = System.nanoTime()
        // Wrapping protects plugins that invoke record directly rather than the emit helper.
        val safeLog = object : AlignmentLogSink {
            override val enabled get() = log.enabled
            override fun record(event: AlignmentLogEvent) = log.emit(event)
            override fun attach(name: String, bytes: () -> ByteArray) {
                try { log.attach(name, bytes) } catch (error: Exception) {
                    log.emit(AlignmentLogEvent("diagnostics.artifact-error", error.message.orEmpty()))
                }
            }
            override fun attachOwned(name: String, bytes: () -> ByteArray) {
                try { log.attachOwned(name, bytes) } catch (error: Exception) {
                    log.emit(AlignmentLogEvent("diagnostics.artifact-error", error.message.orEmpty()))
                }
            }
            override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) {
                try { log.attachDirect(name, capture) } catch (error: Exception) {
                    log.emit(AlignmentLogEvent("diagnostics.artifact-error", error.message.orEmpty()))
                }
            }
        }
        val scheduling = AlignmentScheduling.enter(safeLog)
        safeLog.emit(AlignmentLogEvent("start", "$methodId ${request.map.id}/${request.floor.key}"))
        try {
            val result = request.cancellation.run {
                resolve(methodId)?.align(request, safeLog)
                    ?: AlignmentResult.Unavailable("所选贴合方法不可用", "method-unavailable")
            }
            val detail = when (result) {
                is AlignmentResult.Aligned -> "aligned"
                is AlignmentResult.Unavailable -> result.code
                is AlignmentResult.Rejected -> result.code
            }
            scheduling.restore(safeLog)
            val ended = System.nanoTime()
            safeLog.emit(AlignmentLogEvent("finish", detail,
                mapOf("elapsedMs" to (ended - started) / 1_000_000.0),
                thresholds = mapOf("maximumComputationWallMs" to AlignmentPerformanceBudget.COMPUTATION_MS),
                gates = listOf(AlignmentGate("computation-wall-budget", (ended - started) / 1e6, "<=",
                    AlignmentPerformanceBudget.COMPUTATION_MS, ended - started <= 50_000_000L)),
                labels = mapOf("costScope" to "complete-alignment-call-including-synchronous-diagnostics; no-capture-or-render"),
                timestampNanos = ended, durationNanos = ended - started))
            return result
        } catch (error: Exception) {
            scheduling.restore(safeLog)
            val ended = System.nanoTime()
            if (request.cancellation.isCancelled) {
                safeLog.emit(AlignmentLogEvent("cancelled", request.cancellation.reason,
                    measurements = mapOf("acknowledgementMs" to (ended - request.cancellation.requestedAtNanos) / 1e6),
                    labels = mapOf("checkpoint" to ((error as? AlignmentCancelledException)?.stage ?: "worker-interrupted")),
                    timestampNanos = ended, durationNanos = ended - started))
                throw error
            }
            safeLog.emit(AlignmentLogEvent("error", error.message ?: error.javaClass.simpleName,
                timestampNanos = ended, durationNanos = ended - started))
            throw error
        } finally { scheduling.restore(safeLog) }
    }

    companion object {
        const val DEFAULT_METHOD_ID = "vpsg"
        fun createDefault(repository: MapRepository) = AlignmentRegistry(listOf(Vpsg(repository)))
    }
}
