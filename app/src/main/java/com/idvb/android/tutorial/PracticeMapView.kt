package com.idvb.android.tutorial

import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Screenshot-backed practice surface. All coordinates are image-relative, not screen-relative. */
class PracticeMapView(context: Context, private val store: TutorialStore) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageBounds = RectF()
    private var bitmap: Bitmap? = null
    private var asset = ""
    private var progress = store.state.value
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f
    private var dragging = false

    init { isFocusableInTouchMode = true; contentDescription = "模拟游戏画面；校准时拖动框选，小抄显示调整时拖动或双指缩放" }

    fun update(value: TutorialProgress) {
        progress = value
        val p = value.practice
        val next = if (p.mapOpen) { if (p.scene == "lobby") "calibration" else "map" }
            else when (p.scene) { "side" -> "side"; "game" -> "game"; else -> "lobby" }
        if (next != asset || bitmap == null) {
            val decoded = context.assets.open("tutorial/$next.webp").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            bitmap?.recycle(); bitmap = decoded; asset = next
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { bitmap?.recycle(); bitmap = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.rgb(12, 20, 28))
        val source = bitmap ?: run { canvas.restoreToCount(checkpoint); return }
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale; val h = source.height * scale
        imageBounds.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.alpha = 255; paint.style = Paint.Style.FILL
        canvas.drawBitmap(source, null, imageBounds, paint)
        val p = progress.practice
        if (p.visible) drawGuide(canvas, p)
        if (p.mode == "calibrate") {
            paint.color = Color.argb(85, 0, 0, 0); canvas.drawRect(imageBounds, paint)
            // A faint target teaches the full viewport, rather than a single visible room.
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            paint.color = Color.argb(180, 255, 255, 255)
            paint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            canvas.drawRect(screenRect(listOf(.325f, .17f, .84f, .82f)), paint)
            paint.pathEffect = null
            if (p.rect.size == 4) {
                paint.color = Color.rgb(111, 237, 164); paint.strokeWidth = 4f
                canvas.drawRect(screenRect(p.rect), paint)
            }
            paint.style = Paint.Style.FILL
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun screenRect(r: List<Float>) = RectF(imageBounds.left + r[0] * imageBounds.width(),
        imageBounds.top + r[1] * imageBounds.height(), imageBounds.left + r[2] * imageBounds.width(),
        imageBounds.top + r[3] * imageBounds.height())

    private fun drawGuide(canvas: Canvas, p: PracticeState) {
        canvas.save()
        canvas.translate(imageBounds.left + p.x * imageBounds.width(), imageBounds.top + p.y * imageBounds.height())
        canvas.scale(imageBounds.width(), imageBounds.height())
        canvas.scale(p.scale, p.scale, .46f, .4f)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb((p.opacity * 255).toInt(), 101, 255, 179)
        paint.strokeWidth = .004f
        val rooms = listOf(RectF(.35f,.23f,.385f,.33f), RectF(.385f,.355f,.415f,.43f),
            RectF(.432f,.38f,.47f,.48f), RectF(.479f,.355f,.525f,.465f),
            RectF(.432f,.505f,.477f,.579f), RectF(.542f,.48f,.588f,.555f))
        rooms.forEach { canvas.drawRect(it, paint) }
        canvas.drawLine(.45f,.25f,.45f,.51f,paint)
        canvas.drawLine(.40f,.40f,.40f,.55f,paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((p.opacity * 255).toInt(), 255, 215, 100)
        val marker = if (p.variant == 0) .45f else .50f
        canvas.drawCircle(marker,.40f,.012f,paint)
        canvas.drawCircle(.562f,if (p.variant == 0) .52f else .49f,.01f,paint)
        canvas.restore()
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = store.state.value.practice
        if (p.mode != "calibrate" && p.mode != "adjust") return false
        if (imageBounds.width() <= 0 || imageBounds.height() <= 0) return false
        val x = ((event.x - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val y = ((event.y - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageBounds.contains(event.x, event.y)) return false
                requestFocus(); parent.requestDisallowInterceptTouchEvent(true)
                startX = x; startY = y; lastX = x; lastY = y; dragging = true
                if (p.mode == "calibrate") store.update { it.copy(practice = it.practice.copy(rect = listOf(x,y,x,y))) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) pinchDistance = distance(event)
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (p.mode == "calibrate") store.update { it.copy(practice = it.practice.copy(
                    rect = listOf(min(startX,x),min(startY,y),max(startX,x),max(startY,y)))) }
                else if (event.pointerCount == 2) {
                    val d = distance(event)
                    if (pinchDistance > 0f) store.update { it.copy(practice = it.practice.copy(
                        scale = (it.practice.scale * d / pinchDistance).coerceIn(.5f,1.8f), changed = true)) }
                    pinchDistance = d
                } else if (pinchDistance == 0f) store.update { it.copy(practice = it.practice.copy(
                    x = (it.practice.x + x - lastX).coerceIn(-.25f,.25f),
                    y = (it.practice.y + y - lastY).coerceIn(-.2f,.2f), changed = true)) }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> { dragging = false; pinchDistance = 0f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; pinchDistance = 0f; parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun distance(e: MotionEvent) = hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1))
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (store.state.value.practice.mode == "adjust" && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) {
            store.update { it.copy(practice = it.practice.copy(opacity = (it.practice.opacity +
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) .05f else -.05f).coerceIn(.1f,1f), changed = true)) }
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
}
