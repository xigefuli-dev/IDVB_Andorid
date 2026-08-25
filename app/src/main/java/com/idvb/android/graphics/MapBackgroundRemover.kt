package com.idvb.android.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.idvb.android.idvm.BackgroundLayer
import com.idvb.android.idvm.NormalizedRect
import kotlin.math.abs
import kotlin.math.max

/**
 * 与 Desktop MapBackgroundProcessor 对齐的攻略图背景处理：先找占比最大的
 * 不透明 RGB，再按通道容差清除整段相近颜色，而不是只清除一个精确颜色。
 */
object MapBackgroundRemover {
    private const val DESKTOP_TOLERANCE = 8
    private const val FALLBACK_TOLERANCE = 12

    fun remove(
        bitmap: Bitmap,
        classMarkedForRemoval: Boolean,
        layers: List<BackgroundLayer> = emptyList(),
        sourceWidth: Int = bitmap.width,
        sourceHeight: Int = bitmap.height,
        region: NormalizedRect? = null,
    ): Bitmap {
        val result = if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        val manualMask = createManualMask(result, layers, sourceWidth, sourceHeight, region)
        val maskPixels = IntArray(result.width * result.height)
        manualMask?.getPixels(maskPixels, 0, result.width, 0, 0, result.width, result.height)

        val counts = HashMap<Int, Int>()
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            if (Color.alpha(pixel) != 0 && (manualMask == null || Color.alpha(maskPixels[index]) == 0)) {
                val rgb = pixel and 0x00FFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        val primary = counts.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
            ?: return result
        val mainR = Color.red(primary)
        val mainG = Color.green(primary)
        val mainB = Color.blue(primary)
        val tolerance = if (classMarkedForRemoval) DESKTOP_TOLERANCE else FALLBACK_TOLERANCE

        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            if ((manualMask != null && Color.alpha(maskPixels[index]) != 0) ||
                (Color.alpha(pixel) != 0 &&
                kotlin.math.abs(Color.red(pixel) - mainR) <= tolerance &&
                kotlin.math.abs(Color.green(pixel) - mainG) <= tolerance &&
                kotlin.math.abs(Color.blue(pixel) - mainB) <= tolerance)
            ) {
                pixels[index] = Color.TRANSPARENT
            }
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        manualMask?.recycle()
        return result
    }

    private fun createManualMask(
        bitmap: Bitmap,
        layers: List<BackgroundLayer>,
        sourceWidth: Int,
        sourceHeight: Int,
        region: NormalizedRect?,
    ): Bitmap? {
        val validLayers = layers.filter { it.semantic == "background" && it.points.isNotEmpty() }
        if (validLayers.isEmpty()) return null
        val crop = region ?: NormalizedRect(0.0, 0.0, 1.0, 1.0)
        val mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val xScale = bitmap.width / (crop.width.coerceAtLeast(0.000001) * sourceWidth.coerceAtLeast(1))
        val yScale = bitmap.height / (crop.height.coerceAtLeast(0.000001) * sourceHeight.coerceAtLeast(1))
        validLayers.forEach { layer ->
            val brush = max(1f, layer.brushSizePixels * ((xScale + yScale) / 2f).toFloat())
            paint.strokeWidth = brush
            val points = layer.points.map {
                android.graphics.PointF(
                    ((it.x - crop.x) / crop.width * bitmap.width).toFloat(),
                    ((it.y - crop.y) / crop.height * bitmap.height).toFloat(),
                )
            }
            points.forEach { point ->
                if (layer.shape == "square") {
                    canvas.drawRect(point.x - brush / 2f, point.y - brush / 2f, point.x + brush / 2f, point.y + brush / 2f, paint.apply { style = Paint.Style.FILL })
                } else {
                    canvas.drawCircle(point.x, point.y, brush / 2f, paint.apply { style = Paint.Style.FILL })
                }
            }
            paint.style = Paint.Style.STROKE
            points.zipWithNext().forEach { (from, to) -> canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
        }
        return mask
    }
}
