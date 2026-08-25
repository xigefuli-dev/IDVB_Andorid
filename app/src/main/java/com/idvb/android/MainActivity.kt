package com.idvb.android

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.idvb.android.ui.screens.HomeScreen
import com.idvb.android.ui.screens.maplist.MapListScreen
import com.idvb.android.ui.screens.SettingsScreen
import com.idvb.android.ui.screens.TemplateManagerScreen
import com.idvb.android.ui.screens.TemplatePickerScreen
import com.idvb.android.ui.screens.MapImagesScreen
import com.idvb.android.ui.screens.GateMarkerScreen
import com.idvb.android.data.MapTemplate
import android.net.Uri
import com.idvb.android.ui.screens.maplist.importIdvmUri
import com.idvb.android.ui.theme.IDVBTheme
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.idvm.ImportResult
import com.idvb.android.overlay.OverlayService
import com.idvb.android.ui.rememberPermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var catalogTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IDVBTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var page by remember { mutableStateOf("main") }
                var navigatingForward by remember { mutableStateOf(true) }
                var creationClassId by remember { mutableStateOf<String?>(null) }
                var creationTemplate by remember { mutableStateOf<MapTemplate?>(null) }
                var creationTitle by remember { mutableStateOf("") }
                var creationImages by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
                val permissions = rememberPermissionController()
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    // 始终保留同一块布局空间，避免页面切换时高度变化造成“斜向”动画。
                    bottomBar = { MainBottomBar(tab = tab, visible = page == "main", onSelect = { selected -> navigatingForward = selected > tab; tab = selected }) },
                    floatingActionButton = {
                        if (page == "main" && (tab == 0 || tab == 1)) ServiceStatusFab(
                            ready = permissions.snapshot.allGranted,
                            onClick = {
                                if (permissions.snapshot.allGranted) OverlayService.start(this@MainActivity)
                                else permissions.requestNextMissing()
                            },
                        )
                    },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        AnimatedContent(
                            targetState = page to tab,
                            transitionSpec = {
                                if (navigatingForward) {
                                    (fadeIn() + slideInHorizontally { it / 5 }) togetherWith
                                        (fadeOut() + slideOutHorizontally { -it / 5 })
                                } else {
                                    (fadeIn() + slideInHorizontally { -it / 5 }) togetherWith
                                        (fadeOut() + slideOutHorizontally { it / 5 })
                                }
                            },
                            label = "page-transition",
                        ) { (currentPage, currentTab) ->
                            when (currentPage) {
                                "templates" -> TemplateManagerScreen { navigatingForward = false; page = "main" }
                                "template-picker" -> TemplatePickerScreen(onBack = { navigatingForward = false; page = "main" }) { creationTemplate = it; navigatingForward = true; page = "map-images" }
                                "map-images" -> creationTemplate?.let { template -> MapImagesScreen(template, creationTitle, { navigatingForward = false; page = "template-picker" }) { title, images -> creationTitle = title; creationImages = images; navigatingForward = true; page = "gate-marker" } }
                                "gate-marker" -> creationTemplate?.let { template -> creationClassId?.let { targetClass -> GateMarkerScreen(template, creationTitle, creationImages, targetClass, { navigatingForward = false; page = "map-images" }) { catalogTick++; tab = 1; navigatingForward = false; page = "main" } } }
                                else -> when (currentTab) {
                                    0 -> HomeScreen(permissions = permissions.snapshot)
                                    1 -> MapListScreen(refreshTick = catalogTick) { classId, defaultTitle -> creationClassId = classId; creationTitle = defaultTitle; navigatingForward = true; page = "template-picker" }
                                    else -> SettingsScreen { navigatingForward = true; page = "templates" }
                                }
                            }
                        }
                    }
                }
            }
        }
        handleFileIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFileIntent(intent)
    }

    private fun handleFileIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.action = null // configuration changes must not import the same package twice
        lifecycleScope.launch {
            val (result, _) = withContext(Dispatchers.IO) { importIdvmUri(this@MainActivity, uri) }
            val message = when (result) {
                is ImportResult.Success -> {
                    catalogTick++
                    "地图导入成功"
                }
                is ImportResult.Failure -> "导入失败：${result.reason}"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun ServiceStatusFab(ready: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
        containerColor = if (ready) MaterialTheme.colorScheme.secondary else Color(0xFF34373B),
        contentColor = if (ready) Color.White else SignalGreen,
    ) {
        Icon(
            imageVector = if (ready) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = if (ready) "启动服务" else "补全权限",
            modifier = Modifier.size(28.dp),
        )
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("首页", Icons.Outlined.Home),
    NavItem("列表", Icons.AutoMirrored.Outlined.ListAlt),
    NavItem("设置", Icons.Outlined.Settings),
)

@Composable
private fun MainBottomBar(tab: Int, visible: Boolean = true, onSelect: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .background(Color.Transparent)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 10.dp,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
                val itemWidth = maxWidth / navItems.size
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * tab,
                    animationSpec = spring(dampingRatio = .82f, stiffness = 420f),
                    label = "nav-indicator",
                )
                Box(
                    Modifier.offset(x = indicatorOffset).width(itemWidth).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(29.dp))
                )
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    navItems.forEachIndexed { index, item ->
                        IOSNavItem(item, tab == index) { onSelect(index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.IOSNavItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(item.icon, item.label, tint = foreground, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(2.dp))
        Text(item.label, color = foreground, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
