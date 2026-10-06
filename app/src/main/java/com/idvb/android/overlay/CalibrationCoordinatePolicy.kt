package com.idvb.android.overlay

import com.idvb.android.recognize.gate.ScreenRect

/** Calibration gestures are view-local; capture and persisted ratios are display-local. */
object CalibrationCoordinatePolicy {
    fun screenRegion(region: ScreenRect, originX: Int, originY: Int, viewWidth: Int, viewHeight: Int,
        screenWidth: Int, screenHeight: Int): ScreenRect {
        require(viewWidth > 0 && viewHeight > 0 && screenWidth > 0 && screenHeight > 0)
        require(listOf(region.x, region.y, region.width, region.height).all { it.isFinite() } && region.isValid)
        require(region.x >= 0 && region.y >= 0 && region.x + region.width <= viewWidth && region.y + region.height <= viewHeight)
        val left = (originX + region.x).coerceIn(0.0, screenWidth.toDouble())
        val top = (originY + region.y).coerceIn(0.0, screenHeight.toDouble())
        val right = (originX + region.x + region.width).coerceIn(0.0, screenWidth.toDouble())
        val bottom = (originY + region.y + region.height).coerceIn(0.0, screenHeight.toDouble())
        require(right > left && bottom > top)
        return ScreenRect(left, top, right - left, bottom - top)
    }
}
