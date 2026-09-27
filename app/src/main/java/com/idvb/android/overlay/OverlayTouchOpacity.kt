package com.idvb.android.overlay

import kotlin.math.pow

/** Android checks window alpha, not pixel/View alpha; overlapping windows combine opacity. */
internal object OverlayTouchOpacity {
    fun cap(maximum: Float, windowCount: Int): Float {
        require(windowCount > 0)
        // Leave room for floating point rounding in InputDispatcher's composition.
        val safeMaximum = (maximum.coerceIn(0f, 1f) - .001f).coerceAtLeast(0f)
        return (1.0 - (1.0 - safeMaximum).pow(1.0 / windowCount)).toFloat()
    }
}
