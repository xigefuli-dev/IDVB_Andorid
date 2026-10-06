package com.idvb.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗窗口管理器（对齐参考项目 MapOverlayNativeWindow 的窗口管理职责）。
 *
 * 对应 Win32 分层窗口的标志位映射：
 * - WS_EX_LAYERED      → TYPE_APPLICATION_OVERLAY（透明分层窗口）
 * - WS_EX_NOACTIVATE   → FLAG_NOT_FOCUSABLE（不抢游戏焦点）
 * - WS_EX_TRANSPARENT  → FLAG_NOT_TOUCHABLE（锁定态点击穿透到游戏）
 * - WS_EX_TOOLWINDOW   → FLAG_NOT_FOCUSABLE + 前台服务常驻（不占任务栏/最近任务）
 */
class OverlayWindowManager(private val context: Context) {

    companion object {
        // All callers run on the main thread. Include hidden windows conservatively so
        // visibility changes/animations never temporarily exceed the shared UID budget.
        private val attached = mutableSetOf<OverlayWindowManager>()

        /** Conservative bounds of visible independent overlay roots, in screen coordinates. */
        fun visibleScreenBounds(excluding: Set<OverlayWindowManager> = emptySet()): List<android.graphics.RectF> =
            attached.filter { it !in excluding && it.view?.let { view -> view.isShown && view.alpha > 0f } == true }
                .map { manager ->
                    val root = requireNotNull(manager.view)
                    val location = IntArray(2).also(root::getLocationOnScreen)
                    val w = root.width.takeIf { it > 0 } ?: manager.width.takeIf { it > 0 }
                        ?: manager.context.resources.displayMetrics.widthPixels
                    val h = root.height.takeIf { it > 0 } ?: manager.height.takeIf { it > 0 }
                        ?: manager.context.resources.displayMetrics.heightPixels
                    android.graphics.RectF(location[0].toFloat(), location[1].toFloat(),
                        (location[0] + w).toFloat(), (location[1] + h).toFloat())
                }

        private fun refreshTouchOpacity() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            touchOpacityAssignments().forEach { (manager, alpha) -> manager.applyAlpha(alpha) }
        }

        private fun touchOpacityAssignments(): List<Pair<OverlayWindowManager, Float>> {
            val passive = attached.filter { it.isTouchThrough }
            if (passive.isEmpty()) return emptyList()
            val alphas = OverlayTouchOpacity.distribute(passive.minOf { it.maximumTouchOpacity }, passive.map { it.opacity })
            return passive.zip(alphas)
        }
    }

    private val maximumTouchOpacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
    } else 1f
    private var isTouchThrough = false

    /** Visual opacity belongs on LayoutParams, so Android's input dispatcher sees it. */
    var opacity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isTouchThrough && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                refreshTouchOpacity()
            } else applyAlpha(field)
        }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 窗口左上角（相对屏幕，gravity=TOP|START） */
    var x = 0
    var y = 0

    var gravity: Int = Gravity.TOP or Gravity.START

    /** 窗口尺寸（像素） */
    var width = 0
    var height = 0

    private var view: View? = null

    fun isAdded(): Boolean = view != null

    private fun buildParams(locked: Boolean, focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (locked) {
            // 锁定态整窗点击穿透，对应 WS_EX_TRANSPARENT
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            // 非锁定态开启外部触摸监听，用于接收象限点击事件同时保持游戏触控完全穿透
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = this@OverlayWindowManager.gravity
            this.x = this@OverlayWindowManager.x
            this.y = this@OverlayWindowManager.y
            alpha = opacity
        }
    }

    private class SurfaceAccessibilityDelegate(private val previous: View.AccessibilityDelegate?) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityEvent(host: View, event: android.view.accessibility.AccessibilityEvent) {
            if (previous != null) previous.onInitializeAccessibilityEvent(host, event)
            else super.onInitializeAccessibilityEvent(host, event)
            event.className = "com.idvb.android.overlay.Surface"
        }
    }

    fun add(v: View, locked: Boolean, focusable: Boolean = false) {
        if (v.accessibilityDelegate !is SurfaceAccessibilityDelegate)
            v.accessibilityDelegate = SurfaceAccessibilityDelegate(v.accessibilityDelegate)
        remove()
        isTouchThrough = locked
        attached.add(this)
        // Lower existing layers before attaching the new layer.
        refreshTouchOpacity()
        val params = buildParams(locked, focusable).apply {
            if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                alpha = touchOpacityAssignments().first { it.first === this@OverlayWindowManager }.second
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (error: RuntimeException) {
            attached.remove(this)
            refreshTouchOpacity()
            Log.e("OverlayWindowManager", "Unable to add overlay", error)
            throw error
        }
    }

    /** 应用当前 x/y/宽高；locked 传值则同步切换点击穿透标志 */
    fun update(locked: Boolean? = null) {
        val v = view ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.x = x
        p.y = y
        p.gravity = gravity
        p.width = width
        p.height = height
        if (locked != null) {
            isTouchThrough = locked
            p.flags = if (locked) {
                (p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            } else {
                (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            }
        }
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        if (!isTouchThrough || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) p.alpha = opacity
        if (isTouchThrough) refreshTouchOpacity()
        wm.updateViewLayout(v, p)
        refreshTouchOpacity()
    }

    private fun applyAlpha(alpha: Float) {
        val v = view ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.alpha == alpha) return
        params.alpha = alpha
        wm.updateViewLayout(v, params)
    }

    fun remove() {
        view?.let { wm.removeViewImmediate(it) }
        view = null
        attached.remove(this)
        refreshTouchOpacity()
    }
}
