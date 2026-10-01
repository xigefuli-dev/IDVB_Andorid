package com.idvb.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.idvb.android.idvm.ClassRecord

/** Anchors the menu to the control row without resizing or relaying out its window. */
class OverlayBallMenuWindow(
    private val context: Context,
    private val controls: OverlayWindowManager,
    private val panel: View,
    private val screenSize: () -> Pair<Int, Int>,
) {
    private val window = OverlayWindowManager(context)
    private val preferredWidth = dp(184)
    private val submenuWindow = OverlayWindowManager(context)
    private var submenuView: View? = null
    internal val submenuPanel: View? get() = submenuView

    fun isSubmenuVisible(): Boolean = submenuWindow.isAdded()

    fun toggleClasses(classes: List<ClassRecord>, selectedId: String?, onSelected: (ClassRecord) -> Unit) {
        if (isSubmenuVisible()) { hideSubmenu(); return }
        val items = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        classes.forEach { record ->
            items.addView(TextView(context).apply {
                text = if (record.id == selectedId) "✓  ${record.name}" else record.name
                contentDescription = "选择地图包：${record.name}"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (record.id == selectedId) Color.argb(255, 38, 78, 57)
                        else Color.argb(242, 31, 35, 38))
                    setStroke(dp(1), Color.argb(160, 112, 226, 157))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    hideSubmenu()
                    onSelected(record)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(items)
        }
        submenuView = scroll
        positionSubmenu(scroll)
        submenuWindow.add(scroll, locked = false)
    }

    fun hideSubmenu() {
        submenuWindow.remove()
        submenuView = null
    }

    fun show() {
        updatePosition()
        if (!window.isAdded()) window.add(panel, locked = false)
    }

    fun hide() {
        hideSubmenu()
        window.remove()
    }

    fun updatePosition() {
        val (screenWidth, screenHeight) = screenSize()
        window.width = preferredWidth.coerceAtMost(screenWidth)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        window.height = panel.measuredHeight
        window.x = (controls.x + controls.width - window.width)
            .coerceIn(0, (screenWidth - window.width).coerceAtLeast(0))
        val below = controls.y + controls.height
        window.y = if (below + window.height <= screenHeight) below else controls.y - window.height
        window.y = window.y.coerceIn(0, (screenHeight - window.height).coerceAtLeast(0))
        window.update()
        submenuView?.let(::positionSubmenu)
    }

    private fun positionSubmenu(view: View) {
        val (screenWidth, screenHeight) = screenSize()
        val leftSpace = window.x.coerceAtLeast(0)
        val rightSpace = (screenWidth - window.x - window.width).coerceAtLeast(0)
        val placeLeft = leftSpace >= rightSpace
        submenuWindow.width = minOf(dp(280), if (placeLeft) leftSpace else rightSpace).coerceAtLeast(1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(submenuWindow.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        submenuWindow.height = minOf(view.measuredHeight, (screenHeight - dp(16)).coerceAtLeast(1))
        submenuWindow.x = if (placeLeft) window.x - submenuWindow.width else window.x + window.width
        submenuWindow.y = (window.y + dp(6)).coerceIn(0,
            (screenHeight - submenuWindow.height).coerceAtLeast(0))
        submenuWindow.update()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
