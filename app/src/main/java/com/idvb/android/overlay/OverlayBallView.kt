package com.idvb.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView

/** 独立的小球控制窗；不依赖已废弃的整块地图悬浮层。 */
class OverlayBallView(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onAssistTouch() {}
        fun useAssistTouchToggle(): Boolean = false
        fun onConfigureAutoReference() {}
        fun onScreenTouch(x: Float, y: Float) {}
        fun onResetMap() {}
        fun onChooseMapClass() {}
        fun onOpenGuide() {}
        fun onCloseGuide() {}
        fun onSearch(); fun onToggleGuide(); fun onNextFloor(); fun onNextVariant(); fun onFreeAdjust(); fun onCalibrate(); fun onClose()
        fun onMove(dx: Float, dy: Float)
        fun onMenuExpanded(expanded: Boolean)
    }
    var listener: Listener? = null
    internal var customLayout: OverlayButtonLayout? = null
    internal val buttons = linkedMapOf<String, TextView>()
    internal lateinit var moreButton: TextView
    private lateinit var row: LinearLayout
    private val eyeButton: TextView
    private val floorButton: TextView
    private val variantButton: TextView
    val morePanel: LinearLayout
    private var menuExpanded = false
    private val operationPrefs = com.idvb.android.data.OverlayPrefs(context)
    private var eyeHeld = false

    private fun releaseEye() {
        if (!eyeHeld) return
        eyeHeld = false
        listener?.onCloseGuide()
    }
    private val captureAlphas = mutableMapOf<TextView, Float>()
    private var captureHidden = false

    internal fun captureControls(): List<Pair<String, View>> =
        buttons.map { (id, button) -> id to button } + ("more" to moreButton)

    private fun presentAlpha(button: TextView, value: Float) {
        if (captureHidden) {
            captureAlphas[button] = value
            button.alpha = 0f
        } else button.alpha = value
    }

    /** Hide capture-contaminating pixels while retaining hit targets for an immediate second tap. */
    fun setCaptureHidden(hidden: Boolean, captureBounds: android.graphics.Rect? = null) {
        if (hidden) closeMenu()
        captureHidden = hidden
        // Independent button windows may still be laying out or moving. Their old screen
        // coordinates cannot determine which surface will overlap the next captured frame.
        // All controls retain their hit targets while their pixels remain hidden.
        (buttons.values + moreButton).forEach { button ->
            if (hidden) {
                captureAlphas.putIfAbsent(button, button.alpha)
                button.alpha = 0f
            } else captureAlphas.remove(button)?.let { button.alpha = it }
        }
    }

    fun closeMenu() {
        if (!menuExpanded) return
        menuExpanded = false
        listener?.onMenuExpanded(false)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            releaseEye()
            closeMenu()
        }
        customLayout?.refresh()
    }

    var mapLocked: Boolean = false
        set(value) {
            field = value
            updateEyePresentation()
            floorButton.isEnabled = true
            presentAlpha(floorButton, if (value) 1f else .32f)
        }
    var candidatesAvailable: Boolean = false
        set(value) {
            field = value
            updateEyePresentation()
        }
    var identityVerified: Boolean? = null
        set(value) {
            field = value
            updateFloorPresentation()
        }
    var guideVisible: Boolean = false
        set(value) { field = value; eyeButton.text = if (value) "◉" else "👁" }
    var floorLabel: String = "--"
        set(value) {
            field = value
            updateFloorPresentation()
        }
    var variantsAvailable: Boolean = false
        set(value) {
            field = value
            variantButton.visibility = if (value) View.VISIBLE else View.GONE
            customLayout?.refresh()
        }

    init {
        orientation = VERTICAL; gravity = Gravity.END
        isMotionEventSplittingEnabled = true
        row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isMotionEventSplittingEnabled = true
        }
        row.addView(ball("search", "🔍", "扫描当前屏幕") { listener?.onSearch() })
        eyeButton = ball("eye", "👁", "显示攻略地图") { if (mapLocked || candidatesAvailable) listener?.onToggleGuide() }; row.addView(eyeButton)
        floorButton = ball("floor", "--", "切换楼层") { if (mapLocked) listener?.onNextFloor() }.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        }; row.addView(floorButton)
        moreButton = ball("more", "···", "更多选项") {
            menuExpanded = !menuExpanded
            listener?.onMenuExpanded(menuExpanded)
        }; row.addView(moreButton)
        variantButton = ball("variant", "⇆", "切换地图变体") { listener?.onNextVariant() }.apply { visibility = View.GONE }; row.addView(variantButton)
        addView(row)
        morePanel = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
            isMotionEventSplittingEnabled = true
            addView(menuItem("▤", "选择地图包", closeOnClick = false) { listener?.onChooseMapClass() })
            addView(menuItem("✥", "小抄显示调整") { listener?.onFreeAdjust() })
            addView(menuItem("⚙", "悬浮窗布局调整") { customLayout?.begin("search") })
            addView(menuItem("▣", "校准显示区域") { listener?.onCalibrate() })
            addView(menuItem("↺", "重设地图") { listener?.onResetMap() })
            addView(menuItem("⏻", "关闭悬浮窗") { listener?.onClose() })
        }
        // The menu has its own window; the control row never resizes when it opens.
        mapLocked = false
    }

    private fun updateEyePresentation() {
        val enabled = mapLocked || candidatesAvailable
        eyeButton.isEnabled = true
        presentAlpha(eyeButton, if (enabled) 1f else .32f)
        eyeButton.contentDescription = if (candidatesAvailable) "查看候选地图" else "显示攻略地图"
    }

    private fun updateFloorPresentation() {
        val label = floorLabel.ifBlank { "--" }
        floorButton.text = when (identityVerified) {
            true -> "✓$label"
            false -> "?$label"
            null -> label
        }
        floorButton.contentDescription = when (identityVerified) {
            true -> "结构已确认的地图，切换楼层"
            false -> "人工选择且结构未确认的地图，切换楼层"
            null -> "切换楼层"
        }
    }

    private fun ball(id: String, label: String, description: String, click: () -> Unit) = TextView(context).apply {
        text = label; contentDescription = description; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.argb(238, 31, 35, 38)); setStroke(dp(1), Color.argb(190, 112, 226, 157))
        }
        isClickable = true; isFocusable = true
        var lastRawX = 0f
        var lastRawY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var holdGesture = false
        var clickAvailableAtDown = false
        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var multiTouch = false
        val scaleDetector = android.view.ScaleGestureDetector(context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    customLayout?.scale(id, detector.scaleFactor)
                    return true
                }
            })
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { view, event ->
            if (customLayout?.editing == true && id != "more") {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) customLayout?.select(id)
                scaleDetector.onTouchEvent(event)
                if (event.pointerCount > 1) multiTouch = true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    downRawX = event.rawX; downRawY = event.rawY
                    dragging = false; multiTouch = false
                    activePointerId = event.getPointerId(0)
                    // A control that was unavailable at press time cannot activate when a scan finishes mid-gesture.
                    clickAvailableAtDown = when (id) {
                        "eye" -> mapLocked || candidatesAvailable
                        "floor" -> mapLocked
                        else -> true
                    }
                    // Latch the mode for this gesture, even if preferences change before release.
                    holdGesture = id == "eye" && operationPrefs.holdToActivateEnabled && customLayout?.editing != true && listener?.useAssistTouchToggle() != true
                    if (holdGesture && (mapLocked || candidatesAvailable)) {
                        eyeHeld = true
                        listener?.onOpenGuide()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> true
                MotionEvent.ACTION_MOVE -> {
                    // A held eye remains a display control, including when the finger drifts.
                    if (holdGesture) return@setOnTouchListener true
                    if (!dragging && (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                            kotlin.math.abs(event.rawY - downRawY) > touchSlop)) dragging = true
                    if (dragging && !multiTouch) {
                        val layout = customLayout
                        if (id != "more" && layout?.separated == true) layout.move(id, event.rawX - lastRawX, event.rawY - lastRawY)
                        else if (layout?.editing != true) listener?.onMove(event.rawX - lastRawX, event.rawY - lastRawY)
                    }
                    lastRawX = event.rawX; lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (holdGesture) releaseEye()
                    else if (clickAvailableAtDown && !dragging && !multiTouch && customLayout?.editing != true) { view.performClick(); click() }
                    holdGesture = false
                    clickAvailableAtDown = false
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (holdGesture && event.getPointerId(event.actionIndex) == activePointerId) {
                        releaseEye()
                        holdGesture = false
                    } else if (!holdGesture && event.getPointerId(event.actionIndex) == activePointerId) {
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        if (remainingIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(remainingIndex)
                            lastRawX = event.rawX + (event.getX(remainingIndex) - event.getX(0))
                            lastRawY = event.rawY + (event.getY(remainingIndex) - event.getY(0))
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (holdGesture) releaseEye()
                    holdGesture = false
                    clickAvailableAtDown = false
                    true
                }
                else -> false
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { if (id == "eye") releaseEye() }
        })
        if (id != "more") buttons[id] = this
        layoutParams = LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }; elevation = dp(6).toFloat()
    }

    internal fun restoreRow() {
        row.removeAllViews()
        listOf(buttons.getValue("search"), eyeButton, floorButton, moreButton, variantButton).forEach {
            row.addView(it, LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        }
        variantButton.visibility = if (variantsAvailable) View.VISIBLE else View.GONE
    }

    private fun menuItem(icon: String, label: String, closeOnClick: Boolean = true, click: () -> Unit) = TextView(context).apply {
        text = "$icon   $label"; contentDescription = label
        gravity = Gravity.CENTER_VERTICAL; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(dp(18), dp(11), dp(18), dp(11)); minWidth = dp(184)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.argb(242, 31, 35, 38)); setStroke(dp(1), Color.argb(160, 112, 226, 157))
        }
        isClickable = true; setOnClickListener {
            if (closeOnClick) closeMenu()
            click()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2).also(::getLocationOnScreen)
            val rx = if (event.rawX != 0f) event.rawX else (location[0] + event.x)
            val ry = if (event.rawY != 0f) event.rawY else (location[1] + event.y)
            listener?.onScreenTouch(rx, ry)
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Wait for every independent overlay surface changed by this request. */
internal class OverlayCaptureFrameBarrier(
    private val handler: android.os.Handler,
    private val views: List<Pair<String, View>>,
    private val current: () -> Boolean,
    private val log: com.idvb.android.alignment.AlignmentLogSink,
    private val completed: (Boolean) -> Unit,
) {
    private class Pending(val root: View, val observer: android.view.ViewTreeObserver) {
        var draw: android.view.ViewTreeObserver.OnDrawListener? = null
        var commit: Runnable? = null
        var detach: View.OnAttachStateChangeListener? = null
        var signal: Runnable? = null
        var queued = false
        var done = false
    }
    private val pending = mutableListOf<Pending>()
    @Volatile private var active = true
    private var releaseFrame: android.view.Choreographer.FrameCallback? = null
    private val started = System.nanoTime()

    fun start() {
        check(android.os.Looper.myLooper() == handler.looper)
        views.forEach { (id, view) ->
            val position = IntArray(2).also(view::getLocationOnScreen)
            record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-hidden-control", id,
                measurements = mapOf("x" to position[0].toDouble(), "y" to position[1].toDouble(),
                    "width" to view.width.toDouble(), "height" to view.height.toDouble(), "alpha" to view.alpha.toDouble(),
                    "visibility" to view.visibility.toDouble()),
                labels = mapOf("attached" to view.isAttachedToWindow.toString(),
                    "root" to System.identityHashCode(view.rootView).toString())))
        }
        views.map { it.second.rootView }.distinct().filter { it.isAttachedToWindow }.forEach { root ->
            pending += Pending(root, root.viewTreeObserver)
        }
        record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-render-barrier", "waiting-for-each-root",
            measurements = mapOf("roots" to pending.size.toDouble(), "controls" to views.size.toDouble())))
        if (pending.isEmpty()) { afterAllRoots(); return }
        pending.forEachIndexed { index, entry ->
            val root = entry.root
            fun signal(method: String) {
                if (!active) return
                val action = entry.signal ?: return
                record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-root-rendered", method,
                    measurements = mapOf("rootIndex" to index.toDouble()),
                    labels = mapOf("root" to System.identityHashCode(entry.root).toString())))
                handler.post(action)
            }
            entry.signal = Runnable {
                if (!active || entry.done) return@Runnable
                if (!current()) { finish(false); return@Runnable }
                entry.done = true
                remove(entry)
                if (pending.all { it.done }) afterAllRoots()
            }
            entry.detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (active) {
                        record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-root-detached", index.toString()))
                        finish(false)
                    }
                }
            }.also(root::addOnAttachStateChangeListener)
            if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
                entry.commit = Runnable { signal("hardware-frame-commit") }
                entry.observer.registerFrameCommitCallback(entry.commit!!)
            } else {
                entry.draw = android.view.ViewTreeObserver.OnDrawListener {
                    if (active && !entry.queued) {
                        entry.queued = true
                        signal("software-draw-next-ui-frame")
                    }
                }.also(entry.observer::addOnDrawListener)
            }
            // setVisibility/alpha already invalidated these previously shown roots. Request
            // traversal explicitly as well, including the transparent guide and notification roots.
            root.requestLayout()
            root.invalidate()
        }
    }

    private fun afterAllRoots() {
        if (!active) return
        // A draw callback precedes software buffer submission. Cross the next UI
        // frame only after every window has drawn or committed its own hidden state.
        val frame = android.view.Choreographer.FrameCallback {
            releaseFrame = null
            handler.post { if (active) finish(current()) }
        }
        releaseFrame = frame
        android.view.Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun remove(entry: Pending) {
        if (entry.observer.isAlive) {
            entry.draw?.let(entry.observer::removeOnDrawListener)
            if (android.os.Build.VERSION.SDK_INT >= 29) entry.commit?.let(entry.observer::unregisterFrameCommitCallback)
        }
        entry.detach?.let(entry.root::removeOnAttachStateChangeListener)
        entry.signal?.let(handler::removeCallbacks)
    }

    private fun finish(success: Boolean) {
        if (!active) return
        active = false
        cleanup()
        record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-render-complete",
            if (success) "all-roots-rendered" else "invalidated-before-capture",
            measurements = mapOf("roots" to pending.size.toDouble(), "renderedRoots" to pending.count { it.done }.toDouble()),
            durationNanos = System.nanoTime() - started))
        completed(success)
    }

    fun cancel() {
        if (!active) return
        active = false
        cleanup()
        record(com.idvb.android.alignment.AlignmentLogEvent("capture.overlay-render-cancelled",
            durationNanos = System.nanoTime() - started))
    }

    private fun cleanup() {
        releaseFrame?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
        releaseFrame = null
        pending.forEach(::remove)
    }

    private fun record(event: com.idvb.android.alignment.AlignmentLogEvent) {
        if (log.enabled) runCatching { log.record(event) }
    }
}
