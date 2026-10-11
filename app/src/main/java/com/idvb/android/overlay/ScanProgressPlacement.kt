package com.idvb.android.overlay

import kotlin.math.ceil
import kotlin.math.floor

/** Keep the temporary result bar inside the map crop and away from sidebar evidence. */
internal object ScanProgressPlacement {
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    fun place(left: Double, top: Double, right: Double, bottom: Double,
        screenWidth: Int, screenHeight: Int, preferredWidth: Int, preferredHeight: Int,
        detectionLeft: Double? = null): Bounds? {
        val l = ceil(left.coerceAtLeast(0.0)).toInt()
        val t = ceil(top.coerceAtLeast(0.0)).toInt()
        val r = floor(minOf(right, screenWidth.toDouble(), detectionLeft ?: right)).toInt()
        val b = floor(minOf(bottom, screenHeight.toDouble())).toInt()
        if (r <= l || b <= t) return null
        val width = preferredWidth.coerceIn(1, r - l)
        val height = preferredHeight.coerceIn(1, b - t)
        return Bounds(((l + r - width) / 2).coerceIn(l, r - width),
            (t + (b - t) * .85 - height / 2).toInt().coerceIn(t, b - height), width, height)
    }
}
