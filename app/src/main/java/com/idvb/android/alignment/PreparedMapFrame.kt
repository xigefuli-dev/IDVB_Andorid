package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.Rect

/** Owned, unoccluded viewport from the same capture used to detect the game's map UI.
 * Request-start time is a conservative age bound, including screenshot queue/rate-limit wait.
 */
class PreparedMapFrame(
    val bitmap: Bitmap,
    val bounds: Rect,
    val captureStartedAtMs: Long,
    val captureReceivedAtMs: Long,
    val captureMethod: String = "ACCESSIBILITY",
    val frameSequence: Long? = null,
    private var indicatorBitmap: Bitmap? = null,
    private val indicatorBounds: Rect? = null,
    val frameReceivedNanos: Long? = null,
) {
    fun isFreshFor(region: Rect, nowMs: Long, maximumAgeMs: Long = AutoMapOpenConfig().maximumFrameAgeMs): Boolean = !bitmap.isRecycled && bounds == region &&
        bitmap.width == region.width() && bitmap.height == region.height() &&
        nowMs >= captureStartedAtMs && nowMs - captureStartedAtMs <= maximumAgeMs

    /** Transfers the independently cropped floor UI from this exact detector capture. */
    fun takeIndicatorFor(region: Rect, nowMs: Long, maximumAgeMs: Long): Bitmap? {
        val input = indicatorBitmap ?: return null
        if (input.isRecycled || indicatorBounds != region || input.width != region.width() ||
            input.height != region.height() || nowMs < captureStartedAtMs ||
            nowMs - captureStartedAtMs > maximumAgeMs) return null
        indicatorBitmap = null
        return input
    }

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
        discardIndicator()
    }

    fun discardIndicator() {
        indicatorBitmap?.let { if (!it.isRecycled) it.recycle() }
        indicatorBitmap = null
    }
}

data class AutoMapOpenSample(val pixels: IntArray, val mapFrame: PreparedMapFrame?,
    val comparison: AutoMapOpenComparison? = null, val decisionMs: Double = 0.0)
