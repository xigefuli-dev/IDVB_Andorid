package com.idvb.android.recognize

/** A conservative negative gate: neutral, black and unclassified pixels stay unknown. */
internal object ScanFramePresence {
    const val WIDTH = 160
    const val HEIGHT = 100
    const val MINIMUM_SCENE_FRACTION = .30
    const val MAXIMUM_MAP_FRACTION = .02
    const val ROUTE = "scan-obvious-scene-v1"
    data class Evidence(val sceneFraction: Double, val mapFraction: Double, val unknownFraction: Double) {
        val obviousScene get() = sceneFraction >= MINIMUM_SCENE_FRACTION && mapFraction <= MAXIMUM_MAP_FRACTION
    }

    fun evaluate(pixels: IntArray): Evidence {
        require(pixels.size == WIDTH * HEIGHT)
        var scene = 0
        var map = 0
        var unknown = 0
        for ((index, pixel) in pixels.withIndex()) {
            if (index % WIDTH == 0) com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.frame-presence")
            val r = pixel ushr 16 and 255; val g = pixel ushr 8 and 255; val b = pixel and 255
            val hi = maxOf(r, g, b); val lo = minOf(r, g, b); val delta = hi - lo
            if (hi <= 24 || delta <= 12) { unknown++; continue }
            val hue = when (hi) {
                r -> 60.0 * ((g - b).toDouble() / delta + 6) % 360
                g -> 60.0 * ((b - r).toDouble() / delta + 2)
                else -> 60.0 * ((r - g).toDouble() / delta + 4)
            }
            if (hue in 180.0..260.0 && delta * 255.0 / hi < 140) map++ else scene++
        }
        return Evidence(scene.toDouble() / pixels.size, map.toDouble() / pixels.size, unknown.toDouble() / pixels.size)
    }
}
