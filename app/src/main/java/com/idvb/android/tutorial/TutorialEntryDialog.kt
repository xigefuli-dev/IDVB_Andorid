package com.idvb.android.tutorial

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@Composable
fun TutorialEntryDialog(onEnter: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("请进入新手教程") },
        text = { Text("必须先进入新手教程。各阶段可以选择“跳过这段”，但不能跳过进入教程的流程，也不能跳过教程结束后的 ${TutorialQuiz.questions.size} 题问答。点击“完成教程”后进入问答，全部答对后才能使用软件；任意一题答错，都需要重新进行一遍新手教程。") },
        confirmButton = { Button(onClick = onEnter) { Text("进入新手教程") } },
    )
}
