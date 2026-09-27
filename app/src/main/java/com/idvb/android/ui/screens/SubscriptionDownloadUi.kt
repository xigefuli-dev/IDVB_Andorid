package com.idvb.android.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idvb.android.data.CommunityDownloadJob
import com.idvb.android.data.CommunityJobStatus
import kotlin.math.sin

@Composable
fun SubscriptionDownloadFab(jobs: List<CommunityDownloadJob>, onClick: () -> Unit) {
    val active = jobs.firstOrNull { it.status == CommunityJobStatus.RUNNING }
    val pending = jobs.count { it.status == CommunityJobStatus.QUEUED || it.status == CommunityJobStatus.RUNNING }
    val progress = active?.fraction?.coerceIn(0f, 1f)
    val wave by rememberInfiniteTransition(label = "download-wave").animateFloat(
        initialValue = 0f, targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "wave-offset")
    val fill = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.primaryContainer
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Box(Modifier.size(72.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(base)) {
            Canvas(Modifier.matchParentSize()) {
                if (progress != null && progress > 0f) {
                    val waterline = size.height * (1f - progress)
                    val liquid = Path().apply {
                        moveTo(0f, waterline)
                        for (x in 0..size.width.toInt() step 3) {
                            lineTo(x.toFloat(), waterline + sin(x / size.width * 12f + wave) * 3.dp.toPx())
                        }
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(liquid, fill)
                }
            }
        }
        // Draw the label after the liquid and give it its own surface for contrast at every fill level.
        Box(Modifier.size(38.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f)), contentAlignment = Alignment.Center) {
            if (active == null && pending == 0) Icon(Icons.Outlined.CloudDownload, "查看下载队列", tint = content)
            else Text(if (progress == null || progress == 0f) "…" else "${(progress * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurface, fontSize = if (progress == null) 24.sp else 14.sp)
        }
        if (pending > 0) Box(
            Modifier.align(Alignment.TopEnd).size(25.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center,
        ) {
            Text(pending.toString(), color = MaterialTheme.colorScheme.onSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
fun SubscriptionDownloadDialog(jobs: List<CommunityDownloadJob>, onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载队列") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (jobs.isEmpty()) Text("暂无下载任务")
                jobs.forEach { job ->
                    Column {
                        Text(job.name, style = MaterialTheme.typography.titleSmall)
                        val status = when (job.status) {
                            CommunityJobStatus.QUEUED -> "等待下载 · …"
                            CommunityJobStatus.RUNNING -> "${job.phase} · ${job.fraction?.let { "${(it * 100).toInt()}%" } ?: "…"}"
                            CommunityJobStatus.DONE -> "已安装"
                            CommunityJobStatus.FAILED -> "失败：${job.result.orEmpty()}"
                        }
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (jobs.any { it.status == CommunityJobStatus.DONE || it.status == CommunityJobStatus.FAILED })
                TextButton(onClick = onClear) { Text("清除已结束") }
        },
    )
}
