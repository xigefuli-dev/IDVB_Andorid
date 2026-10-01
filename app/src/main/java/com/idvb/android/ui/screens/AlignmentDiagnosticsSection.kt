package com.idvb.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AlignmentDiagnosticsSection() {
    val context = LocalContext.current
    val store = AppServices.alignmentDiagnostics
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var inputs by remember { mutableStateOf(AppServices.prefs.alignmentReplayInputsEnabled) }
    var packages by remember { mutableStateOf(store.recentPackages()) }
    var selected by remember { mutableStateOf(packages.firstOrNull()) }
    var picker by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pending; pending = null
        if (uri != null && file != null) {
            scope.launch {
                busy = true
                try {
                    val result = withContext(Dispatchers.IO) { store.exportPackage(file, context, uri) }
                    Toast.makeText(context, if (result.isSuccess) "对齐诊断已保存" else "保存失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("对齐诊断", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("保存可回放输入", Modifier.weight(1f))
        Switch(inputs, onCheckedChange = { inputs = it; AppServices.prefs.alignmentReplayInputsEnabled = it })
    }
    Text("每次贴合保存参数、门控、阈值、分阶段耗时与源码指纹，按场次保留最近 20 场全部记录。开启后同时保存截图、参考线图和中间掩膜，用于还原和回放；关闭后会明确标记输入不完整。",
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { packages = store.recentPackages(); picker = true }) {
        Text("选择对齐诊断：${selected?.name ?: "暂无"}")
    }
    TextButton(enabled = !busy, onClick = {
        val file = selected ?: store.recentPackages().firstOrNull()
        scope.launch {
            busy = true
            try {
                val json = withContext(Dispatchers.IO) { file?.let { store.diagnosticsJson(it).getOrNull() } }
                if (json == null) Toast.makeText(context, "对齐诊断不存在或读取失败", Toast.LENGTH_SHORT).show()
                else if (json.length > 200_000) Toast.makeText(context, "诊断数据较多，请保存完整 ZIP 到文件", Toast.LENGTH_LONG).show()
                else {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("IDVB 对齐诊断", json))
                    Toast.makeText(context, "对齐诊断 JSON 已复制", Toast.LENGTH_SHORT).show()
                }
            } finally { busy = false }
        }
    }) { Text("复制对齐诊断 JSON") }
    TextButton(enabled = !busy, onClick = {
        val file = selected ?: store.recentPackages().firstOrNull()
        if (file == null) Toast.makeText(context, "请先执行一次自动贴合", Toast.LENGTH_SHORT).show()
        else { pending = file; export.launch(file.name) }
    }) { Text("保存对齐诊断 ZIP 到文件") }
    if (busy) Text("正在处理诊断文件…", style = MaterialTheme.typography.bodySmall)
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("选择对齐诊断") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (packages.isEmpty()) Text("暂无对齐诊断")
            packages.forEach { file -> TextButton(onClick = { selected = file; picker = false }) { Text(file.name) } }
        } }, confirmButton = { TextButton(onClick = { picker = false }) { Text("关闭") } })
}
