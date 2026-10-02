package com.idvb.android.idvm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 本地地图仓库清单（对齐参考项目 MapRepository 的 maps.json）。
 * 纯 Kotlin DTO，供 [IdvmImporter] 与 data/MapRepository 共用。
 */
@Serializable
data class SideEntranceFeatureRecord(
    /** 相对 maps 根目录的特征图路径。 */
    @SerialName("imagePath") val imagePath: String,
    /** 特征中心在权威识别图中的像素坐标。 */
    @SerialName("centerX") val centerX: Double,
    @SerialName("centerY") val centerY: Double,
    @SerialName("radius") val radius: Int,
    @SerialName("imageWidth") val imageWidth: Int,
    @SerialName("imageHeight") val imageHeight: Int,
)

@Serializable
data class PrebuiltStructureLineRecord(
    @SerialName("imagePath") val imagePath: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("fileLength") val fileLength: Long,
    @SerialName("algorithmId") val algorithmId: String,
)

@Serializable
data class FloorRecord(
    @SerialName("key") val key: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("sortOrder") val sortOrder: Int,
    /** 相对 maps 根目录的图片路径，如 maps/{localMapId}/maps/floor-001.png */
    @SerialName("imagePath") val imagePath: String,
    @SerialName("imageWidth") val imageWidth: Int,
    @SerialName("imageHeight") val imageHeight: Int,
    @SerialName("orientationDegrees") val orientationDegrees: Int = 0,
    /** Desktop 中用户选择的识别裁切区域；旧目录为 null 时从 metadata.json 回读。 */
    @SerialName("previewRegion") val previewRegion: NormalizedRect? = null,
    @SerialName("freeCropPoints") val freeCropPoints: List<NormalizedPoint> = emptyList(),
    /** Desktop 测绘地图导出的独立识别图；普通地图为空并使用 image + previewRegion。 */
    @SerialName("recognitionImagePath") val recognitionImagePath: String? = null,
    @SerialName("recognitionWidth") val recognitionWidth: Int = 0,
    @SerialName("recognitionHeight") val recognitionHeight: Int = 0,
    @SerialName("validMapBounds") val validMapBounds: NormalizedRect? = null,
    @SerialName("sideEntranceFeature") val sideEntranceFeature: SideEntranceFeatureRecord? = null,
    @SerialName("prebuiltStructureLine") val prebuiltStructureLine: PrebuiltStructureLineRecord? = null,
)

@Serializable
data class MapRecord(
    @SerialName("id") val id: String,
    /** 本地 Class 的关联 ID */
    @SerialName("classId") val classId: String,
    @SerialName("title") val title: String,
    /** 导出来源中的逻辑地图 ID */
    @SerialName("sourceMapId") val sourceMapId: String,
    @SerialName("mapVersion") val mapVersion: Int,
    @SerialName("floors") val floors: List<FloorRecord>,
)

@Serializable
data class ClassRecord(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("removeBackground") val removeBackground: Boolean = false,
    @SerialName("scanFloorKey") val scanFloorKey: String? = null,
    /** null 表示旧目录未记录能力，此时可从标注恢复。 */
    val containsVectorRoutes: Boolean? = null,
)

@Serializable
data class MapVariantGroupRecord(
    @SerialName("id") val id: String,
    @SerialName("classId") val classId: String,
    @SerialName("paletteSlot") val paletteSlot: Int,
    @SerialName("mapIds") val mapIds: List<String>,
)

@Serializable
data class MapCatalogDocument(
    @SerialName("classes") val classes: List<ClassRecord> = emptyList(),
    @SerialName("maps") val maps: List<MapRecord> = emptyList(),
    @SerialName("variantGroups") val variantGroups: List<MapVariantGroupRecord> = emptyList(),
) {
    /** 判断 Class 名是否已被占用（忽略大小写，对齐 IDVM §10） */
    fun isClassNameTaken(name: String): Boolean =
        classes.any { it.name.equals(name, ignoreCase = true) }

    /** 查找地图所属的变体组合。 */
    fun findVariantGroup(mapId: String): MapVariantGroupRecord? =
        variantGroups.firstOrNull { mapId in it.mapIds && it.mapIds.size > 1 }

    /** 当前地图所在变体组的下一张地图，沿 IDVM 的 mapIds 顺序循环。 */
    fun nextVariantMapId(currentMapId: String): String? =
        findVariantGroup(currentMapId)?.mapIds
            ?.let { ids -> ids[(ids.indexOf(currentMapId) + 1) % ids.size] }
}
