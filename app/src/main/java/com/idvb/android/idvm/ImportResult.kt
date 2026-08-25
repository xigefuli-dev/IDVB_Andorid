package com.idvb.android.idvm

/** IDVM 导入结果 */
sealed class ImportResult {
    data class Success(
        val importedClasses: List<ClassRecord>,
        val importedMaps: List<MapRecord>,
        val catalog: MapCatalogDocument,
    ) : ImportResult()

    data class Failure(
        val reason: String,
        val details: String? = null,
    ) : ImportResult()
}
