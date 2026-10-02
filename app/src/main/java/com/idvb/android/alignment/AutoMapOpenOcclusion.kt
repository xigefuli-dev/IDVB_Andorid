package com.idvb.android.alignment

import android.graphics.RectF

/** An occluded pixel cannot be evidence of the game behind an IDVB window. */
object AutoMapOpenOcclusion {
    fun overlaps(region: RectF, bounds: List<RectF>): Boolean = bounds.any {
        it.width() > 0f && it.height() > 0f && RectF.intersects(region, it)
    }
}
