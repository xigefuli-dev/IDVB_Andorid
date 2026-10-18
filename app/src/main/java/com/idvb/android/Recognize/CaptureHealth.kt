package com.idvb.android.recognize

import com.idvb.android.data.ScreenCaptureMethod

enum class CaptureAvailability { UNKNOWN, HEALTHY, UNAVAILABLE }
enum class CaptureFailure { NO_GRANT, SYSTEM_STOPPED, SERVICE_UNAVAILABLE, REJECTED, BLACK_FRAME, TIMEOUT, OTHER }

class CaptureException(val failure: CaptureFailure, message: String, val errorCode: Int? = null) : IllegalStateException(message)

data class CaptureHealthSnapshot(
    val source: ScreenCaptureMethod,
    val availability: CaptureAvailability = CaptureAvailability.UNKNOWN,
    val lastSuccessElapsedMs: Long? = null,
    val lastFailure: CaptureFailure? = null,
    val lastFailureReason: String? = null,
    val lastErrorCode: Int? = null,
    val lastFailureElapsedMs: Long? = null,
) {
    val description: String get() = when (availability) {
        CaptureAvailability.UNKNOWN -> "尚未实测截图"
        CaptureAvailability.HEALTHY -> "最近截图实测正常"
        CaptureAvailability.UNAVAILABLE -> lastFailureReason ?: "截图不可用"
    }
}

/** Process-local evidence only. Epochs fence stopped sources; ordering fences late concurrent requests. */
class CaptureHealthMachine(source: ScreenCaptureMethod) {
    data class Ticket(val epoch: Long, val order: Long)
    var snapshot = CaptureHealthSnapshot(source)
        private set
    private var epoch = 0L
    private var order = 0L
    private var acceptedOrder = -1L

    @Synchronized fun beginSession() {
        epoch++; acceptedOrder = -1L
        snapshot = snapshot.copy(availability = CaptureAvailability.UNKNOWN)
    }
    @Synchronized fun ticket() = Ticket(epoch, ++order)
    @Synchronized fun record(ticket: Ticket, nowMs: Long, error: Throwable?): Boolean {
        if (ticket.epoch != epoch || ticket.order < acceptedOrder || error is java.util.concurrent.CancellationException) return false
        acceptedOrder = ticket.order
        snapshot = if (error == null) snapshot.copy(availability = CaptureAvailability.HEALTHY, lastSuccessElapsedMs = nowMs)
        else snapshot.copy(availability = CaptureAvailability.UNAVAILABLE,
            lastFailure = (error as? CaptureException)?.failure ?: CaptureFailure.OTHER,
            lastFailureReason = error.message ?: "截图失败", lastErrorCode = (error as? CaptureException)?.errorCode,
            lastFailureElapsedMs = nowMs)
        return true
    }
}

/** One warning per outage and source; new grants alone cannot reset the warning. */
class CaptureHealthWarnings {
    private val shown = mutableSetOf<ScreenCaptureMethod>()
    fun shouldWarn(health: CaptureHealthSnapshot): Boolean {
        if (health.availability == CaptureAvailability.HEALTHY) shown.remove(health.source)
        return health.availability == CaptureAvailability.UNAVAILABLE && shown.add(health.source)
    }
}

/** Conservative signal, not proof of a permissions problem. No image allocation or storage. */
object BlackFrameDetector {
    const val VERSION = "near-black-grid-v1"
    const val CHANNEL_LIMIT = 8
    const val BLACK_FRACTION = .995
    const val COLUMNS = 64
    const val ROWS = 36
    data class Assessment(val samples: Int, val darkSamples: Int, val maximumChannel: Int) {
        val darkFraction get() = darkSamples.toDouble() / samples
        val isBlack get() = darkFraction >= BLACK_FRACTION
    }
    fun assess(width: Int, height: Int, pixel: (Int, Int) -> Int): Assessment {
        require(width > 0 && height > 0)
        val columns = minOf(width, COLUMNS); val rows = minOf(height, ROWS)
        var dark = 0; var maximum = 0
        repeat(rows) { y -> repeat(columns) { x ->
            val color = pixel(((2L * x + 1) * width / (2L * columns)).toInt(),
                ((2L * y + 1) * height / (2L * rows)).toInt())
            val channel = maxOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
            if (channel <= CHANNEL_LIMIT) dark++
            maximum = maxOf(maximum, channel)
        } }
        return Assessment(columns * rows, dark, maximum)
    }
}

/** Numeric codes are the Android AccessibilityService API constants (API 30/34). */
fun accessibilityCaptureException(code: Int) = CaptureException(CaptureFailure.REJECTED,
    "无障碍截图失败：" + when (code) {
        1 -> "系统内部错误，请稍后重试或重新开启无障碍服务"
        2 -> "系统拒绝无障碍访问，请重新开启 IDVB 无障碍服务"
        3 -> "截图间隔过短，请稍后重试"
        4 -> "显示屏已失效，请重新截图"
        5 -> "目标窗口已失效，请重新打开目标画面"
        6 -> "安全窗口禁止截图，可能被系统拦截"
        else -> "系统返回未知错误，请检查无障碍服务"
    } + "（错误码 $code）", code)
