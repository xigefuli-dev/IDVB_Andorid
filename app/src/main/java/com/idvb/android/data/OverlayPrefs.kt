package com.idvb.android.data

import android.content.Context
import android.os.Build

enum class MapIdentitySource { STRUCTURE_VERIFIED, MANUAL_UNVERIFIED }
enum class ScreenCaptureMethod { MEDIA_PROJECTION, ACCESSIBILITY }

/**
 * 悬浮窗状态持久化（对齐参考项目 Overlay 显示配置）。
 * 位置/尺寸使用像素，保存时按屏幕尺寸归一化，避免不同分辨率下错位。
 */
class OverlayPrefs(context: Context) {

    var skipBatteryOptimization: Boolean
        get() = prefs.getBoolean("skip_battery_optimization", false)
        set(value) = prefs.edit().putBoolean("skip_battery_optimization", value).apply()

    var hideBatterySkipDialog: Boolean
        get() = prefs.getBoolean("hide_battery_skip_dialog", false)
        set(value) = prefs.edit().putBoolean("hide_battery_skip_dialog", value).apply()

    fun saveBatterySkipOptions(permanent: Boolean, hideDialog: Boolean): Boolean = prefs.edit()
        .putBoolean("skip_battery_optimization", permanent)
        .putBoolean("hide_battery_skip_dialog", hideDialog)
        .commit()

    val completedFeatureGuides: Set<String>
        get() = prefs.getStringSet("completed_feature_guides", emptySet()).orEmpty().toSet()

    /** Save settings and acknowledgement in one transaction, only after an explicit choice. */
    @Synchronized
    fun completeFeatureGuide(guideId: String, choiceId: String): Boolean {
        val guide = com.idvb.android.onboarding.FeatureGuideRegistry.entries.single { it.id == guideId }
        val choice = guide.choices.single { it.id == choiceId }
        require(choice.captureMethod != ScreenCaptureMethod.ACCESSIBILITY || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Accessibility screen capture requires Android 11 or later"
        }
        val previous = completedFeatureGuides
        val editor = prefs.edit()
        choice.scanPreset?.let { preset ->
            editor.putString("search_button_action", SearchButtonAction.SCAN_MAP.name)
                .putString("eye_button_action", preset.eyeAction.name)
                .putBoolean("auto_detect_map_open_enabled", preset.autoDetect)
            if (preset == com.idvb.android.onboarding.ScanPreset.AUTOMATIC) {
                editor.putBoolean("show_alignment_output", true)
            }
        }
        choice.captureMethod?.let { method ->
            editor.putString("screen_capture_method", method.name)
        }
        if (editor.putStringSet("completed_feature_guides", previous + guideId).commit()) return true
        // A failed commit still changes the in-memory preferences; keep this guide pending.
        prefs.edit().putStringSet("completed_feature_guides", previous).commit()
        return false
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("overlay", Context.MODE_PRIVATE)

    init {
        // One-time upgrade migration, including previously saved false values.
        // Keep subsequent user choices across launches and service restarts.
        if (!prefs.getBoolean("scan_defaults_v2_applied", false)) {
            prefs.edit()
                .putBoolean("background_scan_enabled", DefaultSettings.BACKGROUND_SCAN)
                .putBoolean("show_unconfirmed_candidates", DefaultSettings.SHOW_UNCONFIRMED_CANDIDATES)
                .putBoolean("recognition_diagnostics_enabled", DefaultSettings.RECOGNITION_DIAGNOSTICS)
                .putBoolean("scan_defaults_v2_applied", true)
                .apply()
        }
    }

    companion object {
        /** 默认透明度，对齐参考项目 MapOpacity=0.46 */
        const val DEFAULT_OPACITY = DefaultSettings.OPACITY
        const val DEFAULT_W_RATIO = 0.6f
        const val DEFAULT_H_RATIO = 0.6f
    }

    var lastMapId: String?
        get() = prefs.getString("last_map_id", null)
        set(value) {
            val edit = prefs.edit().putString("last_map_id", value)
            if (value == null) edit.remove("last_map_identity_source")
            edit.apply()
        }

    /** 自动结构确认与人工选择必须跨服务重启保持不同语义。 */
    var lastMapIdentitySource: MapIdentitySource
        get() = prefs.getString("last_map_identity_source", null)
            ?.let { runCatching { MapIdentitySource.valueOf(it) }.getOrNull() }
            ?: MapIdentitySource.MANUAL_UNVERIFIED
        set(value) = prefs.edit().putString("last_map_identity_source", value.name).apply()

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
        get() = prefs.getBoolean("locked", DefaultSettings.LOCKED)
        set(value) = prefs.edit().putBoolean("locked", value).apply()

    /** 按住激活功能已取消注册，默认关闭且无法被开启。 */
    var holdToActivateEnabled: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = prefs.edit().putBoolean("hold_to_activate_enabled", false).apply()

    var searchButtonAction: SearchButtonAction
        get() = SearchButtonAction.fromStored(prefs.getString("search_button_action", null))
        set(value) = prefs.edit().putString("search_button_action", value.name).apply()

    var eyeButtonAction: EyeButtonAction
        get() = EyeButtonAction.fromStored(prefs.getString("eye_button_action", null))
        set(value) = prefs.edit().putString("eye_button_action", value.name).apply()

    /** 与 👁 的动作独立；缺少有效开图参照时保持停止，既有偏好不会被迁移覆盖。 */
    var autoDetectMapOpenEnabled: Boolean
        get() = prefs.getBoolean("auto_detect_map_open_enabled", DefaultSettings.AUTO_DETECT_MAP_OPEN)
        set(value) = prefs.edit().putBoolean("auto_detect_map_open_enabled", value).apply()

    /** The indicator selects the floor before alignment, without another calibration. */
    var autoFloorEnabled: Boolean
        get() = prefs.getBoolean("auto_floor_enabled", DefaultSettings.AUTO_FLOOR)
        set(value) = prefs.edit().putBoolean("auto_floor_enabled", value).apply()

    /** Stable registry ID, independent of the button action and open to new alignment methods. */
    var alignmentMethodId: String
        get() = prefs.getString("alignment_method_id", null)
            ?: com.idvb.android.alignment.AlignmentRegistry.DEFAULT_METHOD_ID
        set(value) = prefs.edit().putString("alignment_method_id", value).apply()

    /** Numeric alignment traces remain available; this controls additional replay images/masks. */
    var alignmentReplayInputsEnabled: Boolean
        get() = prefs.getBoolean("alignment_replay_inputs_enabled", DefaultSettings.ALIGNMENT_REPLAY_INPUTS)
        set(value) = prefs.edit().putBoolean("alignment_replay_inputs_enabled", value).apply()

    /** 开机自动恢复悬浮窗 */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_on_boot", DefaultSettings.AUTO_START_ON_BOOT)
        set(value) = prefs.edit().putBoolean("auto_start_on_boot", value).apply()

    /** 开发用候选界面模拟开关。 */
    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", DefaultSettings.DEBUG_MODE)
        set(value) = prefs.edit().putBoolean("debug_mode", value).apply()

    /** 扫描完成后暂存候选结果，等待用户点击悬浮窗的眼睛按钮再显示。 */
    var backgroundScanEnabled: Boolean
        get() = prefs.getBoolean("background_scan_enabled", DefaultSettings.BACKGROUND_SCAN)
        set(value) = prefs.edit().putBoolean("background_scan_enabled", value).apply()

    /** 放大镜直接打开按 IDVM 标签筛选的地图目录，不执行屏幕捕获。 */
    var manualMapSelectionEnabled: Boolean
        get() = prefs.getBoolean("manual_map_selection_enabled", DefaultSettings.MANUAL_MAP_SELECTION)
        set(value) = prefs.edit().putBoolean("manual_map_selection_enabled", value).apply()

    /** 未唯一确认时提供候选，后台扫描时等待点击眼睛；关闭则保留原地图并等待重新扫描。 */
    var showUnconfirmedCandidates: Boolean
        get() = prefs.getBoolean("show_unconfirmed_candidates", DefaultSettings.SHOW_UNCONFIRMED_CANDIDATES)
        set(value) = prefs.edit().putBoolean("show_unconfirmed_candidates", value).apply()

    /** 保存真实扫描输入与候选证据，供 Desktop/Android 差分验证。 */
    var recognitionDiagnosticsEnabled: Boolean
        get() = prefs.getBoolean("recognition_diagnostics_enabled", DefaultSettings.RECOGNITION_DIAGNOSTICS)
        set(value) = prefs.edit().putBoolean("recognition_diagnostics_enabled", value).apply()

    /** 打开后才会显示贴合的状态通知信息，默认关闭。 */
    var showAlignmentOutput: Boolean
        get() = prefs.getBoolean("show_alignment_output", DefaultSettings.SHOW_ALIGNMENT_OUTPUT)
        set(value) = prefs.edit().putBoolean("show_alignment_output", value).apply()

    /** 将攻略地图显示区域限制在屏幕范围内。 */
    var constrainGuideToScreen: Boolean
        get() = prefs.getBoolean("constrain_guide_to_screen", DefaultSettings.CONSTRAIN_GUIDE_TO_SCREEN)
        set(value) = prefs.edit().putBoolean("constrain_guide_to_screen", value).apply()

    /** 显示攻略地图时按 Class 标记或楼层主色移除背景。 */
    var removeGuideBackground: Boolean
        get() = prefs.getBoolean("remove_guide_background", DefaultSettings.REMOVE_GUIDE_BACKGROUND)
        set(value) = prefs.edit().putBoolean("remove_guide_background", value).apply()

    var showRoutes: Boolean
        get() = prefs.getBoolean("show_routes", DefaultSettings.SHOW_ROUTES)
        set(value) = prefs.edit().putBoolean("show_routes", value).apply()

    var routeLineThickness: Int
        get() = prefs.getInt("route_line_thickness", DefaultSettings.ROUTE_LINE_THICKNESS).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("route_line_thickness", value.coerceIn(0, 3)).apply()

    /** Preserve the selected source; upgrades never overwrite an explicit user choice. */
    var screenCaptureMethod: ScreenCaptureMethod
        get() = runCatching { ScreenCaptureMethod.valueOf(prefs.getString("screen_capture_method", null).orEmpty()) }
            .getOrDefault(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ScreenCaptureMethod.ACCESSIBILITY
                else ScreenCaptureMethod.MEDIA_PROJECTION)
        set(value) = prefs.edit().putString("screen_capture_method", value.name).apply()

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
