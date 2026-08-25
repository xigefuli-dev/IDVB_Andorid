package com.idvb.android.recognize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import com.idvb.android.data.MapRepository
import com.idvb.android.graphics.decodeMapRegion
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** 全屏原生候选窗：识别结果优先，目录中其余地图继续排在末尾。 */
class CandidateSelectionView(
    context: Context,
    private val result: RecognitionResult,
    repository: MapRepository,
) : View(context) {
    interface Listener { fun onSelected(candidate: RecognitionCandidate); fun onCancelled() }
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbnails: List<Bitmap?> = result.candidates.map { candidate ->
        val floor = candidate.map.floors.firstOrNull { it.key == candidate.floorKey } ?: candidate.map.floors.minByOrNull { it.sortOrder }
        floor?.let {
            createPositionedPreview(
                repository.floorImageFile(candidate.map.id, it.imagePath).path,
                repository.loadSideDoors(candidate.map.id, it.key).firstOrNull(),
                repository.loadPreviewRegion(candidate.map.id, it),
            )
        }
    }
    private var scroll = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false

    private val headerHeight get() = dp(66f)
    private val landscape get() = width > height
    private val cardHeight get() = dp(214f)
    private val cardGap get() = dp(10f)
    private val previewRect get() = if (landscape) {
        RectF(dp(16f), headerHeight, width * .34f, height - dp(16f))
    } else {
        RectF(dp(16f), headerHeight, width - dp(16f), headerHeight + dp(160f))
    }
    private val gridLeft get() = if (landscape) previewRect.right + dp(12f) else dp(12f)
    private val listTop get() = if (landscape) headerHeight else previewRect.bottom + dp(12f)
    private val cardWidth get() = (width - gridLeft - dp(16f) - cardGap) / 2f
    private val cancelRect get() = RectF(width - dp(92f), dp(18f), width - dp(18f), dp(58f))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(17, 20, 23))
        text.textSize = dp(18f); text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("选择候选地图", dp(20f), dp(35f), text)
        text.typeface = android.graphics.Typeface.DEFAULT
        paint.color = Color.rgb(50, 55, 60); canvas.drawRoundRect(cancelRect, dp(10f), dp(10f), paint)
        text.textAlign = Paint.Align.CENTER; text.textSize = dp(13f); canvas.drawText("取消", cancelRect.centerX(), cancelRect.centerY() + dp(5f), text); text.textAlign = Paint.Align.LEFT

        val livePreview = previewRect
        paint.color = Color.BLACK; canvas.drawRoundRect(livePreview, dp(12f), dp(12f), paint)
        drawBitmapFit(canvas, result.capturedRegion, livePreview)

        canvas.save(); canvas.clipRect(0, listTop.toInt(), width, height)
        result.candidates.forEachIndexed { index, candidate ->
            val row = index / 2
            val column = index % 2
            val left = gridLeft + column * (cardWidth + cardGap)
            val top = listTop + row * (cardHeight + cardGap) - scroll
            if (top + cardHeight < listTop || top > height) return@forEachIndexed
            drawCandidate(canvas, index, candidate, thumbnails[index], left, top)
        }
        canvas.restore()
    }

    private fun drawCandidate(canvas: Canvas, index: Int, candidate: RecognitionCandidate, thumbnail: Bitmap?, left: Float, top: Float) {
        val card = RectF(left, top, left + cardWidth, top + cardHeight)
        paint.color = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> Color.rgb(27, 54, 38)
            CandidateDisposition.NEEDS_VERIFICATION -> Color.rgb(57, 48, 27)
            CandidateDisposition.CATALOG_ONLY -> Color.rgb(34, 37, 41)
        }
        canvas.drawRoundRect(card, dp(12f), dp(12f), paint)
        // 双列卡片上半部显示经过侧门定位后的实际视口裁剪。
        val imageRect = RectF(card.left + dp(7f), card.top + dp(7f), card.right - dp(7f), card.bottom - dp(37f))
        paint.color = Color.BLACK; canvas.drawRoundRect(imageRect, dp(7f), dp(7f), paint)
        thumbnail?.let { drawBitmapFit(canvas, it, imageRect) }
        val x = card.left + dp(12f)
        val degree = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> "高"
            CandidateDisposition.NEEDS_VERIFICATION -> "中"
            CandidateDisposition.CATALOG_ONLY -> "低"
        }
        val degreeColor = when (candidate.disposition) {
            CandidateDisposition.RELIABLE -> Color.rgb(150, 225, 170)
            CandidateDisposition.NEEDS_VERIFICATION -> Color.rgb(244, 190, 90)
            CandidateDisposition.CATALOG_ONLY -> Color.rgb(165, 170, 177)
        }
        text.textSize = dp(12f); text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        text.color = Color.WHITE
        drawEllipsized(canvas, candidate.map.title, x, card.bottom - dp(13f), card.width() - dp(58f))
        text.textAlign = Paint.Align.RIGHT; text.color = degreeColor
        canvas.drawText(degree, card.right - dp(12f), card.bottom - dp(13f), text)
        text.textAlign = Paint.Align.LEFT; text.typeface = android.graphics.Typeface.DEFAULT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; lastY = event.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > dp(6f)) moved = true
                if (moved && event.y >= listTop) {
                    val rows = (result.candidates.size + 1) / 2
                    val maxScroll = max(0f, rows * (cardHeight + cardGap) - cardGap - (height - listTop))
                    scroll = (scroll - (event.y - lastY)).coerceIn(0f, maxScroll); invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                if (cancelRect.contains(event.x, event.y)) listener?.onCancelled()
                else if (event.y >= listTop) {
                    val column = ((event.x - gridLeft) / (cardWidth + cardGap)).toInt()
                    val row = ((event.y - listTop + scroll) / (cardHeight + cardGap)).toInt()
                    val localX = event.x - gridLeft - column * (cardWidth + cardGap)
                    val localY = event.y - listTop + scroll - row * (cardHeight + cardGap)
                    if (column in 0..1 && row >= 0 && localX in 0f..cardWidth && localY in 0f..cardHeight) {
                        val index = row * 2 + column
                        result.candidates.getOrNull(index)?.let { listener?.onSelected(it) }
                    }
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        thumbnails.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
        super.onDetachedFromWindow()
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = minOf(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2), paint)
    }
    private fun drawEllipsized(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float) {
        var shown = value
        while (shown.length > 4 && text.measureText(shown) > maxWidth) shown = shown.dropLast(2) + "…"
        canvas.drawText(shown, x, y, text)
    }
    private fun createPositionedPreview(
        path: String,
        gate: com.idvb.android.idvm.NormalizedRect?,
        previewRegion: com.idvb.android.idvm.NormalizedRect?,
    ): Bitmap? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val expectedLandscape = screenWidth > screenHeight
        val outputWidth = (screenWidth * if (expectedLandscape) .30f else .44f).toInt().coerceIn(400, 960)
        val outputHeight = (177f * density).toInt().coerceIn(240, 560)
        val decodeTarget = (max(outputWidth, outputHeight) * 1.35f).toInt()
        // IDVM gate bounds are relative to recognitionRegion. Decode that region
        // directly so neither the full map nor pixels outside the selection can leak in.
        val source = decodeMapRegion(File(path), previewRegion, decodeTarget) ?: return null
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output); canvas.drawColor(Color.BLACK)
        val normalizedGateX = gate?.let { it.x + it.width / 2.0 } ?: .5
        val normalizedGateY = gate?.let { it.y + it.height / 2.0 } ?: .5
        val centerX = normalizedGateX.toFloat() * source.width
        val centerY = normalizedGateY.toFloat() * source.height
        // Keep the entire selected region visible, then use any spare black padding
        // to move the whole side-door rectangle into the 12% safe boundary.
        val targetX = outputWidth * .50f
        val targetY = outputHeight * .65f
        val safeLeft = outputWidth * .12f; val safeRight = outputWidth * .88f
        val safeTop = outputHeight * .12f; val safeBottom = outputHeight * .88f
        val gateLeft = (gate?.x ?: normalizedGateX).toFloat() * source.width
        val gateRight = ((gate?.x ?: normalizedGateX) + (gate?.width ?: 0.0)).toFloat() * source.width
        val gateTop = (gate?.y ?: normalizedGateY).toFloat() * source.height
        val gateBottom = ((gate?.y ?: normalizedGateY) + (gate?.height ?: 0.0)).toFloat() * source.height
        val fitScale = minOf(outputWidth.toFloat() / source.width, outputHeight.toFloat() / source.height)
        fun ranges(scale: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            val imageX = 0f to (outputWidth - source.width * scale)
            val imageY = 0f to (outputHeight - source.height * scale)
            if (gate == null) return imageX to imageY
            val x = maxOf(imageX.first, safeLeft - gateLeft * scale) to
                minOf(imageX.second, safeRight - gateRight * scale)
            val y = maxOf(imageY.first, safeTop - gateTop * scale) to
                minOf(imageY.second, safeBottom - gateBottom * scale)
            return x to y
        }
        fun feasible(scale: Float): Boolean = ranges(scale).let {
            it.first.first <= it.first.second && it.second.first <= it.second.second
        }
        var scale = fitScale
        if (!feasible(scale)) {
            var low = 0f
            var high = fitScale
            repeat(28) {
                val middle = (low + high) / 2f
                if (feasible(middle)) low = middle else high = middle
            }
            scale = low.coerceAtLeast(.01f)
        }
        val allowed = ranges(scale)
        val desiredX = targetX - centerX * scale
        val desiredY = targetY - centerY * scale
        val translateX = desiredX.coerceIn(allowed.first.first, allowed.first.second)
        val translateY = desiredY.coerceIn(allowed.second.first, allowed.second.second)
        // 显式写入仿射矩阵，避免 postScale/postTranslate 在部分顺序下把平移再次缩放。
        val matrix = Matrix().apply {
            setValues(floatArrayOf(
                scale, 0f, translateX,
                0f, scale, translateY,
                0f, 0f, 1f,
            ))
        }
        canvas.drawBitmap(source, matrix, paint)
        source.recycle()
        return output
    }
    private fun dp(value: Float) = value * density
}
