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
        fun onResetMap() {}
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

    fun closeMenu() {
        if (!menuExpanded) return
        menuExpanded = false
        listener?.onMenuExpanded(false)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) closeMenu()
        customLayout?.refresh()
    }

    var mapLocked: Boolean = false
        set(value) {
            field = value
            updateEyePresentation()
            floorButton.isEnabled = true
            floorButton.alpha = if (value) 1f else .32f
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
        row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(ball("search", "🔍", "扫描当前屏幕") { listener?.onSearch() })
        eyeButton = ball("eye", "👁", "显示攻略地图") { if (mapLocked || candidatesAvailable) listener?.onToggleGuide() }; row.addView(eyeButton)
        floorButton = ball("floor", "--", "切换楼层") { if (mapLocked) listener?.onNextFloor() }.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        }; row.addView(floorButton)
        moreButton = ball("more", "···", "更多选项") {
            menuExpanded = !menuExpanded
            listener?.onMenuExpanded(menuExpanded)
        }; row.addView(moreButton)
        variantButton = ball("variant", "⇄", "切换地图变体") { listener?.onNextVariant() }.apply { visibility = View.GONE }; row.addView(variantButton)
        addView(row)
        morePanel = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0)
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
        eyeButton.alpha = if (enabled) 1f else .32f
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
        var held = false
        val hold = Runnable { held = true; customLayout?.begin(id) }
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
                if (event.pointerCount > 1) { multiTouch = true; removeCallbacks(hold) }
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    downRawX = event.rawX; downRawY = event.rawY
                    dragging = false; held = false; multiTouch = false
                    if (id != "more") postDelayed(hold, 2000L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                            kotlin.math.abs(event.rawY - downRawY) > touchSlop)) dragging = true
                    if (dragging && !multiTouch) {
                        removeCallbacks(hold)
                        val layout = customLayout
                        if (id != "more" && layout?.separated == true) layout.move(id, event.rawX - lastRawX, event.rawY - lastRawY)
                        else if (layout?.editing != true) listener?.onMove(event.rawX - lastRawX, event.rawY - lastRawY)
                    }
                    lastRawX = event.rawX; lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(hold)
                    if (!dragging && !held && !multiTouch && customLayout?.editing != true) { view.performClick(); click() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); true }
                else -> false
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { removeCallbacks(hold) }
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

    private fun menuItem(icon: String, label: String, click: () -> Unit) = TextView(context).apply {
        text = "$icon   $label"; contentDescription = label
        gravity = Gravity.CENTER_VERTICAL; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(dp(18), dp(11), dp(18), dp(11)); minWidth = dp(184)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.argb(242, 31, 35, 38)); setStroke(dp(1), Color.argb(160, 112, 226, 157))
        }
        isClickable = true; setOnClickListener {
            closeMenu()
            click()
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
