package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import com.idvb.android.recognize.AccessibilityScreenCaptureService
import com.idvb.android.recognize.ProjectionCaptureFrame
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** Owns every rejected frame, bounded timer, pending capture and actual signature computation. */
class MapOpenFrameCapture(
    private val handler: Handler,
    private val executor: Executor,
    private val token: AlignmentCancellation,
    private val trace: AlignmentTrace,
    private val reference: MapFrameSignature?,
    private val callback: (Result<Bitmap>, MapFrameSignature?) -> Unit,
    private val captureFrame: ((Rect, (Result<Bitmap>) -> Unit) -> (() -> Unit))? = null,
    private val intervalMs: Long = MapOpenReadiness.INTERVAL_MS,
    private val maximumFrameAgeMs: Long = AutoMapOpenConfig().maximumFrameAgeMs,
    private val captureProjectionFrame: ((Rect, Long, (Result<ProjectionCaptureFrame>) -> Unit) -> (() -> Unit))? = null,
    private val initialProjectionSequence: Long = -1L,
    private val indicatorBounds: Rect? = null,
    private val onReadyIndicator: ((Bitmap) -> Unit)? = null,
) {
    private var stopped = false
    private var previous: MapFrameSignature? = null
    private var attempt = 0
    private var abortCapture: (() -> Unit)? = null
    private var started = 0L
    private var processing = false
    private var timeout = false
    private var lastConsumedSequence = initialProjectionSequence
    private val prepared = ArrayDeque<PreparedMapFrame>()
    private lateinit var bounds: Rect
    private val next = Runnable { capture() }
    private val deadline = Runnable {
        if (!stopped) {
            timeout = true
            token.cancel("map-open-timeout")
            abortCapture?.invoke()
            if (!processing) finish(Result.failure(IllegalStateException("等待地图画面超时（3 秒），请检查开图位置和校准区域")))
        }
    }

    /** Takes ownership of all prepared frames, including stale ones and cancellation paths. */
    fun start(region: Rect, initialFrames: List<PreparedMapFrame> = emptyList()): () -> Unit {
        bounds = Rect(region); started = SystemClock.elapsedRealtime()
        prepared.addAll(initialFrames)
        // Prepared samples precede any frames that arrived while hiding overlays or evaluating signatures.
        // Their identities, rather than the session's current arrival counter, define consumption.
        initialFrames.mapNotNull { it.frameSequence }.minOrNull()?.let { lastConsumedSequence = it - 1L }
        trace.emit(AlignmentLogEvent("readiness.configuration", "desktop-standard-map-gate-v1",
            thresholds = mapOf("timeoutMs" to MapOpenReadiness.TIMEOUT_MS.toDouble(), "intervalMs" to intervalMs.toDouble(),
                "color" to MapOpenReadiness.COLOR_THRESHOLD, "brightnessDelta" to MapOpenReadiness.BRIGHTNESS_LIMIT),
            labels = mapOf("sample" to "160x100 nearest-neighbor ARGB32 big-endian", "reference" to if (reference == null) "missing" else "accepted-alignment",
                "replay" to if (trace.captureArtifacts) "sample-inputs-recorded" else "not-replayable-input-recording-disabled",
                "projectionWatermark" to "last-consumed-frame", "initialProjectionSequence" to lastConsumedSequence.toString())))
        trace.emit(AlignmentLogEvent("readiness.input-policy", labels = mapOf(
            "floorBinding" to if (indicatorBounds == null) "disabled" else "same-accepted-ready-capture",
            "pngEncoding" to "lossless-rgba-row-stream-deflate-best-speed",
            "diagnosticCost" to "synchronous-private-spool; included-in-readiness-total")))
        reference?.let { trace.emit(AlignmentLogEvent("readiness.reference", measurements = mapOf("blueGray" to it.blueGrayFraction, "meanValue" to it.meanValue), series = mapOf("histogram" to it.histogram))) }
        handler.postDelayed(deadline, MapOpenReadiness.TIMEOUT_MS)
        capture()
        return {
            if (!stopped) {
                token.cancel("map-open-cancelled")
                trace.emit(AlignmentLogEvent("readiness.cancel-requested", "pending-capture-and-signature"))
                abortCapture?.invoke()
                if (!processing) finish(Result.failure(java.util.concurrent.CancellationException("开图等待已取消")))
            }
        }
    }

    private fun capture() {
        if (stopped) return
        if (token.isCancelled) { finish(Result.failure(java.util.concurrent.CancellationException("开图等待已取消"))); return }
        attempt++
        val number = attempt
        val began = System.nanoTime()
        fun captured(result: Result<Bitmap>, frameSequence: Long? = null, receivedNanos: Long? = null,
            indicator: Bitmap? = null) {
            handler.post {
                trace.emit(AlignmentLogEvent("readiness.capture", "attempt-$number", durationNanos = System.nanoTime() - began,
                    measurements = mapOf("lastConsumedSequence" to lastConsumedSequence.toDouble(),
                        "frameSequence" to (frameSequence?.toDouble() ?: -1.0),
                        "frameReceivedNanos" to (receivedNanos?.toDouble() ?: -1.0))))
                abortCapture = null
                val bitmap = result.getOrNull()
                if (stopped) { bitmap?.recycle(); indicator?.recycle(); return@post }
                if (token.isCancelled) {
                    bitmap?.recycle()
                    indicator?.recycle()
                    finish(Result.failure(if (timeout) IllegalStateException("等待地图画面超时（3 秒），请检查开图位置和校准区域")
                        else java.util.concurrent.CancellationException("开图等待已取消")))
                    return@post
                }
                if (bitmap == null) { indicator?.recycle(); finish(result); return@post }
                if (captureProjectionFrame != null && frameSequence != null) {
                    if (frameSequence <= lastConsumedSequence) {
                        bitmap.recycle()
                        indicator?.recycle()
                        trace.emit(AlignmentLogEvent("readiness.duplicate-frame", "already-consumed",
                            measurements = mapOf("frameSequence" to frameSequence.toDouble(), "lastConsumedSequence" to lastConsumedSequence.toDouble())))
                        handler.post(next)
                        return@post
                    }
                    lastConsumedSequence = frameSequence
                }
                processing = true
                val queued = System.nanoTime()
                try { executor.execute {
                    trace.emit(AlignmentLogEvent("readiness.worker-queue", "attempt-$number", durationNanos = System.nanoTime() - queued))
                    val assessed = runCatching {
                        token.run {
                            val pixels = trace.measure("readiness.sample") {
                                val scaled = Bitmap.createScaledBitmap(bitmap, MapOpenReadiness.WIDTH, MapOpenReadiness.HEIGHT, false)
                                try { IntArray(MapOpenReadiness.WIDTH * MapOpenReadiness.HEIGHT).also {
                                    scaled.getPixels(it, 0, MapOpenReadiness.WIDTH, 0, 0, MapOpenReadiness.WIDTH, MapOpenReadiness.HEIGHT)
                                } } finally { if (scaled !== bitmap) scaled.recycle() }
                            }
                            val signature = trace.measure("readiness.signature") { MapOpenReadiness.signature(pixels) { token.throwIfCancelled("readiness.signature.row") } }
                            val decision = MapOpenReadiness.evaluate(signature, reference, previous)
                            if (trace.captureArtifacts) {
                                try {
                                    trace.attachStream("readiness-$number.png") { output ->
                                        AlignmentFrameSpool.writePng(bitmap, output)
                                    }
                                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    trace.emit(AlignmentLogEvent("diagnostics.artifact-unavailable", error.message.orEmpty(),
                                        labels = mapOf("artifact" to "readiness-$number.png", "status" to "not-recorded")))
                                }
                            }
                            trace.attach("readiness-$number.argb") { ByteBuffer.allocate(pixels.size * 4).apply { pixels.forEach(::putInt) }.array() }
                            trace.emit(AlignmentLogEvent("readiness.frame", decision.mode,
                                measurements = mapOf("attempt" to number.toDouble(), "frameSequence" to (frameSequence?.toDouble() ?: -1.0),
                                    "frameReceivedNanos" to (receivedNanos?.toDouble() ?: -1.0), "score" to decision.score, "blueGray" to signature.blueGrayFraction,
                                    "meanValue" to signature.meanValue, "brightnessDelta" to (decision.brightnessDelta ?: -1.0)),
                                thresholds = mapOf("color" to MapOpenReadiness.COLOR_THRESHOLD, "brightnessDelta" to MapOpenReadiness.BRIGHTNESS_LIMIT),
                                labels = mapOf("decision" to if (decision.ready) "ready" else "wait", "reason" to when {
                                    decision.score < MapOpenReadiness.COLOR_THRESHOLD -> "map-color-not-ready"
                                    decision.brightnessDelta == null -> "previous-frame-required"
                                    !decision.ready -> "brightness-not-ready"
                                    else -> "ready-pending-structure-verification"
                                }), series = mapOf("histogram" to signature.histogram)))
                            signature to decision
                        }
                    }
                    handler.post assessedFrame@{
                        processing = false
                        if (stopped) { bitmap.recycle(); indicator?.recycle(); return@assessedFrame }
                        if (timeout || token.isCancelled || assessed.isFailure) {
                            bitmap.recycle()
                            indicator?.recycle()
                            finish(Result.failure(if (timeout) IllegalStateException("等待地图画面超时（3 秒），请检查开图位置和校准区域")
                                else assessed.exceptionOrNull() ?: java.util.concurrent.CancellationException("开图等待已取消")))
                        } else {
                            val (signature, decision) = assessed.getOrThrow()
                            previous = signature
                            if (decision.ready && prepared.isEmpty()) {
                                if (indicator != null) {
                                    trace.emit(AlignmentLogEvent("floor-indicator.frame-binding", "accepted-readiness-frame",
                                        measurements = mapOf("readinessAttempt" to number.toDouble(),
                                            "frameSequence" to (frameSequence?.toDouble() ?: -1.0)),
                                        labels = mapOf("policy" to "floor-and-map-cropped-from-same-ready-capture")))
                                    onReadyIndicator?.invoke(indicator) ?: indicator.recycle()
                                }
                                finish(Result.success(bitmap), signature)
                            }
                            else {
                                bitmap.recycle()
                                indicator?.recycle()
                                val remaining = MapOpenReadiness.TIMEOUT_MS - (SystemClock.elapsedRealtime() - started)
                                if (remaining <= 0) deadline.run() else handler.postDelayed(next,
                                    if (prepared.isNotEmpty()) 0L else minOf(intervalMs, remaining))
                            }
                        }
                    }
                } } catch (error: java.util.concurrent.RejectedExecutionException) {
                    processing = false; bitmap.recycle(); indicator?.recycle(); finish(Result.failure(error))
                }
            }
        }
        var reused: PreparedMapFrame? = null
        var reusedIndicator: Bitmap? = null
        while (prepared.isNotEmpty() && reused == null) {
            val candidate = prepared.removeFirst()
            val now = SystemClock.uptimeMillis()
            val pairedIndicator = indicatorBounds?.let { candidate.takeIndicatorFor(it, now, maximumFrameAgeMs) }
            val valid = candidate.isFreshFor(bounds, now, maximumFrameAgeMs) &&
                (indicatorBounds == null || pairedIndicator != null)
            trace.emit(AlignmentLogEvent("readiness.prepared-frame", if (valid) "reused-clean-detector-capture" else "discarded-stale-or-region-changed",
                measurements = mapOf("attempt" to number.toDouble(), "captureStartedAtMs" to candidate.captureStartedAtMs.toDouble(),
                    "captureReceivedAtMs" to candidate.captureReceivedAtMs.toDouble(), "ageMs" to (now - candidate.captureStartedAtMs).toDouble(),
                    "frameSequence" to (candidate.frameSequence?.toDouble() ?: -1.0),
                    "frameReceivedNanos" to (candidate.frameReceivedNanos?.toDouble() ?: -1.0)),
                thresholds = mapOf("maximumFrameAgeMs" to maximumFrameAgeMs.toDouble()),
                labels = mapOf("ownership" to "unoccluded-map-viewport", "ageOrigin" to "capture-request-start-including-queue")))
            if (valid) { reused = candidate; reusedIndicator = pairedIndicator } else {
                pairedIndicator?.recycle()
                candidate.frameSequence?.let { lastConsumedSequence = maxOf(lastConsumedSequence, it) }
                candidate.recycle()
            }
        }
        val captureBounds = Rect(bounds).apply { indicatorBounds?.let(::union) }
        fun combined(result: Result<Bitmap>, sequence: Long? = null, received: Long? = null) {
            val input = result.getOrNull()
            if (input == null || indicatorBounds == null) { captured(result, sequence, received); return }
            var map: Bitmap? = null
            var indicator: Bitmap? = null
            try {
                map = Bitmap.createBitmap(input, bounds.left - captureBounds.left, bounds.top - captureBounds.top,
                    bounds.width(), bounds.height())
                indicator = Bitmap.createBitmap(input, indicatorBounds.left - captureBounds.left,
                    indicatorBounds.top - captureBounds.top, indicatorBounds.width(), indicatorBounds.height())
                captured(Result.success(map), sequence, received, indicator)
            } catch (error: Exception) {
                map?.takeUnless { it === input }?.recycle(); indicator?.takeUnless { it === input }?.recycle()
                map = null; indicator = null
                captured(Result.failure(error), sequence, received)
            } finally { if (input !== map && input !== indicator) input.recycle() }
        }
        if (reused != null) {
            reused.discardIndicator()
            captured(Result.success(reused.bitmap), reused.frameSequence, reused.frameReceivedNanos, reusedIndicator)
        }
        else abortCapture = captureProjectionFrame?.invoke(captureBounds, lastConsumedSequence) { result ->
            val frame = result.getOrNull()
            combined(result.map { it.bitmap }, frame?.sequence, frame?.receivedNanos)
        } ?: captureFrame?.invoke(captureBounds) { combined(it) }
            ?: AccessibilityScreenCaptureService.capture(captureBounds, executor, { combined(it) }, trace)
    }

    private fun finish(result: Result<Bitmap>, signature: MapFrameSignature? = null) {
        if (stopped) { result.getOrNull()?.recycle(); return }
        stopped = true; handler.removeCallbacks(next); handler.removeCallbacks(deadline)
        while (prepared.isNotEmpty()) prepared.removeFirst().recycle()
        trace.emit(AlignmentLogEvent("readiness.total", if (result.isSuccess) "ready" else if (timeout) "timeout" else if (token.isCancelled) "cancelled" else "error",
            measurements = mapOf("attempts" to attempt.toDouble(), "cancelResponseMs" to if (token.isCancelled) (System.nanoTime() - token.requestedAtNanos) / 1e6 else 0.0),
            durationNanos = (SystemClock.elapsedRealtime() - started) * 1_000_000))
        callback(result, signature)
    }
}
