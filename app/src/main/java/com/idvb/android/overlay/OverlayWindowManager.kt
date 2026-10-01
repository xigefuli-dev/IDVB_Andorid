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

        private fun refreshTouchOpacity() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val passive = attached.filter { it.isTouchThrough }
            if (passive.isEmpty()) return
            val maximum = passive.minOf { it.maximumTouchOpacity }
            val cap = OverlayTouchOpacity.cap(maximum, passive.size)
            passive.forEach { manager -> manager.applyAlpha(minOf(manager.opacity, cap)) }
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
        }
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = this@OverlayWindowManager.x
            this.y = this@OverlayWindowManager.y
            alpha = opacity
        }
    }

    fun add(v: View, locked: Boolean, focusable: Boolean = false) {
        remove()
        isTouchThrough = locked
        attached.add(this)
        // Lower existing layers before attaching the new layer.
        refreshTouchOpacity()
        val params = buildParams(locked, focusable).apply {
            if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alpha = minOf(opacity, OverlayTouchOpacity.cap(
                attached.filter { it.isTouchThrough }.minOf { it.maximumTouchOpacity },
                attached.count { it.isTouchThrough },
            ))
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
        p.width = width
        p.height = height
        if (locked != null) {
            isTouchThrough = locked
            p.flags = if (locked) {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
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
