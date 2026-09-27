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
    private var pending: Request? = null

    private inner class Request(val region: Rect, val callback: (Result<Bitmap>) -> Unit) : Runnable {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        override fun run() = synchronized(this@ScreenCaptureSession) {
            if (pending !== this) return@synchronized
            val error = failure
            when {
                error != null -> finish(Result.failure(error))
                closed || reader == null -> finish(Result.failure(IllegalStateException("屏幕捕获已停止，请重新授权")))
                latest != null -> finish(runCatching { copyRegion(latest!!, region) })
                SystemClock.uptimeMillis() >= deadline -> finish(Result.failure(IllegalStateException("等待屏幕画面超时（屏幕捕获未收到新帧）")))
                else -> { handler.postDelayed(this, 32L); Unit }
            }
        }
        fun finish(result: Result<Bitmap>) {
            if (pending !== this) { result.getOrNull()?.recycle(); return }
            pending = null
            handler.removeCallbacks(this)
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
                        source.acquireLatestImage()?.let { image -> latest?.close(); latest = image }
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
    fun capture(region: Rect, callback: (Result<Bitmap>) -> Unit) {
        if (closed || failure != null || reader == null) {
            callback(Result.failure(failure ?: IllegalStateException("屏幕捕获会话未启动")))
            return
        }
        pending?.finish(Result.failure(IllegalStateException("截图请求已被替换")))
        Request(Rect(region), callback).also { pending = it; handler.post(it) }
    }

    private fun copyRegion(image: Image, region: Rect): Bitmap {
        val safe = Rect(region)
        require(safe.intersect(0, 0, image.width, image.height)) { "截图区域超出屏幕" }
        val plane = image.planes[0]
        val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        try {
            plane.buffer.rewind()
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
            return if (cropped === full) full.copy(Bitmap.Config.ARGB_8888, false) else cropped
        } finally { full.recycle() }
    }

    private fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        val current = display ?: return
        pending?.finish(Result.failure(IllegalStateException("屏幕尺寸已变化，请重新截图")))
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
        pending?.finish(Result.failure(error))
        latest?.close(); latest = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        Log.w("IDVBCapture", "MediaProjection session stopped: ${error.message}")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        stop(IllegalStateException("屏幕捕获会话已关闭"))
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
