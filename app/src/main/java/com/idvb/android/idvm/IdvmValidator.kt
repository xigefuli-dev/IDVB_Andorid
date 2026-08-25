package com.idvb.android.idvm

import com.idvb.android.idvm.IdvmHeader.Header
import java.util.UUID

/**
 * IDVM 包结构校验（IDVM_FORMAT.md §3/4/5/6/7/8/11）。
 * 校验失败抛出 [IllegalArgumentException]，消息即用户可读原因。
 */
object IdvmValidator {

    /** 校验 header + manifest 原始字节摘要 + manifest 结构一致性 */
    fun validateHeaderAndManifest(
        headerBytes: ByteArray,
        header: Header,
        rawManifest: ByteArray,
        manifest: IdvmManifest,
    ) {
        require(IdvmUtil.isRfc4122(header.packageId)) { "header 的 packageId 不是合法的 RFC 4122 UUID" }

        val manifestDigest = IdvmUtil.sha256(rawManifest)
        require(manifestDigest.contentEquals(header.manifestSha256)) {
            "manifest.json 的 SHA-256 与 header 声明不一致（包已损坏或被篡改）"
        }

        require(manifest.format == "idvm") { "manifest.format 必须为 'idvm'，实际 '${manifest.format}'" }
        val expectedVersion = "1.${header.formatMinor}"
        require(manifest.formatVersion == expectedVersion && manifest.minimumReader == expectedVersion) {
            "header 与 manifest 的 IDVM 版本不一致或读取器版本不受支持"
        }
        require(manifest.packageType == "class-set") { "仅支持 class-set 类型的 IDVM 包" }
        require(manifest.formatVersion in setOf("1.0", "1.1", "1.2")) { "不支持的 IDVM 版本：${manifest.formatVersion}" }
        require(manifest.formatVersion != "1.0" || manifest.variantGroups.isEmpty()) { "IDVM 1.0 包不能声明变体组合" }
        require(manifest.formatVersion != "1.1" || manifest.capabilities.variantGroups) { "IDVM 1.1 包必须声明 variantGroups 能力" }
        require(manifest.formatVersion != "1.2" || manifest.capabilities.floorMarkerKeys) { "IDVM 1.2 包必须声明 floorMarkerKeys 能力" }

        val manifestPackageId = manifest.packageId.lowercase()
        val headerPackageId = header.packageId.toString().lowercase()
        require(manifestPackageId == headerPackageId) { "manifest 与 header 的 packageId 不一致" }

        val manifestCreatedMs = IdvmUtil.parseIsoInstantMs(manifest.createdAt)
        require(manifestCreatedMs == header.createdAt) { "manifest 与 header 的创建时间不一致" }

        require(manifest.classes.size in 1..256) { "包内 Class 数量超出限制" }
        require(manifest.maps.size in 1..4096) { "包内地图数量超出限制" }

        // Class 名称忽略大小写唯一
        val classNames = manifest.classes.map { it.name.lowercase() }
        require(classNames.size == classNames.toSet().size) { "Class 名称存在重复（忽略大小写）" }

        // Class id 唯一
        val classIds = manifest.classes.map { it.classId }
        require(classIds.size == classIds.toSet().size) { "classId 存在重复" }

        // 每张地图必须且只能属于一个 Class；Class.mapIds 与 maps[].classId 完全一致
        val mapIdToClass = manifest.maps.groupBy({ it.mapId }, { it.classId })
        val dupMap = mapIdToClass.filterValues { it.size > 1 }
        require(dupMap.isEmpty()) { "地图 mapId 重复或属于多个 Class：${dupMap.keys}" }

        val classToMapIds = manifest.classes.associate { it.classId to it.mapIds.toSet() }
        val manifestMapIds = manifest.maps.map { it.mapId }.toSet()
        require(classToMapIds.values.flatten().toSet() == manifestMapIds) {
            "Class.mapIds 与 maps[].mapId 集合不一致"
        }
        for ((cid, mids) in classToMapIds) {
            require(mids.all { mapIdToClass[it] == listOf(cid) }) {
                "Class $cid 声明的 mapIds 与地图归属不一致"
            }
        }

        // mapId 唯一、mapVersion 为正整数、root 与 mapId 匹配
        require(manifestMapIds.size == manifest.maps.size) { "地图 mapId 存在重复" }
        for (m in manifest.maps) {
            require(m.mapVersion > 0) { "地图 ${m.mapId} 的 mapVersion 必须为正整数" }
            requireUuid(m.mapId, "mapId")
            requireUuid(m.classId, "classId")
            val compactMapId = UUID.fromString(m.mapId).toString().replace("-", "")
            val root = m.root.trimEnd('/')
            require(root == "maps/$compactMapId") { "地图 ${m.mapId} 的资源根路径无效：'$root'" }
        }

        // 楼层：key 在地图内唯一，sortOrder 从 1 连续递增
        for (m in manifest.maps) {
            require(m.floors.isNotEmpty()) { "地图 ${m.mapId} 必须至少包含一个楼层" }
            val keys = m.floors.map { it.key }
            require(keys.size == keys.toSet().size) { "地图 ${m.mapId} 楼层 key 存在重复" }
            val orders = m.floors.map { it.sortOrder }
            require(orders == (1..m.floors.size).toList()) { "地图 ${m.mapId} 楼层必须按 sortOrder 从 1 连续排列" }
        }

        val mapClassById = manifest.maps.associate { it.mapId to it.classId }
        val groupIds = HashSet<String>()
        val groupedMaps = HashSet<String>()
        val slotsByClass = HashMap<String, MutableSet<Int>>()
        for (group in manifest.variantGroups) {
            requireUuid(group.groupId, "variant groupId")
            require(groupIds.add(group.groupId)) { "变体组合 groupId 重复" }
            require(group.classId in classIds) { "变体组合引用未知 Class" }
            require(group.paletteSlot in 0..11) { "变体组合颜色槽超出 0..11" }
            require(slotsByClass.getOrPut(group.classId) { HashSet() }.add(group.paletteSlot)) { "同一 Class 的变体组合颜色槽不能重复" }
            require(group.mapIds.size >= 2 && group.mapIds.distinct().size == group.mapIds.size) { "变体组合至少需要两张不重复地图" }
            group.mapIds.forEach { mapId ->
                require(mapClassById[mapId] == group.classId) { "变体组合包含缺失地图或跨 Class 成员" }
                require(groupedMaps.add(mapId)) { "同一地图不能属于多个变体组合" }
            }
        }

        // 文件清单：路径唯一、不得包含 header/manifest.json、路径必须安全
        val filePaths = manifest.files.map { it.path }
        require(filePaths.size == filePaths.toSet().size) { "files 清单中存在重复路径" }
        require(!filePaths.contains("header")) { "files 不得包含 header" }
        require(!filePaths.contains("manifest.json")) { "files 不得包含 manifest.json" }
        for (f in manifest.files) {
            require(validateSafeRelativePath(f.path)) { "文件路径不合法：'${f.path}'" }
            require(f.size >= 0) { "文件 ${f.path} size 非法" }
            require(f.sha256.matches(Regex("[0-9a-f]{64}"))) { "文件 ${f.path} 的 sha256 格式非法" }
        }
    }

    /** 校验地图文档（metadata/gates/anchors）与 manifest 的一致性及坐标合法性 */
    fun validateMapDocuments(
        mapId: String,
        manifestMap: ManifestMap?,
        metadata: MetadataDocument?,
        gates: GatesDocument?,
        anchors: AnchorsDocument?,
    ) {
        if (manifestMap != null && metadata != null) {
            require(metadata.schemaVersion in setOf(1, 2)) { "地图 $mapId：不支持 metadata schemaVersion ${metadata.schemaVersion}" }
            require(manifestMap.floors.any() && (metadata.schemaVersion == 2 || manifestMap.floors.all { it.markerKeys.isEmpty() })) {
                "地图 $mapId：旧 metadata schema 不允许楼层 markerKeys"
            }
            require(metadata.map.id == manifestMap.mapId) { "地图 $mapId：metadata.map.id 与 manifest 不一致" }
            require(metadata.map.classId == manifestMap.classId) { "地图 $mapId：metadata.map.classId 与 manifest 不一致" }
            require(metadata.map.title == manifestMap.name) { "地图 $mapId：metadata.map.title 与 manifest.name 不一致" }
            require(metadata.map.coordinateSystem == "normalized-top-left-y-down") { "地图 $mapId：坐标系不受支持" }
        }
        if (metadata != null) {
            require(metadata.floors.isNotEmpty()) { "地图 $mapId：metadata 没有楼层" }
            require(metadata.floors.size == (manifestMap?.floors?.size ?: metadata.floors.size)) {
                "地图 $mapId：metadata 楼层数与 manifest 不一致"
            }
            val metaFloors = metadata.floors.associateBy { it.key }
            if (manifestMap != null) {
                for ((index, mFloor) in manifestMap.floors.withIndex()) {
                    val mf = metaFloors[mFloor.key]
                    require(mf != null) { "地图 $mapId：metadata 缺少楼层 ${mFloor.key}" }
                    require(mf.displayName == mFloor.displayName) { "地图 $mapId：楼层 ${mFloor.key} 显示名不一致" }
                    require(mf.image == mFloor.image) { "地图 $mapId：楼层 ${mFloor.key} 图片路径不一致" }
                    require(mf.sortOrder == index + 1 && mf.sortOrder == mFloor.sortOrder) { "地图 $mapId：楼层 ${mFloor.key} 顺序不一致" }
                    require(mf.markerKeys == mFloor.markerKeys) { "地图 $mapId：楼层 ${mFloor.key} markerKeys 不一致" }
                }
            }
            for (f in metadata.floors) {
                require(f.orientationDegrees in setOf(0, 90, 180, 270)) {
                    "地图 $mapId：楼层 ${f.key} orientationDegrees 只允许 0/90/180/270"
                }
                require(f.imageWidth > 0 && f.imageHeight > 0) { "地图 $mapId：楼层 ${f.key} 图片尺寸非法" }
                f.recognitionRegion?.let { validateRect("地图 $mapId 楼层 ${f.key} recognitionRegion", it) }
                f.validMapBounds?.let { validateRect("地图 $mapId 楼层 ${f.key} validMapBounds", it) }
                f.recognitionImage?.let { path ->
                    validateRecognitionAssetPath(
                        mapId = mapId,
                        floorKey = f.key,
                        root = manifestMap?.root,
                        what = "recognitionImage",
                        path = path,
                    )
                }
                f.sideEntranceFeature?.let { feature ->
                    validateRecognitionAssetPath(
                        mapId = mapId,
                        floorKey = f.key,
                        root = manifestMap?.root,
                        what = "sideEntranceFeature.file",
                        path = feature.file,
                    )
                    require(feature.centerX.isFinite() && feature.centerY.isFinite()) {
                        "地图 $mapId：楼层 ${f.key} 侧门特征中心必须为有限数"
                    }
                    require(feature.centerX >= 0.0 && feature.centerY >= 0.0 && feature.radius > 0) {
                        "地图 $mapId：楼层 ${f.key} 侧门特征坐标或半径非法"
                    }
                }
            }
        }
        if (gates != null) {
            val gateIds = gates.gates.map { it.id }
            require(gateIds.size == gateIds.toSet().size) { "地图 $mapId：gates 存在重复 id" }
            var mainEntrance = 0
            for (g in gates.gates) {
                require(g.role in setOf("mainEntrance", "sideEntrance", "exit", "unknown")) {
                    "地图 $mapId：gate ${g.id} role 非法 '${g.role}'"
                }
                if (g.role == "mainEntrance") mainEntrance++
                require(g.confidence in 0.0..1.0) { "地图 $mapId：gate ${g.id} confidence 越界" }
                g.bounds?.let { validateRect("地图 $mapId gate ${g.id}", it) }
            }
            require(mainEntrance <= 1) { "地图 $mapId：每个楼层最多一个 mainEntrance" }
        }
        if (anchors != null) {
            if (metadata != null) {
                val metaKeys = metadata.floors.map { it.key }.toSet()
                require(anchors.floors.keys == metaKeys) { "地图 $mapId：anchors.floors 与 metadata 楼层集合不一致" }
            }
            for ((floorKey, floorAnchors) in anchors.floors) {
                for (a in floorAnchors.anchors) {
                    require(a.role in setOf("required", "optional")) {
                        "地图 $mapId：anchor ${a.id} role 非法"
                    }
                    require(a.weight >= 0.0 && a.weight.isFinite()) { "地图 $mapId：anchor ${a.id} weight 非法" }
                    require(a.gateId == null || a.bounds == null) {
                        "地图 $mapId：anchor ${a.id} 不得同时写 gateId 与 bounds"
                    }
                    a.bounds?.let { validateRect("地图 $mapId anchor ${a.id}", it) }
                }
            }
        }
    }

    fun validateRect(context: String, r: NormalizedRect) {
        require(r.x.isFinite() && r.y.isFinite() && r.width.isFinite() && r.height.isFinite()) {
            "$context 坐标必须为有限数"
        }
        require(r.width > 0.0 && r.height > 0.0) { "$context 矩形必须有正面积" }
        val eps = 1e-6
        require(r.x >= -eps && r.y >= -eps && (r.x + r.width) <= 1.0 + eps && (r.y + r.height) <= 1.0 + eps) {
            "$context 矩形必须完整落在 0..1（允许 $eps 边界误差）"
        }
    }

    private fun validateRecognitionAssetPath(
        mapId: String,
        floorKey: String,
        root: String?,
        what: String,
        path: String,
    ) {
        require(validateSafeRelativePath(path)) {
            "地图 $mapId：楼层 $floorKey 的 $what 路径不安全"
        }
        if (root != null) {
            require(path.startsWith("${root.trimEnd('/')}/data/")) {
                "地图 $mapId：楼层 $floorKey 的 $what 必须位于地图 data 目录"
            }
        }
        require(path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in setOf("png", "jpg", "jpeg")) {
            "地图 $mapId：楼层 $floorKey 的 $what 不是受支持的图片"
        }
    }

    /**
     * 校验 ZIP 内逻辑路径是否安全（§2）：用 '/' 分隔、无绝对路径/空段/`.`/`..`/反斜杠/空字节、
     * 小写比较大小写敏感、不得为设备文件/符号链接。
     */
    fun validateSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.contains('\\') || path.contains(' ')) return false
        if (path.startsWith('/') || path.startsWith("./") || path.contains("/./") || path.endsWith("/.")) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    /** 校验 UUID 文本合法 */
    fun requireUuid(value: String, what: String) {
        try {
            require(IdvmUtil.isRfc4122(UUID.fromString(value))) { "$what 不是合法 UUID：$value" }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$what 不是合法 UUID：$value")
        }
    }
}
