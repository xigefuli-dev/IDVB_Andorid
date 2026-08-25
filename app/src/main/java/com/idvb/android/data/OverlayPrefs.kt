package com.idvb.android.data

import android.content.Context

/**
 * 悬浮窗状态持久化（对齐参考项目 Overlay 显示配置）。
 * 位置/尺寸使用像素，保存时按屏幕尺寸归一化，避免不同分辨率下错位。
 */
class OverlayPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("overlay", Context.MODE_PRIVATE)

    companion object {
        /** 默认透明度，对齐参考项目 MapOpacity=0.46 */
        const val DEFAULT_OPACITY = 0.46f
        const val DEFAULT_W_RATIO = 0.6f
        const val DEFAULT_H_RATIO = 0.6f
    }

    var lastMapId: String?
        get() = prefs.getString("last_map_id", null)
        set(value) = prefs.edit().putString("last_map_id", value).apply()

    var lastFloorKey: String?
        get() = prefs.getString("last_floor_key", null)
        set(value) = prefs.edit().putString("last_floor_key", value).apply()

    /** 地图列表当前展示的关卡模式，也是扫描算法唯一允许使用的模式。 */
    var selectedMapClassId: String?
        get() = prefs.getString("selected_map_class_id", null)
        set(value) = prefs.edit().putString("selected_map_class_id", value).apply()

    var opacity: Float
        get() = prefs.getFloat("opacity", DEFAULT_OPACITY)
        set(value) = prefs.edit().putFloat("opacity", value.coerceIn(0.1f, 1f)).apply()

    var locked: Boolean
        get() = prefs.getBoolean("locked", false)
        set(value) = prefs.edit().putBoolean("locked", value).apply()

    /** 开机自动恢复悬浮窗 */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_on_boot", false)
        set(value) = prefs.edit().putBoolean("auto_start_on_boot", value).apply()

    /** 开发用候选界面模拟开关。 */
    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(value) = prefs.edit().putBoolean("debug_mode", value).apply()

    /** 按当前方向保存蓝图校准区域，值为 0..1 的屏幕比例。 */
    fun setCaptureRegion(landscape: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        val prefix = if (landscape) "capture_landscape" else "capture_portrait"
        prefs.edit()
            .putFloat("${prefix}_left", left.coerceIn(0f, 1f))
            .putFloat("${prefix}_top", top.coerceIn(0f, 1f))
            .putFloat("${prefix}_right", right.coerceIn(0f, 1f))
            .putFloat("${prefix}_bottom", bottom.coerceIn(0f, 1f))
            .apply()
    }

    fun captureRegion(landscape: Boolean): FloatArray? {
        val prefix = if (landscape) "capture_landscape" else "capture_portrait"
        val left = prefs.getFloat("${prefix}_left", Float.NaN)
        val top = prefs.getFloat("${prefix}_top", Float.NaN)
        val right = prefs.getFloat("${prefix}_right", Float.NaN)
        val bottom = prefs.getFloat("${prefix}_bottom", Float.NaN)
        return if (listOf(left, top, right, bottom).any { it.isNaN() }) null else floatArrayOf(left, top, right, bottom)
    }

    /** 自由调整后的攻略图显示矩形；允许轻微越过屏幕边缘。 */
    fun setGuideRegion(landscape: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        val prefix = if (landscape) "guide_landscape" else "guide_portrait"
        prefs.edit()
            .putFloat("${prefix}_left", left.coerceIn(-2f, 3f))
            .putFloat("${prefix}_top", top.coerceIn(-2f, 3f))
            .putFloat("${prefix}_right", right.coerceIn(-2f, 3f))
            .putFloat("${prefix}_bottom", bottom.coerceIn(-2f, 3f))
            .apply()
    }

    fun guideRegion(landscape: Boolean): FloatArray? {
        val prefix = if (landscape) "guide_landscape" else "guide_portrait"
        val left = prefs.getFloat("${prefix}_left", Float.NaN)
        val top = prefs.getFloat("${prefix}_top", Float.NaN)
        val right = prefs.getFloat("${prefix}_right", Float.NaN)
        val bottom = prefs.getFloat("${prefix}_bottom", Float.NaN)
        return if (listOf(left, top, right, bottom).any { it.isNaN() } || right <= left || bottom <= top) {
            null
        } else floatArrayOf(left, top, right, bottom)
    }

    fun clearGuideRegion(landscape: Boolean) {
        val prefix = if (landscape) "guide_landscape" else "guide_portrait"
        prefs.edit()
            .remove("${prefix}_left").remove("${prefix}_top")
            .remove("${prefix}_right").remove("${prefix}_bottom")
            .apply()
    }

    // ---- 位置/尺寸：按屏幕比例保存 ----

    /** x/屏幕宽 与 y/屏幕高（Float），未设置返回 null */
    fun positionRatio(): Pair<Float, Float>? {
        val x = prefs.getFloat("pos_x_ratio", Float.NaN)
        val y = prefs.getFloat("pos_y_ratio", Float.NaN)
        return if (x.isNaN() || y.isNaN()) null else x to y
    }

    fun setPositionRatio(x: Float, y: Float) {
        prefs.edit().putFloat("pos_x_ratio", x).putFloat("pos_y_ratio", y).apply()
    }

    /** 窗口宽/屏幕宽 与 高/屏幕高 */
    fun sizeRatio(): Pair<Float, Float>? {
        val w = prefs.getFloat("size_w_ratio", Float.NaN)
        val h = prefs.getFloat("size_h_ratio", Float.NaN)
        return if (w.isNaN() || h.isNaN()) null else w to h
    }

    fun setSizeRatio(w: Float, h: Float) {
        prefs.edit().putFloat("size_w_ratio", w).putFloat("size_h_ratio", h).apply()
    }
}
