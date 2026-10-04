package com.idvb.android.alignment

import android.graphics.RectF

/** An occluded pixel cannot be evidence of the game behind an IDVB window. */
object AutoMapOpenOcclusion {
    /** The guide's transparent detection cutout is not an occluding surface. */
    fun visibleParts(bounds: RectF, viewport: RectF?, excluded: RectF?): List<RectF> {
        val visible = RectF(bounds)
        if (viewport != null && !visible.intersect(viewport)) return emptyList()
        val cutout = excluded?.let(::RectF) ?: return listOf(visible)
        if (!cutout.intersect(visible)) return listOf(visible)
        return listOf(
            RectF(visible.left, visible.top, visible.right, cutout.top),
            RectF(visible.left, cutout.bottom, visible.right, visible.bottom),
            RectF(visible.left, cutout.top, cutout.left, cutout.bottom),
            RectF(cutout.right, cutout.top, visible.right, cutout.bottom),
        ).filter { it.width() > 0f && it.height() > 0f }
    }

    fun overlaps(region: RectF, bounds: List<RectF>): Boolean = bounds.any {
        it.width() > 0f && it.height() > 0f && RectF.intersects(region, it)
    }
}
