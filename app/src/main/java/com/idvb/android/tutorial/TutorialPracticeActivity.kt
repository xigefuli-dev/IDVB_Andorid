package com.idvb.android.tutorial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.idvb.android.overlay.OverlayService
import com.idvb.android.ui.theme.IDVBTheme

class TutorialPracticeActivity : ComponentActivity() {
    private val store by lazy { TutorialStore.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IDVBTheme {
                BackHandler { leave() }
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    val panelWidth = if (maxWidth < 700.dp) 260.dp else 300.dp
                    if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
                        PracticeBoard(store, Modifier.weight(1f).fillMaxHeight())
                        TutorialPanel(store, inPractice = true, onPause = ::leave,
                            modifier = Modifier.width(panelWidth).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        PracticeBoard(store, Modifier.fillMaxWidth().weight(1f))
                        TutorialPanel(store, inPractice = true, onPause = ::leave,
                            modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        OverlayService.setPracticeForeground(true)
    }
    override fun onStop() {
        OverlayService.setPracticeForeground(false)
        super.onStop()
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (store.state.value.practice.mode == "adjust" && event.keyCode in listOf(
                android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) store.update { it.copy(practice = it.practice.copy(
                opacity = (it.practice.opacity + if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) .05f else -.05f).coerceIn(.1f, 1f),
                changed = true)) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun leave() {
        store.update { it.copy(practiceOpen = false) }
        finish()
    }
}

@Composable
private fun PracticeBoard(store: TutorialStore, modifier: Modifier) {
    val progress by store.state.collectAsState()
    val p = progress.practice
    var message by remember(progress.step) { mutableStateOf("") }
    fun change(transform: (PracticeState) -> PracticeState) = store.update { it.copy(practice = transform(it.practice)) }
    Box(modifier) {
        AndroidView(factory = { PracticeMapView(it, store).apply { update(progress) } },
            update = { it.update(progress) }, modifier = Modifier.fillMaxSize().clipToBounds())
        Text("模拟练习 · 不会操作真正的游戏", color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xCC15241D)).padding(8.dp),
            style = MaterialTheme.typography.labelSmall)
        if (p.scene == "desktop") {
            Button(onClick = { store.performed(TutorialStep.LOBBY) { it.copy(scene = "lobby") } },
                modifier = Modifier.align(Alignment.Center)) { Text("进入加页手记") }
        } else if (p.mode == "normal") {
            Column(Modifier.align(Alignment.TopStart).padding(top = 34.dp, start = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        if (progress.step < TutorialStep.SCAN) message = "先跟着右边的步骤完成准备，再扫描。"
                        else if (!p.mapOpen) message = "先打开游戏地图，再扫描。"
                        else {
                            store.performed(TutorialStep.SCAN) { it.copy(scanned = true, selected = false, visible = false) }
                            message = "练习扫描完成，点 👁 查看候选地图。"
                        }
                    }, contentPadding = PaddingValues(8.dp)) { Text("🔍") }
                    Button(onClick = {
                        when {
                            p.scanned -> change { it.copy(mode = "candidates", menu = false) }
                            p.selected -> store.performed(TutorialStep.SHOW) { it.copy(visible = !it.visible) }
                            else -> message = "还没有候选地图，请先扫描。"
                        }
                    }, contentPadding = PaddingValues(8.dp)) { Text("👁") }
                    if (p.selected) Button(onClick = {
                        store.performed(TutorialStep.SWITCH) { it.copy(variant = 1 - it.variant) }
                        message = "已切换到相似图 ${if (p.variant == 0) "B" else "A"}，没有重新扫描。"
                    }, contentPadding = PaddingValues(8.dp)) { Text("⇆") }
                    Button(onClick = { change { it.copy(menu = !it.menu) } }, contentPadding = PaddingValues(8.dp)) { Text("…") }
                }
                if (p.menu) Surface(tonalElevation = 6.dp) {
                    Column {
                        TextButton(onClick = {
                            if (!p.mapOpen || p.scene != "lobby") message = "请在校准练习中先打开游戏地图。"
                            else change { it.copy(mode = "calibrate", menu = false) }
                        }) { Text("校准显示区域") }
                        TextButton(onClick = {
                            if (!p.selected) message = "请先扫描并选中地图。"
                            else change { it.copy(mode = "adjust", menu = false, visible = true, changed = false) }
                        }) { Text("自由调整") }
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    progress.step == TutorialStep.ENTER && p.scene == "lobby" -> Button(onClick = { change { it.copy(scene = "side", mapOpen = false) } }) { Text("从侧门入场") }
                    p.scene == "side" -> Button(onClick = { change { it.copy(scene = "game", mapOpen = false) } }) { Text("进入场景") }
                    else -> Button(onClick = {
                        if (p.scene == "game" && !p.mapOpen) store.performed(TutorialStep.ENTER) { it.copy(mapOpen = true) }
                        else change { it.copy(mapOpen = !it.mapOpen) }
                    }) { Text(if (p.mapOpen) "关闭游戏地图" else "打开游戏地图") }
                }
            }
        } else if (p.mode == "calibrate") {
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { change { it.copy(mode = "normal") } }) { Text("取消") }
                Button(onClick = { change { it.copy(rect = emptyList()) } }) { Text("重选") }
                Button(onClick = {
                    if (isPracticeCalibrationValid(p.rect)) {
                        store.performed(TutorialStep.CALIBRATE) { it.copy(mode = "normal") }
                        message = "练习区域已保存。正式游戏里也需要校准一次。"
                    } else message = "请沿虚线框选整块地图画布，不要只圈一个房间。"
                }) { Text("确认并保存") }
            }
        } else if (p.mode == "candidates") {
            Surface(Modifier.align(Alignment.Center).padding(12.dp), tonalElevation = 8.dp) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("地图候选列表 · 练习", style = MaterialTheme.typography.titleMedium)
                    Text("对照房间与走廊。本局对应 A，B 是同组相似图。")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (variant in 0..1) Column(Modifier.weight(1f)) {
                            val preview = progress.copy(practice = PracticeState(scene = "game", mapOpen = true,
                                visible = true, variant = variant))
                            AndroidView(factory = { PracticeMapView(it, store).apply { update(preview) } },
                                update = { it.update(preview) }, modifier = Modifier.fillMaxWidth().height(100.dp).clipToBounds())
                            Text(if (variant == 0) "A · 本局布局" else "B · 相似布局", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Button(onClick = {
                        store.performed(TutorialStep.SELECT) { it.copy(selected = true, scanned = false, visible = false, variant = 0, mode = "normal") }
                        message = "已选中 A，再点一次 👁 显示地图。"
                    }) { Text("本局地图 · A　✓ 房间布局一致") }
                    OutlinedButton(onClick = { message = "B 是相似图，请先选择本局对应的 A；稍后练习 ⇆ 切换。" }) { Text("相似地图 · B　房间标记不同") }
                    TextButton(onClick = { change { it.copy(mode = "normal") } }) { Text("返回画面") }
                }
            }
        } else if (p.mode == "adjust") {
            Column(Modifier.align(Alignment.BottomCenter).background(Color(0xCC15241D)).padding(4.dp)) {
                Text("拖动位置 · 双指缩放 · 音量键调透明度", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { change { it.copy(scale = (it.scale - .1f).coerceAtLeast(.5f), changed = true) } }) { Text("缩小") }
                    TextButton(onClick = { change { it.copy(scale = (it.scale + .1f).coerceAtMost(1.8f), changed = true) } }) { Text("放大") }
                    TextButton(onClick = { change { it.copy(opacity = (it.opacity - .1f).coerceAtLeast(.1f), changed = true) } }) { Text("变淡") }
                    TextButton(onClick = { change { it.copy(opacity = (it.opacity + .1f).coerceAtMost(1f), changed = true) } }) { Text("变深") }
                    Button(onClick = {
                        if (!p.changed) message = "先试着拖动地图、改大小或调透明度，再保存。"
                        else {
                            store.performed(TutorialStep.ADJUST) { it.copy(mode = "normal") }
                            message = "练习调整已保存。"
                        }
                    }) { Text("保存") }
                }
            }
        }
        if (message.isNotEmpty()) Surface(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 8.dp).widthIn(max = 250.dp),
            color = MaterialTheme.colorScheme.inverseSurface) {
            TextButton(onClick = { message = "" }) { Text(message, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
