package com.idvb.android.recognize

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 通过系统无障碍截图 API 提供一次性截图，不读取或操作界面节点。 */
class AccessibilityScreenCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AccessibilityScreenCaptureService? = null
        val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

        fun capture(region: Rect, executor: Executor, callback: (Result<Bitmap>) -> Unit) {
            val service = instance
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || service == null) {
                callback(Result.failure(IllegalStateException("无障碍截图服务未启用")))
                return
            }
            val completed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                if (completed.compareAndSet(false, true)) {
                    callback(Result.failure(IllegalStateException("无障碍截图响应超时，请检查无障碍服务")))
                }
            }
            fun finish(result: Result<Bitmap>) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    callback(result)
                } else result.getOrNull()?.recycle()
            }
            handler.postDelayed(timeout, 3_000L)
            val screenshotCallback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    runCatching {
                        val buffer = result.hardwareBuffer
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?: error("无法读取无障碍截图")
                            val full = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: error("无法转换无障碍截图")
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            try {
                                val safe = Rect(region)
                                require(safe.intersect(0, 0, full.width, full.height)) { "截图区域超出屏幕" }
                                // 再复制一次，确保返回结果不依赖全屏截图或 HardwareBuffer 的生命周期。
                                val cropped = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
                                if (cropped === full) full.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: error("无法裁剪无障碍截图") else cropped
                            } finally {
                                full.recycle()
                            }
                        } finally {
                            buffer.close()
                        }
                    }.also(::finish)
                }

                override fun onFailure(errorCode: Int) {
                    finish(Result.failure(IllegalStateException("无障碍截图失败（$errorCode）")))
                }
            }
            runCatching {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, screenshotCallback)
            }.onFailure { finish(Result.failure(it)) }
        }
    }
}
