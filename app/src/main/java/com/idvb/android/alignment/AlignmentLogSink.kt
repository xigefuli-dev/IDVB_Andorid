package com.idvb.android.alignment

/** Algorithm-neutral events; adapters may write Logcat, JSON, diagnostics packages or test traces.
 * Frames are deliberately caller-owned and are not implicitly retained by logging.
 */
data class AlignmentLogEvent(
    val stage: String,
    val detail: String = "",
    val measurements: Map<String, Double> = emptyMap(),
    val thresholds: Map<String, Double> = emptyMap(),
    val gates: List<AlignmentGate> = emptyList(),
    val series: Map<String, List<Double>> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val timestampNanos: Long = System.nanoTime(),
    val durationNanos: Long? = null,
)

data class AlignmentGate(val name: String, val actual: Double, val comparison: String,
    val threshold: Double, val passed: Boolean)

fun interface AlignmentLogSink {
    fun record(event: AlignmentLogEvent)
    val enabled: Boolean get() = true
    /** Lazy so a sink that does not retain images never reads or encodes them. */
    fun attach(name: String, bytes: () -> ByteArray) = Unit
    /** Only for suppliers owning immutable managed data, never a live Mat or Bitmap. */
    fun attachOwned(name: String, bytes: () -> ByteArray) = attach(name, bytes)
    /** Capture an owned immutable buffer during this call, while a borrowed input is alive.
     * Consumers may stream the owned copy later; never defer capture of a Mat address. */
    fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) = attach(name) {
        val buffer = capture()
        ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }

    companion object {
        val NONE = object : AlignmentLogSink {
            override val enabled = false
            override fun record(event: AlignmentLogEvent) = Unit
        }
    }
}

/** A broken diagnostics adapter must never change an algorithm's acceptance decision. */
internal fun AlignmentLogSink.emit(event: AlignmentLogEvent) {
    if (!enabled) return
    try { record(event) } catch (_: Exception) { }
}

internal inline fun <T> AlignmentLogSink.measure(stage: String, block: () -> T): T {
    AlignmentCancellation.checkpoint(stage)
    if (!enabled) return block()
    val started = System.nanoTime()
    var error: String? = null
    try { return block() }
    catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName; throw failure }
    finally {
        val ended = System.nanoTime()
        emit(AlignmentLogEvent(stage, error ?: "completed", timestampNanos = ended,
            durationNanos = ended - started))
    }
}
