package com.idvb.android.idvm

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.floor

/**
 * IDVM 地图包导入器（移植参考项目 IdvmPackageService.Reader.cs / Validator.cs）。
 *
 * 纯 Kotlin，可在 JVM 单元测试中完整往返。落盘到 mapsRoot：
 * ```
 * mapsRoot/maps.json                    # 目录清单（导入成功后原子更新）
 * mapsRoot/{localMapId}/maps/{image}    # 楼层原图
 * mapsRoot/{localMapId}/data/{metadata,gates,anchors}.json
 * ```
 * 导入语义（IDVM_FORMAT.md §10）：每个 Class 新建本地 Class；原名被占则「原名 - 新添加N」；
 * 每张地图生成新本地 mapId；重复导入=新副本；全部验证通过才提交目录清单。
 */
class IdvmImporter(private val json: Json = IdvmJson.instance) {

    fun importPackage(
        packageFile: File,
        mapsRoot: File,
        currentCatalog: MapCatalogDocument,
        catalogSaver: (MapCatalogDocument) -> Unit,
    ): ImportResult {
        return try {
            if (!packageFile.exists()) {
                return ImportResult.Failure("找不到地图包文件：${packageFile.path}")
            }
            if (packageFile.isDirectory) {
                return importDirectory(packageFile, mapsRoot, currentCatalog, catalogSaver)
            }
            val bundleResult = tryImportBundleOrWrappedPackage(packageFile, mapsRoot, currentCatalog, catalogSaver)
            if (bundleResult != null) {
                return bundleResult
            }

            val (result, journal) = importInternal(packageFile, mapsRoot, currentCatalog)
            catalogSaver(result.catalog)
            journal.delete()
            ImportResult.Success(result.importedClasses, result.importedMaps, result.catalog)
        } catch (e: IllegalArgumentException) {
            ImportResult.Failure(e.message ?: "导入失败", e.cause?.message)
        } catch (e: java.util.zip.ZipException) {
            ImportResult.Failure("所选文件不是有效的 IDVM 地图包（非 ZIP 格式）", e.message)
        } catch (e: Exception) {
            ImportResult.Failure("导入失败：${e.message}", e.stackTraceToString().take(2000))
        }
    }

    /** 批量导入多个包（或目录中的全部包），更新目录清单并汇总导入记录。 */
    fun importPackages(
        packageFiles: List<File>,
        mapsRoot: File,
        currentCatalog: MapCatalogDocument,
        catalogSaver: (MapCatalogDocument) -> Unit,
    ): ImportResult {
        if (packageFiles.isEmpty()) return ImportResult.Failure("没有提供地图包文件")
        val allImportedClasses = mutableListOf<ClassRecord>()
        val allImportedMaps = mutableListOf<MapRecord>()
        var runningCatalog = currentCatalog
        val failures = mutableListOf<String>()

        for (file in packageFiles) {
            when (val res = importPackage(file, mapsRoot, runningCatalog, { runningCatalog = it })) {
                is ImportResult.Success -> {
                    allImportedClasses += res.importedClasses
                    allImportedMaps += res.importedMaps
                    runningCatalog = res.catalog
                }
                is ImportResult.Failure -> {
                    failures += "${file.name}: ${res.reason}"
                }
            }
        }

        if (allImportedMaps.isNotEmpty() || failures.isEmpty()) {
            catalogSaver(runningCatalog)
            return ImportResult.Success(allImportedClasses, allImportedMaps, runningCatalog)
        }
        return ImportResult.Failure("全部地图包导入失败：${failures.joinToString("；")}")
    }

    private fun importDirectory(
        directory: File,
        mapsRoot: File,
        currentCatalog: MapCatalogDocument,
        catalogSaver: (MapCatalogDocument) -> Unit,
    ): ImportResult {
        val headerFile = File(directory, "header")
        val manifestFile = File(directory, "manifest.json")
        if (headerFile.isFile && manifestFile.isFile) {
            val tempZip = File(mapsRoot, ".dir-import-${UUID.randomUUID()}.idvm")
            try {
                java.util.zip.ZipOutputStream(tempZip.outputStream().buffered()).use { outStream ->
                    directory.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(directory).path.replace('\\', '/')
                        outStream.putNextEntry(ZipEntry(rel))
                        file.inputStream().buffered().use { it.copyTo(outStream) }
                        outStream.closeEntry()
                    }
                }
                val (result, journal) = importInternal(tempZip, mapsRoot, currentCatalog)
                catalogSaver(result.catalog)
                journal.delete()
                return ImportResult.Success(result.importedClasses, result.importedMaps, result.catalog)
            } finally {
                tempZip.delete()
            }
        }

        val idvmFiles = directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("idvm", ignoreCase = true) }
            .toList()
        if (idvmFiles.isNotEmpty()) {
            return importPackages(idvmFiles, mapsRoot, currentCatalog, catalogSaver)
        }

        return ImportResult.Failure("所选目录不是有效的 IDVM 地图包，且未在其中找到任何 .idvm 文件：${directory.name}")
    }

    private fun tryImportBundleOrWrappedPackage(
        packageFile: File,
        mapsRoot: File,
        currentCatalog: MapCatalogDocument,
        catalogSaver: (MapCatalogDocument) -> Unit,
    ): ImportResult? {
        val zip = try {
            ZipFile(packageFile)
        } catch (_: Exception) {
            return null
        }

        zip.use { zf ->
            if (zf.getEntry("header") != null) {
                return null
            }

            // 1. 压缩包容器内含有 *.idvm（例如全包 ZIP 或目录压缩包）
            val idvmEntries = zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".idvm", ignoreCase = true) }
                .toList()
            if (idvmEntries.isNotEmpty()) {
                val bundleDir = File(mapsRoot, ".bundle-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val extractedFiles = mutableListOf<File>()
                    for (entry in idvmEntries) {
                        val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        val tempFile = File(bundleDir, "${UUID.randomUUID()}-$safeName")
                        zf.getInputStream(entry).use { inStream ->
                            tempFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
                        }
                        extractedFiles += tempFile
                    }
                    return importPackages(extractedFiles, mapsRoot, currentCatalog, catalogSaver)
                } finally {
                    bundleDir.deleteRecursively()
                }
            }

            // 2. 单一子目录包裹模式（如 some_folder/header 与 some_folder/manifest.json）
            val headerEntry = zf.entries().asSequence().firstOrNull {
                !it.isDirectory && (it.name.endsWith("/header") || it.name.endsWith("\\header"))
            }
            if (headerEntry != null) {
                val prefix = headerEntry.name.substringBeforeLast("header")
                if (prefix.isNotEmpty() && zf.getEntry("${prefix}manifest.json") != null) {
                    val unwrapZip = File(mapsRoot, ".unwrap-${UUID.randomUUID()}.idvm")
                    try {
                        java.util.zip.ZipOutputStream(unwrapZip.outputStream().buffered()).use { outStream ->
                            for (entry in zf.entries()) {
                                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                                val logicalName = entry.name.removePrefix(prefix).replace('\\', '/')
                                outStream.putNextEntry(ZipEntry(logicalName))
                                zf.getInputStream(entry).use { it.copyTo(outStream) }
                                outStream.closeEntry()
                            }
                        }
                        val (result, journal) = importInternal(unwrapZip, mapsRoot, currentCatalog)
                        catalogSaver(result.catalog)
                        journal.delete()
                        return ImportResult.Success(result.importedClasses, result.importedMaps, result.catalog)
                    } finally {
                        unwrapZip.delete()
                    }
                }
            }

            throw IllegalArgumentException(
                "包内缺少 header 条目。若为包含多个地图包的压缩包，请确保其内部包含 .idvm 文件；或直接解压后导入其中的 .idvm 地图包。"
            )
        }
    }

    private class MapDoc(
        val manifestMap: ManifestMap,
        val metadata: MetadataDocument,
        val gates: GatesDocument,
        val anchors: AnchorsDocument,
    )

    private class ImportOutcome(
        val importedClasses: List<ClassRecord>,
        val importedMaps: List<MapRecord>,
        val catalog: MapCatalogDocument,
    )

    private data class ImportedImageAsset(
        val localPath: String,
        val width: Int,
        val height: Int,
    )

    private fun importInternal(
        packageFile: File,
        mapsRoot: File,
        currentCatalog: MapCatalogDocument,
    ): Pair<ImportOutcome, File> {
        require(packageFile.isFile) { "找不到地图包文件：${packageFile.path}" }

        val movedDirs = mutableListOf<File>()
        var staging: File? = null
        var journal: File? = null
        try {
            staging = File(mapsRoot, ".staging-${UUID.randomUUID()}")
            staging.mkdirs()
            require(staging.isDirectory) { "无法创建暂存目录" }

            journal = File(mapsRoot, ".idvm-import-${UUID.randomUUID()}").apply { writeText("") }
            val outcome = ZipFile(packageFile).use { zip -> extractAndValidate(zip, mapsRoot, staging!!, currentCatalog, movedDirs, journal!!) }
            cleanupStaging(staging)
            return outcome to journal
        } catch (e: Exception) {
            cleanupStaging(staging)
            movedDirs.forEach { dir -> dir.deleteRecursively() }
            journal?.delete()
            throw e
        }
    }

    private fun extractAndValidate(
        zip: ZipFile,
        mapsRoot: File,
        staging: File,
        currentCatalog: MapCatalogDocument,
        movedDirs: MutableList<File>,
        journal: File,
    ): ImportOutcome {
        // 1) header
        val headerEntry = zip.getEntry("header") ?: error("包内缺少 header 条目")
        val headerBytes = IdvmUtil.readBounded(zip.getInputStream(headerEntry), IdvmLimits.MAX_JSON_BYTES)
        val header = IdvmHeader.parse(headerBytes)

        // 2) manifest
        val manifestEntry = zip.getEntry("manifest.json") ?: error("包内缺少 manifest.json 条目")
        val rawManifest = IdvmUtil.readBounded(zip.getInputStream(manifestEntry), IdvmLimits.MAX_JSON_BYTES)
        val manifest = json.decodeFromString<IdvmManifest>(rawManifest.decodeToString())
        IdvmValidator.validateHeaderAndManifest(headerBytes, header, rawManifest, manifest)

        // 3) 条目盘点 + 安全限制 + 文件清单精确集合
        val inventory = buildInventory(zip, manifest)

        // 4) 逐地图解析/校验文档
        val mapDocs = mutableListOf<MapDoc>()
        for (m in manifest.maps) {
            val root = m.root
            val metadata = readJsonDoc<MetadataDocument>(zip, inventory, "$root/data/metadata.json", "metadata.json")
            val gates = readJsonDoc<GatesDocument>(zip, inventory, "$root/data/gates.json", "gates.json")
            val anchors = readJsonDoc<AnchorsDocument>(zip, inventory, "$root/data/anchors.json", "anchors.json")
            IdvmValidator.validateMapDocuments(m.mapId, m, metadata, gates, anchors)
            mapDocs += MapDoc(m, metadata, gates, anchors)
        }

        // 5) 校验清单里每个文件都属于某个地图的楼层图或 data JSON
        for (f in manifest.files) {
            val owner = mapDocs.singleOrNull { f.path.startsWith("${it.manifestMap.root}/") }
            require(owner != null && (f.path.startsWith("${owner.manifestMap.root}/maps/") || f.path.startsWith("${owner.manifestMap.root}/data/"))) {
                "清单文件不属于任何地图的 maps 或 data 负载：'${f.path}'"
            }
        }

        // 6) 解析本地 Class 名（原名被占 → 原名 - 新添加N）
        val taken = currentCatalog.classes.map { it.name.lowercase() }.toMutableSet()
        val classIdMap = LinkedHashMap<String, String>() // manifest classId → local classId
        val newClasses = mutableListOf<ClassRecord>()
        for (c in manifest.classes) {
            val localId = UUID.randomUUID().toString()
            val localName = resolveClassName(c.name, taken)
            classIdMap[c.classId] = localId
            newClasses += ClassRecord(id = localId, name = localName,
                removeBackground = c.properties.removeBackground, scanFloorKey = c.properties.scanFloorKey)
        }

        // 7) 逐地图解压到 staging，校验图片尺寸与摘要
        val newMaps = mutableListOf<MapRecord>()
        val sourceToLocalMapIds = LinkedHashMap<String, String>()
        for (doc in mapDocs) {
            val localMapId = UUID.randomUUID().toString()
            journal.appendText("$localMapId\n")
            sourceToLocalMapIds[doc.manifestMap.mapId] = localMapId
            val localDir = File(staging, localMapId)
            File(localDir, "maps").mkdirs()
            File(localDir, "data").mkdirs()

            val floorRecords = doc.manifestMap.floors.sortedBy { it.sortOrder }.map { mf ->
                val metaFloor = doc.metadata.floors.first { it.key == mf.key }
                val src = inventory[mf.image] ?: error("缺少楼层图条目：${mf.image}")
                val relative = mf.image.removePrefix("${doc.manifestMap.root}/")
                val localImage = File(localDir, relative).apply { parentFile?.mkdirs() }
                copyVerifiedEntry(zip, src, mf.image, manifest, localImage)
                verifyImageDimensions(mf.image, localImage, doc, mf)
                val recognitionAsset = metaFloor.recognitionImage?.let { logicalPath ->
                    inspectImageAsset(
                        zip = zip,
                        inventory = inventory,
                        manifest = manifest,
                        root = doc.manifestMap.root,
                        localMapId = localMapId,
                        logicalPath = logicalPath,
                        what = "楼层 ${mf.key} 识别图",
                    )
                }
                val recognitionSize = recognitionAsset?.let { it.width to it.height }
                    ?: recognitionSize(metaFloor)
                val sideEntranceFeature = metaFloor.sideEntranceFeature?.let { feature ->
                    val asset = inspectImageAsset(
                        zip = zip,
                        inventory = inventory,
                        manifest = manifest,
                        root = doc.manifestMap.root,
                        localMapId = localMapId,
                        logicalPath = feature.file,
                        what = "楼层 ${mf.key} 侧门特征图",
                    )
                    require(feature.centerX <= recognitionSize.first && feature.centerY <= recognitionSize.second) {
                        "楼层 ${mf.key} 侧门特征中心超出识别图范围"
                    }
                    SideEntranceFeatureRecord(
                        imagePath = asset.localPath,
                        centerX = feature.centerX,
                        centerY = feature.centerY,
                        radius = feature.radius,
                        imageWidth = asset.width,
                        imageHeight = asset.height,
                    )
                }
                val prebuiltStructureLine = metaFloor.prebuiltStructureLine?.let { line ->
                    val asset = inspectImageAsset(
                        zip = zip,
                        inventory = inventory,
                        manifest = manifest,
                        root = doc.manifestMap.root,
                        localMapId = localMapId,
                        logicalPath = line.file,
                        what = "楼层 ${mf.key} 预制线图",
                    )
                    val lineEntry = manifest.files.first { it.path == line.file }
                    val algorithmEntry = manifest.files.firstOrNull { it.path == line.algorithmFile }
                        ?: error("楼层 ${mf.key} 预制线图算法未在文件清单中声明")
                    require(asset.width == line.width && asset.height == line.height &&
                        asset.width == recognitionSize.first && asset.height == recognitionSize.second &&
                        lineEntry.size == line.fileLength &&
                        lineEntry.sha256.equals(line.sha256, ignoreCase = true) &&
                        algorithmEntry.sha256.equals(line.algorithmSha256, ignoreCase = true)) {
                        "楼层 ${mf.key} 预制线图与识别图尺寸或文件登记不一致"
                    }
                    val algorithmZipEntry = inventory[line.algorithmFile]
                        ?: error("楼层 ${mf.key} 预制线图算法文件缺失")
                    verifyEntry(zip, algorithmZipEntry, line.algorithmFile, manifest)
                    PrebuiltStructureLineRecord(
                        imagePath = asset.localPath,
                        sha256 = line.sha256,
                        width = line.width,
                        height = line.height,
                        fileLength = line.fileLength,
                        algorithmId = line.algorithmId,
                    )
                }
                FloorRecord(
                    key = mf.key,
                    displayName = mf.displayName,
                    sortOrder = mf.sortOrder,
                    imagePath = "$localMapId/$relative",
                    imageWidth = metaFloor.imageWidth,
                    imageHeight = metaFloor.imageHeight,
                    orientationDegrees = metaFloor.orientationDegrees,
                    previewRegion = metaFloor.recognitionRegion,
                    freeCropPoints = metaFloor.freeCropPoints,
                    recognitionImagePath = recognitionAsset?.localPath,
                    recognitionWidth = recognitionSize.first,
                    recognitionHeight = recognitionSize.second,
                    validMapBounds = metaFloor.validMapBounds,
                    sideEntranceFeature = sideEntranceFeature,
                    prebuiltStructureLine = prebuiltStructureLine,
                )
            }

            // 保留该地图全部权威负载（含未来版本新增的识别图、侧门特征等）
            for (declared in manifest.files.filter { it.path.startsWith("${doc.manifestMap.root}/") }) {
                val path = declared.path
                if (doc.manifestMap.floors.any { it.image == path }) continue
                val src = inventory[path] ?: error("缺少清单文件：$path")
                val relative = path.removePrefix("${doc.manifestMap.root}/")
                val target = File(localDir, relative).apply { parentFile?.mkdirs() }
                copyVerifiedEntry(zip, src, path, manifest, target)
            }

            // 8) 提交：staging → 最终目录
            val finalDir = File(mapsRoot, localMapId)
            require(localDir.renameTo(finalDir) || moveRecursively(localDir, finalDir)) {
                "无法写入本地地图目录：$finalDir"
            }
            movedDirs += finalDir

            newMaps += MapRecord(
                id = localMapId,
                classId = classIdMap.getValue(doc.manifestMap.classId),
                title = doc.manifestMap.name,
                sourceMapId = doc.manifestMap.mapId,
                mapVersion = doc.manifestMap.mapVersion,
                floors = floorRecords,
            )
        }

        val newGroups = manifest.variantGroups.map { group -> MapVariantGroupRecord(
            id = UUID.randomUUID().toString(),
            classId = classIdMap.getValue(group.classId),
            paletteSlot = group.paletteSlot,
            mapIds = group.mapIds.map(sourceToLocalMapIds::getValue),
        ) }
        val updatedCatalog = currentCatalog.copy(
            classes = currentCatalog.classes + newClasses,
            maps = currentCatalog.maps + newMaps,
            variantGroups = currentCatalog.variantGroups + newGroups,
        )
        return ImportOutcome(newClasses, newMaps, updatedCatalog)
    }

    /** 条目盘点：拒绝目录/符号链接/非法路径/重复条目，限制条目数与总展开大小 */
    private fun buildInventory(zip: ZipFile, manifest: IdvmManifest): Map<String, ZipEntry> {
        val result = LinkedHashMap<String, ZipEntry>()
        var total = 0L
        var count = 0
        for (e in zip.entries()) {
            val name = e.name
            require(IdvmValidator.validateSafeRelativePath(name)) { "ZIP 条目路径不合法：'$name'" }
            require(!e.isDirectory) { "ZIP 不允许目录条目：'$name'" }
            // 注：不做 unixMode 符号链接检测——路径校验已阻断穿越，且解压从不引用链接
            require(result.put(name, e) == null) { "ZIP 存在重复条目：'$name'" }
            count++
            require(count <= IdvmLimits.MAX_ENTRIES) { "条目数超过上限 ${IdvmLimits.MAX_ENTRIES}" }
            require(e.size <= IdvmLimits.MAX_SINGLE_FILE_BYTES) { "文件 '${e.name}' 超过单文件上限" }
            total += e.size
            require(total <= IdvmLimits.MAX_TOTAL_BYTES) { "包展开总大小超过上限" }
        }

        val declared = manifest.files.map { it.path }.toSet()
        val actual = result.keys - setOf("header", "manifest.json")
        val missing = declared - actual
        val extra = actual - declared
        require(missing.isEmpty() && extra.isEmpty()) {
            buildString {
                if (missing.isNotEmpty()) append("清单声明但包内缺失：${missing.sorted().joinToString(", ")}；")
                if (extra.isNotEmpty()) append("包内存在但清单未声明：${extra.sorted().joinToString(", ")}；")
            }.trimEnd('；')
        }
        return result
    }

    /** 读取并解析一个 JSON 文档，同时受单文件/JSON 大小上限约束 */
    private inline fun <reified T> readJsonDoc(
        zip: ZipFile,
        inventory: Map<String, ZipEntry>,
        path: String,
        what: String,
    ): T {
        val entry = inventory[path] ?: error("缺少 $what 条目：$path")
        require(entry.size <= IdvmLimits.MAX_JSON_BYTES) { "$what 超过 JSON 大小上限" }
        val bytes = IdvmUtil.readBounded(zip.getInputStream(entry), IdvmLimits.MAX_JSON_BYTES)
        return try {
            json.decodeFromString<T>(bytes.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("$what 解析失败（${path}）：${e.message}")
        }
    }

    /** 校验条目大小与 SHA-256 均与 manifest.files 声明一致 */
    private fun verifyEntry(zip: ZipFile, entry: ZipEntry, path: String, manifest: IdvmManifest) {
        val declared = manifest.files.firstOrNull { it.path == path }
            ?: error("清单未声明文件：$path")
        require(entry.size == declared.size) { "文件 '$path' 大小与清单声明不一致" }
        val actual = zip.getInputStream(entry).use(::hashOf)
        require(actual == declared.sha256) { "文件 '$path' 的 SHA-256 与清单声明不一致" }
    }

    private fun copyVerifiedEntry(zip: ZipFile, entry: ZipEntry, path: String, manifest: IdvmManifest, target: File) {
        val declared = manifest.files.firstOrNull { it.path == path } ?: error("清单未声明文件：$path")
        require(entry.size == declared.size && entry.size in 0..IdvmLimits.MAX_SINGLE_FILE_BYTES) {
            "文件 '$path' 大小与清单声明不一致"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= declared.size) { "文件 '$path' 超过清单声明大小" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        } }
        require(total == declared.size && IdvmUtil.sha256Hex(digest.digest()) == declared.sha256) {
            "文件 '$path' 的 SHA-256 与清单声明不一致"
        }
    }

    /** 校验图片能解码且尺寸与 metadata 声明一致 */
    private fun verifyImageDimensions(path: String, file: File, doc: MapDoc, mf: ManifestFloor) {
        val metaFloor = doc.metadata.floors.firstOrNull { it.key == mf.key }
            ?: error("metadata 缺少楼层 ${mf.key}")
        val dim = file.inputStream().use(ImageProbe::dimensions)
            ?: error("楼层图 '$path' 无法识别为 PNG/JPEG")
        require(dim.first == metaFloor.imageWidth && dim.second == metaFloor.imageHeight) {
            "楼层图 '$path' 尺寸 ${dim.first}x${dim.second} 与 metadata 声明 ${metaFloor.imageWidth}x${metaFloor.imageHeight} 不一致"
        }
    }

    private fun inspectImageAsset(
        zip: ZipFile,
        inventory: Map<String, ZipEntry>,
        manifest: IdvmManifest,
        root: String,
        localMapId: String,
        logicalPath: String,
        what: String,
    ): ImportedImageAsset {
        val entry = inventory[logicalPath] ?: error("$what 未在 IDVM 文件清单中声明：$logicalPath")
        verifyEntry(zip, entry, logicalPath, manifest)
        val dimensions = zip.getInputStream(entry).use(ImageProbe::dimensions)
            ?: error("$what 无法识别为 PNG/JPEG：$logicalPath")
        val prefix = "${root.trimEnd('/')}/"
        require(logicalPath.startsWith(prefix)) { "$what 不属于当前地图目录" }
        return ImportedImageAsset(
            localPath = "$localMapId/${logicalPath.removePrefix(prefix)}",
            width = dimensions.first,
            height = dimensions.second,
        )
    }

    private fun recognitionSize(floorMetadata: MetadataFloor): Pair<Int, Int> {
        val region = floorMetadata.recognitionRegion
            ?: return floorMetadata.imageWidth to floorMetadata.imageHeight
        val left = floor(region.x * floorMetadata.imageWidth).toInt().coerceIn(0, floorMetadata.imageWidth - 1)
        val top = floor(region.y * floorMetadata.imageHeight).toInt().coerceIn(0, floorMetadata.imageHeight - 1)
        val right = ceil((region.x + region.width) * floorMetadata.imageWidth).toInt()
            .coerceIn(left + 1, floorMetadata.imageWidth)
        val bottom = ceil((region.y + region.height) * floorMetadata.imageHeight).toInt()
            .coerceIn(top + 1, floorMetadata.imageHeight)
        return (right - left) to (bottom - top)
    }

    private fun resolveClassName(desired: String, taken: MutableSet<String>): String {
        if (desired.lowercase() !in taken) {
            taken += desired.lowercase()
            return desired
        }
        var n = 1
        while (true) {
            val candidate = "$desired - 新添加$n"
            if (candidate.lowercase() !in taken) {
                taken += candidate.lowercase()
                return candidate
            }
            n++
        }
    }

    private fun cleanupStaging(staging: File?) {
        if (staging != null && staging.exists()) staging.deleteRecursively()
    }

    /** renameTo 失败（跨卷等）时退化为逐文件移动 */
    private fun moveRecursively(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        dst.parentFile?.mkdirs()
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                if (!moveRecursively(child, File(dst, child.name))) return false
            }
            return src.delete()
        }
        if (src.renameTo(dst)) return true
        src.copyTo(dst, overwrite = true)
        return src.delete()
    }

    private fun hashOf(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            md.update(buf, 0, n)
        }
        return IdvmUtil.sha256Hex(md.digest())
    }
}
