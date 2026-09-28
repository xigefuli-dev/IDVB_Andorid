package com.idvb.android.tutorial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TutorialPanel(
    store: TutorialStore,
    hasMaps: Boolean = false,
    permissionsReady: Boolean = false,
    inPractice: Boolean = false,
    onPractice: () -> Unit = {},
    onPause: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress by store.state.collectAsState()
    var confirmSkip by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var feedback by remember(progress.step) { mutableStateOf("") }
    val step = progress.step
    val practiceStep = step >= TutorialStep.LOBBY && step < TutorialStep.DONE
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (inPractice) "模拟实战 · ${(step.ordinal + 1).coerceAtMost(12)}/12" else "新手教程 · ${(step.ordinal + 1).coerceAtMost(12)}/12",
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onPause, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text(if (inPractice) "返回首页" else "稍后继续")
                }
            }
            LinearProgressIndicator(progress = { step.ordinal / 12f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(step.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(step.instructions, style = MaterialTheme.typography.bodyMedium)
                if (step == TutorialStep.DOWNLOAD) {
                    Row {
                        Checkbox(progress.downloaded, onCheckedChange = { checked -> store.update { it.copy(downloaded = checked) } })
                        Text("我已经下载好地图包了", modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("下载是否完成需要你自己确认；下一步会检查地图有没有真正导入。", style = MaterialTheme.typography.bodySmall)
                }
                if (practiceStep && !inPractice) {
                    Button(onClick = onPractice, modifier = Modifier.fillMaxWidth()) { Text("进入模拟实战") }
                    Text("先在这里练习，再去游戏里照着做。", style = MaterialTheme.typography.bodySmall)
                }
                if (step == TutorialStep.DONE) {
                    Text("已检查 ${progress.passed.size} 段 · 已跳过 ${progress.skipped.size} 段", modifier = Modifier.padding(top = 10.dp))
                    if (progress.skipped.isNotEmpty()) Text("跳过的内容没有算作学会，可以重新练习补上。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { confirmRestart = true }) { Text("从头再练一次") }
                }
                if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (step != TutorialStep.DONE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { confirmSkip = true }) { Text("跳过这段") }
                    Button(onClick = {
                        if (progress.canPass(hasMaps, permissionsReady)) store.update { it.advance() }
                        else feedback = step.hint
                    }) { Text("检查") }
                }
            } else Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    if (confirmSkip) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text("跳过“${step.title}”？") },
        text = { Text("这一段会记为“已跳过”，不会帮你完成操作。正式使用时，地图包、权限和校准仍然需要准备好。") },
        dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("继续学习") } },
        confirmButton = { TextButton(onClick = { confirmSkip = false; store.update { it.advance(skip = true) } }) { Text("确认跳过") } },
    )
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("从头再练一次？") },
        text = { Text("会重置教程进度和模拟练习，已经导入的地图与正式设置都会保留。") },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            confirmRestart = false
            store.update { TutorialProgress() }
            if (inPractice) onPause()
        }) { Text("重新开始") } },
    )
}
