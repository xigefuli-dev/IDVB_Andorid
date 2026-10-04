package com.idvb.android.ui.screens.maplist

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import com.idvb.android.AppServices
import com.idvb.android.idvm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.idvb.android.ui.screens.Eyebrow
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.Ink
import com.idvb.android.ui.theme.Paper
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapListScreen(
    refreshTick: Int,
    onOpenImportGuide: () -> Unit = {},
    onCatalogChanged: () -> Unit = {},
    onCreateMap: (String?, String) -> Unit = { _, _ -> },
    onOpenMap: (MapRecord) -> Unit = {},
) {
    val context = LocalContext.current
    val catalog by AppServices.repository.catalogState.collectAsState()
    var classId by remember { mutableStateOf(AppServices.prefs.selectedMapClassId) }
    var deleteMode by remember { mutableStateOf(false) }
    var classMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var newClassDialog by remember { mutableStateOf(false) }
    var newClassName by remember { mutableStateOf("") }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("overlay", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "selected_map_class_id") classId = AppServices.prefs.selectedMapClassId
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(refreshTick) { withContext(Dispatchers.IO) { AppServices.repository.loadCatalog() } }
    LaunchedEffect(catalog) {
        val doc = catalog ?: return@LaunchedEffect
        if (classId !in doc.classes.map { it.id }) classId = doc.classes.firstOrNull()?.id
        AppServices.prefs.selectedMapClassId = classId
    }
    val doc = catalog
    val currentClass = doc?.classes?.firstOrNull { it.id == classId }
    val maps = doc?.maps?.filter { it.classId == classId }.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("地图系统", style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = classMenuOpen,
                    onExpandedChange = { if (!doc?.classes.isNullOrEmpty()) classMenuOpen = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = currentClass?.name ?: "暂无关卡", onValueChange = {}, readOnly = true,
                        singleLine = true, label = { Text("关卡模式", lineHeight = 16.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classMenuOpen) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().height(64.dp),
                    )
                    ExposedDropdownMenu(classMenuOpen, { classMenuOpen = false }) {
                        doc?.classes?.forEach { mode -> DropdownMenuItem(
                            text = { Text(mode.name, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)) },
                            onClick = {
                                classId = mode.id
                                AppServices.prefs.selectedMapClassId = mode.id
                                classMenuOpen = false
                            },
                        ) }
                    }
                }
                Box {
                    IconButton(
                        onClick = { moreMenuOpen = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("创建地图") },
                            leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
                            enabled = currentClass != null,
                            onClick = { moreMenuOpen = false; onCreateMap(currentClass?.id, "地图 ${maps.size + 1}") },
                        )
                        DropdownMenuItem(
                            text = { Text("新建关卡") },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            onClick = {
                                moreMenuOpen = false
                                newClassName = ""
                                newClassDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入地图包") },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                moreMenuOpen = false
                                onOpenImportGuide()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除关卡") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            enabled = currentClass != null,
                            onClick = {
                                moreMenuOpen = false
                                deleteMode = true
                            },
                        )
                    }
                }
            }
        }

        when {
            doc == null -> SkeletonGrid()
            doc.classes.isEmpty() -> EmptyMessage("暂无地图\n请先下载地图包并使用本应用打开")
            maps.isEmpty() -> EmptyMessage("“${currentClass?.name.orEmpty()}”关卡下暂无地图")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(maps, key = { it.id }) { map -> MapCoverCard(map, onClick = { onOpenMap(map) }) }
            }
        }
    }

    if (newClassDialog) {
        val trimmedName = newClassName.trim()
        val duplicateName = doc?.isClassNameTaken(trimmedName) == true
        AlertDialog(
            onDismissRequest = { newClassDialog = false },
            title = { Text("新建关卡") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新关卡暂不包含地图，可在更多菜单中导入地图包。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = newClassName,
                        onValueChange = { newClassName = it },
                        singleLine = true,
                        label = { Text("关卡名称") },
                        isError = duplicateName,
                        supportingText = { if (duplicateName) Text("该关卡名称已存在") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = trimmedName.isNotEmpty() && !duplicateName,
                    onClick = {
                        val current = catalog ?: MapCatalogDocument()
                        val created = ClassRecord("class-${UUID.randomUUID()}", trimmedName)
                        val updated = current.copy(classes = current.classes + created)
                        AppServices.repository.saveCatalog(updated)
                        classId = created.id
                        AppServices.prefs.selectedMapClassId = created.id
                        onCatalogChanged()
                        newClassDialog = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newClassDialog = false }) { Text("取消") } },
        )
    }
    if (deleteMode) AlertDialog(
        onDismissRequest = { deleteMode = false }, title = { Text("删除当前关卡？") },
        text = { Text("将删除“${currentClass?.name}”关卡下的 ${maps.size} 张地图及其本地图片，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = {
            classId?.let(AppServices.repository::deleteClass)
            onCatalogChanged()
            deleteMode = false
        }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteMode = false }) { Text("取消") } },
    )
}

@Composable
private fun MapCoverCard(map: MapRecord, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(2.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(Deep)) {
                val mainFloor = map.floors.minByOrNull { it.sortOrder }
                if (mainFloor == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                mainFloor?.let { floor -> FloorThumbnail(map.id, floor, Modifier.fillMaxSize()) }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Text(map.title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${map.floors.size} 个楼层", fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun FloorThumbnail(mapId: String, floor: FloorRecord, modifier: Modifier) {
    val preview by produceState<PreviewState>(PreviewState.Loading, mapId, floor.imagePath) {
        value = withContext(Dispatchers.IO) { decodePreviewCrop(mapId, floor)?.let(PreviewState::Ready) ?: PreviewState.Missing }
    }
    Box(modifier.background(Deep), contentAlignment = Alignment.Center) {
        when (val state = preview) {
            is PreviewState.Ready -> Image(state.bitmap.asImageBitmap(), floor.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            PreviewState.Missing -> Text("预览文件缺失", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreviewState.Loading -> Unit
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Missing : PreviewState
    data class Ready(val bitmap: android.graphics.Bitmap) : PreviewState
}

/**
 * 解码 Desktop 保存的 recognitionRegion，并按 freeCropPoints 裁出透明多边形。
 * 这同时避免高分辨率地图在列表页因内存压力而显示为空白。
 */
private fun decodePreviewCrop(mapId: String, floor: FloorRecord): android.graphics.Bitmap? {
    val region = AppServices.repository.loadPreviewRegion(mapId, floor)
    val points = AppServices.repository.loadFreeCropPoints(mapId, floor)
    val cacheKey = "$mapId:${floor.imagePath}:$region:$points"
    val image = AppServices.repository.floorImageFile(mapId, floor.imagePath)
    return com.idvb.android.resources.MapBitmapCaches.previews.load(cacheKey) {
        com.idvb.android.graphics.decodeMapRegion(image, region, 720, points)
    }
}
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { items(6) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp)).padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.55f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)))
            Box(Modifier.padding(top = 12.dp).width(92.dp).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
            Box(Modifier.padding(top = 8.dp).width(58.dp).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
        }
    } }
}

@Composable
private fun EmptyMessage(message: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
