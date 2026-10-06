package com.idvb.android.tutorial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun TutorialQuizScreen(store: TutorialStore, onFinished: () -> Unit) {
    val progress by store.state.collectAsState()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var selected by remember(progress.quizAnswered) { mutableIntStateOf(-1) }
    var error by remember(progress.quizAnswered) { mutableStateOf("") }
    val question = TutorialQuiz.questions.getOrNull(progress.quizAnswered)
    val scrollState = rememberScrollState()
    LaunchedEffect(progress.quizAnswered) { scrollState.scrollTo(0) }

    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxHeight().fillMaxWidth().padding(horizontal = 24.dp)) {
                Column(Modifier.padding(top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新手教程 / 最后一步", style = MaterialTheme.typography.labelLarge, color = colors.secondary)
                    Text("教程结束后问答", style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (progress.completed) "全部答对" else "第 ${progress.quizAnswered + 1} 题 / 共 ${TutorialQuiz.questions.size} 题",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("${progress.quizAnswered} / ${TutorialQuiz.questions.size} 已答对",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    LinearProgressIndicator(
                        progress = { progress.quizAnswered.toFloat() / TutorialQuiz.questions.size },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.secondary,
                        trackColor = colors.surfaceContainerHighest,
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (progress.completed) {
                        Surface(shape = RoundedCornerShape(2.dp), color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text("全部回答正确，新手教程已完成。", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(20.dp))
                        }
                    } else if (question != null) {
                        Surface(shape = RoundedCornerShape(2.dp), color = colors.surfaceContainer,
                            contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("请选择正确答案", style = MaterialTheme.typography.labelLarge, color = colors.secondary)
                                Text(question.title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                            }
                        }
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            question.options.forEachIndexed { index, option ->
                                QuizOptionCard(option, index, selected == index) { selected = index; error = "" }
                            }
                        }
                        Text("全部答对后完成教程；答错需要重新进行一遍新手教程。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.padding(top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(2.dp),
                        enabled = progress.completed || (selected >= 0 && question != null),
                        onClick = {
                            if (progress.completed) onFinished()
                            else if (question != null) {
                                if (selected != question.correct) {
                                    store.update { it.answerQuiz(selected) }
                                    if (store.state.value.step != TutorialStep.DONE) {
                                        android.widget.Toast.makeText(context, "回答错误，请重新完成一遍新手教程。", android.widget.Toast.LENGTH_LONG).show()
                                        onFinished()
                                    } else error = "保存失败，请重试。"
                                } else {
                                    store.update { it.answerQuiz(selected) }
                                    if (store.state.value.quizAnswered == progress.quizAnswered) error = "保存失败，请重试。"
                                }
                            }
                        },
                    ) { Text(if (progress.completed) "开始使用" else "提交答案") }
                    if (!progress.completed) TextButton(onClick = {
                        store.update { TutorialProgress(active = true) }
                        if (store.state.value.step == TutorialStep.DOWNLOAD && !store.state.value.practiceFinished) onFinished()
                        else error = "保存失败，请重试。"
                    }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(2.dp)) { Text("我要重做新手教程") }
                }
            }
        }
    }
}

@Composable
private fun QuizOptionCard(text: String, index: Int, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceVariant.copy(alpha = .55f),
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.secondary else colors.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(32.dp), shape = RoundedCornerShape(2.dp),
                color = if (selected) colors.secondary else colors.surfaceContainerHighest,
                contentColor = if (selected) colors.onSecondary else colors.onSurfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Text(('A'.code + index).toChar().toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp))
            RadioButton(selected = selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = colors.secondary, unselectedColor = colors.outline))
        }
    }
}
