package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Samples the unoccluded game patch without hiding, moving or redrawing any overlay. */
class AutoMapOpenFrameSampler(
    private val handler: Handler,
    private val executor: Executor,
    private val captureMethod: String = "ACCESSIBILITY",
    private val compare: ((IntArray) -> AutoMapOpenComparison)? = null,
    private val frameSequence: () -> Long? = { null },
    private val frameReceivedNanos: () -> Long? = { null },
    private val captureFrame: (Rect, (Result<Bitmap>) -> Unit) -> (() -> Unit),
) {
    fun sample(bounds: Rect, isCurrent: () -> Boolean, isOccluded: () -> Boolean,
        callback: (Result<IntArray>) -> Unit): () -> Unit = sampleWithFrame(bounds, bounds, null,
            isCurrent, isOccluded, { true }) { result -> callback(result.map { it.pixels }) }

    /** A clean viewport is optional: occluded map pixels must never seed alignment. */
    fun sampleWithFrame(bounds: Rect, captureBounds: Rect, mapBounds: Rect?,
        isCurrent: () -> Boolean, isOccluded: () -> Boolean, isMapOccluded: () -> Boolean,
        log: AlignmentLogSink = AlignmentLogSink.NONE,
        indicatorBounds: Rect? = null, isIndicatorOccluded: () -> Boolean = { true },
        callback: (Result<AutoMapOpenSample>) -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        var abort: (() -> Unit)? = null
        val startedAtMs = SystemClock.uptimeMillis()
        fun finish(result: Result<AutoMapOpenSample>) {
            if (done.compareAndSet(false, true)) callback(result)
            else result.getOrNull()?.mapFrame?.recycle()
        }
        if (!isCurrent() || isOccluded()) {
            finish(Result.failure(IllegalStateException("auto-detection-occluded-or-paused")))
        } else {
            val mapWasClean = mapBounds != null && !isMapOccluded()
            val indicatorWasClean = indicatorBounds != null && !isIndicatorOccluded()
            abort = captureFrame(Rect(captureBounds)) { captured ->
                val receivedAtMs = SystemClock.uptimeMillis()
                handler.post {
                    val bitmap = captured.getOrNull()
                    if (done.get()) bitmap?.recycle()
                    else if (!isCurrent() || isOccluded()) {
                        bitmap?.recycle()
                        finish(Result.failure(IllegalStateException("auto-detection-occluded-or-paused")))
                    } else if (bitmap == null) finish(Result.failure(captured.exceptionOrNull() ?: IllegalStateException("capture-failed")))
                    else {
                        val queued = System.nanoTime()
                        val retainMap = mapWasClean && !isMapOccluded()
                        val retainIndicator = indicatorWasClean && !isIndicatorOccluded()
                        try { executor.execute {
                            log.emit(AlignmentLogEvent("sample.worker-queue", durationNanos = System.nanoTime() - queued))
                            var retained: PreparedMapFrame? = null
                            val sampled = runCatching {
                                require(bitmap.width == captureBounds.width() && bitmap.height == captureBounds.height()) { "auto-detection-size-changed" }
                                require(captureBounds.contains(bounds)) { "auto-detection-region-changed" }
                                val reducedAt = System.nanoTime()
                                val patch = Bitmap.createBitmap(bitmap, bounds.left - captureBounds.left,
                                    bounds.top - captureBounds.top, bounds.width(), bounds.height())
                                val pixels = try { AutoMapOpenReferenceStore.samplePixels(patch) }
                                    finally { if (patch !== bitmap) patch.recycle() }
                                log.emit(AlignmentLogEvent("sample.roi", durationNanos = System.nanoTime() - reducedAt))
                                val decidedAt = System.nanoTime()
                                val comparison = compare?.invoke(pixels)
                                val decisionMs = (System.nanoTime() - decidedAt) / 1e6
                                log.emit(AlignmentLogEvent("sample.spatial-comparison", durationNanos = (decisionMs * 1e6).toLong(),
                                    labels = mapOf("backend" to AutoMapOpenNativeKernel.backend,
                                        "search" to "unchanged-all-windows-all-nine-probes", "score" to "unchanged-double-precision")))
                                if (retainMap && (comparison == null || comparison.score >= .85) && captureBounds.contains(requireNotNull(mapBounds))) {
                                    val copiedAt = System.nanoTime()
                                    val cropped = Bitmap.createBitmap(bitmap, mapBounds.left - captureBounds.left,
                                        mapBounds.top - captureBounds.top, mapBounds.width(), mapBounds.height())
                                    val owned = if (cropped === bitmap) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else cropped
                                    val indicator = indicatorBounds?.takeIf { retainIndicator && captureBounds.contains(it) }?.let { area ->
                                        val crop = Bitmap.createBitmap(bitmap, area.left - captureBounds.left,
                                            area.top - captureBounds.top, area.width(), area.height())
                                        if (crop === bitmap) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else crop
                                    }
                                    retained = PreparedMapFrame(owned, Rect(mapBounds), startedAtMs, receivedAtMs,
                                        captureMethod, frameSequence(), indicator, indicatorBounds?.let(::Rect), frameReceivedNanos())
                                    log.emit(AlignmentLogEvent("sample.floor-indicator", if (indicator == null) "not-retained" else "clean-same-capture",
                                        measurements = indicatorBounds?.let { mapOf("left" to it.left.toDouble(), "top" to it.top.toDouble(),
                                            "width" to it.width().toDouble(), "height" to it.height().toDouble()) }.orEmpty()))
                                    log.emit(AlignmentLogEvent("sample.clean-viewport", durationNanos = System.nanoTime() - copiedAt,
                                        measurements = mapOf("captureStartedAtMs" to startedAtMs.toDouble(), "captureReceivedAtMs" to receivedAtMs.toDouble(),
                                            "left" to mapBounds.left.toDouble(), "top" to mapBounds.top.toDouble(),
                                            "width" to mapBounds.width().toDouble(), "height" to mapBounds.height().toDouble())))
                                }
                                AutoMapOpenSample(pixels, retained, comparison, decisionMs)
                            }
                            if (sampled.isFailure) retained?.recycle()
                            bitmap.recycle()
                            handler.post {
                                if (!isCurrent() || isOccluded()) {
                                    sampled.getOrNull()?.mapFrame?.recycle()
                                    finish(Result.failure(IllegalStateException("auto-detection-occluded-or-paused")))
                                } else if (isMapOccluded()) {
                                    sampled.getOrNull()?.mapFrame?.recycle()
                                    finish(sampled.map { it.copy(mapFrame = null) })
                                } else {
                                    if (isIndicatorOccluded()) sampled.getOrNull()?.mapFrame?.discardIndicator()
                                    finish(sampled)
                                }
                            }
                        } } catch (error: java.util.concurrent.RejectedExecutionException) {
                            bitmap.recycle(); finish(Result.failure(error))
                        }
                    }
                }
            }
        }
        return {
            finish(Result.failure(java.util.concurrent.CancellationException("auto-detection-cancelled")))
            abort?.invoke()
        }
    }
}
