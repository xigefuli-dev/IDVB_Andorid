package com.idvb.android.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** Small independent windows leave the space between controls touchable by the underlying app. */
class OverlayButtonLayout(
    private val context: Context,
    private val balls: OverlayBallView,
    private val anchor: OverlayWindowManager,
    private val screenSize: () -> Pair<Int, Int>,
) {
    private val prefs = context.getSharedPreferences("overlay_button_layout", Context.MODE_PRIVATE)
    private val windows = linkedMapOf<String, OverlayWindowManager>()
    private val scales = mutableMapOf<String, Float>()
    private var selected: String? = null
    private fun buttonSize(id: String) = (size * (scales[id] ?: 1f)).toInt()
    private val positions = linkedMapOf<String, Pair<Float, Float>>()
    private var actionPanel: View? = null
    private val editor = OverlayWindowManager(context)
    private var editorView: View? = null
    private val actions = OverlayWindowManager(context)
    private val size get() = (48 * context.resources.displayMetrics.density).toInt()
    var editing = false
        private set
    val separated get() = windows.isNotEmpty()

    init {
        balls.customLayout = this
        if (prefs.getBoolean("custom", false)) {
            split()
            balls.buttons.keys.forEach { id ->
                scales[id] = prefs.getFloat("${id}_scale", 1f).coerceIn(.5f, 2f)
                positions[id] = prefs.getFloat("${id}_x", .5f) to prefs.getFloat("${id}_y", .3f)
            }
            refresh()
        }
    }

    private fun split() {
        if (separated) return
        balls.closeMenu()
        val locations = balls.buttons.mapValues { (_, button) ->
            IntArray(2).also { button.getLocationOnScreen(it) }
        }
        val moreLocation = IntArray(2).also { balls.moreButton.getLocationOnScreen(it) }
        balls.buttons.forEach { (id, button) ->
            (button.parent as ViewGroup).removeView(button)
            val window = OverlayWindowManager(context)
            window.width = size; window.height = size
            window.x = locations.getValue(id)[0]; window.y = locations.getValue(id)[1]
            // A currently unavailable variant still receives an editable default position.
            if (button.visibility == View.GONE) { window.x = anchor.x; window.y = anchor.y + size }
            val holder = LinearLayout(context).apply {
                isMotionEventSplittingEnabled = true
                addView(button, LinearLayout.LayoutParams(size, size))
            }
            window.add(holder, locked = false)
            windows[id] = window
            remember(id)
        }
        anchor.x = moreLocation[0]; anchor.y = moreLocation[1]
        anchor.width = size + (4 * context.resources.displayMetrics.density).toInt()
        anchor.update()
        refresh()
    }

    fun begin(id: String) {
        if (editing || balls.visibility != View.VISIBLE) return
        balls.closeMenu()
        split()
        editing = true
        selected = id
        showEditor()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(31, 35, 38))
            addView(Button(context).apply { text = "确认"; setOnClickListener { finish(reset = false) } }, LinearLayout.LayoutParams(-1, size))
            addView(Button(context).apply { text = "重置"; setOnClickListener { finish(reset = true) } }, LinearLayout.LayoutParams(-1, size))
        }
        actions.width = (120 * context.resources.displayMetrics.density).toInt()
        actions.height = size * 2
        positionActions()
        actionPanel = panel
        actions.add(panel, locked = false)
        refresh()
    }

    private fun finish(reset: Boolean) {
        editing = false
        selected = null
        editor.remove(); editorView = null
        actions.remove()
        actionPanel = null
        if (reset) {
            prefs.edit().clear().apply()
            windows.forEach { (id, window) ->
                val button = balls.buttons.getValue(id)
                (button.parent as ViewGroup).removeView(button)
                window.remove()
            }
            windows.clear(); positions.clear(); scales.clear()
            balls.buttons.forEach { (id, button) ->
                button.textSize = if (id == "floor") 12f else 18f
                highlight(id, false)
            }
            balls.restoreRow()
            anchor.width = ((if (balls.variantsAvailable) 264 else 212) * context.resources.displayMetrics.density).toInt()
            val (w, h) = screenSize()
            anchor.x = anchor.x.coerceIn(0, (w - anchor.width).coerceAtLeast(0))
            anchor.y = anchor.y.coerceIn(0, (h - anchor.height).coerceAtLeast(0))
            anchor.update()
        } else {
            val edit = prefs.edit().putBoolean("custom", true)
            positions.forEach { (id, p) -> edit.putFloat("${id}_x", p.first).putFloat("${id}_y", p.second).putFloat("${id}_scale", scales[id] ?: 1f) }
            edit.apply()
        }
        refresh()
    }

    fun move(id: String, dx: Float, dy: Float) {
        if (!editing) return
        val window = windows[id] ?: return
        val (w, h) = screenSize()
        val size = buttonSize(id)
        window.x = (window.x + dx.toInt()).coerceIn(0, (w - size).coerceAtLeast(0))
        window.y = (window.y + dy.toInt()).coerceIn(0, (h - size).coerceAtLeast(0))
        window.update()
        remember(id)
    }

    private fun remember(id: String) {
        val window = windows.getValue(id)
        val (w, h) = screenSize()
        val size = buttonSize(id)
        positions[id] = window.x.toFloat() / (w - size).coerceAtLeast(1) to
            window.y.toFloat() / (h - size).coerceAtLeast(1)
    }

    private fun positionActions() {
        val (w, h) = screenSize()
        actions.x = (w - actions.width) / 2
        // The confirm button itself is at the screen center; reset sits below it.
        actions.y = h / 2 - size / 2
        actions.update()
    }

    fun refresh() {
        val (w, h) = screenSize()
        windows.forEach { (id, window) ->
            val size = buttonSize(id)
            window.width = size; window.height = size
            val p = positions.getValue(id)
            window.x = (p.first.coerceIn(0f, 1f) * (w - size).coerceAtLeast(0)).toInt()
            window.y = (p.second.coerceIn(0f, 1f) * (h - size).coerceAtLeast(0)).toInt()
            val button = balls.buttons.getValue(id)
            button.layoutParams = LinearLayout.LayoutParams(size, size)
            button.textSize = (if (id == "floor") 12f else 18f) * (scales[id] ?: 1f)
            highlight(id, editing && selected == id)
            button.visibility = if (balls.visibility != View.VISIBLE) View.INVISIBLE
                else if (id == "variant" && !balls.variantsAvailable && !editing) View.INVISIBLE else View.VISIBLE
            (button.parent as View).visibility = button.visibility
            window.update()
        }
        editorView?.visibility = balls.visibility
        if (editing) { editor.width = w; editor.height = h; editor.update() }
        actionPanel?.visibility = balls.visibility
        if (editing) positionActions()
    }

    /** Capture both fingers anywhere on the editor, including outside a 50% sized button. */
    private fun showEditor() {
        var dragId: String? = null
        var lastX = 0f
        var lastY = 0f
        var multiple = false
        val detector = android.view.ScaleGestureDetector(context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    selected?.let { scale(it, detector.scaleFactor) }
                    return true
                }
            })
        val view = View(context).apply {
            contentDescription = "布局编辑：点选按钮，拖动移动，双指缩放"
            setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        multiple = false
                        lastX = event.rawX; lastY = event.rawY
                        dragId = windows.entries.lastOrNull { (_, window) ->
                            event.rawX >= window.x && event.rawX < window.x + window.width &&
                                event.rawY >= window.y && event.rawY < window.y + window.height
                        }?.key
                        dragId?.let { select(it) }
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> multiple = true
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!multiple && !detector.isInProgress) {
                            dragId?.let { move(it, event.rawX - lastX, event.rawY - lastY) }
                        }
                        lastX = event.rawX; lastY = event.rawY
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> dragId = null
                }
                true
            }
        }
        val (w, h) = screenSize()
        editor.width = w; editor.height = h
        editorView = view
        editor.add(view, locked = false)
    }

    fun select(id: String) {
        if (!editing) return
        selected = id
        refresh()
    }

    fun scale(id: String, factor: Float) {
        if (!editing || selected != id || !factor.isFinite()) return
        val window = windows[id] ?: return
        val oldSize = buttonSize(id)
        scales[id] = ((scales[id] ?: 1f) * factor).coerceIn(.5f, 2f)
        val newSize = buttonSize(id)
        val (w, h) = screenSize()
        window.x = (window.x + (oldSize - newSize) / 2).coerceIn(0, (w - newSize).coerceAtLeast(0))
        window.y = (window.y + (oldSize - newSize) / 2).coerceIn(0, (h - newSize).coerceAtLeast(0))
        remember(id)
        refresh()
    }

    private fun highlight(id: String, selected: Boolean) {
        val density = context.resources.displayMetrics.density
        (balls.buttons.getValue(id).background as? android.graphics.drawable.GradientDrawable)?.setStroke(
            ((if (selected) 3 else 1) * density).toInt(),
            if (selected) Color.YELLOW else Color.argb(190, 112, 226, 157),
        )
    }

    fun dispose() {
        editor.remove(); editorView = null
        actions.remove()
        actionPanel = null
        windows.values.forEach { it.remove() }
        balls.customLayout = null
    }
}

