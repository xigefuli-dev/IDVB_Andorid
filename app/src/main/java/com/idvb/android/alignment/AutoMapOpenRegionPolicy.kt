package com.idvb.android.alignment

/** The map calibration may end at the screen edge or inside the fixed sidebar. */
object AutoMapOpenRegionPolicy {
    fun leftRatio(screenWidth: Int, screenHeight: Int, calibratedRightRatio: Float,
        sidebarAspectRatio: Double): Float {
        require(screenWidth > screenHeight && screenHeight > 0)
        require(calibratedRightRatio.isFinite() && calibratedRightRatio in 0f..1f)
        require(sidebarAspectRatio.isFinite() && sidebarAspectRatio > 0)
        // Retain enough pixels for the largest unchanged comparison search window.
        val latestLeft = (1.0 - sidebarAspectRatio * screenHeight * 1.15 / screenWidth).coerceIn(0.0, 1.0)
        return minOf(calibratedRightRatio, latestLeft.toFloat())
    }
}
