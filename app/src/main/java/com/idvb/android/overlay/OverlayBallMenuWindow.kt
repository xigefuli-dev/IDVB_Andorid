package com.idvb.android.overlay

import android.content.Context
import android.view.View

/** Anchors the menu to the control row without resizing or relaying out its window. */
class OverlayBallMenuWindow(
    context: Context,
    private val controls: OverlayWindowManager,
    private val panel: View,
    private val screenSize: () -> Pair<Int, Int>,
) {
    private val window = OverlayWindowManager(context)
    private val preferredWidth = (184 * context.resources.displayMetrics.density).toInt()

    fun show() {
        updatePosition()
        if (!window.isAdded()) window.add(panel, locked = false)
    }

    fun hide() = window.remove()

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
    }
}
