package com.idvb.android.recognize

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AutoMapOpenTiming
import com.idvb.android.alignment.emit

/** All projection, reader and retained-frame operations share this session's monitor. */
class ScreenCaptureSession(private val context: Context) {
    val grantRevision = ScreenCaptureGrant.revision
    private val thread = HandlerThread("idvb-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var width = 0
    private var height = 0
    private var closed = false
    private var failure: Throwable? = null
    private val pending = linkedSetOf<Request>()
    private var sequence = 0L
    private var latestReceivedNanos = 0L
    private var deliveredAutoSequence = -1L
    private var packedPixels: java.nio.ByteBuffer? = null
    @Volatile var onFrameAvailable: (() -> Unit)? = null
    @Volatile var onStopped: (() -> Unit)? = null

    private inner class Request(val region: Rect, val minimumSequence: Long, val auto: Boolean,
        val log: AlignmentLogSink, val callback: (Result<Bitmap>) -> Unit) : Runnable {
        val started = System.nanoTime()
        val deadline = SystemClock.uptimeMillis() + 3_000L
        val timeout = Runnable { synchronized(this@ScreenCaptureSession) {
            if (this in pending) finish(Result.failure(IllegalStateException("等待屏幕画面超时（屏幕捕获未收到新帧）")))
        } }
        override fun run() = synchronized(this@ScreenCaptureSession) {
            if (this !in pending) return@synchronized
            val error = failure
            when {
                error != null -> finish(Result.failure(error))
                closed || reader == null -> finish(Result.failure(IllegalStateException("屏幕捕获已停止，请重新授权")))
                latest != null && sequence > minimumSequence && (!auto || ProjectionFrameFreshness.usable(
                    sequence, minimumSequence, System.nanoTime() - latestReceivedNanos, AutoMapOpenTiming.MAXIMUM_CAPTURE_AGE_MS)) -> {
                    val copiedAt = System.nanoTime()
                    val result = runCatching { copyRegion(latest!!, region) }
                    if (auto && result.isSuccess) deliveredAutoSequence = sequence
                    log.emit(AlignmentLogEvent("capture.projection-frame", durationNanos = System.nanoTime() - started,
                        measurements = mapOf("frameSequence" to sequence.toDouble(), "frameTimestampNanos" to latest!!.timestamp.toDouble(),
                            "frameReceivedNanos" to latestReceivedNanos.toDouble(), "frameAgeMs" to (copiedAt - latestReceivedNanos) / 1e6,
                            "waitMs" to (copiedAt - started) / 1e6, "copyMs" to (System.nanoTime() - copiedAt) / 1e6),
                        labels = mapOf("captureMethod" to "MEDIA_PROJECTION", "distinctFrame" to (minimumSequence >= 0).toString())))
                    finish(result)
                }
                SystemClock.uptimeMillis() >= deadline -> finish(Result.failure(IllegalStateException("等待屏幕画面超时（屏幕捕获未收到新帧）")))
                else -> Unit // Image arrival wakes pending requests; no polling sleep.
            }
        }
        fun finish(result: Result<Bitmap>) {
            if (!pending.remove(this)) { result.getOrNull()?.recycle(); return }
            handler.removeCallbacks(this)
            handler.removeCallbacks(timeout)
            result.exceptionOrNull()?.let { Log.e("IDVBCapture", "MediaProjection capture failed", it) }
            callback(result)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = synchronized(this@ScreenCaptureSession) {
            stop(IllegalStateException("屏幕捕获授权已失效，请重新授权"))
        }
        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) = synchronized(this@ScreenCaptureSession) {
            if (!closed && newWidth > 0 && newHeight > 0) {
                runCatching { resize(newWidth, newHeight) }.onFailure { stop(it) }
            }
        }
    }

    @Synchronized
    fun start(screenWidth: Int, screenHeight: Int): Boolean {
        if (closed || failure != null) return false
        return runCatching {
            if (projection == null) {
                val data = ScreenCaptureGrant.consume(grantRevision) ?: return false
                projection = (context.getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(Activity.RESULT_OK, data) ?: error("屏幕捕获授权无效"))
                    .also { it.registerCallback(projectionCallback, handler) }
                reader = newReader(screenWidth, screenHeight)
                width = screenWidth; height = screenHeight
                display = projection!!.createVirtualDisplay(
                    "IDVB capture", width, height, context.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler,
                ) ?: error("无法创建屏幕捕获显示器")
            } else resize(screenWidth, screenHeight)
            true
        }.getOrElse { stop(it); false }
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 3).also {
            it.setOnImageAvailableListener({ source ->
                synchronized(this) {
                    if (closed || source !== reader) return@synchronized
                    runCatching {
                        // Retain one frame, leaving two slots for acquireLatestImage to discard older frames.
                        source.acquireLatestImage()?.let { image ->
                            latest?.close(); latest = image
                            sequence++; latestReceivedNanos = System.nanoTime()
                            pending.toList().forEach { handler.post(it) }
                            onFrameAvailable?.invoke()
                        }
                    }.onFailure { stop(it) }
                }
            }, handler)
        }

    /** Clear BEFORE hiding overlays, so the final static frame produced by hiding them is retained. */
    @Synchronized
    fun prepareCapture() {
        latest?.close(); latest = null
        runCatching { reader?.acquireLatestImage()?.close() }.onFailure { stop(it) }
    }

    @Synchronized
    fun capture(region: Rect, callback: (Result<Bitmap>) -> Unit): () -> Unit =
        request(region, -1L, false, AlignmentLogSink.NONE, callback)

    /** Each detector sample owns a distinct frame; readiness awaits a new frame after its seed. */
    @Synchronized
    fun captureAuto(region: Rect, log: AlignmentLogSink, callback: (Result<Bitmap>) -> Unit): () -> Unit =
        request(region, deliveredAutoSequence, true, log, callback)

    /** Cache completion/retry may resume an already observed static opening. Record the
     * original frame time/sequence; this must never be presented as a newly arrived image. */
    @Synchronized
    fun captureRetained(region: Rect, log: AlignmentLogSink, callback: (Result<Bitmap>) -> Unit): () -> Unit =
        request(region, -1L, false, log, callback)

    @Synchronized
    fun hasFreshAutoFrame(): Boolean = latest != null && ProjectionFrameFreshness.usable(
        sequence, deliveredAutoSequence, System.nanoTime() - latestReceivedNanos, AutoMapOpenTiming.MAXIMUM_CAPTURE_AGE_MS)

    @Synchronized
    fun captureNext(region: Rect, log: AlignmentLogSink, callback: (Result<Bitmap>) -> Unit): () -> Unit =
        request(region, sequence, false, log, callback)

    private fun request(region: Rect, minimumSequence: Long, auto: Boolean, log: AlignmentLogSink,
        callback: (Result<Bitmap>) -> Unit): () -> Unit {
        if (closed || failure != null || reader == null) {
            callback(Result.failure(failure ?: IllegalStateException("屏幕捕获会话未启动")))
            return {}
        }
        val request = Request(Rect(region), minimumSequence, auto, log, callback)
        pending.add(request)
        handler.post(request)
        handler.postDelayed(request.timeout, 3_000L)
        return { synchronized(this) {
            request.finish(Result.failure(java.util.concurrent.CancellationException("截图请求已取消")))
        } }
    }

    private fun copyRegion(image: Image, region: Rect): Bitmap {
        val safe = Rect(region)
        require(safe.intersect(0, 0, image.width, image.height)) { "截图区域超出屏幕" }
        val plane = image.planes[0]
        require(plane.pixelStride == 4) { "不支持的屏幕捕获像素格式" }
        val packed = ScreenCapturePixels.copy(plane.buffer, plane.rowStride, safe.left, safe.top,
            safe.width(), safe.height(), packedPixels).also { packedPixels = it }
        val cropped = Bitmap.createBitmap(safe.width(), safe.height(), Bitmap.Config.ARGB_8888)
        try {
            cropped.copyPixelsFromBuffer(packed)
            return cropped
        } catch (error: Throwable) { cropped.recycle(); throw error }
    }

    @Synchronized fun cancelPending() {
        pending.toList().forEach { it.finish(Result.failure(java.util.concurrent.CancellationException("截图请求已取消"))) }
    }

    private fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        val current = display ?: return
        pending.toList().forEach { it.finish(Result.failure(IllegalStateException("屏幕尺寸已变化，请重新截图"))) }
        val next = newReader(w, h)
        try {
            current.resize(w, h, context.resources.displayMetrics.densityDpi)
            current.surface = next.surface
        } catch (error: Throwable) { next.close(); throw error }
        latest?.close(); latest = null
        reader?.close(); reader = next
        width = w; height = h
    }

    private fun stop(error: Throwable) {
        failure = error
        ScreenCaptureGrant.invalidate(grantRevision)
        pending.toList().forEach { it.finish(Result.failure(error)) }
        latest?.close(); latest = null
        display?.release(); display = null
        reader?.close(); reader = null
        packedPixels = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        onStopped?.invoke()
        Log.w("IDVBCapture", "MediaProjection session stopped: ${error.message}")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        onFrameAvailable = null; onStopped = null
        stop(IllegalStateException("屏幕捕获会话已关闭"))
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
