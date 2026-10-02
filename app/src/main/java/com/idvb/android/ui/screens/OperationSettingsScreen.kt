package com.idvb.android.ui.screens

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.idvb.android.AppServices
import com.idvb.android.UsageConsent
import com.idvb.android.alignment.AutoMapOpenReferenceStore
import com.idvb.android.overlay.OverlayService
import com.idvb.android.data.EyeButtonAction
import com.idvb.android.data.SearchButtonAction
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.Paper
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep

@Composable
fun OperationSettingsScreen(onBack: () -> Unit) {
    var searchAction by remember { mutableStateOf(AppServices.prefs.searchButtonAction) }
    var eyeAction by remember { mutableStateOf(AppServices.prefs.eyeButtonAction) }
    var autoDetectMapOpen by remember { mutableStateOf(AppServices.prefs.autoDetectMapOpenEnabled) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("操作", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            Text("按键操作", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
            Spacer(Modifier.height(10.dp))
            OperationSection(Icons.Outlined.Search, "按下“🔍”时的操作", searchAction.label) {
                Column(Modifier.selectableGroup()) {
                    SearchButtonAction.entries.forEach { action ->
                        OperationChoice(action.label, searchAction == action) {
                            searchAction = action
                            AppServices.prefs.searchButtonAction = action
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OperationSection(
                Icons.Outlined.Visibility, "按下“👁”时的操作",
                eyeAction.label,
            ) {
                Text("打开时", style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
                Column(Modifier.selectableGroup()) {
                    EyeButtonAction.entries.forEach { action ->
                        OperationChoice(action.label, eyeAction == action) {
                            eyeAction = action
                            AppServices.prefs.eyeButtonAction = action
                        }
                    }
                }
                Text(
                    when (eyeAction) {
                        EyeButtonAction.SHOW_AND_ALIGN -> "每次显示时，根据当前游戏画面重新贴合所选地图和楼层。请先打开游戏地图并校准显示区域。"
                        EyeButtonAction.SHOW_ONLY -> "保留当前显示位置，不重新贴合。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text("有待确认的扫描结果时，👁 会先打开候选列表供选择。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            AutoMapOpenSettings(autoDetectMapOpen) { enabled ->
                autoDetectMapOpen = enabled
                AppServices.prefs.autoDetectMapOpenEnabled = enabled
            }
            Text("悬浮窗布局调整请从“…”菜单进入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OperationSection(
    icon: ImageVector,
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起设置" else "展开设置") { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(2.dp), color = Deep) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = SignalGreen, modifier = Modifier.size(23.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
                }
            }
        }
    }
}

@Composable
private fun OperationChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AutoMapOpenSettings(enabled: Boolean, onEnabledChanged: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("自动检测开图", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("智能识别游戏开图并自动贴合，关图后停止。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(enabled, onCheckedChange = onEnabledChanged)
            }
            Text("系统已内置地图侧边栏参照，无需手动设置。校准显示区域并扫描或选择地图后，持续检测当前画面：打开游戏地图自动贴合，关闭后自动隐去，无需额外点击。可在“视觉”选择屏幕捕获以获得更快响应。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
