package com.idvb.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.ui.PermissionSnapshot
import com.idvb.android.ui.theme.SignalGreenDeep
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.data.ScreenCaptureMethod

private data class PermissionItem(val title: String, val detail: String, val granted: Boolean)

@Composable
fun HomeScreen(
    permissions: PermissionSnapshot,
    hasMaps: Boolean = true,
) {
    val items = listOf(
        PermissionItem("悬浮窗", "在其他应用上层显示地图", permissions.overlay),
        PermissionItem(
            if (permissions.captureMethod == ScreenCaptureMethod.ACCESSIBILITY) "无障碍" else "屏幕捕获",
            if (permissions.captureMethod == ScreenCaptureMethod.ACCESSIBILITY) "通过 IDVB 无障碍服务截取识别画面" else "读取当前屏幕画面以进行识别",
            permissions.screenCapture,
        ),
        PermissionItem("通知", "显示服务运行状态与控制入口", permissions.notifications),
        PermissionItem("前台服务", "FOREGROUND_SERVICE", permissions.foregroundService),
        if (permissions.captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION) {
            PermissionItem("媒体投影服务", "FOREGROUND_SERVICE_MEDIA_PROJECTION", permissions.mediaProjectionService)
        } else {
            PermissionItem("截图服务", "AccessibilityService.takeScreenshot", permissions.screenCapture)
        },
        PermissionItem("电池优化白名单", "允许忽略后台高耗电限制", permissions.batteryOptimization),
    )
    val grantedCount = items.count { it.granted }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 92.dp),
    ) {
        Text("服务状态", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                !hasMaps -> "需先导入地图包并获得全部权限后才能运行。"
                permissions.allGranted -> "准备就绪，可以启动服务。"
                else -> "需获得全部权限后才能正常运行。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Card(
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = {}),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("新手教程", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text("→", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("运行所需权限", style = MaterialTheme.typography.titleLarge)
                        Text("$grantedCount / ${items.size} 已获得", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(42.dp).background(if (permissions.allGranted) SignalGreenDeep else Color(0xFF34373B), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (permissions.allGranted) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (permissions.allGranted) Color.White else SignalGreen)
                    }
                }
                Spacer(Modifier.height(18.dp))
                items.forEachIndexed { index, item ->
                    PermissionRow(item)
                    if (index != items.lastIndex) Spacer(Modifier.height(9.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (!hasMaps) {
                "点击右下角 ➜ 将优先指引导入地图包，随后按顺序申请运行权限；全部完成后按钮会变为 ▶。"
            } else {
                "点击右下角的 ➜ 将按顺序申请尚未获得的权限；全部完成后按钮会变为 ▶。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .35f), RoundedCornerShape(2.dp)).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(if (item.granted) SignalGreenDeep else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
            Icon(if (item.granted) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = if (item.granted) "已获得" else "未获得", tint = if (item.granted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (item.granted) "已获得" else "未获得", style = MaterialTheme.typography.labelMedium, color = if (item.granted) SignalGreenDeep else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Eyebrow(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).background(SignalGreenDeep))
    Text("  $text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
