package com.idvb.android.data

import android.content.Context
import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.idvb.android.data.MapTemplate
import com.idvb.android.idvm.*
import com.idvb.android.idvm.IdvmJson
import com.idvb.android.idvm.MapCatalogDocument
import com.idvb.android.idvm.MapRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

data class ResolvedSideEntranceFeature(
    val file: File,
    val centerX: Double,
    val centerY: Double,
    val radius: Int,
    val width: Int,
    val height: Int,
)

data class ResolvedPrebuiltStructureLine(
    val file: File,
    val width: Int,
    val height: Int,
    val algorithmId: String,
)

data class FloorRecognitionAssets(
    /** 独立识别图；为空时应从楼层原图解码 [recognitionRegion]。 */
    val recognitionImageFile: File?,
    val recognitionRegion: NormalizedRect?,
    val recognitionWidth: Int,
    val recognitionHeight: Int,
    val validMapBounds: NormalizedRect?,
    val sideEntranceFeature: ResolvedSideEntranceFeature?,
    val prebuiltStructureLine: ResolvedPrebuiltStructureLine?,
)

/**
 * 本地地图仓库（对齐参考项目 MapRepository）。
 * 根目录 filesDir/idvb/maps，清单文件 maps.json。
 */
class MapRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json: Json = IdvmJson.instance

    val mapsRoot: File = File(appContext.filesDir, "idvb/maps")
    private val catalogFile: File = File(mapsRoot, "maps.json")
    @Volatile private var cachedCatalog: MapCatalogDocument? = null
    internal var onCatalogChanged: (() -> Unit)? = null

    @Synchronized fun loadCatalog(): MapCatalogDocument {
        cachedCatalog?.let { return it }
        recoverInterruptedImports()
        if (!catalogFile.exists()) return MapCatalogDocument().also { cachedCatalog = it }
        return try {
            json.decodeFromString<MapCatalogDocument>(catalogFile.readText())
        } catch (e: Exception) {
            // 清单损坏按空仓库处理，不阻塞导入
            MapCatalogDocument()
        }.also { cachedCatalog = it }
    }

    @Synchronized fun saveCatalog(doc: MapCatalogDocument) {
        mapsRoot.mkdirs()
        val text = json.encodeToString(MapCatalogDocument.serializer(), doc)
        val tmp = File(mapsRoot, "maps.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(catalogFile)) {
            catalogFile.writeText(text)
            tmp.delete()
        }
        cachedCatalog = doc
        onCatalogChanged?.invoke()
    }

    /** 楼层原图绝对路径 */
    fun floorImageFile(localMapId: String, imagePath: String): File =
        sequenceOf(
            File(mapsRoot, imagePath),
            File(File(mapsRoot, localMapId), imagePath.removePrefix("$localMapId/")),
            File(File(mapsRoot, "$localMapId/maps"), File(imagePath).name),
        ).firstOrNull(File::isFile) ?: File(mapsRoot, imagePath)

    fun deleteMap(map: MapRecord) {
        val dir = File(mapsRoot, map.id)
        if (dir.exists()) dir.deleteRecursively()
        val doc = loadCatalog()
        saveCatalog(
            doc.copy(
                maps = doc.maps.filterNot { it.id == map.id },
                variantGroups = doc.variantGroups.filterNot { map.id in it.mapIds },
                classes = doc.classes.filterNot { c ->
                    c.id == map.classId && doc.maps.none { it.classId == c.id && it.id != map.id }
                },
            )
        )
    }

    fun deleteClass(classId: String) {
        val doc = loadCatalog()
        val mapIds = doc.maps.filter { it.classId == classId }.map { it.id }.toSet()
        mapIds.forEach { File(mapsRoot, it).deleteRecursively() }
        saveCatalog(doc.copy(
            classes = doc.classes.filterNot { it.id == classId },
            maps = doc.maps.filterNot { it.id in mapIds },
            variantGroups = doc.variantGroups.filterNot { it.classId == classId || it.mapIds.any(mapIds::contains) },
        ))
    }

    /** 保存 Android 创建向导的草稿；图片、metadata、门标记与清单作为一个提交单元写入。 */
    fun createMap(
        classId: String,
        title: String,
        template: MapTemplate,
        images: Map<String, Uri>,
        sideDoors: List<NormalizedRect>,
        resolver: ContentResolver,
    ): MapRecord {
        require(template.floors.isNotEmpty() && template.floors.all { images[it.id] != null }) { "楼层图片不完整" }
        require(loadCatalog().classes.any { it.id == classId }) { "关卡不存在" }
        val mapId = UUID.randomUUID().toString()
        val staging = File(mapsRoot, ".staging-$mapId")
        val finalDir = File(mapsRoot, mapId)
        staging.deleteRecursively(); File(staging, "maps").mkdirs(); File(staging, "data").mkdirs()
        try {
            val floors = template.floors.mapIndexed { index, floor ->
                val fileName = "floor-${(index + 1).toString().padStart(3, '0')}.png"
                val output = File(staging, "maps/$fileName")
                openImageInputStream(images.getValue(floor.id), resolver).use { input -> output.outputStream().use(input::copyTo) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取 ${floor.name} 图片" }
                FloorRecord(floor.id, floor.name, index + 1, "$mapId/maps/$fileName", bounds.outWidth, bounds.outHeight)
            }
            val metadata = MetadataDocument(1, MetadataMap(mapId, classId, title, "android", "normalized-top-left-y-down"), floors.map {
                MetadataFloor(it.key, it.displayName, it.sortOrder, "maps/${File(it.imagePath).name}", it.imageWidth, it.imageHeight)
            })
            val gates = GatesDocument(1, sideDoors.mapIndexed { index, rect -> Gate("side-${index + 1}", floors.first().key, "sideEntrance", rect) })
            val anchors = AnchorsDocument(1, floors.associate { it.key to FloorAnchors() })
            File(staging, "data/metadata.json").writeText(json.encodeToString(MetadataDocument.serializer(), metadata))
            File(staging, "data/gates.json").writeText(json.encodeToString(GatesDocument.serializer(), gates))
            File(staging, "data/anchors.json").writeText(json.encodeToString(AnchorsDocument.serializer(), anchors))
            check(staging.renameTo(finalDir)) { "无法提交地图文件" }
            val record = MapRecord(mapId, classId, title, mapId, 1, floors)
            val catalog = loadCatalog()
            saveCatalog(catalog.copy(maps = catalog.maps + record))
            return record
        } catch (e: Exception) {
            staging.deleteRecursively(); if (!loadCatalog().maps.any { it.id == mapId }) finalDir.deleteRecursively()
            throw e
        }
    }

    /** 更新已有地图，保留其本地 ID，避免从地图列表重新进入创建流程时生成重复地图。 */
    fun updateMap(
        mapId: String,
        classId: String,
        title: String,
        template: MapTemplate,
        images: Map<String, Uri>,
        sideDoors: List<NormalizedRect>,
        resolver: ContentResolver,
    ): MapRecord {
        require(template.floors.isNotEmpty() && template.floors.all { images[it.id] != null }) { "楼层图片不完整" }
        val catalog = loadCatalog()
        val existing = catalog.maps.firstOrNull { it.id == mapId } ?: error("地图不存在")
        require(catalog.classes.any { it.id == classId }) { "关卡不存在" }

        val mapDir = File(mapsRoot, mapId)
        val originalMetadata = readDocument<MetadataDocument>(File(mapDir, "data/metadata.json"))
        val originalGates = readDocument<GatesDocument>(File(mapDir, "data/gates.json"))
            ?: GatesDocument(schemaVersion = 1)
        val originalAnchors = readDocument<AnchorsDocument>(File(mapDir, "data/anchors.json"))
            ?: AnchorsDocument(schemaVersion = 1)
        val staging = File(mapsRoot, ".staging-update-$mapId-${UUID.randomUUID()}")
        val backup = File(mapsRoot, ".backup-update-$mapId-${UUID.randomUUID()}")
        staging.deleteRecursively()
        if (mapDir.isDirectory) mapDir.copyRecursively(staging, overwrite = true) else staging.mkdirs()
        File(staging, "maps").mkdirs()
        File(staging, "data").mkdirs()

        try {
            val preservedFloorKeys = mutableSetOf<String>()
            val floors = template.floors.mapIndexed { index, floor ->
                val fileName = "floor-${(index + 1).toString().padStart(3, '0')}.png"
                val output = File(staging, "maps/$fileName")
                val source = images.getValue(floor.id)
                val existingFloor = existing.floors.firstOrNull { it.key.equals(floor.id, ignoreCase = true) }
                val preserveRecognition = existingFloor != null && pointsToFile(
                    source,
                    floorImageFile(mapId, existingFloor.imagePath),
                )
                openImageInputStream(source, resolver).use { input ->
                    output.outputStream().use(input::copyTo)
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取 ${floor.name} 图片" }
                if (preserveRecognition) {
                    preservedFloorKeys += floor.id
                    val metadataFloor = originalMetadata?.floors?.firstOrNull {
                        it.key.equals(floor.id, ignoreCase = true)
                    }
                    existingFloor!!.copy(
                        displayName = floor.name,
                        sortOrder = index + 1,
                        imagePath = "$mapId/maps/$fileName",
                        imageWidth = bounds.outWidth,
                        imageHeight = bounds.outHeight,
                        previewRegion = existingFloor.previewRegion ?: metadataFloor?.recognitionRegion,
                        freeCropPoints = existingFloor.freeCropPoints.ifEmpty { metadataFloor?.freeCropPoints.orEmpty() },
                    )
                } else {
                    FloorRecord(
                        floor.id,
                        floor.name,
                        index + 1,
                        "$mapId/maps/$fileName",
                        bounds.outWidth,
                        bounds.outHeight,
                    )
                }
            }
            val metadata = MetadataDocument(
                schemaVersion = originalMetadata?.schemaVersion ?: 1,
                map = originalMetadata?.map?.copy(title = title)
                    ?: MetadataMap(mapId, classId, title, "android", "normalized-top-left-y-down"),
                floors = floors.map { floor ->
                    val old = originalMetadata?.floors?.firstOrNull {
                        it.key.equals(floor.key, ignoreCase = true)
                    }
                    if (floor.key in preservedFloorKeys && old != null) {
                        old.copy(
                            displayName = floor.displayName,
                            sortOrder = floor.sortOrder,
                            imageWidth = floor.imageWidth,
                            imageHeight = floor.imageHeight,
                        )
                    } else {
                        MetadataFloor(
                            floor.key,
                            floor.displayName,
                            floor.sortOrder,
                            "maps/${File(floor.imagePath).name}",
                            floor.imageWidth,
                            floor.imageHeight,
                        )
                    }
                },
            )
            val editedFloor = floors.first()
            val recognitionRegion = editedFloor.previewRegion.takeIf {
                editedFloor.key in preservedFloorKeys
            }
            val gates = IdvmGateCoordinateAdapter.replaceSideEntrancesFromSourceImageEditor(
                document = originalGates,
                floorKey = editedFloor.key,
                sourceImageBounds = sideDoors,
                recognitionRegion = recognitionRegion,
            )
            val anchors = originalAnchors.copy(
                floors = floors.associate { floor ->
                    floor.key to if (floor.key in preservedFloorKeys) {
                        originalAnchors.floors.entries.firstOrNull {
                            it.key.equals(floor.key, ignoreCase = true)
                        }?.value ?: FloorAnchors()
                    } else {
                        FloorAnchors()
                    }
                },
            )
            val allSourceImagesPreserved = preservedFloorKeys.size == floors.size
            // Imported Desktop documents may contain newer recognition,
            // annotation, and background-layer fields not modeled by Android.
            // When the source images did not change, the copied payload is
            // still authoritative and must remain byte-for-byte untouched.
            if (!allSourceImagesPreserved) {
                File(staging, "data/metadata.json").writeText(json.encodeToString(MetadataDocument.serializer(), metadata))
                File(staging, "data/anchors.json").writeText(json.encodeToString(AnchorsDocument.serializer(), anchors))
            }
            File(staging, "data/gates.json").writeText(json.encodeToString(GatesDocument.serializer(), gates))

            if (mapDir.exists()) check(mapDir.renameTo(backup)) { "无法暂存原地图文件" }
            try {
                check(staging.renameTo(mapDir)) { "无法提交地图文件" }
            } catch (e: Exception) {
                if (backup.exists() && !mapDir.exists()) backup.renameTo(mapDir)
                throw e
            }
            backup.deleteRecursively()

            val updated = existing.copy(classId = classId, title = title, floors = floors)
            saveCatalog(catalog.copy(maps = catalog.maps.map { if (it.id == mapId) updated else it }))
            return updated
        } catch (e: Exception) {
            staging.deleteRecursively()
            if (backup.exists() && !mapDir.exists()) backup.renameTo(mapDir)
            throw e
        }
    }

    /** 读取已有地图的侧门标记，用于从地图列表重新打开创建流程时恢复标记。 */
    fun loadSideDoors(mapId: String): List<NormalizedRect> = runCatching {
        val file = File(mapsRoot, "$mapId/data/gates.json")
        if (!file.isFile) return emptyList()
        json.decodeFromString<GatesDocument>(file.readText()).gates
            .filter { it.role == "sideEntrance" && it.enabled }
            .mapNotNull { it.bounds }
    }.getOrDefault(emptyList())

    /** 读取指定楼层的侧门，避免多楼层地图误用其他楼层的第一个门。 */
    fun loadSideDoors(mapId: String, floorKey: String): List<NormalizedRect> = runCatching {
        val file = File(mapsRoot, "$mapId/data/gates.json")
        if (!file.isFile) return emptyList()
        json.decodeFromString<GatesDocument>(file.readText()).gates
            .filter {
                it.role == "sideEntrance" && it.enabled &&
                    it.floorKey.equals(floorKey, ignoreCase = true)
            }
            .mapNotNull { it.bounds }
    }.getOrDefault(emptyList())

    /**
     * Returns side doors in full source-image coordinates for Android's editor.
     * Scanner callers must keep using [loadSideDoors] because IDVM storage is
     * recognition-image relative.
     */
    fun loadSideDoorsForEditing(mapId: String, floor: FloorRecord): List<NormalizedRect> = runCatching {
        val file = File(mapsRoot, "$mapId/data/gates.json")
        if (!file.isFile) return emptyList()
        val document = json.decodeFromString<GatesDocument>(file.readText())
        IdvmGateCoordinateAdapter.gatesForSourceImageEditor(
            document = document,
            floorKey = floor.key,
            recognitionRegion = loadPreviewRegion(mapId, floor),
        )
    }.getOrDefault(emptyList())

    /** Restores the selected Desktop display region for catalogs imported by older app builds. */
    fun loadPreviewRegion(mapId: String, floor: FloorRecord): NormalizedRect? {
        floor.previewRegion?.let { return it }
        return runCatching {
            val file = File(mapsRoot, "$mapId/data/metadata.json")
            if (!file.isFile) return null
            json.decodeFromString<MetadataDocument>(file.readText()).floors
                .firstOrNull { it.key.equals(floor.key, ignoreCase = true) }
                ?.recognitionRegion
        }.getOrNull()
    }

    /** 旧版目录未保存多边形到清单时，从随包 metadata 恢复。 */
    fun loadFreeCropPoints(mapId: String, floor: FloorRecord): List<NormalizedPoint> {
        if (floor.freeCropPoints.size >= 3) return floor.freeCropPoints
        return runCatching {
            val file = File(mapsRoot, "$mapId/data/metadata.json")
            if (!file.isFile) return emptyList()
            json.decodeFromString<MetadataDocument>(file.readText()).floors
                .firstOrNull { it.key.equals(floor.key, ignoreCase = true) }
                ?.freeCropPoints.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 读取 Desktop IDVM 为指定楼层保存的人工遮瑕层。 */
    fun loadBackgroundLayers(mapId: String, floorKey: String): List<BackgroundLayer> = runCatching {
        val file = File(mapsRoot, "$mapId/data/anchors.json")
        if (!file.isFile) return emptyList()
        json.decodeFromString<AnchorsDocument>(file.readText()).floors[floorKey]
            ?.backgroundLayers.orEmpty()
            .filter { it.semantic == "background" && it.points.isNotEmpty() }
    }.getOrDefault(emptyList())

    /**
     * 返回桌面版导出的权威识别资产。新目录优先读取 maps.json 中的已验证记录；
     * 旧 Android 目录则从仍保留在地图目录中的 metadata.json 无损回读。
     */
    fun loadRecognitionAssets(mapId: String, floorRecord: FloorRecord): FloorRecognitionAssets {
        val metadataFloor = loadMetadataFloor(mapId, floorRecord.key)
        val recognitionRegion = floorRecord.previewRegion ?: metadataFloor?.recognitionRegion
        val recognitionFile = floorRecord.recognitionImagePath
            ?.let(::resolveCatalogAsset)
            ?.takeIf(File::isFile)
            ?: metadataFloor?.recognitionImage
                ?.let { resolvePortableDataAsset(mapId, it) }
                ?.takeIf(File::isFile)
        val recognitionBounds = recognitionFile?.let(::imageBounds)
        val fallbackRecognitionSize = recognitionRegionSize(
            width = floorRecord.imageWidth,
            height = floorRecord.imageHeight,
            region = recognitionRegion,
        )
        val recognitionWidth = recognitionBounds?.first
            ?: floorRecord.recognitionWidth.takeIf { it > 0 }
            ?: fallbackRecognitionSize.first
        val recognitionHeight = recognitionBounds?.second
            ?: floorRecord.recognitionHeight.takeIf { it > 0 }
            ?: fallbackRecognitionSize.second

        val storedFeature = floorRecord.sideEntranceFeature
        val resolvedFeature = storedFeature?.let { feature ->
            resolveCatalogAsset(feature.imagePath).takeIf(File::isFile)?.let { file ->
                ResolvedSideEntranceFeature(
                    file = file,
                    centerX = feature.centerX,
                    centerY = feature.centerY,
                    radius = feature.radius,
                    width = feature.imageWidth,
                    height = feature.imageHeight,
                )
            }
        } ?: metadataFloor?.sideEntranceFeature?.let { feature ->
            resolvePortableDataAsset(mapId, feature.file).takeIf(File::isFile)?.let { file ->
                val bounds = imageBounds(file) ?: return@let null
                ResolvedSideEntranceFeature(
                    file = file,
                    centerX = feature.centerX,
                    centerY = feature.centerY,
                    radius = feature.radius,
                    width = bounds.first,
                    height = bounds.second,
                )
            }
        }

        val storedLine = floorRecord.prebuiltStructureLine
        val resolvedLine = storedLine?.let { line ->
            resolveCatalogAsset(line.imagePath).takeIf { it.isFile && it.length() == line.fileLength }
                ?.let { file ->
                    ResolvedPrebuiltStructureLine(file, line.width, line.height, line.algorithmId)
                }
        } ?: metadataFloor?.prebuiltStructureLine?.let { line ->
            resolvePortableDataAsset(mapId, line.file)
                .takeIf { it.isFile && it.length() == line.fileLength }
                ?.let { file ->
                    ResolvedPrebuiltStructureLine(file, line.width, line.height, line.algorithmId)
                }
        }

        return FloorRecognitionAssets(
            recognitionImageFile = recognitionFile,
            recognitionRegion = recognitionRegion,
            recognitionWidth = recognitionWidth,
            recognitionHeight = recognitionHeight,
            validMapBounds = floorRecord.validMapBounds ?: metadataFloor?.validMapBounds,
            sideEntranceFeature = resolvedFeature,
            prebuiltStructureLine = resolvedLine?.takeIf {
                it.width == recognitionWidth && it.height == recognitionHeight
            },
        )
    }

    /** IDVM 1.3 地图标签；目录损坏时按无标签处理，不能阻断手动选择。 */
    fun loadTags(mapId: String): List<MetadataTag> = runCatching {
        val file = File(mapsRoot, "$mapId/data/metadata.json")
        if (!file.isFile) emptyList() else json.decodeFromString<MetadataDocument>(file.readText()).tags
    }.getOrDefault(emptyList())

    private fun loadMetadataFloor(mapId: String, floorKey: String): MetadataFloor? = runCatching {
        val file = File(mapsRoot, "$mapId/data/metadata.json")
        if (!file.isFile) return null
        json.decodeFromString<MetadataDocument>(file.readText()).floors
            .firstOrNull { it.key.equals(floorKey, ignoreCase = true) }
    }.getOrNull()

    private fun resolveCatalogAsset(path: String): File = File(mapsRoot, path)

    /** 将包内 maps/{sourceId}/data/... 映射到本地 maps/{localId}/data/...。 */
    private fun resolvePortableDataAsset(localMapId: String, logicalPath: String): File {
        val normalized = logicalPath.replace('\\', '/')
        val marker = "/data/"
        val suffix = normalized.substringAfter(marker, missingDelimiterValue = "")
        if (suffix.isEmpty() || suffix.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return File(mapsRoot, ".invalid-recognition-asset")
        }
        val mapDirectory = File(mapsRoot, localMapId)
        val candidate = File(mapDirectory, "data/$suffix")
        val rootPath = mapDirectory.canonicalFile.path + File.separator
        return candidate.canonicalFile.takeIf { it.path.startsWith(rootPath) }
            ?: File(mapsRoot, ".invalid-recognition-asset")
    }

    private fun imageBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    private fun recognitionRegionSize(width: Int, height: Int, region: NormalizedRect?): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || region == null) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val left = floor(region.x * width).toInt().coerceIn(0, width - 1)
        val top = floor(region.y * height).toInt().coerceIn(0, height - 1)
        val right = ceil((region.x + region.width) * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil((region.y + region.height) * height).toInt().coerceIn(top + 1, height)
        return (right - left) to (bottom - top)
    }

    private fun openImageInputStream(uri: Uri, resolver: ContentResolver): java.io.InputStream =
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path) { "图片路径为空" }).inputStream()
        } else {
            resolver.openInputStream(uri) ?: error("无法读取图片")
        }

    private fun pointsToFile(uri: Uri, expected: File): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return runCatching { File(path).canonicalFile == expected.canonicalFile }.getOrDefault(false)
    }

    private inline fun <reified T> readDocument(file: File): T? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString<T>(file.readText())
    }.getOrNull()

    private fun recoverInterruptedImports() {
        if (!mapsRoot.isDirectory) return
        mapsRoot.listFiles { file -> file.name.startsWith(".staging-") }?.forEach(File::deleteRecursively)
        mapsRoot.listFiles { file -> file.name.startsWith(".idvm-import-") }?.forEach { journal ->
            runCatching {
                val committed = if (catalogFile.exists()) {
                    json.decodeFromString<MapCatalogDocument>(catalogFile.readText()).maps.map { it.id }.toSet()
                } else emptySet()
                journal.readLines().filter { it.isNotBlank() }.filterNot(committed::contains)
                    .forEach { File(mapsRoot, it).deleteRecursively() }
                journal.delete()
            }
        }
    }
}
