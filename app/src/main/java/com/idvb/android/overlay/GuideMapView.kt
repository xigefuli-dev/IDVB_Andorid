package com.idvb.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.ImageView

/** 新攻略图显示层，仅负责渲染；窗口本身由服务设置为完全点击穿透。 */
class GuideMapView(context: Context) : ImageView(context) {
    private var bitmap: Bitmap? = null
    private var alignedBounds: RectF? = null
    private var alignedViewport: RectF? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var nextDraw: ((Long) -> Unit)? = null

    fun afterNextAlignedDraw(callback: ((Long) -> Unit)?) { nextDraw = callback }
    init {
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = false
    }

    fun showBitmap(bitmap: Bitmap?) {
        this.bitmap = bitmap
        setImageBitmap(bitmap)
    }

    /** Draw directly in screen pixels; clipping must never resize or shift an accepted pose. */
    fun showAlignment(bounds: RectF, viewport: RectF) {
        alignedBounds = RectF(bounds)
        alignedViewport = RectF(viewport)
        invalidate()
    }

    fun clearAlignment() {
        nextDraw = null
        alignedBounds = null
        alignedViewport = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val started = System.nanoTime()
        val bounds = alignedBounds
        if (bounds == null) { super.onDraw(canvas); return }
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val saved = canvas.save()
        alignedViewport?.let { canvas.clipRect(it) }
        canvas.drawBitmap(image, null, bounds, paint)
        canvas.restoreToCount(saved)
        nextDraw?.let { callback -> nextDraw = null; callback(System.nanoTime() - started) }
    }
}
