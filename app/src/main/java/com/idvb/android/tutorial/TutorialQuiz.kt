package com.idvb.android.tutorial

data class TutorialQuestion(val title: String, val options: List<String>, val correct: Int)

object TutorialQuiz {
    const val REVISION = 3
    val questions = listOf(
        TutorialQuestion("如果你玩的是噩梦模式，你应该从哪里入场？", listOf("一楼侧门", "二楼侧门", "一楼大门"), 2),
        TutorialQuestion("如果你玩的是困难模式，你应该从哪里入场？", listOf("一楼侧门", "二楼侧门", "一楼大门"), 0),
        TutorialQuestion("你应该在哪里进行显示校准？", listOf("地图内部", "等待队友的大厅（有镜子那个）", "匹配大厅"), 1),
        TutorialQuestion("如果你玩的是噩梦地图，并且正准备扫描地图。你应该怎么做？", listOf(
            "从一楼大门进入，探索一定程度然后打开地图点🔍按钮",
            "从二楼进入，探索一定程度然后打开地图点🔍按钮",
            "从二楼进入，探索一定程度然后打开地图点👁按钮"), 0),
        TutorialQuestion("🔍、👁的作用分别是？", listOf("选择与显示地图、扫描地图", "扫描地图、选择与显示地图"), 1),
        TutorialQuestion("地图包应该从哪里获取？", listOf("去找小抄地图作者要", "123网盘", "群文件和软件内订阅"), 2),
        TutorialQuestion("关闭后如何重新打开悬浮窗？", listOf("重新过一遍新手教程", "点首页右下角的箭头按钮或者启动按钮"), 1),
        TutorialQuestion("地下室可以用来扫描地图吗？", listOf("可以", "不可以"), 1),
        TutorialQuestion("如果自动小抄使用异常，我应该？", listOf("去 设置 - 预设 切换成传统小抄"), 0),
        TutorialQuestion("使用软件遇到问题应该怎么办？", listOf(
            "去官方Q群里抱怨",
            "进入 设置 - 问题反馈，向官方发送完整的对局回放日志",
            "去官方Q群里说软件太垃圾了"), 1),
        TutorialQuestion("如果尝试给官方反馈问题但是上传失败，应该怎么做？", listOf(
            "点击“导出包分享”，把回放日志发送到官方群里",
            "等 Bug 自己被修好"), 0),
    )
}
