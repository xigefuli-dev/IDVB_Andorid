package com.idvb.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.idvm.IdvmImporter
import com.idvb.android.idvm.ImportResult
import com.idvb.android.ui.screens.maplist.importIdvmUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入页：SAF 选 .idvm → 拷贝到 cacheDir → IdvmImporter 导入 → 结果显示。
 * 对齐参考项目「导入地图包」工作流。
 */
@Composable
fun ImportScreen(onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                result = null
                val (r, name) = withContext(Dispatchers.IO) { importIdvmUri(context, uri) }
                importing = false
                result = r
                fileName = name
                if (r is ImportResult.Success) onImported()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("导入 IDVM 地图包", style = MaterialTheme.typography.headlineSmall)
        Text(
            "从参考项目导出的 .idvm 格式地图包，导入后自动校验完整性并生成本地地图清单。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { launcher.launch(arrayOf("*/*")) },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                Text("正在导入…")
            } else {
                Text("选择 .idvm 文件")
            }
        }

        when (val r = result) {
            is ImportResult.Success -> SuccessCard(fileName, r)
            is ImportResult.Failure -> FailureCard(fileName, r)
            null -> HintCard()
        }
    }
}

/** 拷贝 content URI 到临时文件并导入，返回（结果, 文件名） */
@Composable
private fun SuccessCard(fileName: String?, r: ImportResult.Success) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✓ 导入成功", style = MaterialTheme.typography.titleMedium)
            fileName?.let { Text("文件：$it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "新增 ${r.importedClasses.size} 个 Class，${r.importedMaps.size} 张地图。",
                style = MaterialTheme.typography.bodyMedium,
            )
            r.importedMaps.forEach { m ->
                Text("• ${m.title}（${m.floors.size} 层）", style = MaterialTheme.typography.bodySmall)
            }
            Text("去「地图」页选择地图并显示悬浮窗。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FailureCard(fileName: String?, r: ImportResult.Failure) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✕ 导入失败", style = MaterialTheme.typography.titleMedium)
            fileName?.let { Text("文件：$it", style = MaterialTheme.typography.bodySmall) }
            Text(r.reason, style = MaterialTheme.typography.bodyMedium)
            if (r.details != null) {
                Text(
                    r.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun HintCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ℹ", style = MaterialTheme.typography.headlineSmall)
            Text(
                "尚未导入。请选择通过 IDVM 导出器生成的 .idvm 地图包。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
