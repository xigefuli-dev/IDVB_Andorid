package com.idvb.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var debugMode by remember { mutableStateOf(AppServices.prefs.debugMode) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("通用", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text("开发与诊断", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(2.dp), color = Deep) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.BugReport, null, tint = SignalGreen, modifier = Modifier.size(23.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                    Text("调试模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "点击 🔍 时跳过识别，直接生成模拟候选结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugMode,
                    onCheckedChange = {
                        debugMode = it
                        AppServices.prefs.debugMode = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Paper,
                        checkedTrackColor = SignalGreenDeep,
                        uncheckedThumbColor = Color.LightGray,
                    ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "调试模式只影响候选结果生成，不会执行屏幕捕获或侧门扫描。每次都会重新随机候选地图。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
