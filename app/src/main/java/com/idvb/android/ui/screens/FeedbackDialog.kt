package com.idvb.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.idvb.android.AppServices
import com.idvb.android.feedback.FeedbackService
import com.idvb.android.feedback.FeedbackText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedbackViewModel : ViewModel() {
    var description by mutableStateOf("")
    var logs by mutableStateOf(false)
    var diagnostics by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var success by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    fun submit() {
        if (busy || success || !FeedbackText.isValid(description)) return
        val text = description
        val sendLogs = logs
        val sendDiagnostics = diagnostics
        busy = true
        status = "正在整理附件并提交反馈…"
        viewModelScope.launch {
            try {
                status = withContext(Dispatchers.IO) {
                    FeedbackService(AppServices.context).submit(text, sendLogs, sendDiagnostics)
                }
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = "提交未确认成功：${error.message ?: "网络或服务异常，请稍后重试。"}"
            } finally {
                busy = false
            }
        }
    }

    fun dismiss() {
        if (success) {
            description = ""
            logs = false
            diagnostics = false
            success = false
        }
        status = ""
    }
}

@Composable
fun FeedbackDialog(onDismiss: () -> Unit, model: FeedbackViewModel = viewModel()) {
    val close = { model.dismiss(); onDismiss() }
    AlertDialog(
        onDismissRequest = { if (!model.busy) close() },
        title = { Text("反馈问题") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("无需登录。请详细描述遇到的问题或改进建议。")
                OutlinedTextField(
                    value = model.description,
                    onValueChange = { if (it.length <= 4000) model.description = it },
                    enabled = !model.busy && !model.success,
                    label = { Text("问题描述") },
                    modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 7,
                )
                Text("当前加权字数：${FeedbackText.weightedLength(model.description)}（至少 11；中文算 2，最多 4000 字符）")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(model.logs, { model.logs = it }, enabled = !model.busy && !model.success)
                    Text("发送日志")
                }
                Text("发送最近最多 20 场已保存的应用日志，补充当前进程最近最多 1000 条日志，以及版本、系统、机型、屏幕分辨率、DPI 和缩放信息。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(model.diagnostics, { model.diagnostics = it }, enabled = !model.busy && !model.success)
                    Text("发送诊断数据")
                }
                Text("发送最近最多 20 场全部已保存的识别和对齐诊断，包含捕获的游戏画面、结果与追踪记录。启动悬浮窗或重置地图开始新场次；同场重复扫描不会挤占其他场次。勾选前请确认画面中没有需要保密的内容。附件可能较大，建议使用 Wi-Fi。")
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.status.isNotBlank()) Text(model.status)
            }
        },
        confirmButton = {
            if (model.success) TextButton(onClick = close) { Text("完成") }
            else TextButton(onClick = model::submit, enabled = !model.busy && FeedbackText.isValid(model.description)) { Text("立即反馈") }
        },
        dismissButton = {
            if (!model.success) TextButton(onClick = close, enabled = !model.busy) { Text("取消") }
        },
    )
}
