package com.idvb.android.data

enum class SearchButtonAction(val label: String) {
    SCAN_MAP("扫描地图");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SCAN_MAP
    }
}

enum class EyeButtonAction(val label: String) {
    SHOW_AND_ALIGN("展示并自动贴合"),
    SHOW_ONLY("只展示");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SHOW_AND_ALIGN
    }
}
