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
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.emit

/** 提供截图与用户配置位置的单次点击，不读取界面节点。 */
class AccessibilityScreenCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        foregroundPackage = null
        foregroundChangedListener?.invoke(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val updated = event.packageName?.toString()
        val ignored = ForegroundWindowPolicy.ignoredWindowReason(updated, event.className?.toString())
        if (ignored != null) {
            android.util.Log.i("IDVB-Foreground", "stage=window-ignored reason=$ignored " +
                "retained=$foregroundPackage package=$updated class=${event.className} window=${event.windowId}")
            return
        }
        if (updated != foregroundPackage) android.util.Log.i("IDVB-Foreground",
            "previous=$foregroundPackage package=$updated class=${event.className} window=${event.windowId}")
        if (updated != foregroundPackage) {
            foregroundPackage = updated
            foregroundChangedListener?.invoke(updated)
        }
    }
    override fun onInterrupt() { foregroundPackage = null; foregroundChangedListener?.invoke(null) }

    override fun onDestroy() {
        if (instance === this) { instance = null; foregroundPackage = null; foregroundChangedListener?.invoke(null) }
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AccessibilityScreenCaptureService? = null
        @Volatile var foregroundPackage: String? = null
            private set
        @Volatile var foregroundChangedListener: ((String?) -> Unit)? = null
        // Shared by detection, readiness and scan. Android platform baseline is 333ms;
        // retain a small scheduling margin rather than causing request-local retry storms.
        private var lastScreenshotAttemptMs = 0L
        private const val MIN_SCREENSHOT_INTERVAL_MS = 350L
        val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

        fun click(x: Float, y: Float, callback: (Boolean) -> Unit) {
            val service = instance
            if (service == null || (!com.idvb.android.UsageConsent.isAccepted(service) || !com.idvb.android.tutorial.TutorialStore.get(service).state.value.completed) || !x.isFinite() || !y.isFinite() || x < 0 || y < 0) {
                callback(false); return
            }
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)).build()
            val completed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable { if (completed.compareAndSet(false, true)) callback(false) }
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) { handler.removeCallbacks(timeout); callback(success) }
            }
            handler.postDelayed(timeout, 1_000L)
            val accepted = runCatching { service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(true)
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(false)
            }, handler) }.getOrDefault(false)
            if (!accepted) finish(false)
        }

        fun capture(region: Rect, executor: Executor, callback: (Result<Bitmap>) -> Unit,
            log: AlignmentLogSink = AlignmentLogSink.NONE): () -> Unit {
            val service = instance
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || service == null) {
                callback(Result.failure(IllegalStateException("无障碍截图服务未启用")))
                return {}
            }
            if ((!com.idvb.android.UsageConsent.isAccepted(service) || !com.idvb.android.tutorial.TutorialStore.get(service).state.value.completed)) {
                callback(Result.failure(IllegalStateException("请先打开 IDVB，确认使用责任声明并完成新手教程")))
                return {}
            }
            val completed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val retries = CaptureRetryController { delay, action ->
                val runnable = Runnable(action)
                handler.postDelayed(runnable, delay)
                val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
                cancel
            }
            val timeout = Runnable {
                if (completed.compareAndSet(false, true)) {
                    retries.stop()
                    callback(Result.failure(IllegalStateException("无障碍截图响应超时，请检查无障碍服务")))
                }
            }
            var scheduledAttempt: Runnable? = null
            fun finish(result: Result<Bitmap>) {
                if (completed.compareAndSet(false, true)) {
                    retries.stop()
                    scheduledAttempt?.let(handler::removeCallbacks)
                    scheduledAttempt = null
                    handler.removeCallbacks(timeout)
                    callback(result)
                } else result.getOrNull()?.recycle()
            }
            handler.postDelayed(timeout, 3_000L)
            var attemptNumber = 0
            var attemptStarted = 0L
            var attemptStartUptimeMs = 0L
            lateinit var attempt: () -> Unit
            val screenshotCallback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (completed.get()) { result.hardwareBuffer.close(); return }
                    log.emit(AlignmentLogEvent("capture.accessibility.attempt", "captured",
                        measurements = mapOf("attempt" to attemptNumber.toDouble(), "attemptStartUptimeMs" to attemptStartUptimeMs.toDouble()),
                        durationNanos = System.nanoTime() - attemptStarted))
                    val conversionStarted = System.nanoTime()
                    var fullWidth = 0; var fullHeight = 0
                    var actualRegion: Rect? = null
                    val converted = runCatching {
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
                                fullWidth = full.width; fullHeight = full.height
                                val safe = Rect(region)
                                require(safe.intersect(0, 0, full.width, full.height)) { "截图区域超出屏幕" }
                                actualRegion = Rect(safe)
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
                    }
                    log.emit(AlignmentLogEvent("capture.accessibility.convert-and-crop", if (converted.isSuccess) "completed" else "failed",
                        durationNanos = System.nanoTime() - conversionStarted,
                        measurements = mapOf("width" to region.width().toDouble(), "height" to region.height().toDouble(),
                            "fullWidth" to fullWidth.toDouble(), "fullHeight" to fullHeight.toDouble(),
                            "requestedLeft" to region.left.toDouble(), "requestedTop" to region.top.toDouble(),
                            "requestedRight" to region.right.toDouble(), "requestedBottom" to region.bottom.toDouble()) +
                            (actualRegion?.let { mapOf("actualLeft" to it.left.toDouble(), "actualTop" to it.top.toDouble(),
                                "actualRight" to it.right.toDouble(), "actualBottom" to it.bottom.toDouble()) } ?: emptyMap()),
                        labels = mapOf("failure" to converted.exceptionOrNull()?.toString().orEmpty())))
                    finish(converted)
                }

                override fun onFailure(errorCode: Int) {
                    if (completed.get()) return
                    log.emit(AlignmentLogEvent("capture.accessibility.attempt", "error-$errorCode",
                        measurements = mapOf("attempt" to attemptNumber.toDouble(), "errorCode" to errorCode.toDouble(), "attemptStartUptimeMs" to attemptStartUptimeMs.toDouble()),
                        durationNanos = System.nanoTime() - attemptStarted))
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        val waiting = System.nanoTime()
                        val delay = retries.retry {
                            log.emit(AlignmentLogEvent("capture.accessibility.retry-wait", "interval-too-short",
                                durationNanos = System.nanoTime() - waiting))
                            attempt()
                        }
                        if (delay != null) {
                            log.emit(AlignmentLogEvent("capture.accessibility.retry-scheduled", "interval-too-short",
                                measurements = mapOf("delayMs" to delay.toDouble()),
                                thresholds = mapOf("maximumAttempts" to 9.0, "totalTimeoutMs" to 3_000.0)))
                            return
                        }
                    }
                    finish(Result.failure(IllegalStateException("无障碍截图失败（$errorCode）")))
                }
            }
            attempt = {
                if (!completed.get()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val delay = MIN_SCREENSHOT_INTERVAL_MS - (now - lastScreenshotAttemptMs)
                    if (delay > 0) {
                        val waitingAt = System.nanoTime()
                        scheduledAttempt = Runnable {
                            scheduledAttempt = null
                            log.emit(AlignmentLogEvent("capture.accessibility.reservation-wait", "shared-screenshot-interval",
                                durationNanos = System.nanoTime() - waitingAt, measurements = mapOf("scheduledDelayMs" to delay.toDouble()),
                                thresholds = mapOf("minimumScreenshotIntervalMs" to MIN_SCREENSHOT_INTERVAL_MS.toDouble())))
                            attempt()
                        }
                        handler.postDelayed(scheduledAttempt!!, delay)
                    } else {
                    lastScreenshotAttemptMs = now
                    attemptStartUptimeMs = now
                    attemptStarted = System.nanoTime(); attemptNumber++
                    runCatching {
                        if ((!com.idvb.android.UsageConsent.isAccepted(service) || !com.idvb.android.tutorial.TutorialStore.get(service).state.value.completed)) error("请先打开 IDVB，确认使用责任声明并完成新手教程")
                        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, screenshotCallback)
                    }.onFailure { finish(Result.failure(it)) }
                    }
                }
            }
            // Serialize interval reservations on the main looper, including worker callers.
            scheduledAttempt = Runnable { scheduledAttempt = null; attempt() }
            handler.post(scheduledAttempt!!)
            // Android cannot revoke an in-flight screenshot binder call. Stop timeout/crop work,
            // acknowledge immediately, and close any late HardwareBuffer without decoding it.
            return {
                log.emit(AlignmentLogEvent("capture.accessibility.cancel", "pending-retry-and-decode-cancelled"))
                finish(Result.failure(java.util.concurrent.CancellationException("截图请求已取消")))
            }
        }
    }
}
