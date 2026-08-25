package com.idvb.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 全屏蓝图模式：遮罩、吞掉全部触摸，并让用户框选地图显示区域。 */
class BlueprintCalibrationView(context: Context) : View(context) {
    interface Listener {
        fun onConfirmed(region: RectF)
        fun onCancelled()
    }

    var listener: Listener? = null
    private val mask = Paint().apply { color = Color.argb(165, 55, 58, 62) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 226, 157); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 31, 35, 38) }
    private val confirmButton = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 132, 83) }
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var selecting = false
    private var selection: RectF? = null

    private val cancelRect get() = RectF(dp(20f), height - dp(66f), dp(112f), height - dp(18f))
    private val resetRect get() = RectF(width / 2f - dp(46f), height - dp(66f), width / 2f + dp(46f), height - dp(18f))
    private val confirmRect get() = RectF(width - dp(112f), height - dp(66f), width - dp(20f), height - dp(18f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        val headerCenterX = width * 0.25f
        text.textSize = dp(18f); canvas.drawText("蓝图模式 · 框选地图显示区域", headerCenterX, dp(42f), text)
        text.textSize = dp(13f); canvas.drawText("触摸不会传递到其他应用", headerCenterX, dp(66f), text)
        selection?.let {
            // 框内减弱滤镜，方便精确对齐底层游戏画面。
            canvas.save()
            canvas.clipRect(it)
            canvas.drawColor(Color.argb(95, 55, 58, 62))
            canvas.restore()
            canvas.drawRect(it, border)
            text.textSize = dp(12f)
            canvas.drawText("${it.width().toInt()} × ${it.height().toInt()} px", it.centerX(), (it.top - dp(8f)).coerceAtLeast(dp(84f)), text)
        }
        drawButton(canvas, cancelRect, "取消", button)
        drawButton(canvas, resetRect, "重选", button)
        drawButton(canvas, confirmRect, "确认", if (validSelection()) confirmButton else button)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cancelRect.contains(event.x, event.y) || resetRect.contains(event.x, event.y) || confirmRect.contains(event.x, event.y)) return true
                startX = event.x; startY = event.y; currentX = event.x; currentY = event.y; selecting = true
                selection = makeRect(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (selecting) {
                currentX = event.x.coerceIn(0f, width.toFloat())
                currentY = event.y.coerceIn(0f, height.toFloat())
                selection = makeRect(); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (selecting) {
                    selecting = false; currentX = event.x.coerceIn(0f, width.toFloat()); currentY = event.y.coerceIn(0f, height.toFloat())
                    selection = makeRect(); invalidate()
                } else when {
                    cancelRect.contains(event.x, event.y) -> listener?.onCancelled()
                    resetRect.contains(event.x, event.y) -> { selection = null; invalidate() }
                    confirmRect.contains(event.x, event.y) && validSelection() -> selection?.let { listener?.onConfirmed(RectF(it)) }
                }
            }
        }
        return true
    }

    private fun makeRect() = RectF(min(startX, currentX), min(startY, currentY), max(startX, currentX), max(startY, currentY))
    private fun validSelection() = selection?.let { it.width() >= dp(40f) && it.height() >= dp(40f) } == true
    private fun drawButton(canvas: Canvas, rect: RectF, label: String, paint: Paint) {
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        text.textSize = dp(14f); canvas.drawText(label, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
