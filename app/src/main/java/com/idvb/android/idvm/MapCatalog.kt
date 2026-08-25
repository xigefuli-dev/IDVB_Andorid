package com.idvb.android.idvm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 本地地图仓库清单（对齐参考项目 MapRepository 的 maps.json）。
 * 纯 Kotlin DTO，供 [IdvmImporter] 与 data/MapRepository 共用。
 */
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
}
