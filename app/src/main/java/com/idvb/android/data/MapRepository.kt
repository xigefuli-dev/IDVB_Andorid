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

    fun loadCatalog(): MapCatalogDocument {
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

    fun saveCatalog(doc: MapCatalogDocument) {
        mapsRoot.mkdirs()
        val text = json.encodeToString(MapCatalogDocument.serializer(), doc)
        val tmp = File(mapsRoot, "maps.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(catalogFile)) {
            catalogFile.writeText(text)
            tmp.delete()
        }
        cachedCatalog = doc
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
                FloorRecord(floor.id, floor.name, index, "$mapId/maps/$fileName", bounds.outWidth, bounds.outHeight)
            }
            val metadata = MetadataDocument(1, MetadataMap(mapId, classId, title, "android", "normalized-top-left"), floors.map {
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
        val staging = File(mapsRoot, ".staging-update-$mapId-${UUID.randomUUID()}")
        val backup = File(mapsRoot, ".backup-update-$mapId-${UUID.randomUUID()}")
        staging.deleteRecursively()
        if (mapDir.isDirectory) mapDir.copyRecursively(staging, overwrite = true) else staging.mkdirs()
        File(staging, "maps").mkdirs()
        File(staging, "data").mkdirs()

        try {
            val floors = template.floors.mapIndexed { index, floor ->
                val fileName = "floor-${(index + 1).toString().padStart(3, '0')}.png"
                val output = File(staging, "maps/$fileName")
                openImageInputStream(images.getValue(floor.id), resolver).use { input ->
                    output.outputStream().use(input::copyTo)
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取 ${floor.name} 图片" }
                FloorRecord(floor.id, floor.name, index, "$mapId/maps/$fileName", bounds.outWidth, bounds.outHeight)
            }
            val metadata = MetadataDocument(1, MetadataMap(mapId, classId, title, "android", "normalized-top-left"), floors.map {
                MetadataFloor(it.key, it.displayName, it.sortOrder, "maps/${File(it.imagePath).name}", it.imageWidth, it.imageHeight)
            })
            val gates = GatesDocument(1, sideDoors.mapIndexed { index, rect -> Gate("side-${index + 1}", floors.first().key, "sideEntrance", rect) })
            val anchors = AnchorsDocument(1, floors.associate { it.key to FloorAnchors() })
            File(staging, "data/metadata.json").writeText(json.encodeToString(MetadataDocument.serializer(), metadata))
            File(staging, "data/gates.json").writeText(json.encodeToString(GatesDocument.serializer(), gates))
            File(staging, "data/anchors.json").writeText(json.encodeToString(AnchorsDocument.serializer(), anchors))

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

    private fun openImageInputStream(uri: Uri, resolver: ContentResolver): java.io.InputStream =
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path) { "图片路径为空" }).inputStream()
        } else {
            resolver.openInputStream(uri) ?: error("无法读取图片")
        }

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
