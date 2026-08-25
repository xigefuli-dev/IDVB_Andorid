package com.idvb.android.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.ui.openAccessibilityServiceSettings
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep

@Composable
fun VisionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppServices.prefs.screenCaptureMethod) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("视觉", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text("屏幕捕获方式", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        CaptureMethodRow("捕捉屏幕", "使用 Android 标准屏幕捕获授权；授权失效后需要重新确认",
            selected == ScreenCaptureMethod.MEDIA_PROJECTION, true,
            { Icon(Icons.Outlined.ScreenshotMonitor, null, tint = SignalGreen) }) {
            selected = ScreenCaptureMethod.MEDIA_PROJECTION
            AppServices.prefs.screenCaptureMethod = selected
        }
        Spacer(Modifier.height(10.dp))
        val accessibilitySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        CaptureMethodRow("无障碍", if (accessibilitySupported) "使用 IDVB 无障碍服务截图；启用后无需反复确认屏幕捕获" else "需要 Android 11 或更高版本",
            selected == ScreenCaptureMethod.ACCESSIBILITY, accessibilitySupported,
            { Icon(Icons.Outlined.AccessibilityNew, null, tint = SignalGreen) }) {
            selected = ScreenCaptureMethod.ACCESSIBILITY
            AppServices.prefs.screenCaptureMethod = selected
            openAccessibilityServiceSettings(context)
        }
        if (selected == ScreenCaptureMethod.ACCESSIBILITY) {
            TextButton(onClick = { openAccessibilityServiceSettings(context) }, modifier = Modifier.align(Alignment.End)) {
                Text("打开 IDVB 无障碍设置")
            }
        }
        Text("更改后将在下一次启动悬浮服务时生效。无障碍模式仅用于截图，不读取或操作界面内容。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        Spacer(Modifier.height(8.dp))
        Text("攻略地图显示", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        var constrainGuideToScreen by remember { mutableStateOf(AppServices.prefs.constrainGuideToScreen) }
        VisualOptionRow(
            title = "限制显示边界",
            description = "防止攻略地图超出屏幕范围。",
            checked = constrainGuideToScreen,
            onCheckedChange = {
                constrainGuideToScreen = it
                AppServices.prefs.constrainGuideToScreen = it
            },
        )
        Spacer(Modifier.height(10.dp))
        var removeGuideBackground by remember { mutableStateOf(AppServices.prefs.removeGuideBackground) }
        VisualOptionRow(
            title = "去掉地图背景",
            description = "自动识别并移除地图背景色，支持一定颜色范围。",
            checked = removeGuideBackground,
            onCheckedChange = {
                removeGuideBackground = it
                AppServices.prefs.removeGuideBackground = it
            },
        )
    }
}

@Composable
private fun VisualOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        border = BorderStroke(1.dp, if (checked) SignalGreenDeep else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun CaptureMethodRow(title: String, description: String, selected: Boolean, enabled: Boolean,
    icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) .55f else .25f),
        border = BorderStroke(1.dp, if (selected) SignalGreenDeep else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(2.dp), color = Deep) { Box(contentAlignment = Alignment.Center) { icon() } }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
    }
}
