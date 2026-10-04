package com.idvb.android.ui.screens

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.keepalive.BatteryOptimization
import com.idvb.android.ui.PermissionSnapshot

@Composable
fun BatterySkipDialog(
    permanent: Boolean,
    hideDialog: Boolean,
    onPermanentChange: (Boolean) -> Unit,
    onHideDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳过电池优化白名单") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("如果你始终无法获取对应权限，可以跳过该权限的获取流程，但你的使用体验可能受到影响。\n你可以在 '设置'-'通用'-'权限管理' 中调整。")
                Spacer(Modifier.height(12.dp))
                SkipOption("永久跳过该权限", permanent, onPermanentChange)
                SkipOption("不再显示该弹窗", hideDialog, onHideDialogChange)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认并保存") } },
    )
}

@Composable
private fun SkipOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun PermissionManagementScreen(permissions: PermissionSnapshot, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var permanent by remember { mutableStateOf(AppServices.prefs.skipBatteryOptimization) }
    var hideDialog by remember { mutableStateOf(AppServices.prefs.hideBatterySkipDialog) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("权限管理", style = MaterialTheme.typography.headlineSmall)
        }
        Text("跳过的权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("电池白名单优化", style = MaterialTheme.typography.titleLarge)
                Text(when {
                    permissions.batteryOptimization -> "已获得权限"
                    permanent -> "已永久跳过"
                    permissions.batteryOptimizationSkipped -> "本次已跳过"
                    else -> "未跳过"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SkipOption("永久跳过该权限", permanent) {
                    permanent = it
                    AppServices.prefs.skipBatteryOptimization = it
                    onChanged()
                }
                SkipOption("不再显示该弹窗", hideDialog) {
                    hideDialog = it
                    AppServices.prefs.hideBatterySkipDialog = it
                    onChanged()
                }
                Text("关闭永久跳过后，下次启动会重新检查该权限。", style = MaterialTheme.typography.bodySmall)
                if (!permanent && permissions.batteryOptimizationSkipped) TextButton(onClick = onChanged) {
                    Text("取消本次跳过")
                }
                TextButton(onClick = {
                    if (!BatteryOptimization.requestIgnoreBatteryOptimizations(context)) {
                        BatteryOptimization.openBatteryOptimizationSettings(context)
                    }
                }) { Text("获取权限") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
