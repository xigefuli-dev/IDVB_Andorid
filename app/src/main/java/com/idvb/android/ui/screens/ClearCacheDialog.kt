package com.idvb.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.diagnostics.CacheBreakdown
import com.idvb.android.diagnostics.CacheCleaner
import com.idvb.android.ui.theme.Paper
import com.idvb.android.ui.theme.SignalGreenDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ClearCacheState {
    data object Calculating : ClearCacheState
    data class Ready(val breakdown: CacheBreakdown) : ClearCacheState
    data object Cleaning : ClearCacheState
}

@Composable
fun ClearCacheDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheCleaner = remember(context) { CacheCleaner(context) }
    var state by remember { mutableStateOf<ClearCacheState>(ClearCacheState.Calculating) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val breakdown = cacheCleaner.calculateCache()
            state = ClearCacheState.Ready(breakdown)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (state !is ClearCacheState.Cleaning) {
                onDismiss()
            }
        },
        title = {
            Text("清理缓存", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            when (val currentState = state) {
                is ClearCacheState.Calculating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = SignalGreenDeep,
                        )
                        Text(
                            "正在计算非必要缓存与日志数据...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ClearCacheState.Cleaning -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = SignalGreenDeep,
                        )
                        Text(
                            "正在清理缓存，请稍候...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ClearCacheState.Ready -> {
                    val breakdown = currentState.breakdown
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (breakdown.totalBytes == 0L) {
                            Text(
                                "未发现非必要缓存文件，当前无需清理。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "共发现 ${breakdown.formattedTotal} 非必要缓存：",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Surface(
                                shape = RoundedCornerShape(2.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "日志与诊断数据",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            breakdown.formattedDiagnostics,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "包含识别与贴合记录、崩溃日志、屏幕截图等数据",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "临时文件与缓存",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            breakdown.formattedTempCache,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "包含导入解压临时文件、对齐参考缓存等临时数据",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "清理后不会影响已保存的地图、订阅列表与用户设置。是否确认删除？",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            when (val currentState = state) {
                is ClearCacheState.Calculating -> {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
                is ClearCacheState.Ready -> {
                    TextButton(onClick = onDismiss) {
                        Text(if (currentState.breakdown.totalBytes == 0L) "关闭" else "取消")
                    }
                }
                is ClearCacheState.Cleaning -> {
                    // 清理过程中禁止取消
                }
            }
        },
        confirmButton = {
            when (val currentState = state) {
                is ClearCacheState.Ready -> {
                    if (currentState.breakdown.totalBytes > 0L) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    state = ClearCacheState.Cleaning
                                    val freed = withContext(Dispatchers.IO) {
                                        cacheCleaner.clearCache()
                                    }
                                    Toast.makeText(
                                        context,
                                        "已清理 ${CacheCleaner.formatBytes(freed)} 缓存",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignalGreenDeep,
                                contentColor = Paper,
                            ),
                        ) {
                            Text("确认删除")
                        }
                    }
                }
                else -> {}
            }
        }
    )
}
