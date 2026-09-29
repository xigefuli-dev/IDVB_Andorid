package com.idvb.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.Paper
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep
import java.io.File
import java.text.DateFormat
import java.util.Date

/** 通用设置子页，视觉语言与设置主页保持一致。 */
@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var backgroundScanEnabled by remember { mutableStateOf(AppServices.prefs.backgroundScanEnabled) }
    var manualMapSelectionEnabled by remember { mutableStateOf(AppServices.prefs.manualMapSelectionEnabled) }
    var showUnconfirmedCandidates by remember { mutableStateOf(AppServices.prefs.showUnconfirmedCandidates) }
    var debugMode by remember { mutableStateOf(AppServices.prefs.debugMode) }
    var diagnosticsEnabled by remember { mutableStateOf(AppServices.prefs.recognitionDiagnosticsEnabled) }
    var recentDiagnostics by remember { mutableStateOf(AppServices.recognitionDiagnostics.recentPackages()) }
    var selectedDiagnostic by remember { mutableStateOf(recentDiagnostics.firstOrNull()) }
    var showDiagnosticPicker by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    val saveDiagnostic = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) {
            val result = AppServices.recognitionDiagnostics.exportPackage(source, context, uri)
            Toast.makeText(context,
                if (result.isSuccess) "诊断包已保存" else "保存失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    fun selectedPackage(): File? = selectedDiagnostic?.takeIf(File::isFile)
        ?: AppServices.recognitionDiagnostics.latestPackage()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("通用", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text("扫描", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        SettingsToggleSection(
            icon = Icons.Outlined.Search,
            title = "后台扫描",
            description = "扫描完成后只提示候选结果，点击悬浮窗 👁 时再打开候选界面",
            checked = backgroundScanEnabled,
            onCheckedChange = {
                backgroundScanEnabled = it
                AppServices.prefs.backgroundScanEnabled = it
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingsToggleSection(
            icon = Icons.Outlined.Search,
            title = "手动选择地图",
            description = "点击悬浮窗 🔍 时跳过识别，直接选择地图。标签筛选始终可用，不受此开关影响",
            checked = manualMapSelectionEnabled,
            onCheckedChange = {
                manualMapSelectionEnabled = it
                AppServices.prefs.manualMapSelectionEnabled = it
            },
        )

        Spacer(Modifier.height(10.dp))
        SettingsToggleSection(
            icon = Icons.Outlined.Search,
            title = "扫描未确认时显示候选",
            description = "开启后提供候选地图；后台扫描时点击 👁 查看，否则立即显示。关闭后提示未识别，等待下一次扫描",
            checked = showUnconfirmedCandidates,
            onCheckedChange = {
                showUnconfirmedCandidates = it
                AppServices.prefs.showUnconfirmedCandidates = it
            },
        )

        Spacer(Modifier.height(22.dp))
        Text("开发与诊断", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        SettingsToggleSection(
            icon = Icons.Outlined.BugReport,
            title = "调试模式",
            description = "点击 🔍 时跳过识别，直接生成模拟候选结果",
            checked = debugMode,
            onCheckedChange = {
                debugMode = it
                AppServices.prefs.debugMode = it
            },
        )
        Text(
            "调试模式只影响候选结果生成，不会执行屏幕捕获或侧门扫描。每次都会重新随机候选地图。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        )

        SettingsToggleSection(
            icon = Icons.Outlined.Save,
            title = "保存识别诊断",
            description = "保存原始扫描帧、捕获区域和全部候选证据",
            checked = diagnosticsEnabled,
            onCheckedChange = {
                diagnosticsEnabled = it
                AppServices.prefs.recognitionDiagnosticsEnabled = it
            },
            footer = {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = {
                        recentDiagnostics = AppServices.recognitionDiagnostics.recentPackages()
                        if (recentDiagnostics.isEmpty()) {
                            Toast.makeText(context, "还没有识别诊断；请开启保存后执行一次真实扫描", Toast.LENGTH_LONG).show()
                        } else showDiagnosticPicker = true
                    },
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
                ) { Text("选择诊断：${selectedPackage()?.name ?: "暂无"}") }
                TextButton(
                    onClick = {
                        val source = selectedPackage()
                        val result = source?.let(AppServices.recognitionDiagnostics::diagnosticsJson)
                        val json = result?.getOrNull()
                        if (json == null) {
                            Toast.makeText(context, "没有可复制的诊断 JSON：${result?.exceptionOrNull()?.message ?: "请先扫描"}",
                                Toast.LENGTH_LONG).show()
                        } else {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("IDVB 识别诊断 JSON", json))
                            Toast.makeText(context, "诊断 JSON 已复制，可直接粘贴", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
                ) { Text("复制诊断 JSON（不含截图）") }
                TextButton(
                    onClick = {
                        val source = selectedPackage()
                        if (source == null) {
                            Toast.makeText(context, "还没有识别诊断；请开启保存后执行一次真实扫描", Toast.LENGTH_LONG).show()
                        } else {
                            pendingExport = source
                            saveDiagnostic.launch(source.name)
                        }
                    },
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
                ) { Text("保存诊断 ZIP 到文件") }
                TextButton(
                    onClick = {
                        if (!AppServices.recognitionDiagnostics.shareLatest(context)) {
                            Toast.makeText(context, "还没有识别诊断；请开启保存后执行一次真实扫描", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Outlined.IosShare, null)
                    Spacer(Modifier.width(8.dp))
                    Text("分享最近一次诊断")
                }
            },
        )
        Text(
            "识别诊断最多保留最近 10 份，只在开启后记录真实扫描。复制 JSON 不含截图；ZIP 包含原始游戏截图。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDiagnosticPicker) AlertDialog(
        onDismissRequest = { showDiagnosticPicker = false },
        title = { Text("选择识别诊断") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                recentDiagnostics.forEach { file ->
                    TextButton(onClick = {
                        selectedDiagnostic = file
                        showDiagnosticPicker = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(Date(file.lastModified())) + " · " + file.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showDiagnosticPicker = false }) { Text("关闭") }
        },
    )
}

@Composable
private fun SettingsToggleSection(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(2.dp), color = Deep) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = SignalGreen, modifier = Modifier.size(23.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange, colors = diagnosticSwitchColors())
            }
            footer?.invoke(this)
        }
    }
}

@Composable
private fun diagnosticSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Paper,
    checkedTrackColor = SignalGreenDeep,
    uncheckedThumbColor = Color.LightGray,
)
