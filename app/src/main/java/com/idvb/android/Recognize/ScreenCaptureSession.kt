package com.idvb.android.recognize

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/** 服务生命周期内保持的捕获会话，为前台扫描和后续后台扫描共用。 */
class ScreenCaptureSession(private val context: Context) {
    private val thread = HandlerThread("idvb-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            display?.release(); display = null
            reader?.close(); reader = null
            projection = null
        }

        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            if (newWidth > 0 && newHeight > 0) resizeExistingDisplay(newWidth, newHeight)
        }
    }

    @Synchronized
    fun start(screenWidth: Int, screenHeight: Int): Boolean {
        return runCatching {
            if (projection == null) {
                val data = ScreenCaptureGrant.data ?: return false
                if (ScreenCaptureGrant.resultCode != Activity.RESULT_OK) return false
                projection = context.getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(ScreenCaptureGrant.resultCode, data)
                    ?.also { it.registerCallback(projectionCallback, handler) }
            }
            if (display == null || reader == null) {
                createDisplay(screenWidth, screenHeight)
            } else if (width != screenWidth || height != screenHeight) {
                resizeExistingDisplay(screenWidth, screenHeight)
            }
            reader != null
        }.getOrDefault(false)
    }

    fun capture(region: Rect, callback: (Result<Bitmap>) -> Unit) {
        val imageReader = reader ?: return callback(Result.failure(IllegalStateException("屏幕捕获会话未启动")))
        val completed = AtomicBoolean(false)
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                callback(Result.failure(IllegalStateException("等待屏幕画面超时")))
            }
        }
        handler.post {
            // 会话创建后没有消费者时队列会被旧帧填满。先全部释放，虚拟屏幕才能
            // 在小球隐藏后写入一张真正的新帧。
            runCatching {
                while (true) (imageReader.acquireNextImage() ?: break).close()
            }
            handler.postDelayed(timeout, 3_000L)
            val poll = object : Runnable {
                override fun run() {
                    if (completed.get()) return
                    val image = runCatching { imageReader.acquireLatestImage() }.getOrNull()
                    if (image == null) {
                        handler.postDelayed(this, 32L)
                        return
                    }
                    try {
                        if (!completed.compareAndSet(false, true)) return
                        handler.removeCallbacks(timeout)
                        callback(Result.success(copyRegion(image, region)))
                    } catch (error: Throwable) {
                        completed.set(true)
                        handler.removeCallbacks(timeout)
                        callback(Result.failure(error))
                    } finally { image.close() }
                }
            }
            handler.post(poll)
        }
    }

    private fun copyRegion(image: android.media.Image, region: Rect): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        val left = region.left.coerceIn(0, image.width - 1)
        val top = region.top.coerceIn(0, image.height - 1)
        val right = region.right.coerceIn(left + 1, image.width)
        val bottom = region.bottom.coerceIn(top + 1, image.height)
        return Bitmap.createBitmap(full, left, top, right - left, bottom - top).also { full.recycle() }
    }

    /** 每个 MediaProjection 授权生命周期内只允许创建一次 VirtualDisplay。 */
    private fun createDisplay(screenWidth: Int, screenHeight: Int) {
        display?.release(); reader?.close()
        width = screenWidth; height = screenHeight
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        display = projection?.createVirtualDisplay(
            "IDVB capture", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler,
        )
    }

    /**
     * 横竖屏切换必须原地调整现有 VirtualDisplay。重新 createVirtualDisplay 在
     * Android 14+ 会触发 SecurityException 并令整个捕获授权失效。
     */
    @Synchronized
    private fun resizeExistingDisplay(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0 ||
            screenWidth == width && screenHeight == height) return
        val currentDisplay = display ?: return
        val nextReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            android.graphics.PixelFormat.RGBA_8888,
            3,
        )
        val previousReader = reader
        currentDisplay.resize(screenWidth, screenHeight, context.resources.displayMetrics.densityDpi)
        currentDisplay.surface = nextReader.surface
        reader = nextReader
        width = screenWidth
        height = screenHeight
        previousReader?.close()
    }

    fun close() {
        display?.release(); reader?.close()
        projection?.unregisterCallback(projectionCallback); projection?.stop(); thread.quitSafely()
        display = null; reader = null; projection = null
    }
}
