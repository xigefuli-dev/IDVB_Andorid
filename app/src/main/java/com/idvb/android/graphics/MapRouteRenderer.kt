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
    private val HALO = Color.argb(240, 10, 15, 22)
    private val HALO_OPAQUE = Color.argb(255, 10, 15, 22)
    private val hexColor = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

    fun colorOf(a: MapAnnotation): Int = a.color?.trim()?.takeIf { hexColor.matches(it) }?.let(Color::parseColor)
        ?: Color.parseColor(colors[a.colorIndex.coerceIn(0, colors.lastIndex)])

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
        for (a in annotations.sortedBy { it.isMarker }) {
            // Old on-disk packages may predate annotation validation.
            if (runCatching { IdvmValidator.validateAnnotation(a.copy(color = null, colorIndex = 0)) }.isFailure) continue
            paint.reset()
            paint.isAntiAlias = true
            paint.color = colorOf(a)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * thicknessMultiplier(level)
            if (a.type == "route") {
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val path = Path()
                a.points!!.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(x(point.x), y(point.y)) else path.lineTo(x(point.x), y(point.y))
                }
                if (a.text?.startsWith("阶段 ") == true) {
                    val routeColor = paint.color
                    val routeWidth = paint.strokeWidth
                    paint.color = HALO
                    paint.strokeWidth = routeWidth + 2f
                    canvas.drawPath(path, paint)
                    paint.color = routeColor
                    paint.strokeWidth = routeWidth
                }
                canvas.drawPath(path, paint)
                continue
            }
            if (a.type == "line") {
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x(a.start!!.x), y(a.start.y), x(a.end!!.x), y(a.end.y), paint)
                continue
            }
            val b = a.bounds ?: continue
            val rect = RectF(x(b.x), y(b.y), x(b.x + b.width), y(b.y + b.height))
            if (a.type != "text") {
                canvas.drawRect(rect, paint)
                continue
            }
            if (a.text.isNullOrBlank()) continue
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
            val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2
            canvas.save()
            canvas.clipRect(rect)
            if (a.isMarker) {
                val textColor = paint.color
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = (paint.textSize * .14f).coerceAtLeast(2f)
                paint.color = HALO_OPAQUE
                canvas.drawText(a.text, rect.centerX(), baseline, paint)
                paint.style = Paint.Style.FILL
                paint.color = textColor
            }
            canvas.drawText(a.text, rect.centerX(), baseline, paint)
            canvas.restore()
        }
        return output
    }
}
