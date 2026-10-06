package com.idvb.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.ceil

/** A passive outline outside the saved capture viewport, with no fill or controls. */
class CalibrationBorderView(context: Context) : View(context) {
    companion object {
        const val OPACITY = .20f
    }

    val borderWidth = ceil(resources.displayMetrics.density.toDouble()).toInt().coerceAtLeast(1)
    private val paint = Paint().apply { color = Color.rgb(112, 226, 157) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val edge = borderWidth.toFloat()
        val right = width.toFloat()
        val bottom = height.toFloat()
        // Filled strips avoid an antialiased stroke spilling into captured map pixels.
        canvas.drawRect(0f, 0f, right, edge, paint)
        canvas.drawRect(0f, bottom - edge, right, bottom, paint)
        canvas.drawRect(0f, edge, edge, bottom - edge, paint)
        canvas.drawRect(right - edge, edge, right, bottom - edge, paint)
    }
}
