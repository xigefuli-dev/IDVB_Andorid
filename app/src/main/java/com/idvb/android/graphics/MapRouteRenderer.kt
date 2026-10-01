package com.idvb.android.graphics

import android.graphics.*
import com.idvb.android.idvm.*

/** Composites display-only annotations after background removal; never alters recognition assets. */
object MapRouteRenderer {
    fun thicknessMultiplier(level: Int): Float = when (level.coerceIn(0, 3)) {
        0 -> 1f
        1 -> 1.5f
        2 -> 2f
        else -> 3f
    }

    private val colors = listOf("#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#32ADE6",
        "#007AFF", "#AF52DE", "#FF2D55", "#F2F2F2")

    fun render(bitmap: Bitmap, annotations: List<MapAnnotation>, region: NormalizedRect?,
               sourceWidth: Int, sourceHeight: Int, polygon: List<NormalizedPoint>, level: Int): Bitmap {
        if (annotations.isEmpty()) return bitmap
        if (sourceWidth <= 0 || sourceHeight <= 0) return bitmap
        val crop = region.validOrFull()
        // Match the integer crop used by BitmapRegionDecoder.
        val pixels = crop.toPixelRect(sourceWidth, sourceHeight)
        val left = pixels.left
        val top = pixels.top
        val cropWidth = pixels.width().toDouble()
        val cropHeight = pixels.height().toDouble()
        fun x(value: Double) = ((value * sourceWidth - left) / cropWidth * bitmap.width).toFloat()
        fun y(value: Double) = ((value * sourceHeight - top) / cropHeight * bitmap.height).toFloat()
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        if (polygon.size >= 3) {
            val path = Path()
            polygon.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.x), y(p.y)) else path.lineTo(x(p.x), y(p.y)) }
            path.close()
            canvas.clipPath(path)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (a in annotations) {
            // Old on-disk packages may predate annotation validation.
            if (runCatching { IdvmValidator.validateAnnotation(a) }.isFailure) continue
            paint.reset()
            paint.isAntiAlias = true
            paint.color = Color.parseColor(a.color ?: colors[a.colorIndex.coerceIn(0, 8)])
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * thicknessMultiplier(level)
            if (a.type == "line") {
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x(a.start!!.x), y(a.start.y), x(a.end!!.x), y(a.end.y), paint)
                continue
            }
            val b = a.bounds ?: continue
            val rect = RectF(x(b.x), y(b.y), x(b.x + b.width), y(b.y + b.height))
            if (a.type == "text") {
                paint.strokeWidth = 2f
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            }
            canvas.drawRect(rect, paint)
            if (a.type != "text" || a.text.isNullOrBlank()) continue
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            val legacy = a.fontFamily == null && a.fontSize == null && a.isBold == null &&
                a.isItalic == null && a.isStrikethrough == null
            val style = (if (legacy || a.isBold == true) Typeface.BOLD else 0) or
                (if (a.isItalic == true) Typeface.ITALIC else 0)
            paint.typeface = Typeface.create(a.fontFamily ?: "sans-serif", style)
            paint.isStrikeThruText = a.isStrikethrough == true
            paint.textSize = (a.fontSize?.toFloat()?.times(bitmap.width / crop.width.toFloat() / 1280f)
                ?: (rect.height() * .85f)).coerceAtMost(rect.height() * .85f).coerceAtLeast(1f)
            val measured = paint.measureText(a.text)
            if (measured > rect.width()) paint.textSize *= rect.width() / measured
            paint.textAlign = Paint.Align.CENTER
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(a.text, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            canvas.restore()
        }
        return output
    }
}
