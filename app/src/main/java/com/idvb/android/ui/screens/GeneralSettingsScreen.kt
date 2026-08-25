package com.idvb.android.ui.screens

import android.widget.Toast
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

/** 通用设置子页，视觉语言与设置主页保持一致。 */
@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var backgroundScanEnabled by remember { mutableStateOf(AppServices.prefs.backgroundScanEnabled) }
    var debugMode by remember { mutableStateOf(AppServices.prefs.debugMode) }
    var diagnosticsEnabled by remember { mutableStateOf(AppServices.prefs.recognitionDiagnosticsEnabled) }

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
            "识别诊断最多保留最近 10 份，只在开启后记录真实扫描；分享包内包含原始游戏截图，请按需发送。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
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
