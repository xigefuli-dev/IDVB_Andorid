package com.idvb.android

import android.os.Bundle
import android.os.Build
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
import androidx.compose.material.icons.outlined.CloudDownload
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import com.idvb.android.ui.screens.MapSubscriptionsScreen
import com.idvb.android.ui.screens.SubscriptionDownloadFab
import com.idvb.android.ui.screens.SubscriptionDownloadDialog
import com.idvb.android.ui.screens.GeneralSettingsScreen
import com.idvb.android.ui.screens.VisionSettingsScreen
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.ui.screens.TemplateManagerScreen
import com.idvb.android.ui.screens.TemplatePickerScreen
import com.idvb.android.ui.screens.MapImagesScreen
import com.idvb.android.ui.screens.GateMarkerScreen
import com.idvb.android.data.MapTemplate
import com.idvb.android.data.TemplateFloor
import android.net.Uri
import com.idvb.android.ui.screens.maplist.importIdvmUri
import com.idvb.android.ui.screens.maplist.importIdvmUris
import com.idvb.android.ui.theme.IDVBTheme
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.idvm.ImportResult
import com.idvb.android.overlay.OverlayService
import com.idvb.android.ui.rememberPermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.idvb.android.tutorial.TutorialStore
import com.idvb.android.tutorial.TutorialStep
import com.idvb.android.tutorial.TutorialPanel
import com.idvb.android.tutorial.TutorialPracticeActivity
import androidx.compose.foundation.layout.heightIn

class MainActivity : ComponentActivity() {
    private var catalogTick by mutableIntStateOf(0)
    private var openHomeRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IDVBTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var page by remember { mutableStateOf("main") }
                var navigatingForward by remember { mutableStateOf(true) }
                var creationClassId by remember { mutableStateOf<String?>(null) }
                var creationMapId by remember { mutableStateOf<String?>(null) }
                var creationTemplate by remember { mutableStateOf<MapTemplate?>(null) }
                var creationTitle by remember { mutableStateOf("") }
                var creationImages by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
                var creationSideDoors by remember { mutableStateOf(emptyList<com.idvb.android.idvm.NormalizedRect>()) }
                val permissions = rememberPermissionController()
                val tutorialStore = remember { TutorialStore.get(context) }
                val tutorial by tutorialStore.state.collectAsState()
                val openPractice = {
                    tutorialStore.update { it.copy(practiceOpen = true) }
                    startActivity(Intent(this@MainActivity, TutorialPracticeActivity::class.java))
                }
                LaunchedEffect(Unit) {
                    if (savedInstanceState == null && tutorial.active && tutorial.practiceOpen && tutorial.step >= TutorialStep.LOBBY) {
                        openPractice()
                    }
                }
                LaunchedEffect(tutorial.active, tutorial.step) {
                    if (!tutorial.active) return@LaunchedEffect
                    val tutorialTab = when (tutorial.step) {
                        TutorialStep.IMPORT -> 2
                        TutorialStep.PERMISSIONS -> 0
                        else -> null
                    } ?: return@LaunchedEffect
                    navigatingForward = tutorialTab > tab
                    page = "main"
                    tab = tutorialTab
                }

                val hasMaps = remember(catalogTick, page, tab) {
                    AppServices.repository.loadCatalog().maps.isNotEmpty()
                }
                var showImportGuideDialog by remember { mutableStateOf(false) }
                var importing by remember { mutableStateOf(false) }
                val downloadJobs by AppServices.communityDownloads.jobs.collectAsState()
                val downloadRevision by AppServices.communityDownloads.completionRevision.collectAsState()
                var showDownloadQueue by remember { mutableStateOf(false) }
                LaunchedEffect(downloadRevision) { if (downloadRevision > 0) catalogTick++ }

                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isEmpty() || importing) return@rememberLauncherForActivityResult
                    scope.launch {
                        importing = true
                        val (result, fileName) = withContext(Dispatchers.IO) { importIdvmUris(context, uris) }
                        importing = false
                        when (result) {
                            is ImportResult.Success -> {
                                catalogTick++
                                Toast.makeText(context, "地图导入成功：$fileName", Toast.LENGTH_LONG).show()
                            }
                            is ImportResult.Failure -> {
                                Toast.makeText(context, "导入失败：${result.reason}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                LaunchedEffect(openHomeRequest) {
                    if (openHomeRequest > 0) {
                        navigatingForward = false
                        page = "main"
                        tab = 0
                        permissions.refresh()
                    }
                }
                LaunchedEffect(page, tab) {
                    if (page == "main" && tab == 0) permissions.refresh()
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    // 始终保留同一块布局空间，避免页面切换时高度变化造成“斜向”动画。
                    bottomBar = { MainBottomBar(tab = tab, visible = page == "main", onSelect = { selected -> navigatingForward = selected > tab; tab = selected }) },
                    floatingActionButton = {
                        val permissionsReady = permissions.snapshot.readyToStart
                        val ready = hasMaps && permissionsReady
                        val description = if (!hasMaps) "导入地图" else if (ready) "启动服务" else "补全权限"
                        if (page == "main" && (tab == 0 || tab == 1)) ServiceStatusFab(
                            ready = ready,
                            description = description,
                            onClick = {
                                if (!hasMaps) {
                                    showImportGuideDialog = true
                                } else if (permissions.snapshot.captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION &&
                                    permissions.snapshot.overlay &&
                                    permissions.snapshot.notifications &&
                                    permissions.snapshot.foregroundService &&
                                    permissions.snapshot.mediaProjectionService &&
                                    permissions.snapshot.batteryOptimization) {
                                    permissions.requestScreenCapture {
                                        OverlayService.start(this@MainActivity)
                                        moveTaskToBack(true)
                                    }
                                } else if (permissions.snapshot.allGranted) {
                                    OverlayService.start(this@MainActivity)
                                    moveTaskToBack(true)
                                } else permissions.requestNextMissing()
                            },
                        )
                        if (page == "main" && tab == 2) SubscriptionDownloadFab(downloadJobs) {
                            showDownloadQueue = true
                        }
                    },
                ) { innerPadding ->
                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (tutorial.active && page == "main") TutorialPanel(
                            store = tutorialStore,
                            hasMaps = hasMaps,
                            permissionsReady = permissions.snapshot.readyToStart,
                            onPractice = openPractice,
                            onPause = { tutorialStore.update { it.copy(active = false) } },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        )
                        AnimatedContent(
                            targetState = page to tab,
                            modifier = Modifier.weight(1f),
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
                                "general-settings" -> GeneralSettingsScreen { navigatingForward = false; page = "main" }
                                "vision-settings" -> VisionSettingsScreen { navigatingForward = false; page = "main" }
                                "template-picker" -> TemplatePickerScreen(onBack = { navigatingForward = false; page = "main" }) {
                                    creationMapId = null
                                    creationSideDoors = emptyList()
                                    creationTemplate = it
                                    creationImages = emptyMap()
                                    navigatingForward = true
                                    page = "map-images"
                                }
                                "map-images" -> creationTemplate?.let { template ->
                                    MapImagesScreen(
                                        template = template,
                                        defaultTitle = creationTitle,
                                        initialImages = creationImages,
                                        existingMapId = creationMapId,
                                        onBack = {
                                            navigatingForward = false
                                            page = if (creationMapId == null) "template-picker" else "main"
                                        },
                                        onDelete = {
                                            creationMapId?.let { mapId ->
                                                AppServices.repository.loadCatalog().maps
                                                    .firstOrNull { it.id == mapId }
                                                    ?.let(AppServices.repository::deleteMap)
                                                if (AppServices.prefs.lastMapId == mapId) {
                                                    AppServices.prefs.lastMapId = null
                                                    AppServices.prefs.lastFloorKey = null
                                                }
                                            }
                                            catalogTick++
                                            tab = 1
                                            creationMapId = null
                                            creationTemplate = null
                                            creationImages = emptyMap()
                                            creationSideDoors = emptyList()
                                            navigatingForward = false
                                            page = "main"
                                        },
                                        onNext = { title, images ->
                                            creationTitle = title
                                            creationImages = images
                                            navigatingForward = true
                                            page = "gate-marker"
                                        },
                                    )
                                }
                                "gate-marker" -> creationTemplate?.let { template -> creationClassId?.let { targetClass ->
                                    GateMarkerScreen(
                                        template = template,
                                        title = creationTitle,
                                        images = creationImages,
                                        classId = targetClass,
                                        onBack = { navigatingForward = false; page = "map-images" },
                                        onSaved = {
                                            catalogTick++
                                            tab = 1
                                            creationMapId = null
                                            creationTemplate = null
                                            creationImages = emptyMap()
                                            creationSideDoors = emptyList()
                                            navigatingForward = false
                                            page = "main"
                                        },
                                        existingMapId = creationMapId,
                                        initialSideDoors = creationSideDoors,
                                    )
                                } }
                                else -> when (currentTab) {
                                    0 -> HomeScreen(
                                        permissions = permissions.snapshot,
                                        hasMaps = hasMaps,
                                        onOpenTutorial = { tutorialStore.update { it.copy(active = true) } },
                                        tutorialLabel = if (tutorial.step == TutorialStep.DONE) "新手教程 · 查看 / 重练" else "新手教程 · 继续第 ${tutorial.step.ordinal + 1} 段",
                                    )
                                    1 -> MapListScreen(
                                        refreshTick = catalogTick,
                                        onOpenImportGuide = { showImportGuideDialog = true },
                                        onCatalogChanged = { catalogTick++ },
                                        onCreateMap = { classId, defaultTitle ->
                                            creationClassId = classId
                                            creationMapId = null
                                            creationTemplate = null
                                            creationTitle = defaultTitle
                                            creationImages = emptyMap()
                                            creationSideDoors = emptyList()
                                            navigatingForward = true
                                            page = "template-picker"
                                        },
                                        onOpenMap = { map ->
                                            val floors = map.floors.sortedBy { it.sortOrder }
                                            creationClassId = map.classId
                                            creationMapId = map.id
                                            creationTemplate = MapTemplate(
                                                id = "saved-${map.id}",
                                                name = map.title,
                                                floors = floors.map { TemplateFloor(it.key, it.displayName) },
                                            )
                                            creationTitle = map.title
                                            creationImages = floors.associate { floor ->
                                                floor.key to Uri.fromFile(AppServices.repository.floorImageFile(map.id, floor.imagePath))
                                            }
                                            creationSideDoors = floors.firstOrNull()?.let { floor ->
                                                AppServices.repository.loadSideDoorsForEditing(map.id, floor)
                                            }.orEmpty()
                                            navigatingForward = true
                                            page = "map-images"
                                        },
                                    )
                                    2 -> MapSubscriptionsScreen()
                                    else -> SettingsScreen(
                                        onOpenGeneral = { navigatingForward = true; page = "general-settings" },
                                        onOpenVision = { navigatingForward = true; page = "vision-settings" },
                                        onOpenTemplates = { navigatingForward = true; page = "templates" },
                                    )
                                }
                            }
                        }
                    }
                }
                if (showImportGuideDialog) {
                    AlertDialog(
                        onDismissRequest = { showImportGuideDialog = false },
                        title = { Text("导入地图包") },
                        text = {
                            Text(
                                "请下载地图包，然后选择“用其他应用打开”或者“分享”，选择 IDVB，随后地图包将会自动加载。",
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showImportGuideDialog = false }) {
                                Text("我知道了")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showImportGuideDialog = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            ) {
                                Text("手动选择文件")
                            }
                        },
                    )
                }
                if (showDownloadQueue) SubscriptionDownloadDialog(
                    downloadJobs,
                    onDismiss = { showDownloadQueue = false },
                    onClear = { AppServices.communityDownloads.clearFinished() },
                )
            }
        }
        handleFileIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_HOME) openHomeRequest++
        handleFileIntent(intent)
    }

    companion object {
        /** 通知栏进入应用时始终落在首页，不能遗留在外部设置跳转前的子页。 */
        const val ACTION_OPEN_HOME = "com.idvb.android.OPEN_HOME"
    }

    private fun handleFileIntent(intent: Intent?) {
        val source = intent ?: return
        val uris = when (source.action) {
            Intent.ACTION_VIEW -> (listOfNotNull(source.data) + source.clipData.allUris()).distinct()
            Intent.ACTION_SEND -> (listOfNotNull(source.sharedStreamUri(), source.data) + source.clipData.allUris()).distinct()
            Intent.ACTION_SEND_MULTIPLE -> (source.sharedStreamUris() + source.clipData.allUris() + listOfNotNull(source.data)).distinct()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        intent.action = null // configuration changes must not import the same package twice
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> importIdvmUri(this@MainActivity, uri).first }
            }
            val successCount = results.count { it is ImportResult.Success }
            if (successCount > 0) catalogTick += successCount
            val failures = results.filterIsInstance<ImportResult.Failure>()
            val message = when {
                failures.isEmpty() && successCount == 1 -> "地图导入成功"
                failures.isEmpty() -> "已导入 $successCount 个地图包"
                successCount > 0 -> "已导入 $successCount 个，${failures.size} 个失败：${failures.first().reason}"
                else -> "导入失败：${failures.first().reason}"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}

private fun Intent.sharedStreamUri(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(Intent.EXTRA_STREAM)
}

private fun Intent.sharedStreamUris(): List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
} else {
    @Suppress("DEPRECATION")
    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}

private fun android.content.ClipData?.firstUri(): Uri? =
    this?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

private fun android.content.ClipData?.allUris(): List<Uri> = buildList {
    val data = this@allUris ?: return@buildList
    for (index in 0 until data.itemCount) data.getItemAt(index).uri?.let(::add)
}

@Composable
private fun ServiceStatusFab(
    ready: Boolean,
    description: String = if (ready) "启动服务" else "补全权限",
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
        containerColor = if (ready) MaterialTheme.colorScheme.secondary else Color(0xFF34373B),
        contentColor = if (ready) Color.White else SignalGreen,
    ) {
        Icon(
            imageVector = if (ready) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
        )
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("首页", Icons.Outlined.Home),
    NavItem("列表", Icons.AutoMirrored.Outlined.ListAlt),
    NavItem("订阅", Icons.Outlined.CloudDownload),
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
