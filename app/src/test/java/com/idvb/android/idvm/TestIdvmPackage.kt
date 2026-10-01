package com.idvb.android.idvm

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试内构造 IDVM 包（模拟参考项目 IdvmPackageService.Writer 的输出结构） */
data class FloorImage(
    val key: String,
    val displayName: String,
    val sortOrder: Int,
    val bytes: ByteArray,
    val orientationDegrees: Int = 0,
    val recognitionImageBytes: ByteArray? = null,
    val sideEntranceFeatureBytes: ByteArray? = null,
    val sideEntranceFeatureCenterX: Double = 0.5,
    val sideEntranceFeatureCenterY: Double = 0.5,
    val sideEntranceFeatureRadius: Int = 1,
    val prebuiltLineBytes: ByteArray? = null,
)

data class BuiltPackage(
    val zipBytes: ByteArray,
    val header: IdvmHeader.Header,
    val rawManifest: ByteArray,
    val manifest: IdvmManifest,
    val floorImagePaths: List<String>,
)

object TestIdvmPackage {

    val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    fun build(
        packageId: UUID = UUID.randomUUID(),
        className: String = "S1",
        classId: String = "1a1e8f54-cfa8-4ead-a03c-7760675cf830",
        mapId: String = "61ac15d7-78e9-4454-a659-aeeed373e902",
        mapName: String = "军工厂",
        formatMinor: Int = 0,
        createdAt: String = "2026-08-02T03:00:00.0000000+00:00",
        floors: List<FloorImage> = listOf(FloorImage("1f", "1F", 1, PNG_1X1)),
        mutateManifest: (IdvmManifest) -> IdvmManifest = { it },
        mutateMetadata: (MetadataDocument) -> MetadataDocument = { it },
        mutateHeaderBytes: (ByteArray) -> ByteArray = { it },
        corruptHeaderSha: Boolean = false,
        extraEntries: Map<String, ByteArray> = emptyMap(),
        mutateAnchors: (AnchorsDocument) -> AnchorsDocument = { it },
    ): BuiltPackage {
        val root = "maps/${mapId.replace("-", "")}" 
        val imagePaths = floors.map { "$root/maps/floor-${it.sortOrder.toString().padStart(3, '0')}.png" }
        val recognitionImagePaths = floors.map { floor ->
            floor.recognitionImageBytes?.let { "$root/data/floor-${floor.sortOrder.toString().padStart(3, '0')}-recognition.png" }
        }
        val featurePaths = floors.map { floor ->
            floor.sideEntranceFeatureBytes?.let { "$root/data/floor-${floor.sortOrder.toString().padStart(3, '0')}-side-entrance-feature.png" }
        }
        val linePaths = floors.map { floor ->
            floor.prebuiltLineBytes?.let { "$root/data/floor-${floor.sortOrder.toString().padStart(3, '0')}-prebuilt-structure.png" }
        }
        val algorithmPath = "$root/data/prebuilt-structure.idva"
        val algorithmBytes = "test-idva-1.1".encodeToByteArray()
        val dims = floors.map { ImageProbe.dimensions(it.bytes) ?: (0 to 0) }

        val metadata = MetadataDocument(
            schemaVersion = when {
                formatMinor >= 3 -> 3
                formatMinor >= 2 -> 2
                else -> 1
            },
            map = MetadataMap(
                id = mapId, classId = classId, title = mapName,
                source = "manual", coordinateSystem = "normalized-top-left-y-down",
            ),
            floors = floors.mapIndexed { i, f ->
                MetadataFloor(
                    key = f.key, displayName = f.displayName, sortOrder = f.sortOrder,
                    image = imagePaths[i], imageWidth = dims[i].first, imageHeight = dims[i].second,
                    orientationDegrees = f.orientationDegrees,
                    recognitionRegion = NormalizedRect(0.02, 0.02, 0.96, 0.96),
                    validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    recognitionImage = recognitionImagePaths[i],
                    sideEntranceFeature = featurePaths[i]?.let { path ->
                        SideEntranceFeatureMetadata(
                            file = path,
                            centerX = f.sideEntranceFeatureCenterX,
                            centerY = f.sideEntranceFeatureCenterY,
                            radius = f.sideEntranceFeatureRadius,
                        )
                    },
                    prebuiltStructureLine = linePaths[i]?.let { path ->
                        val line = requireNotNull(f.prebuiltLineBytes)
                        val lineSize = requireNotNull(ImageProbe.dimensions(line))
                        PrebuiltStructureLineMetadata(
                            file = path,
                            sha256 = IdvmUtil.sha256Hex(IdvmUtil.sha256(line)),
                            sourceSha256 = IdvmUtil.sha256Hex(IdvmUtil.sha256(f.recognitionImageBytes ?: f.bytes)),
                            width = lineSize.first,
                            height = lineSize.second,
                            fileLength = line.size.toLong(),
                            algorithmId = "test-algorithm",
                            algorithmFile = algorithmPath,
                            algorithmSha256 = IdvmUtil.sha256Hex(IdvmUtil.sha256(algorithmBytes)),
                            algorithmSchemaVersion = "1.1",
                        )
                    },
                )
            },
        ).let(mutateMetadata)
        val gates = GatesDocument(schemaVersion = 1, gates = emptyList())
        val anchors = AnchorsDocument(
            schemaVersion = 1,
            floors = floors.associate { f ->
                f.key to FloorAnchors(anchors = emptyList(), wholeImageIgnoreRegions = emptyList())
            },
        ).let(mutateAnchors)

        val json = IdvmJson.instance
        val dataFiles = mapOf(
            "$root/data/metadata.json" to json.encodeToString(MetadataDocument.serializer(), metadata).encodeToByteArray(),
            "$root/data/gates.json" to json.encodeToString(GatesDocument.serializer(), gates).encodeToByteArray(),
            "$root/data/anchors.json" to json.encodeToString(AnchorsDocument.serializer(), anchors).encodeToByteArray(),
        )
        val recognitionPayload = buildMap {
            floors.forEachIndexed { index, floor ->
                recognitionImagePaths[index]?.let { path -> put(path, floor.recognitionImageBytes!!) }
                featurePaths[index]?.let { path -> put(path, floor.sideEntranceFeatureBytes!!) }
                linePaths[index]?.let { path -> put(path, floor.prebuiltLineBytes!!) }
            }
            if (linePaths.any { it != null }) put(algorithmPath, algorithmBytes)
        }
        val payload: Map<String, ByteArray> = dataFiles +
            floors.zip(imagePaths).associate { (f, p) -> p to f.bytes } +
            recognitionPayload

        val manifest = IdvmManifest(
            format = "idvm",
            formatVersion = "1.$formatMinor",
            packageType = "class-set",
            packageId = packageId.toString().lowercase(),
            createdAt = createdAt,
            minimumReader = "1.$formatMinor",
            classes = listOf(ManifestClass(classId = classId, name = className, mapIds = listOf(mapId))),
            maps = listOf(
                ManifestMap(
                    mapId = mapId, mapVersion = 1, name = mapName, classId = classId,
                    createdAt = createdAt, updatedAt = createdAt, root = root,
                    floors = floors.mapIndexed { i, f ->
                        ManifestFloor(key = f.key, displayName = f.displayName, sortOrder = f.sortOrder, image = imagePaths[i])
                    },
                )
            ),
            files = payload.map { (path, bytes) ->
                ManifestFile(path = path, size = bytes.size.toLong(), sha256 = IdvmUtil.sha256Hex(IdvmUtil.sha256(bytes)))
            },
            capabilities = ManifestCapabilities(
                variantGroups = formatMinor >= 1,
                floorMarkerKeys = formatMinor >= 2,
                mapTags = formatMinor >= 3,
            ),
        ).let(mutateManifest)

        val rawManifest = json.encodeToString(IdvmManifest.serializer(), manifest).encodeToByteArray()
        val header = IdvmHeader.write(
            IdvmHeader.Header(
                formatMajor = IdvmHeader.FORMAT_MAJOR,
                formatMinor = formatMinor,
                packageId = packageId,
                createdAt = IdvmUtil.parseIsoInstantMs(createdAt),
                manifestSha256 = if (corruptHeaderSha) ByteArray(32) { 0x11 } else IdvmUtil.sha256(rawManifest),
            )
        ).let(mutateHeaderBytes)

        return BuiltPackage(
            zipBytes = buildZip(header, rawManifest, payload, extraEntries),
            header = IdvmHeader.parse(header),
            rawManifest = rawManifest,
            manifest = manifest,
            floorImagePaths = imagePaths,
        )
    }

    private fun buildZip(
        header: ByteArray,
        rawManifest: ByteArray,
        payload: Map<String, ByteArray>,
        extra: Map<String, ByteArray>,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            writeEntry(zos, "header", header, store = true)
            writeEntry(zos, "manifest.json", rawManifest, store = false)
            payload.forEach { (path, bytes) -> writeEntry(zos, path, bytes, store = false) }
            extra.forEach { (path, bytes) -> writeEntry(zos, path, bytes, store = false) }
        }
        return bos.toByteArray()
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, bytes: ByteArray, store: Boolean) {
        val e = ZipEntry(name)
        e.method = if (store) ZipEntry.STORED else ZipEntry.DEFLATED
        if (store) {
            e.size = bytes.size.toLong()
            val crc = CRC32().also { it.update(bytes) }
            e.crc = crc.value
        }
        zos.putNextEntry(e)
        zos.write(bytes)
        zos.closeEntry()
    }
}
