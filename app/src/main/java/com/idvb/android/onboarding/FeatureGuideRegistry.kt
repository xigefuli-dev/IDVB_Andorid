package com.idvb.android.onboarding

import com.idvb.android.data.EyeButtonAction
import com.idvb.android.data.ScreenCaptureMethod

object CaptureMethodDescriptions {
    const val MEDIA_PROJECTION = "识别速度更快，耗电量更大；使用悬浮窗期间无法录屏。"
    const val ACCESSIBILITY = "识别速度较慢，耗电量更低；不与录屏冲突。需要 Android 11 或更高版本。"
}

enum class ScanPreset(val eyeAction: EyeButtonAction, val autoDetect: Boolean) {
    TRADITIONAL(EyeButtonAction.SHOW_ONLY, false),
    AUTOMATIC(EyeButtonAction.SHOW_AND_ALIGN, true),
}

data class FeatureGuideChoice(
    val id: String,
    val title: String,
    val description: String,
    val scanPreset: ScanPreset? = null,
    val captureMethod: ScreenCaptureMethod? = null,
)

data class FeatureGuide(
    val id: String,
    val order: Int,
    val title: String,
    val description: String,
    val choices: List<FeatureGuideChoice>,
)

/** Register new guides here. Keep IDs stable across releases; order only controls presentation.
 * A changed ID deliberately requests a new confirmation. Never derive completion from app version
 * or tutorial/consent state. Informational guides can provide a single “我知道了” choice.
 */
object FeatureGuideRegistry {
    val entries = listOf(
        FeatureGuide(
            "scan-preset-v1", 100, "选择扫描模式", "先选一种适合你的使用方式，之后可在“设置 → 预设”中重新选择，也可在“设置 → 操作”中调整。",
            listOf(
                FeatureGuideChoice("traditional", "传统小抄", "扫描地图后，点击 👁 显示或隐藏小抄，手动调整位置，不自动贴合游戏画面。", ScanPreset.TRADITIONAL),
                FeatureGuideChoice("automatic", "自动小抄（Beta）", "扫描或选择地图并校准显示区域后，自动检测游戏开图并贴合，关图后隐去。功能仍在测试中，效果可能受画面影响。", ScanPreset.AUTOMATIC),
            ),
        ),
        FeatureGuide(
            "capture-method-v1", 200, "选择屏幕捕获方式", "选择适合你的识别方式，正式启动悬浮窗时再进行授权。之后可在“设置 → 视觉”中修改。",
            listOf(
                FeatureGuideChoice("media-projection", "屏幕捕获", CaptureMethodDescriptions.MEDIA_PROJECTION,
                    captureMethod = ScreenCaptureMethod.MEDIA_PROJECTION),
                FeatureGuideChoice("accessibility", "无障碍", CaptureMethodDescriptions.ACCESSIBILITY,
                    captureMethod = ScreenCaptureMethod.ACCESSIBILITY),
            ),
        ),
    )

    fun pending(completed: Set<String>, guides: List<FeatureGuide> = entries): List<FeatureGuide> {
        require(guides.map { it.id }.distinct().size == guides.size) { "Duplicate feature guide ID" }
        require(guides.all { guide -> guide.id.isNotBlank() && guide.choices.isNotEmpty() &&
            guide.choices.map { it.id }.distinct().size == guide.choices.size })
        return guides.filter { it.id !in completed }.sortedWith(compareBy({ it.order }, { it.id }))
    }
}
