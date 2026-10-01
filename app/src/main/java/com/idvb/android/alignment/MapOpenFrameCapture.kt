package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import com.idvb.android.recognize.AccessibilityScreenCaptureService
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
) {
    private var stopped = false
    private var previous: MapFrameSignature? = null
    private var attempt = 0
    private var abortCapture: (() -> Unit)? = null
    private var started = 0L
    private var processing = false
    private var timeout = false
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

    fun start(region: Rect): () -> Unit {
        bounds = Rect(region); started = SystemClock.elapsedRealtime()
        trace.emit(AlignmentLogEvent("readiness.configuration", "desktop-standard-map-gate-v1",
            thresholds = mapOf("timeoutMs" to MapOpenReadiness.TIMEOUT_MS.toDouble(), "intervalMs" to MapOpenReadiness.INTERVAL_MS.toDouble(),
                "color" to MapOpenReadiness.COLOR_THRESHOLD, "brightnessDelta" to MapOpenReadiness.BRIGHTNESS_LIMIT),
            labels = mapOf("sample" to "160x100 nearest-neighbor ARGB32 big-endian", "reference" to if (reference == null) "missing" else "accepted-alignment",
                "replay" to if (trace.captureArtifacts) "sample-inputs-recorded" else "not-replayable-input-recording-disabled")))
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
        val captured: (Result<Bitmap>) -> Unit = { result ->
            handler.post {
                trace.emit(AlignmentLogEvent("readiness.capture", "attempt-$number", durationNanos = System.nanoTime() - began))
                abortCapture = null
                val bitmap = result.getOrNull()
                if (stopped) { bitmap?.recycle(); return@post }
                if (token.isCancelled) {
                    bitmap?.recycle()
                    finish(Result.failure(if (timeout) IllegalStateException("等待地图画面超时（3 秒），请检查开图位置和校准区域")
                        else java.util.concurrent.CancellationException("开图等待已取消")))
                    return@post
                }
                if (bitmap == null) { finish(result); return@post }
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
                                val width = bitmap.width; val height = bitmap.height
                                val original = trace.measure("diagnostics.copy-readiness-pixels") {
                                    IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
                                }
                                trace.attachDeferred("readiness-$number.png") {
                                    val owned = Bitmap.createBitmap(original, width, height, Bitmap.Config.ARGB_8888)
                                    try { java.io.ByteArrayOutputStream().use { output ->
                                        check(owned.compress(Bitmap.CompressFormat.PNG, 100, output))
                                        output.toByteArray()
                                    } } finally { owned.recycle() }
                                }
                            }
                            trace.attach("readiness-$number.argb") { ByteBuffer.allocate(pixels.size * 4).apply { pixels.forEach(::putInt) }.array() }
                            trace.emit(AlignmentLogEvent("readiness.frame", decision.mode,
                                measurements = mapOf("attempt" to number.toDouble(), "score" to decision.score, "blueGray" to signature.blueGrayFraction,
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
                        if (stopped) { bitmap.recycle(); return@assessedFrame }
                        if (timeout || token.isCancelled || assessed.isFailure) {
                            bitmap.recycle()
                            finish(Result.failure(if (timeout) IllegalStateException("等待地图画面超时（3 秒），请检查开图位置和校准区域")
                                else assessed.exceptionOrNull() ?: java.util.concurrent.CancellationException("开图等待已取消")))
                        } else {
                            val (signature, decision) = assessed.getOrThrow()
                            previous = signature
                            if (decision.ready) finish(Result.success(bitmap), signature)
                            else {
                                bitmap.recycle()
                                val remaining = MapOpenReadiness.TIMEOUT_MS - (SystemClock.elapsedRealtime() - started)
                                if (remaining <= 0) deadline.run() else handler.postDelayed(next, minOf(MapOpenReadiness.INTERVAL_MS, remaining))
                            }
                        }
                    }
                } } catch (error: java.util.concurrent.RejectedExecutionException) {
                    processing = false; bitmap.recycle(); finish(Result.failure(error))
                }
            }
        }
        abortCapture = captureFrame?.invoke(bounds, captured)
            ?: AccessibilityScreenCaptureService.capture(bounds, executor, captured, trace)
    }

    private fun finish(result: Result<Bitmap>, signature: MapFrameSignature? = null) {
        if (stopped) { result.getOrNull()?.recycle(); return }
        stopped = true; handler.removeCallbacks(next); handler.removeCallbacks(deadline)
        trace.emit(AlignmentLogEvent("readiness.total", if (result.isSuccess) "ready" else if (timeout) "timeout" else if (token.isCancelled) "cancelled" else "error",
            measurements = mapOf("attempts" to attempt.toDouble(), "cancelResponseMs" to if (token.isCancelled) (System.nanoTime() - token.requestedAtNanos) / 1e6 else 0.0),
            durationNanos = (SystemClock.elapsedRealtime() - started) * 1_000_000))
        callback(result, signature)
    }
}
