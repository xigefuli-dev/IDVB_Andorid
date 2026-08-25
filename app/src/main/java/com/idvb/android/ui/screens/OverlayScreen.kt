package com.idvb.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.overlay.OverlayService
import com.idvb.android.overlay.OverlayState

/**
 * 悬浮窗控制页：实时展示并控制前台服务状态。
 * 锁定态下悬浮窗点击穿透，只能通过本页或常驻通知解锁。
 */
@Composable
fun OverlayScreen() {
    val context = LocalContext.current
    val state by OverlayState.state.collectAsState()
    var localOpacity by remember { mutableFloatStateOf(state.opacity) }
    // 服务端更新透明度时同步本地滑杆
    LaunchedEffect(state.opacity) { localOpacity = state.opacity }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("悬浮窗", style = MaterialTheme.typography.headlineSmall)
        StatusCard(state.running, state.visible, state.locked, state.mapTitle, state.floorLabel)

        if (!state.running) {
            Button(
                onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        OverlayService.start(context)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("启动悬浮窗")
            }
            Text(
                "若未显示，请先在「地图」页选择地图。悬浮窗默认半透明（46%），可在下方调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                OverlayService.sendAction(context, OverlayService.ACTION_TOGGLE_LOCK)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.locked) "🔓 解锁悬浮窗" else "🔒 锁定（点击穿透）")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                OverlayService.sendAction(context, OverlayService.ACTION_TOGGLE_VISIBLE)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.visible) "🙈 隐藏" else "👁 显示")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            OverlayService.sendAction(context, OverlayService.ACTION_CLOSE)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("关闭悬浮窗")
                    }
                }
            }
        }

        HorizontalDivider()

        // 透明度（未运行时写入持久化，运行时远程设置）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("透明度：${(localOpacity * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = localOpacity,
                onValueChange = {
                    localOpacity = it
                    if (state.running) {
                        OverlayService.sendAction(context, OverlayService.ACTION_SET_OPACITY) {
                            putExtra(OverlayService.EXTRA_OPACITY, it)
                        }
                    } else {
                        AppServices.prefs.opacity = it
                    }
                },
                valueRange = 0.1f..1f,
            )
        }

        // 楼层切换（仅运行时）
        if (state.running && state.mapTitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前楼层", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            OverlayService.sendAction(context, OverlayService.ACTION_SET_FLOOR) {
                                putExtra(OverlayService.EXTRA_FLOOR_DELTA, -1)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("◀ 上一层") }
                    Text(
                        state.floorLabel ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedButton(
                        onClick = {
                            OverlayService.sendAction(context, OverlayService.ACTION_SET_FLOOR) {
                                putExtra(OverlayService.EXTRA_FLOOR_DELTA, 1)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("下一层 ▶") }
                }
            }
        }

        Text(
            "提示：锁定后悬浮窗会点击穿透到游戏，此时请通过上方按钮或下拉通知栏的「解锁」通知来解锁。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(running: Boolean, visible: Boolean, locked: Boolean, mapTitle: String?, floorLabel: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (running) "● 运行中" else "○ 未运行",
                style = MaterialTheme.typography.titleMedium,
            )
            if (running) {
                Text("地图：${mapTitle ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("楼层：${floorLabel ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (visible) "状态：显示中" else "状态：已隐藏",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (locked) "状态：已锁定（点击穿透到游戏）" else "状态：未锁定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "悬浮窗未启动。从「地图」页选择地图，或点击下方按钮启动。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
