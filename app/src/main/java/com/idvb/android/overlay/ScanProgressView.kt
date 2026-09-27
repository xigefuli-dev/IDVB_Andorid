package com.idvb.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** Small, touch-through scan indicator modeled on Desktop's game overlay bar. */
internal class ScanProgressView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 82, 112); alpha = 235 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 130, 228) }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val bounds = RectF()
    private var progress = 0f
    private var label = "正在扫描地图…"
    private var failed = false

    fun report(value: Double, text: String) {
        if (value < progress) return
        progress = max(progress, value.coerceIn(0.0, 1.0).toFloat())
        label = text
        contentDescription = "$label ${(progress * 100).roundToInt()}%"
        invalidate()
    }

    fun finish(success: Boolean) {
        progress = 1f
        failed = !success
        label = if (success) "扫描完成" else "扫描失败"
        fill.color = if (success) Color.rgb(0, 186, 28) else Color.rgb(179, 38, 30)
        contentDescription = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, background)
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, width * progress, height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fill)
        canvas.restoreToCount(saved)

        val baseline = height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        val left = 20f * density
        val right = width - 20f * density
        val percentage = if (failed) "失败" else "${(progress * 100).roundToInt()}%"
        val numberWidth = labelPaint.measureText(percentage)
        val available = (right - left - numberWidth - 14f * density).coerceAtLeast(0f)
        val displayLabel = android.text.TextUtils.ellipsize(
            label, labelPaint, available, android.text.TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(displayLabel, left, baseline, labelPaint)
        canvas.drawText(percentage, right - numberWidth, baseline, labelPaint)
    }
}
