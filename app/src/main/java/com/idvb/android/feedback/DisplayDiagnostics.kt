package com.idvb.android.feedback

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/** Read at log collection time so rotation and display-setting changes are reflected. */
internal object DisplayDiagnostics {
    @Suppress("DEPRECATION")
    fun describe(context: Context): String = buildString {
        val metrics = context.resources.displayMetrics
        appendLine("应用资源分辨率: ${metrics.widthPixels} x ${metrics.heightPixels} px")
        appendLine("逻辑 DPI (densityDpi): ${metrics.densityDpi}")
        appendLine("界面缩放倍率 (density): ${metrics.density}")
        appendLine("字体缩放倍率 (fontScale): ${context.resources.configuration.fontScale}")
        appendLine("系统报告的物理 DPI: X=${metrics.xdpi}, Y=${metrics.ydpi}（仅供参考）")

        // Keep mode pixels separate from app-visible bounds, which can differ under
        // resolution overrides, compatibility mode, rotation and multi-window.
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val mode = runCatching { display?.mode }.getOrNull()
        appendLine("默认显示器当前模式分辨率: ${mode?.let { "${it.physicalWidth} x ${it.physicalHeight} px" } ?: "不可用"}")
        val bounds = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = context.getSystemService(WindowManager::class.java)
                manager.maximumWindowMetrics.bounds.let { "${it.width()} x ${it.height()} px" }
            } else {
                display?.let {
                    val realMetrics = DisplayMetrics()
                    it.getRealMetrics(realMetrics)
                    "${realMetrics.widthPixels} x ${realMetrics.heightPixels} px"
                }
            }
        }.getOrNull()
        appendLine("当前方向下最大显示区域: ${bounds ?: "不可用"}")
    }
}
