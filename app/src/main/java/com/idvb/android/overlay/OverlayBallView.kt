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
        fun onSearch(); fun onToggleGuide(); fun onNextFloor(); fun onCalibrate(); fun onClose()
        fun onMove(dx: Float, dy: Float)
        fun onMenuExpanded(expanded: Boolean)
    }
    var listener: Listener? = null
    private val eyeButton: TextView
    private val floorButton: TextView
    private val morePanel: LinearLayout

    var mapLocked: Boolean = false
        set(value) {
            field = value
            eyeButton.isEnabled = value; floorButton.isEnabled = value
            eyeButton.alpha = if (value) 1f else .32f; floorButton.alpha = if (value) 1f else .32f
        }
    var guideVisible: Boolean = false
        set(value) { field = value; eyeButton.text = if (value) "◉" else "👁" }
    var floorLabel: String = "--"
        set(value) { field = value; floorButton.text = value.ifBlank { "--" } }

    init {
        orientation = VERTICAL; gravity = Gravity.END
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(ball("🔍", "扫描当前屏幕") { listener?.onSearch() })
        eyeButton = ball("👁", "显示攻略地图") { listener?.onToggleGuide() }; row.addView(eyeButton)
        floorButton = ball("--", "切换楼层") { listener?.onNextFloor() }.apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        }; row.addView(floorButton)
        row.addView(ball("···", "更多选项") {
            morePanel.visibility = if (morePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            listener?.onMenuExpanded(morePanel.visibility == View.VISIBLE)
        })
        addView(row)
        morePanel = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.END; visibility = View.GONE; setPadding(0, dp(6), 0, 0)
            addView(menuItem("校准显示区域") { listener?.onCalibrate() })
            addView(menuItem("关闭悬浮窗") { listener?.onClose() })
        }
        addView(morePanel); mapLocked = false
    }

    private fun ball(label: String, description: String, click: () -> Unit) = TextView(context).apply {
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
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    downRawX = event.rawX; downRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                            kotlin.math.abs(event.rawY - downRawY) > touchSlop)) dragging = true
                    if (dragging) listener?.onMove(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX; lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) { view.performClick(); click() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        layoutParams = LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }; elevation = dp(6).toFloat()
    }

    private fun menuItem(label: String, click: () -> Unit) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(18), dp(11), dp(18), dp(11))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.argb(242, 31, 35, 38)); setStroke(dp(1), Color.argb(160, 112, 226, 157))
        }
        isClickable = true; setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
