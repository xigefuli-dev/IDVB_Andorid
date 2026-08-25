package com.idvb.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Blocking blueprint used to position, scale and fade the currently displayed guide image. */
class BlueprintImageAdjustView(
    context: Context,
    private val bitmap: Bitmap,
    initialRegion: RectF,
    private val resetRegion: RectF,
    initialOpacity: Float,
) : View(context) {
    interface Listener {
        fun onConfirmed(region: RectF, opacity: Float)
    }

    var listener: Listener? = null
    private val mask = Paint().apply { color = Color.argb(58, 66, 69, 73) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (initialOpacity.coerceIn(.1f, 1f) * 255).toInt()
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 226, 157)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val normalButton = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 31, 35, 38) }
    private val confirmButton = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 132, 83) }

    private var imageRegion = RectF(initialRegion)
    private var opacity = initialOpacity.coerceIn(.1f, 1f)
    private var gestureStartRegion = RectF(imageRegion)
    private var startX = 0f
    private var startY = 0f
    private var pinchDistance = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinching = false

    private val confirmRect get() = RectF(width - dp(104f), dp(18f), width - dp(18f), dp(64f))
    private val resetRect get() = RectF(width - dp(198f), dp(18f), width - dp(112f), dp(64f))

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        val destination = fittedBitmapRect(imageRegion)
        bitmapPaint.alpha = (opacity * 255).toInt()
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        canvas.drawRect(destination, border)
        drawButton(canvas, resetRect, "重置", normalButton)
        drawButton(canvas, confirmRect, "确认", confirmButton)
        text.textSize = dp(12f)
        canvas.drawText(
            "单指拖动图片，双指进行缩放，音量键调整不透明度",
            width / 2f,
            height - dp(24f),
            text,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                if (resetRect.contains(event.x, event.y) || confirmRect.contains(event.x, event.y)) return true
                gestureStartRegion = RectF(imageRegion)
                startX = event.x
                startY = event.y
                pinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                gestureStartRegion = RectF(imageRegion)
                pinchDistance = pointerDistance(event).coerceAtLeast(1f)
                pinchFocusX = (event.getX(0) + event.getX(1)) / 2f
                pinchFocusY = (event.getY(0) + event.getY(1)) / 2f
                pinching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val scale = (pointerDistance(event) / pinchDistance).coerceIn(.2f, 5f)
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    val centerX = gestureStartRegion.centerX() + focusX - pinchFocusX
                    val centerY = gestureStartRegion.centerY() + focusY - pinchFocusY
                    val halfW = gestureStartRegion.width() * scale / 2f
                    val halfH = gestureStartRegion.height() * scale / 2f
                    imageRegion.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
                    constrainRegion()
                    invalidate()
                } else if (!pinching) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    imageRegion.set(gestureStartRegion)
                    imageRegion.offset(dx, dy)
                    constrainRegion()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指缩放结束后，把当前矩形和仍按住的手指设为新的拖动基准。
                // 否则下一次 ACTION_MOVE 会继续使用缩放前的基准，造成尺寸瞬间还原。
                gestureStartRegion = RectF(imageRegion)
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                startX = event.getX(remainingIndex)
                startY = event.getY(remainingIndex)
                pinching = false
            }
            MotionEvent.ACTION_UP -> when {
                resetRect.contains(event.x, event.y) -> {
                    imageRegion.set(resetRegion)
                    opacity = com.idvb.android.data.OverlayPrefs.DEFAULT_OPACITY
                    invalidate()
                }
                confirmRect.contains(event.x, event.y) -> listener?.onConfirmed(RectF(imageRegion), opacity)
            }
            MotionEvent.ACTION_CANCEL -> pinching = false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                opacity = (opacity + .05f).coerceAtMost(1f)
                invalidate()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                opacity = (opacity - .05f).coerceAtLeast(.1f)
                invalidate()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun fittedBitmapRect(container: RectF): RectF {
        val scale = min(container.width() / bitmap.width, container.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        return RectF(
            container.centerX() - width / 2f,
            container.centerY() - height / 2f,
            container.centerX() + width / 2f,
            container.centerY() + height / 2f,
        )
    }

    private fun constrainRegion() {
        val minimum = dp(48f)
        if (imageRegion.width() < minimum || imageRegion.height() < minimum) {
            val scale = max(minimum / imageRegion.width(), minimum / imageRegion.height())
            val cx = imageRegion.centerX()
            val cy = imageRegion.centerY()
            val halfW = imageRegion.width() * scale / 2f
            val halfH = imageRegion.height() * scale / 2f
            imageRegion.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        val visible = dp(36f)
        val dx = when {
            imageRegion.right < visible -> visible - imageRegion.right
            imageRegion.left > width - visible -> width - visible - imageRegion.left
            else -> 0f
        }
        val dy = when {
            imageRegion.bottom < visible -> visible - imageRegion.bottom
            imageRegion.top > height - visible -> height - visible - imageRegion.top
            else -> 0f
        }
        imageRegion.offset(dx, dy)
    }

    private fun pointerDistance(event: MotionEvent): Float = hypot(
        (event.getX(0) - event.getX(1)).toDouble(),
        (event.getY(0) - event.getY(1)).toDouble(),
    ).toFloat()

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, paint: Paint) {
        canvas.drawRoundRect(rect, dp(11f), dp(11f), paint)
        text.textSize = dp(14f)
        canvas.drawText(label, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
