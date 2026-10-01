package com.idvb.android.idvm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * IDVM 数据模型（对齐 IDVM_FORMAT.md v1.0）。
 * 纯 Kotlin / kotlinx.serialization，无 Android 依赖，可在 JVM 单元测试中往返。
 */
object IdvmJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

/** 归一化坐标矩形：原点左上，x 向右 y 向下，取值 0..1 */
@Serializable
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

// ---------- manifest.json ----------

@Serializable
data class ManifestClass(
    @SerialName("classId") val classId: String,
    @SerialName("name") val name: String,
    @SerialName("mapIds") val mapIds: List<String>,
    @SerialName("properties") val properties: ManifestClassProperties = ManifestClassProperties(),
)

@Serializable
data class ManifestClassProperties(
    @SerialName("removeBackground") val removeBackground: Boolean = false,
    /** Desktop 为地图类别指定的扫描楼层。 */
    @SerialName("scanFloorKey") val scanFloorKey: String? = null,
    @SerialName("containsVectorRoutes") val containsVectorRoutes: Boolean = false,
)

@Serializable
data class ManifestVariantGroup(
    @SerialName("groupId") val groupId: String,
    @SerialName("classId") val classId: String,
    @SerialName("paletteSlot") val paletteSlot: Int,
    @SerialName("mapIds") val mapIds: List<String>,
)

@Serializable
data class ManifestFloor(
    @SerialName("key") val key: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("sortOrder") val sortOrder: Int,
    @SerialName("image") val image: String,
    @SerialName("markerKeys") val markerKeys: List<String> = emptyList(),
)

@Serializable
data class ManifestMap(
    @SerialName("mapId") val mapId: String,
    @SerialName("mapVersion") val mapVersion: Int,
    @SerialName("name") val name: String,
    @SerialName("classId") val classId: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("updatedAt") val updatedAt: String,
    @SerialName("root") val root: String,
    @SerialName("floors") val floors: List<ManifestFloor>,
)

@Serializable
data class ManifestFile(
    @SerialName("path") val path: String,
    @SerialName("size") val size: Long,
    @SerialName("sha256") val sha256: String,
)

@Serializable
data class ManifestCapabilities(
    @SerialName("multiClass") val multiClass: Boolean = true,
    @SerialName("multiFloor") val multiFloor: Boolean = true,
    @SerialName("recognitionAnchors") val recognitionAnchors: Boolean = true,
    @SerialName("derivedCache") val derivedCache: Boolean = false,
    @SerialName("backgroundLayers") val backgroundLayers: Boolean = false,
    @SerialName("classBackgroundRemoval") val classBackgroundRemoval: Boolean = false,
    @SerialName("variantGroups") val variantGroups: Boolean = false,
    @SerialName("floorMarkerKeys") val floorMarkerKeys: Boolean = false,
    @SerialName("mapTags") val mapTags: Boolean = false,
    @SerialName("containsVectorRoutes") val containsVectorRoutes: Boolean = false,
)

@Serializable
data class IdvmManifest(
    @SerialName("format") val format: String,
    @SerialName("formatVersion") val formatVersion: String,
    @SerialName("packageType") val packageType: String,
    @SerialName("packageId") val packageId: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("minimumReader") val minimumReader: String,
    @SerialName("classes") val classes: List<ManifestClass>,
    @SerialName("maps") val maps: List<ManifestMap>,
    @SerialName("variantGroups") val variantGroups: List<ManifestVariantGroup> = emptyList(),
    @SerialName("files") val files: List<ManifestFile>,
    @SerialName("capabilities") val capabilities: ManifestCapabilities,
    @SerialName("supportedPlatforms") val supportedPlatforms: List<String> = emptyList(),
)

// ---------- metadata.json ----------

@Serializable
data class MetadataMap(
    @SerialName("id") val id: String,
    @SerialName("classId") val classId: String,
    @SerialName("title") val title: String,
    @SerialName("source") val source: String,
    @SerialName("coordinateSystem") val coordinateSystem: String,
)

@Serializable
data class SideEntranceFeatureMetadata(
    @SerialName("file") val file: String,
    @SerialName("centerX") val centerX: Double,
    @SerialName("centerY") val centerY: Double,
    @SerialName("radius") val radius: Int,
)

/** Desktop VPSG 预制线图及生成算法的完整性登记。 */
@Serializable
data class PrebuiltStructureLineMetadata(
    @SerialName("file") val file: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("sourceSha256") val sourceSha256: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("fileLength") val fileLength: Long,
    @SerialName("algorithmId") val algorithmId: String,
    @SerialName("algorithmFile") val algorithmFile: String,
    @SerialName("algorithmSha256") val algorithmSha256: String,
    @SerialName("algorithmSchemaVersion") val algorithmSchemaVersion: String,
    @SerialName("engineRevision") val engineRevision: Int = 0,
)

@Serializable
data class MetadataFloor(
    @SerialName("key") val key: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("sortOrder") val sortOrder: Int,
    @SerialName("image") val image: String,
    @SerialName("imageWidth") val imageWidth: Int,
    @SerialName("imageHeight") val imageHeight: Int,
    @SerialName("orientationDegrees") val orientationDegrees: Int = 0,
    @SerialName("recognitionRegion") val recognitionRegion: NormalizedRect? = null,
    /** Desktop 自由裁剪多边形，点坐标相对楼层原图。 */
    @SerialName("freeCropPoints") val freeCropPoints: List<NormalizedPoint> = emptyList(),
    @SerialName("validMapBounds") val validMapBounds: NormalizedRect? = null,
    @SerialName("markerKeys") val markerKeys: List<String> = emptyList(),
    /** Desktop 为测绘地图导出的权威识别图；为空时使用 image + recognitionRegion。 */
    @SerialName("recognitionImage") val recognitionImage: String? = null,
    /** Desktop 已按当前算法生成并遮掉公共侧门图标的权威特征图。 */
    @SerialName("sideEntranceFeature") val sideEntranceFeature: SideEntranceFeatureMetadata? = null,
    @SerialName("prebuiltStructureLine") val prebuiltStructureLine: PrebuiltStructureLineMetadata? = null,
)

/** Desktop 地图标签；Android 暂不使用。 */
@Serializable
data class MetadataTag(
    @SerialName("groupId") val groupId: String,
    @SerialName("groupName") val groupName: String,
    @SerialName("value") val value: String,
)

/** Desktop 全图识别设置；Android 暂不使用。 */
@Serializable
data class RecognitionWholeImage(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("weight") val weight: Double = 0.15,
    @SerialName("annotatedReferencePenalty") val annotatedReferencePenalty: Double = 0.55,
    @SerialName("referenceMayContainAnnotations") val referenceMayContainAnnotations: Boolean = false,
)

@Serializable
data class RecognitionSettings(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("wholeImage") val wholeImage: RecognitionWholeImage = RecognitionWholeImage(),
)

@Serializable
data class MetadataDocument(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("map") val map: MetadataMap,
    @SerialName("floors") val floors: List<MetadataFloor>,
    @SerialName("recognition") val recognition: RecognitionSettings = RecognitionSettings(),
    @SerialName("tags") val tags: List<MetadataTag> = emptyList(),
)

// ---------- gates.json ----------

@Serializable
data class Gate(
    @SerialName("id") val id: String,
    @SerialName("floorKey") val floorKey: String,
    @SerialName("role") val role: String,
    @SerialName("bounds") val bounds: NormalizedRect? = null,
    @SerialName("directionDegrees") val directionDegrees: Double = 0.0,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("confidence") val confidence: Double = 1.0,
)

@Serializable
data class GatesDocument(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("gates") val gates: List<Gate> = emptyList(),
)

// ---------- anchors.json ----------

@Serializable
data class Anchor(
    @SerialName("id") val id: String,
    @SerialName("key") val key: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("role") val role: String,
    @SerialName("weight") val weight: Double,
    @SerialName("builtIn") val builtIn: Boolean,
    @SerialName("gateId") val gateId: String? = null,
    @SerialName("bounds") val bounds: NormalizedRect? = null,
)

@Serializable
data class FloorAnchors(
    @SerialName("anchors") val anchors: List<Anchor> = emptyList(),
    @SerialName("wholeImageIgnoreRegions") val wholeImageIgnoreRegions: List<NormalizedRect> = emptyList(),
    @SerialName("backgroundLayers") val backgroundLayers: List<BackgroundLayer> = emptyList(),
    @SerialName("annotations") val annotations: List<MapAnnotation> = emptyList(),
)

/** Desktop 标注使用楼层原图坐标，而非识别区域坐标。 */
@Serializable
data class MapAnnotation(
    val id: String,
    val type: String,
    val colorIndex: Int = 0,
    val color: String? = null,
    val bounds: NormalizedRect? = null,
    val start: NormalizedPoint? = null,
    val end: NormalizedPoint? = null,
    val text: String? = null,
    val fontFamily: String? = null,
    val fontSize: Double? = null,
    val isBold: Boolean? = null,
    val isItalic: Boolean? = null,
    val isStrikethrough: Boolean? = null,
)

@Serializable
data class BackgroundLayer(
    @SerialName("id") val id: String = "",
    @SerialName("semantic") val semantic: String = "background",
    @SerialName("shape") val shape: String = "circle",
    @SerialName("brushSizePixels") val brushSizePixels: Int = 64,
    @SerialName("points") val points: List<NormalizedPoint> = emptyList(),
)

@Serializable
data class NormalizedPoint(
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
)

@Serializable
data class AnchorsDocument(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("floors") val floors: Map<String, FloorAnchors> = emptyMap(),
)
