package com.idvb.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.idvb.android.AppServices
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.onboarding.CaptureMethodDescriptions
import com.idvb.android.ui.theme.SignalGreenDeep
import com.idvb.android.ui.components.SettingsChoiceCard

@Composable
fun VisionSettingsScreen(onBack: () -> Unit) {
    var captureMethod by remember { mutableStateOf(AppServices.prefs.screenCaptureMethod) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("视觉", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text("屏幕捕获方式", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Column(Modifier.selectableGroup().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsChoiceCard("屏幕捕获", CaptureMethodDescriptions.MEDIA_PROJECTION,
                Icons.AutoMirrored.Outlined.ScreenShare, captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION) {
                captureMethod = ScreenCaptureMethod.MEDIA_PROJECTION
                AppServices.prefs.screenCaptureMethod = captureMethod
            }
            SettingsChoiceCard("无障碍", CaptureMethodDescriptions.ACCESSIBILITY,
                Icons.Outlined.AccessibilityNew, captureMethod == ScreenCaptureMethod.ACCESSIBILITY,
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                captureMethod = ScreenCaptureMethod.ACCESSIBILITY
                AppServices.prefs.screenCaptureMethod = captureMethod
            }
        }
        Text("正式启动悬浮窗时再进行授权。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("攻略地图显示", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
        Spacer(Modifier.height(10.dp))
        var constrainGuideToScreen by remember { mutableStateOf(AppServices.prefs.constrainGuideToScreen) }
        var showRoutes by remember { mutableStateOf(AppServices.prefs.showRoutes) }
        var thickness by remember { mutableIntStateOf(AppServices.prefs.routeLineThickness) }
        VisualOptionRow("显示路线", "显示支持的地图中保存的路线、方框和文字标注。", showRoutes) {
            showRoutes = it
            AppServices.prefs.showRoutes = it
        }
        Spacer(Modifier.height(12.dp))
        RouteThicknessSlider(value = thickness, enabled = showRoutes, onValueChange = {
            thickness = it
            AppServices.prefs.routeLineThickness = it
        })
        Text("调整矢量线路和方框的粗细；图片中已有的路线不受影响。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteThicknessSlider(value: Int, enabled: Boolean, onValueChange: (Int) -> Unit) {
    val labels = listOf("细", "中", "粗", "更粗")
    val colors = MaterialTheme.colorScheme
    val activeColor = if (enabled) colors.secondary else colors.onSurface.copy(alpha = .18f)
    val trackColor = if (enabled) colors.outlineVariant else colors.onSurface.copy(alpha = .1f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("线路粗细", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("当前：${labels[value]}", style = MaterialTheme.typography.bodySmall,
            color = if (enabled) colors.secondary else colors.onSurfaceVariant)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(kotlin.math.round(it).toInt().coerceIn(0, 3)) },
        valueRange = 0f..3f,
        steps = 2,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
            contentDescription = "线路粗细"
            stateDescription = labels[value]
        },
        thumb = {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                color = colors.surface,
                border = BorderStroke(2.dp, activeColor),
                shadowElevation = if (enabled) 2.dp else 0.dp,
            ) {}
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val start = Offset(if (isRtl) size.width else 0f, center.y)
                val end = Offset(if (isRtl) 0f else size.width, center.y)
                drawLine(trackColor, start, end, strokeWidth = size.height, cap = StrokeCap.Round)
                val fraction = (state.value / 3f).coerceIn(0f, 1f)
                if (fraction > 0f) drawLine(activeColor, start,
                    Offset(start.x + (end.x - start.x) * fraction, center.y),
                    strokeWidth = size.height, cap = StrokeCap.Round)
            }
        },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label ->
            Text(label, style = MaterialTheme.typography.labelSmall,
                fontWeight = if (index == value) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled && index == value) colors.secondary else colors.onSurfaceVariant)
        }
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
