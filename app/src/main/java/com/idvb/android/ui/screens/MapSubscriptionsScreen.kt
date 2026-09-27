package com.idvb.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idvb.android.data.CommunityEntry
import com.idvb.android.data.CommunitySubscriptions
import com.idvb.android.data.CommunityJobStatus
import com.idvb.android.AppServices
import com.idvb.android.ui.theme.Deep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MapSubscriptionsScreen() {
    val context = LocalContext.current
    val service = remember(context) { CommunitySubscriptions(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val jobs by AppServices.communityDownloads.jobs.collectAsState()
    val completionRevision by AppServices.communityDownloads.completionRevision.collectAsState()
    var entries by remember { mutableStateOf<List<CommunityEntry>>(emptyList()) }
    var subscribedIds by remember { mutableStateOf(service.saved().map { it.id }.toSet()) }
    var selected by remember { mutableStateOf<CommunityEntry?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { service.catalog().filter { it.link != null } } }
            .onSuccess { entries = it }
            .onFailure { snackbar.showSnackbar("无法加载社区地图：${it.message ?: "网络不可用"}") }
        loading = false
    }
    LaunchedEffect(completionRevision) {
        subscribedIds = withContext(Dispatchers.IO) { service.saved().map { it.id }.toSet() }
    }

    fun confirm(entry: CommunityEntry) {
        selected = null
        if (entry.id !in subscribedIds) {
            val added = AppServices.communityDownloads.enqueue(entry)
            scope.launch { snackbar.showSnackbar(if (added) "已加入下载队列" else "已在下载队列中") }
        } else {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { service.unsubscribe(entry.id) } }
                    .onSuccess { subscribedIds = service.saved().map { it.id }.toSet() }
                    .onFailure { snackbar.showSnackbar("取消订阅失败：${it.message ?: "请稍后重试"}") }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("地图订阅", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            if (!loading && entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("暂无可订阅的地图", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(entries, key = { it.id }) { entry ->
                val pending = jobs.any { it.publicationId == entry.id &&
                    it.status in setOf(CommunityJobStatus.QUEUED, CommunityJobStatus.RUNNING) }
                SubscriptionCard(entry, service, entry.id in subscribedIds) {
                    if (pending) scope.launch { snackbar.showSnackbar("已在下载队列中") }
                    else selected = entry
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(if (entry.id in subscribedIds) "是否取消订阅该地图？" else "是否订阅该地图？") },
            confirmButton = { TextButton(onClick = { confirm(entry) }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SubscriptionCard(
    entry: CommunityEntry,
    service: CommunitySubscriptions,
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Card(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                val cover by produceState<android.graphics.Bitmap?>(null, entry.coverUrl) {
                    value = entry.coverUrl?.let { url ->
                        withContext(Dispatchers.IO) { runCatching { service.cover(url) }.getOrNull() }
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(Deep), contentAlignment = Alignment.Center) {
                    if (cover != null) Image(cover!!.asImageBitmap(), entry.name,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Outlined.Map, contentDescription = null,
                        modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primaryContainer)
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                    Text(entry.name, fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${entry.publisher} · ${formatUpdatedAt(entry.updatedAt)}", fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = subscribed,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-8).dp),
            enter = fadeIn(tween(240)) + scaleIn(tween(350), initialScale = .55f) + slideInVertically(tween(350)) { -it / 2 },
            exit = fadeOut(tween(380)) + scaleOut(tween(420), targetScale = .45f,
                transformOrigin = TransformOrigin(0f, 1f)) +
                slideOutHorizontally(tween(420)) { it / 2 },
        ) {
            val angle by transition.animateFloat(label = "sticker-peel") { state ->
                if (state == EnterExitState.Visible) -12f else 55f
            }
            Box(
                modifier = Modifier.size(43.dp).graphicsLayer { rotationZ = angle }
                    .shadow(8.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "已订阅",
                    modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    }
}

private fun formatUpdatedAt(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrElse { value.take(16).replace('T', ' ') }
