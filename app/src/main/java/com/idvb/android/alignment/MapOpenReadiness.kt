package com.idvb.android.alignment

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MapFrameSignature(val histogram: List<Double>, val blueGrayFraction: Double, val meanValue: Double)
data class MapFrameReadiness(val ready: Boolean, val mode: String, val score: Double, val brightnessDelta: Double?)

/** Desktop MapViewportPresenceDetector's ordinary-map ready gate. This is not identity verification. */
object MapOpenReadiness {
    const val WIDTH = 160
    const val HEIGHT = 100
    const val TIMEOUT_MS = 3_000L
    const val INTERVAL_MS = 250L // Android screenshot API rate limit; desktop uses much shorter capture intervals.
    const val COLOR_THRESHOLD = .85
    const val BRIGHTNESS_LIMIT = .10

    fun signature(argb: IntArray, checkpoint: () -> Unit = {}): MapFrameSignature {
        require(argb.size == WIDTH * HEIGHT)
        val bins = DoubleArray(108)
        var blue = 0
        var valueSum = 0.0
        argb.forEachIndexed { index, pixel ->
            if (index % WIDTH == 0) checkpoint()
            val r = (pixel ushr 16 and 255).toDouble()
            val g = (pixel ushr 8 and 255).toDouble()
            val b = (pixel and 255).toDouble()
            val hi = max(r, max(g, b)); val lo = min(r, min(g, b)); val delta = hi - lo
            val hueDegrees = if (delta == 0.0) 0.0 else when (hi) {
                r -> 60 * (((g - b) / delta + 6) % 6)
                g -> 60 * ((b - r) / delta + 2)
                else -> 60 * ((r - g) / delta + 4)
            }
            val hue = (hueDegrees / 2).toInt().coerceIn(0, 179)
            val sat = if (hi == 0.0) 0 else (255 * delta / hi).toInt().coerceIn(0, 255)
            bins[(hue * 18 / 180) * 6 + sat * 6 / 256]++
            if (hue in 90..130 && sat < 140) blue++
            valueSum += hi
        }
        return MapFrameSignature(bins.map { it / argb.size }, blue.toDouble() / argb.size, valueSum / argb.size)
    }

    fun evaluate(candidate: MapFrameSignature, reference: MapFrameSignature?, previous: MapFrameSignature?): MapFrameReadiness {
        val usable = reference?.takeIf { it.histogram.size == 108 && it.histogram.sum() > 0 }
        val score = if (usable == null) candidate.blueGrayFraction else {
            val dot = candidate.histogram.zip(usable.histogram).sumOf { it.first * it.second }
            val norm = sqrt(candidate.histogram.sumOf { it * it } * usable.histogram.sumOf { it * it })
            if (norm > 0) dot / norm else 0.0
        }
        val baseline = usable ?: previous
        val delta = baseline?.let { abs(candidate.meanValue - it.meanValue) / 255 }
        return MapFrameReadiness(score >= COLOR_THRESHOLD && delta != null && delta <= BRIGHTNESS_LIMIT,
            if (usable == null) "blue-gray-fallback" else "reference-hsv", score, delta)
    }
}
