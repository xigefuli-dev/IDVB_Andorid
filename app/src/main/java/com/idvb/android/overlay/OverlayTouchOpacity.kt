package com.idvb.android.overlay

import kotlin.math.pow

/** Android checks window alpha, not pixel/View alpha; overlapping windows combine opacity. */
internal object OverlayTouchOpacity {
    /** Reserve faint layers at their requested opacity before capping stronger layers. */
    fun distribute(maximum: Float, requested: List<Float>): List<Float> {
        if (requested.isEmpty()) return emptyList()
        val targetTransmission = 1.0 - (maximum.coerceIn(0f, 1f) - .001f).coerceAtLeast(0f)
        val result = MutableList(requested.size) { 0f }
        var transmission = 1.0
        requested.indices.sortedBy { requested[it] }.forEachIndexed { rank, index ->
            val cap = (1.0 - (targetTransmission / transmission).coerceIn(0.0, 1.0)
                .pow(1.0 / (requested.size - rank))).toFloat()
            val alpha = minOf(requested[index].coerceIn(0f, 1f), cap)
            result[index] = alpha
            transmission *= 1.0 - alpha
        }
        return result
    }

    fun cap(maximum: Float, windowCount: Int): Float {
        require(windowCount > 0)
        // Leave room for floating point rounding in InputDispatcher's composition.
        val safeMaximum = (maximum.coerceIn(0f, 1f) - .001f).coerceAtLeast(0f)
        return (1.0 - (1.0 - safeMaximum).pow(1.0 / windowCount)).toFloat()
    }
}
