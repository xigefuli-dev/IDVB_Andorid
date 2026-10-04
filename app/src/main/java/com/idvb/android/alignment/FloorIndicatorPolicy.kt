package com.idvb.android.alignment

import com.idvb.android.recognize.gate.ScreenRect
import kotlin.math.ceil

data class FloorIndicatorScore(val template: String, val floorKey: String, val similarity: Double,
    val scale: Double, val x: Double, val y: Double, val width: Double, val height: Double)

object FloorIndicatorPolicy {
    const val VERSION = "floor-indicator-content-v1"

    /** Physical landscape pixels, including the status/cutout area above calibration. */
    fun region(screenWidth: Int, screenHeight: Int, calibrationTop: Double): ScreenRect? {
        if (screenWidth <= screenHeight || screenHeight <= 0 || !calibrationTop.isFinite()) return null
        val bottom = ceil(calibrationTop).toInt().coerceIn(0, screenHeight)
        if (bottom == 0) return null
        val left = screenWidth / 2
        return ScreenRect(left.toDouble(), 0.0, (screenWidth - left).toDouble(), bottom.toDouble())
    }

    /** No resolution prior, score threshold, margin gate or structural evidence. */
    fun winner(scores: List<FloorIndicatorScore>, availableFloors: Set<String>): FloorIndicatorScore? =
        scores.filter { it.floorKey in availableFloors && it.similarity.isFinite() }
            .maxByOrNull { it.similarity }
}
