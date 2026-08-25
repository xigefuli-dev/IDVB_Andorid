package com.idvb.android.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.idvb.android.AppServices
import com.idvb.android.data.MapTemplate
import com.idvb.android.data.TemplateFloor
import com.idvb.android.idvm.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TemplateManagerScreen(onBack: () -> Unit) {
    var templates by remember { mutableStateOf(AppServices.templates.load()) }
    var showCreate by remember { mutableStateOf(false) }
    var selectedTemplateId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val selectedTemplate = templates.firstOrNull { it.id == selectedTemplateId }
    ScreenHeader("模板", onBack) {
        IconButton(onClick = {
            val template = selectedTemplate
            if (template == null) {
                showCreate = true
            } else if (AppServices.templates.delete(template.id)) {
                templates = AppServices.templates.load()
                selectedTemplateId = null
            } else {
                Toast.makeText(context, "默认模板不能删除", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(
                imageVector = if (selectedTemplate == null) Icons.Outlined.Add else Icons.Outlined.Delete,
                contentDescription = if (selectedTemplate == null) "新建模板" else "删除模板",
                tint = if (selectedTemplate == null) LocalContentColor.current else MaterialTheme.colorScheme.error,
            )
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 76.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(templates, key = { it.id }) { template ->
            val selected = template.id == selectedTemplateId
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { selectedTemplateId = if (selected) null else template.id },
            ) {
                TemplateCard(template, selected)
            }
        }
    }
    if (showCreate) TemplateEditorDialog(onDismiss = { showCreate = false }) { name, floors ->
        AppServices.templates.add(name, floors); templates = AppServices.templates.load(); showCreate = false
    }
}

@Composable
fun TemplatePickerScreen(onBack: () -> Unit, onSelect: (MapTemplate) -> Unit) {
    val templates = remember { AppServices.templates.load() }
    ScreenHeader("选择模板", onBack)
    LazyColumn(Modifier.fillMaxSize().padding(top = 76.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("选择后，地图只能包含模板定义的楼层。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(templates, key = { it.id }) { template ->
            Box(Modifier.clickable { onSelect(template) }) { TemplateCard(template) }
        }
    }
}

@Composable
private fun TemplateCard(template: MapTemplate, selected: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(template.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(template.floors.joinToString("  ·  ") { "${it.id} / ${it.name}" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TemplateEditorDialog(onDismiss: () -> Unit, onSave: (String, List<TemplateFloor>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var floors by remember { mutableStateOf(listOf(TemplateFloor("", ""))) }
    val ids = floors.map { it.id.trim().lowercase() }
    val valid = name.isNotBlank() && floors.isNotEmpty() && floors.all { it.id.isNotBlank() && it.name.isNotBlank() } && ids.distinct().size == ids.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("创建模板") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, singleLine = true) }
            items(floors.size) { index ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(floors[index].id, { v -> floors = floors.toMutableList().also { it[index] = it[index].copy(id = v) } }, Modifier.weight(.8f), label = { Text("楼层 ID") }, singleLine = true)
                    OutlinedTextField(floors[index].name, { v -> floors = floors.toMutableList().also { it[index] = it[index].copy(name = v) } }, Modifier.weight(1.2f), label = { Text("名称") }, singleLine = true)
                }
            }
            item { TextButton(onClick = { floors = floors + TemplateFloor("", "") }) { Icon(Icons.Outlined.Add, null); Text("添加楼层") } }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(name.trim(), floors.map { TemplateFloor(it.id.trim().lowercase(), it.name.trim()) }) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun MapImagesScreen(
    template: MapTemplate,
    defaultTitle: String,
    initialImages: Map<String, Uri> = emptyMap(),
    existingMapId: String? = null,
    onBack: () -> Unit,
    onDelete: () -> Unit = {},
    onNext: (String, Map<String, Uri>) -> Unit,
) {
    var title by remember(template.id, defaultTitle) { mutableStateOf(defaultTitle) }
    var selected by remember(template.id, defaultTitle) { mutableStateOf(initialImages) }
    var pickingFloor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val key = pickingFloor
        if (uri != null && key != null) selected = selected + (key to uri)
        pickingFloor = null
    }
    ScreenHeader(if (existingMapId == null) "创建地图" else "编辑地图", onBack) {
        if (existingMapId != null) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, "删除地图", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 76.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("地图名称") }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        template.floors.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { floor ->
                    FloorImageSlot(floor, selected[floor.id], Modifier.weight(1f)) {
                        pickingFloor = floor.id
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onNext(title.trim(), selected) }, enabled = title.isNotBlank() && selected.size == template.floors.size, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一步：标记门") }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除地图？") },
        text = { Text("将删除“${title.ifBlank { defaultTitle }}”及其全部本地图片，此操作无法撤销。") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
private fun FloorImageSlot(floor: TemplateFloor, uri: Uri?, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = uri?.let { withContext(Dispatchers.IO) { openImageInputStream(context, it)?.use(BitmapFactory::decodeStream) } }
    }
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), floor.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Image, null); Text("选择图片") }
        }
        Text("${floor.id} / ${floor.name}", Modifier.padding(top = 6.dp))
    }
}

@Composable
fun GateMarkerScreen(
    template: MapTemplate,
    title: String,
    images: Map<String, Uri>,
    classId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    existingMapId: String? = null,
    initialSideDoors: List<NormalizedRect> = emptyList(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = template.floors.first()
    val uri = images.getValue(main.id)
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { openImageInputStream(context, uri)?.use(BitmapFactory::decodeStream) }
    }
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    var marks by remember(template.id, existingMapId) { mutableStateOf(initialSideDoors.map(::rectToStroke)) }; var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }; var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    ScreenHeader("标记门 · ${main.name}", onBack)
    Column(Modifier.fillMaxSize().padding(top = 76.dp, bottom = 16.dp)) {
        Text("第一项为主楼层。双指缩放/移动，单指在侧门位置涂抹。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { canvasSize = it }.pointerInput(bitmap, canvasSize) {
            awaitEachGesture {
                var gestureZoom = zoom
                var gesturePan = pan
                fun normalized(position: Offset): Offset {
                val bmp = bitmap ?: return Offset.Zero
                val base = minOf(canvasSize.width.toFloat() / bmp.width, canvasSize.height.toFloat() / bmp.height)
                val w = bmp.width * base; val h = bmp.height * base
                    val untransformed = (position - gesturePan) / gestureZoom
                return Offset(((untransformed.x - (canvasSize.width-w)/2) / w).coerceIn(0f,1f), ((untransformed.y - (canvasSize.height-h)/2) / h).coerceIn(0f,1f))
                }
                val first = awaitFirstDown(requireUnconsumed = false)
                var isPainting = true
                currentStroke = listOf(normalized(first.position))
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        isPainting = false
                        currentStroke = emptyList()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val nextZoom = (gestureZoom * event.calculateZoom()).coerceIn(1f, 6f)
                        val appliedZoom = nextZoom / gestureZoom
                        // Keep the map point below the fingers stationary while scaling, then apply finger movement.
                        gesturePan = centroid - (centroid - panChange - gesturePan) * appliedZoom
                        gestureZoom = nextZoom
                        zoom = gestureZoom
                        pan = gesturePan
                    } else if (isPainting && pressed.size == 1) {
                        currentStroke = currentStroke + normalized(pressed.first().position)
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (isPainting && currentStroke.isNotEmpty()) marks = marks + listOf(currentStroke)
                currentStroke = emptyList()
            }
        }) {
            val bmp = bitmap ?: return@Canvas
            val base = minOf(size.width / bmp.width, size.height / bmp.height)
            val w = bmp.width * base; val h = bmp.height * base; val origin = Offset((size.width-w)/2, (size.height-h)/2)
            withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, pivot = Offset.Zero) }) {
                drawImage(bmp.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(origin.x.toInt(), origin.y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))
                (marks + listOf(currentStroke).filter { it.isNotEmpty() }).forEach { points ->
                    if (points.size == 1) {
                        val p = points.first()
                        drawCircle(Color(0xFF63CF7B), 9f / zoom, Offset(origin.x + p.x*w, origin.y + p.y*h))
                    } else {
                        val path = Path().apply {
                            val first = points.first(); moveTo(origin.x + first.x*w, origin.y + first.y*h)
                            points.drop(1).forEach { point -> lineTo(origin.x + point.x*w, origin.y + point.y*h) }
                        }
                        drawPath(path, Color(0xFF63CF7B), style = Stroke(width = 18f/zoom, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("缩放", style = MaterialTheme.typography.labelLarge)
            Slider(value = zoom, onValueChange = { nextZoom ->
                val focus = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                val appliedZoom = nextZoom / zoom
                pan = focus - (focus - pan) * appliedZoom
                zoom = nextZoom
            }, valueRange = 1f..6f, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text(String.format("%.1f×", zoom), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { marks = emptyList() }, enabled = marks.isNotEmpty()) { Text("清除") }; Button(onClick = {
            bitmap ?: return@Button; saving = true
            val normalized = marks.map { stroke ->
                val left = stroke.minOf { it.x }; val top = stroke.minOf { it.y }
                val right = stroke.maxOf { it.x }; val bottom = stroke.maxOf { it.y }
                NormalizedRect(left.toDouble(), top.toDouble(), (right-left).toDouble().coerceAtLeast(.005), (bottom-top).toDouble().coerceAtLeast(.005))
            }
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (existingMapId == null) {
                            AppServices.repository.createMap(classId, title, template, images, normalized, context.contentResolver)
                        } else {
                            AppServices.repository.updateMap(existingMapId, classId, title, template, images, normalized, context.contentResolver)
                        }
                    }
                    onSaved()
                } catch (error: Exception) {
                    Toast.makeText(context, "保存失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                } finally {
                    saving = false
                }
            }
        }, enabled = marks.isNotEmpty() && !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else "完成并保存") } }
    }
}

private fun rectToStroke(rect: NormalizedRect): List<Offset> = listOf(
    Offset(rect.x.toFloat(), rect.y.toFloat()),
    Offset((rect.x + rect.width).toFloat(), rect.y.toFloat()),
    Offset((rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()),
    Offset(rect.x.toFloat(), (rect.y + rect.height).toFloat()),
)

private fun openImageInputStream(context: android.content.Context, uri: Uri): java.io.InputStream? =
    if (uri.scheme == "file") uri.path?.let { java.io.File(it).inputStream() } else context.contentResolver.openInputStream(uri)

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }; Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); actions() }
}
