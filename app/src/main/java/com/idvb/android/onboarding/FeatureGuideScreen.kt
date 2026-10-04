package com.idvb.android.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.ui.components.SettingsChoiceCard
import com.idvb.android.ui.theme.SignalGreenDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FeatureGuideScreen(onFinished: () -> Unit, onExit: () -> Unit, replayAll: Boolean = false) {
    var pending by remember { mutableStateOf(FeatureGuideRegistry.pending(AppServices.prefs.completedFeatureGuides)) }
    // Manual re-selection walks every registered guide, regardless of stored completion.
    // Keep its position across recreation without erasing first-launch acknowledgements.
    var replayIndex by rememberSaveable { mutableIntStateOf(0) }
    val replayGuides = remember { FeatureGuideRegistry.pending(emptySet()) }
    val guide = if (replayAll) replayGuides.getOrNull(replayIndex) else pending.firstOrNull()
    var exit by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { if (!saving) exit = true }
    if (guide == null) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }
    var selected by rememberSaveable(guide.id) { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { exit = true }, enabled = !saving) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Text("预设", style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(10.dp))
                Text(guide.title, style = MaterialTheme.typography.labelLarge, color = SignalGreenDeep)
                Text(guide.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    guide.choices.forEach { choice ->
                        SettingsChoiceCard(
                            title = choice.title,
                            description = choice.description,
                            icon = when {
                                choice.captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION -> Icons.AutoMirrored.Outlined.ScreenShare
                                choice.captureMethod == ScreenCaptureMethod.ACCESSIBILITY -> Icons.Outlined.AccessibilityNew
                                choice.scanPreset == ScanPreset.AUTOMATIC -> Icons.Outlined.AutoFixHigh
                                else -> Icons.Outlined.Map
                            },
                            selected = selected == choice.id,
                            enabled = !saving && (choice.captureMethod != ScreenCaptureMethod.ACCESSIBILITY ||
                                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R),
                            onSelect = { selected = choice.id },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (error) Text("保存失败，请重试。", color = MaterialTheme.colorScheme.error)
            Button(enabled = selected != null && !saving, shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp).heightIn(min = 48.dp), onClick = {
                val choice = selected ?: return@Button
                saving = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) { AppServices.prefs.completeFeatureGuide(guide.id, choice) }
                    saving = false
                    error = !success
                    if (success) {
                        if (replayAll) {
                            if (replayIndex == replayGuides.lastIndex) {
                                // Retain the completed page while its navigation exit animation runs.
                                saving = true
                                onFinished()
                            } else replayIndex++
                        }
                        else pending = FeatureGuideRegistry.pending(AppServices.prefs.completedFeatureGuides)
                    }
                }
            }) { Text(if (saving) "正在保存…" else "确认并继续") }
        }
    }
    if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text(if (replayAll) "结束预设选择？" else "退出应用？") },
        text = { Text(if (replayAll) "已确认的选择会保留，再次进入预设时将从头开始。" else "尚未确认的引导将在下次启动时继续显示。") },
        confirmButton = { TextButton(onClick = onExit) { Text(if (replayAll) "返回设置" else "退出") } },
        dismissButton = { TextButton(onClick = { exit = false }) { Text("继续选择") } })
}
