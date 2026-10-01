package com.idvb.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Normalized full-screen coordinates, stored separately for each orientation. */
class AssistTouchStore(context: Context) {
    private val prefs = context.getSharedPreferences("assist_touch", Context.MODE_PRIVATE)
    fun load(landscape: Boolean): List<Float> = prefs.getString(if (landscape) "landscape" else "portrait", null)
        ?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf(::validAssistPoints).orEmpty()
    fun save(landscape: Boolean, points: List<Float>) {
        require(points.isEmpty() || validAssistPoints(points))
        prefs.edit().putString(if (landscape) "landscape" else "portrait", points.joinToString(",")).apply()
    }
}

fun validAssistPoints(points: List<Float>) = points.size == 4 && points.all { it.isFinite() && it in 0f..1f }

/** Shared editor for the overlay and isolated tutorial; no images or external storage. */
class AssistTouchEditorView(
    context: Context,
    initial: List<Float>,
    private val guideTargets: List<PointF>,
    private val guideLabels: List<String> = emptyList(),
    private val confirm: (List<Float>) -> Unit,
) : View(context) {
    constructor(context: Context, initial: List<Float>, confirm: (List<Float>) -> Unit) :
        this(context, initial, emptyList(), emptyList(), confirm)
    constructor(context: Context, initial: List<Float>, guideTargets: List<PointF>, confirm: (List<Float>) -> Unit) :
        this(context, initial, guideTargets, emptyList(), confirm)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val points = arrayOfNulls<PointF>(2)
    private var dragging = -1
    private var previous: PointF? = null
    private var pressedAction = 0
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private val radius get() = 26f * density
    private val trayTop get() = height - 82f * density
    private fun home(i: Int) = PointF((44 + i * 72) * density, height - 40f * density)
    private fun center(i: Int) = points[i]?.let { PointF(it.x * width, it.y * height) } ?: home(i)
    private fun resetRect() = RectF(width - 184f * density, trayTop - 44f * density, width - 98f * density, trayTop - 6f * density)
    private fun confirmRect() = RectF(width - 92f * density, trayTop - 44f * density, width - 6f * density, trayTop - 6f * density)
    init {
        if (validAssistPoints(initial)) for (i in 0..1) points[i] = PointF(initial[i * 2], initial[i * 2 + 1])
        contentDescription = "辅助触控编辑：拖动打开和关闭到目标位置；底部右侧重置、确认"
        isFocusableInTouchMode = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0x22000000)
        if (guideTargets.isNotEmpty()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = 0xAA91DDA7.toInt()
            for (target in guideTargets) {
                val tx = target.x * width
                val ty = target.y * height
                canvas.drawCircle(tx, ty, radius * 1.5f, paint)
            }
            paint.style = Paint.Style.FILL
            for (idx in guideTargets.indices) {
                val target = guideTargets[idx]
                val label = guideLabels.getOrNull(idx) ?: "目标位置"
                val tx = target.x * width
                val ty = target.y * height
                text(canvas, label, tx, ty, 0xEEFFFFFF.toInt())
            }
        }
        if (dragging < 0) {
        paint.color = 0xED202824.toInt()
        canvas.drawRoundRect(RectF(0f, trayTop, width.toFloat(), height + 20f * density), 20f * density, 20f * density, paint)
        paint.color = Color.WHITE
        val barRight = minOf(width / 2f + 45f * density, resetRect().left - 12f * density)
        canvas.drawRoundRect(RectF(maxOf(12f * density, barRight - 90f * density), trayTop - 12f * density, barRight, trayTop - 8f * density), 4f, 4f, paint)
        for ((rect, label) in listOf(resetRect() to "重置", confirmRect() to "确认")) {
            paint.color = 0xED334A3B.toInt(); canvas.drawRoundRect(rect, 10f * density, 10f * density, paint)
            text(canvas, label, rect.centerX(), rect.centerY())
        }
        }
        for (i in 0..1) {
            if (dragging >= 0 && dragging != i && points[i] == null) continue
            val p = center(i)
            paint.color = 0xFF91DDA7.toInt(); canvas.drawCircle(p.x, p.y, radius, paint)
            paint.color = 0xFF15241D.toInt(); paint.strokeWidth = density
            canvas.drawLine(p.x - 5 * density, p.y, p.x + 5 * density, p.y, paint)
            canvas.drawLine(p.x, p.y - 5 * density, p.x, p.y + 5 * density, paint)
            text(canvas, if (i == 0) "打开" else "关闭", p.x, p.y + 14 * density, 0xFF15241D.toInt())
        }
    }
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, color: Int = Color.WHITE) {
        paint.color = color; paint.textSize = 14f * density; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                pressedAction = if (resetRect().contains(event.x, event.y)) 1 else if (confirmRect().contains(event.x, event.y)) 2 else 0
                dragging = if (pressedAction != 0) -1 else (1 downTo 0).firstOrNull { val p = center(it); kotlin.math.hypot(event.x - p.x, event.y - p.y) <= radius * 1.3f } ?: -1
                previous = points.getOrNull(dragging)?.let { PointF(it.x, it.y) }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                moved = moved || kotlin.math.hypot(event.x - downX, event.y - downY) > 3 * density
                points[dragging] = PointF((event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f)); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragging >= 0) {
                    // The tray disappears during dragging, so even bottom game controls remain reachable.
                    points[dragging] = if (!moved) previous else
                        PointF((event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f))
                } else if (pressedAction == 1 && resetRect().contains(event.x, event.y)) points.fill(null)
                else if (pressedAction == 2 && confirmRect().contains(event.x, event.y)) {
                    if (points.all { it != null }) confirm(points.flatMap { listOf(it!!.x, it.y) })
                    else if (points.all { it == null }) confirm(emptyList())
                    else android.widget.Toast.makeText(context, "请放置打开和关闭两个键，或重置后确认", android.widget.Toast.LENGTH_SHORT).show()
                }
                dragging = -1; pressedAction = 0; invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging >= 0) points[dragging] = previous; dragging = -1; pressedAction = 0; invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
