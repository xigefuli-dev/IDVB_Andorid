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
) {
    fun isFreshFor(region: Rect, nowMs: Long): Boolean = !bitmap.isRecycled && bounds == region &&
        bitmap.width == region.width() && bitmap.height == region.height() &&
        nowMs >= captureStartedAtMs && nowMs - captureStartedAtMs <= AutoMapOpenConfig().maximumFrameAgeMs

    fun recycle() { if (!bitmap.isRecycled) bitmap.recycle() }
}

data class AutoMapOpenSample(val pixels: IntArray, val mapFrame: PreparedMapFrame?)
