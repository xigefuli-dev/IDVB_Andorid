package com.idvb.android.tutorial

import kotlinx.serialization.Serializable

enum class TutorialStep(val title: String, val instructions: String, val hint: String) {
    DOWNLOAD("先准备好地图包", "你能看到这里，说明 IDVB 已经安装好了。接下来，请下载一个地图包（文件名通常以 .idvm 结尾）。先别解压，也不用再安装它。", "请先下载地图包，再勾选下方的确认项。"),
    IMPORT(
        "安装一张地图",
        "在此处订阅地图，或者导入在其他地方下载的地图。\n\n订阅：点击下方地图并确认订阅，等待安装完成。\n\n导入：下载地图包后，点“用其他应用打开”或“分享”并选择 IDVB；也可以到首页或列表页，点右下角的箭头手动选择文件。看到“地图导入成功”后，回到这里点“检查”。",
        "还没找到已安装的地图。请在下方完成订阅安装，或先把下载的地图包导入 IDVB，再点“检查”。",
    ),
    PERMISSIONS("把运行权限打开", "请切换到“首页”，点右下角的箭头。手机会带你去打开一项权限，按提示允许后返回 IDVB，再点一次箭头。重复操作，直到按钮变成播放形状。", "还有权限没打开，请继续点首页右下角的箭头。"),
    START("启动，然后回到桌面", "点首页右下角的启动按钮。如果手机询问是否允许录屏，请选择整个屏幕并允许。启动后会回到手机桌面，屏幕上会出现 IDVB 的小按钮。再打开 IDVB，点“检查”，我们一起练习游戏里的操作。", "还没有启动成功。请点首页右下角的启动按钮，并完成手机弹出的授权。"),
    LOBBY("进入加页手记", "正式使用时，回到桌面后打开第五人格，再进入加页手记。这里先用模拟场景练一遍：点画面中的“进入加页手记”，看到对局大厅后点“检查”。", "请先点模拟画面中的“进入加页手记”。"),
    CALIBRATE("告诉 IDVB 地图在哪儿", "先点“打开游戏地图”。再点悬浮按钮“…”→“校准显示区域”。用手指从地图显示范围的左上角拖到右下角，把整块地图画布框住，不要框进左侧队友和右侧按钮。点“确认并保存”，再检查。", "请打开地图，在“…”里选择“校准显示区域”，框好地图的完整显示范围并保存。"),
    ENTER("试试从侧门进入", "校准已经练过了。点“从侧门入场”，再点门口的“进入场景”。进入后点“打开游戏地图”。每次扫描前，都要先把游戏里的地图打开。", "请依次点“从侧门入场”“进入场景”“打开游戏地图”。"),
    SCAN("让 IDVB 找地图", "游戏地图已经打开。现在点悬浮按钮“🔍”，等提示扫描完成。这里的扫描是练习结果；真正游戏里会根据你打开的地图画面进行识别。", "请点“🔍”完成一次练习扫描。"),
    SELECT("选中本局地图", "点“👁”打开地图候选列表。对照画面里的房间和走廊，选与本局一致的那张图。练习中请选“本局地图 · A”。选中后列表会收起。", "请点“👁”打开候选列表，再选“本局地图 · A”。"),
    SHOW("把攻略图显示出来", "选好地图后，再点一次“👁”。攻略图就会显示在游戏地图上。以后想暂时藏起来，也点这个按钮。", "请再点一次“👁”，让攻略图显示出来。"),
    ADJUST("调到看着舒服", "点“…”→“小抄显示调整”。用手指拖动攻略图可以挪位置，双指张开或合拢可以改大小，音量键可以调透明度。练习里也提供大小和透明度按钮。试着调整后点“保存”。", "请进入“小抄显示调整”，改变位置、大小或透明度，再保存。"),
    SWITCH("相似图，一键切换", "有些地图长得很像。如果当前地图不对，点“⇆”就能切换同组的相似图，不用重新扫描。试着点一次，看看图上的标记有什么变化。只有地图包提供了相似图时，才会出现这个按钮。", "请点一次“⇆”，切换到另一张练习地图。"),
    DONE("练习完成，可以去实战了", "正式使用时：启动 IDVB → 打开第五人格的加页手记 → 打开游戏地图 → 首次使用先校准 → 🔍 扫描 → 👁 选图 → 再点 👁 显示。练习的校准和地图选择不会覆盖你的正式设置。", ""),
}

@Serializable
data class PracticeState(
    val scene: String = "desktop",
    val mapOpen: Boolean = false,
    val menu: Boolean = false,
    val mode: String = "normal",
    val rect: List<Float> = emptyList(),
    val scanned: Boolean = false,
    val selected: Boolean = false,
    val visible: Boolean = false,
    val variant: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val opacity: Float = .7f,
    val changed: Boolean = false,
)

@Serializable
data class TutorialProgress(
    val step: TutorialStep = TutorialStep.DOWNLOAD,
    val active: Boolean = true,
    val practiceOpen: Boolean = false,
    val downloaded: Boolean = false,
    val serviceStarted: Boolean = false,
    val passed: Set<TutorialStep> = emptySet(),
    val skipped: Set<TutorialStep> = emptySet(),
    val performed: Set<TutorialStep> = emptySet(),
    val practice: PracticeState = PracticeState(),
) {
    fun advance(skip: Boolean = false): TutorialProgress {
        if (step == TutorialStep.DONE) return this
        val next = TutorialStep.entries[step.ordinal + 1]
        // Prepare the next exercise even when its prerequisite was skipped. These
        // sample prerequisites never count as actions performed by the learner.
        val ready = when (next) {
            TutorialStep.CALIBRATE -> practice.copy(scene = "lobby", mapOpen = false)
            TutorialStep.ENTER -> practice.copy(scene = "lobby", mapOpen = false)
            TutorialStep.SCAN -> practice.copy(scene = "game", mapOpen = true)
            TutorialStep.SELECT -> practice.copy(scene = "game", mapOpen = true, scanned = true)
            TutorialStep.SHOW -> practice.copy(selected = true, visible = false, scanned = false)
            TutorialStep.ADJUST, TutorialStep.SWITCH -> practice.copy(selected = true, visible = true, scanned = false)
            else -> practice
        }.copy(menu = false, mode = "normal", changed = false)
        return copy(step = next, practice = ready,
            passed = if (skip) passed else passed + step,
            skipped = if (skip) skipped + step else skipped)
    }

    fun canPass(hasMaps: Boolean = false, permissionsReady: Boolean = false): Boolean = when (step) {
        TutorialStep.DOWNLOAD -> downloaded || hasMaps
        TutorialStep.IMPORT -> hasMaps
        TutorialStep.PERMISSIONS -> permissionsReady
        TutorialStep.START -> serviceStarted
        TutorialStep.SHOW -> step in performed && practice.visible
        TutorialStep.DONE -> true
        else -> step in performed
    }
}

/** Bounds relative to the supplied calibration screenshot's full image. */
fun isPracticeCalibrationValid(rect: List<Float>): Boolean = rect.size == 4 &&
    rect.all { it.isFinite() } &&
    kotlin.math.abs(rect[0] - .325f) <= .065f &&
    kotlin.math.abs(rect[1] - .17f) <= .065f &&
    kotlin.math.abs(rect[2] - .84f) <= .065f &&
    kotlin.math.abs(rect[3] - .82f) <= .065f
