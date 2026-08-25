package com.idvb.android.overlay

import android.content.Context
import android.graphics.PixelFormat
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

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 窗口左上角（相对屏幕，gravity=TOP|START） */
    var x = 0
    var y = 0

    /** 窗口尺寸（像素） */
    var width = 0
    var height = 0

    private var view: View? = null

    fun isAdded(): Boolean = view != null

    private fun buildParams(locked: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
        }
    }

    fun add(v: View, locked: Boolean) {
        remove()
        view = v
        runCatching { wm.addView(v, buildParams(locked)) }
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
            p.flags = if (locked) {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
