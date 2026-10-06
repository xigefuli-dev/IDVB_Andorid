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
    START("启动悬浮窗", "权限准备好后，点首页右下角的启动按钮。教程期间不会唤起真实悬浮窗，而是显示启动提示。读完提示后回到这里点“检查”，接下来用模拟场景练习。结束新手教程并通过问答后，同样点击启动按钮即可唤起悬浮窗。", "请先完成运行权限，再点首页右下角的启动按钮，阅读提示后回来检查。"),
    LOBBY("进入加页手记", "正式使用时，回到桌面后打开第五人格，再进入加页手记。这里先用模拟场景练一遍：点画面中的“进入加页手记”，看到对局大厅后点“检查”。", "请先点模拟画面中的“进入加页手记”。"),
    PACKAGE("选用地图包", "点悬浮按钮“…”→“选择地图包”，在旁边展开的列表里点本局要用的地图包。选中后子菜单会收起；切换地图包后请重新扫描。这里先选择“S0 厄运之女 · 困难（示例）”。", "请在“…”旁的子菜单里点“S0 厄运之女 · 困难（示例）”。"),
    CALIBRATE("首次使用先校准地图", "先点“打开游戏地图”。再点悬浮按钮“…”→“校准显示区域”。用手指从地图显示范围的左上角拖到右下角，把整块地图画布框住，不要框进左侧队友和右侧按钮。点“确认并保存”，再检查。", "请打开地图，在“…”里选择“校准显示区域”，框好地图的完整显示范围并保存。"),
    @Deprecated("已取消注册辅助触控教程步骤")
    ASSIST_TOUCH("设置开图和关图位置", "点“…”→“辅助触控”，从下方键库把“打开”和“关闭”分别拖到对应的游戏按钮位置（左上角小地图与右上角关闭键）。右侧可重置或确认。两个键平时隐藏；正式使用时开启“按住激活”，点击 👁 开图、再次点击关图。这里的练习不会覆盖正式位置。", "请将“打开”拖到左上角小地图、“关闭”拖到右上角关闭键，再点确认。"),
    ENTER("试试从侧门进入", "校准已经练过了。点“从侧门入场”，再点门口的“进入场景”。进入后点“打开游戏地图”。每次扫描前，都要先把游戏里的地图打开。", "请依次点“从侧门入场”“进入场景”“打开游戏地图”。"),
    SCAN("让 IDVB 找地图", "游戏地图已经打开。现在点悬浮按钮“🔍”，等提示扫描完成。这里的扫描是练习结果；真正游戏里会根据你打开的地图画面进行识别。", "请点“🔍”完成一次练习扫描。"),
    SELECT("选中本局地图", "点“👁”打开地图候选列表。对照画面里的房间和走廊，选与本局一致的那张图。练习中请选“本局地图 · A”。选中后列表会收起。", "请点“👁”打开候选列表，再选“本局地图 · A”。"),
    SHOW("把攻略图显示出来", "选好地图后，再点一次“👁”。正式使用默认会按当前游戏画面自动贴合所选地图和楼层。再次点击仍会隐藏地图，每次重新显示都会重新贴合。这里先练习显示和隐藏。", "请再点一次“👁”，让攻略图显示出来。"),
    ADJUST("调到看着舒服", "点“…”→“小抄显示调整”。用手指拖动攻略图可以挪位置，双指张开或合拢可以改大小，音量键可以调透明度。练习里也提供大小和透明度按钮。试着调整后点“保存”。", "请进入“小抄显示调整”，改变位置、大小或透明度，再保存。"),
    SWITCH("相似图，一键切换", "有些地图长得很像。如果当前地图不对，点“⇆”就能切换同组的相似图，不用重新扫描。试着点一次，看看图上的标记有什么变化。只有地图包提供了相似图时，才会出现这个按钮。", "请点一次“⇆”，切换到另一张练习地图。"),
    DONE("练习完成，可以去实战了", "正式使用时：启动 IDVB → 在“…”→“选择地图包”选好本局地图包 → 打开第五人格的加页手记 → 打开游戏地图 → 首次使用先校准 → 🔍 扫描 → 👁 选图 → 再点 👁 展示并自动贴合。可到“设置 → 操作”切换为“只展示”。练习的校准和地图选择不会覆盖你的正式设置。", ""),
    ;

    companion object {
        /** 活跃注册的教程步骤列表（已取消注册 ASSIST_TOUCH） */
        val activeSteps: List<TutorialStep> = entries.filter { it != ASSIST_TOUCH }
    }
}

@Serializable
data class PracticeState(
    val scene: String = "desktop",
    val mapOpen: Boolean = false,
    val menu: Boolean = false,
    val packageMenu: Boolean = false,
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
    val assistPoints: List<Float> = emptyList(),
)

@Serializable
data class TutorialProgress(
    val step: TutorialStep = TutorialStep.DOWNLOAD,
    val active: Boolean = false,
    val practiceOpen: Boolean = false,
    val downloaded: Boolean = false,
    val serviceStarted: Boolean = false,
    val startButtonPracticed: Boolean = false,
    val passed: Set<TutorialStep> = emptySet(),
    val skipped: Set<TutorialStep> = emptySet(),
    val performed: Set<TutorialStep> = emptySet(),
    val practice: PracticeState = PracticeState(),
    val quizAnswered: Int = 0,
    val completionRevision: Int = 0,
    val practiceFinished: Boolean = false,
) {
    val stagesFinished: Boolean get() = TutorialStep.activeSteps
        .filter { it != TutorialStep.DONE }.all { it in passed || it in skipped }
    val completed: Boolean get() = completionRevision == TutorialQuiz.REVISION &&
        stagesFinished &&
        quizAnswered == TutorialQuiz.questions.size

    fun requiredCheckpoint(): TutorialProgress {
        if (completed) return this
        val missing = TutorialStep.activeSteps.firstOrNull { it != TutorialStep.DONE && it !in passed && it !in skipped }
        return copy(active = true, step = missing ?: TutorialStep.DONE,
            quizAnswered = if (missing != null || completionRevision == 1) 0 else quizAnswered.coerceIn(0, TutorialQuiz.questions.size),
            practiceFinished = missing == null && (practiceFinished || (completionRevision != 1 && quizAnswered > 0)),
            completionRevision = 0)
    }

    fun finishPractice(): TutorialProgress {
        if (step != TutorialStep.DONE || !stagesFinished) return this
        return copy(practiceFinished = true, practiceOpen = false)
    }

    fun answerQuiz(option: Int): TutorialProgress {
        if (!practiceFinished || step != TutorialStep.DONE || quizAnswered !in TutorialQuiz.questions.indices ||
            !stagesFinished) return this
        if (option !in TutorialQuiz.questions[quizAnswered].options.indices) return this
        if (TutorialQuiz.questions[quizAnswered].correct != option) return TutorialProgress(active = true)
        val answered = quizAnswered + 1
        return copy(quizAnswered = answered,
            completionRevision = if (answered == TutorialQuiz.questions.size) TutorialQuiz.REVISION else 0,
            active = answered != TutorialQuiz.questions.size)
    }

    fun advance(skip: Boolean = false): TutorialProgress {
        if (step == TutorialStep.DONE) return this
        val active = TutorialStep.activeSteps
        val currentIndex = active.indexOf(step)
        val next = if (currentIndex in 0 until active.lastIndex) {
            active[currentIndex + 1]
        } else if (step == TutorialStep.ASSIST_TOUCH) {
            TutorialStep.ENTER
        } else {
            TutorialStep.DONE
        }
        // Prepare the next exercise even when its prerequisite was skipped. These
        // sample prerequisites never count as actions performed by the learner.
        val ready = when (next) {
            TutorialStep.PACKAGE -> practice.copy(scene = "lobby", mapOpen = false)
            TutorialStep.CALIBRATE -> practice.copy(scene = "lobby", mapOpen = false)
            TutorialStep.ENTER -> practice.copy(scene = "lobby", mapOpen = false)
            TutorialStep.SCAN -> practice.copy(scene = "game", mapOpen = true)
            TutorialStep.SELECT -> practice.copy(scene = "game", mapOpen = true, scanned = true)
            TutorialStep.SHOW -> practice.copy(selected = true, visible = false, scanned = false)
            TutorialStep.ADJUST, TutorialStep.SWITCH -> practice.copy(selected = true, visible = true, scanned = false)
            else -> practice
        }.copy(menu = false, packageMenu = false, mode = "normal", changed = false)
        return copy(step = next, practice = ready,
            passed = if (skip) passed else passed + step,
            skipped = if (skip) skipped + step else skipped)
    }

    fun canPass(hasMaps: Boolean = false, permissionsReady: Boolean = false): Boolean = when (step) {
        TutorialStep.DOWNLOAD -> downloaded || hasMaps
        TutorialStep.IMPORT -> hasMaps
        TutorialStep.PERMISSIONS -> permissionsReady
        TutorialStep.START -> permissionsReady && startButtonPracticed
        TutorialStep.SHOW -> step in performed && practice.visible
        TutorialStep.ASSIST_TOUCH -> step in performed && isPracticeAssistTouchValid(practice.assistPoints)
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

/** Target placement positions for opening and closing assist buttons in practice. */
fun isPracticeAssistTouchValid(points: List<Float>): Boolean {
    if (points.size != 4 || points.any { !it.isFinite() || it !in 0f..1f }) return false
    val p1x = points[0]; val p1y = points[1]
    val p2x = points[2]; val p2y = points[3]
    val t1x = 0.077f; val t1y = 0.265f
    val t2x = 0.938f; val t2y = 0.253f
    val tol = 0.15f
    fun match(px: Float, py: Float, tx: Float, ty: Float) =
        kotlin.math.abs(px - tx) <= tol && kotlin.math.abs(py - ty) <= tol
    return (match(p1x, p1y, t1x, t1y) && match(p2x, p2y, t2x, t2y)) ||
        (match(p1x, p1y, t2x, t2y) && match(p2x, p2y, t1x, t1y))
}
